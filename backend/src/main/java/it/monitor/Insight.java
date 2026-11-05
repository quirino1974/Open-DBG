package it.monitor;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Insight: health check giornaliero di MySQL e MariaDB. Controlli di sola lettura, basati sull'ultimo
 * rilevamento e su poche variabili di configurazione. Il report viene prodotto una volta al giorno
 * alle {@link #oraProduzione()} (o su richiesta) e conservato nello storico.
 */
final class Insight {

    /** Ora della produzione giornaliera, dal parametro insight.ora. */
    static LocalTime oraProduzione() {
        return LocalTime.of(Impostazioni.intero("insight.ora"), 0);
    }

    /** Fonti delle raccomandazioni (verificate in rete alla data di realizzazione). */
    static final List<Map<String, String>> FONTI = List.of(
            fonte("MySQL 8.4 Reference Manual – Password Management", "https://dev.mysql.com/doc/refman/8.4/en/password-management.html"),
            fonte("MySQL 8.4 Reference Manual – Password Security per amministratori", "https://dev.mysql.com/doc/refman/8.4/en/password-security-admin.html"),
            fonte("MariaDB Knowledge Base – Memory Allocation", "https://mariadb.com/docs/server/ha-and-performance/mariadb-memory-allocation"),
            fonte("Percona – Upgrade checklist per MySQL 8.4", "https://docs.percona.com/percona-server/8.4/upgrade-checklist-8.4.html"),
            fonte("MariaDB Galera – Flow Control", "https://mariadb.com/docs/galera-cluster/galera-management/performance-tuning/flow-control-in-galera-cluster"),
            fonte("MongoDB – Replica Set Arbiter", "https://docs.mongodb.com/manual/core/replica-set-arbiter"),
            fonte("SQLShack – Page life expectancy in SQL Server", "https://www.sqlshack.com/page-life-expectancy-ple-in-sql-server/"),
            fonte("Fine vita di MySQL 8.0 (EOL)", "https://endoflife.ai/mysql/8.0"));

    private Insight() {
    }

    private static Map<String, String> fonte(String titolo, String url) {
        return Map.of("titolo", titolo, "url", url);
    }

    // ------------------------------------------------------------ storico

    static void init() throws SQLException {
        try (Connection c = Store.conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS insight_report ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " generato_il DATETIME(3) NOT NULL,"
                    + " prossimo DATETIME(3) NOT NULL,"
                    + " payload LONGTEXT NOT NULL,"
                    + " INDEX idx_ins_time (generato_il)"
                    + ") ENGINE=InnoDB");
        }
    }

    /** Ultimo report salvato (generato_il e payload), oppure null se non ne esiste ancora. */
    private static Object[] ultimoGrezzo() throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT generato_il, prossimo, payload FROM insight_report ORDER BY id DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            return new Object[]{rs.getTimestamp(1).toLocalDateTime(), rs.getTimestamp(2).toLocalDateTime(), rs.getString(3)};
        }
    }

    /**
     * Report più recente, filtrato sui server visibili (null = tutti). Restituisce null se non esiste ancora.
     */
    static Map<String, Object> ultimo(Set<String> visibili) throws Exception {
        Object[] grezzo = ultimoGrezzo();
        if (grezzo == null) return null;
        Map<String, Object> report = Main.JSON.readValue((String) grezzo[2], Map.class);
        report.put("generatoIl", grezzo[0].toString());
        report.put("prossimo", grezzo[1].toString());
        // Il commento IA viene mostrato solo se l'IA è attiva adesso: un report generato con l'IA attiva non deve riportarlo dopo la disattivazione
        if (!AiConfig.abilitata()) {
            report.remove("analisiIA");
            report.remove("analisiIAProvider");
            report.remove("analisiIAErrore");
        }
        // Solo i server con l'Insight attivo ora (anche se nel report salvato compaiono ancora) e visibili all'utente
        Set<String> inclusi = serverInclusi();
        List<Object> filtrati = new ArrayList<>();
        for (Object o : (List<?>) report.get("server")) {
            String id = String.valueOf(((Map<?, ?>) o).get("id"));
            if (inclusi.contains(id) && (visibili == null || visibili.contains(id))) filtrati.add(o);
        }
        report.put("server", filtrati);
        return report;
    }

    /** Id dei server con il flag Insight attivo nell'anagrafica. */
    static Set<String> serverInclusi() throws SQLException {
        Set<String> out = new java.util.HashSet<>();
        for (JsonNode s : Main.loadServers()) {
            if (s.path("insight").asBoolean(false) && !Main.isSospeso(s)) out.add(s.get("id").asText());
        }
        return out;
    }

    // ------------------------------------------------------------ pianificazione

    /** Prossima produzione: la prossima ora di produzione dopo l'istante indicato. */
    static LocalDateTime prossimaProduzione(LocalDateTime da) {
        LocalDateTime p = da.toLocalDate().atTime(oraProduzione());
        return p.isAfter(da) ? p : p.plusDays(1);
    }

    /** Chiamato periodicamente dallo scheduler: genera il report se quello di oggi (o di ieri) manca. */
    static void controllaPianificazione() {
        try {
            Object[] ultimo = ultimoGrezzo();
            LocalDateTime adesso = LocalDateTime.now();
            LocalDateTime dovuto = adesso.toLocalDate().atTime(oraProduzione());
            if (adesso.isBefore(dovuto)) dovuto = dovuto.minusDays(1);
            if (ultimo == null || ((LocalDateTime) ultimo[0]).isBefore(dovuto)) {
                genera();
                System.out.println("Insight: report giornaliero generato " + adesso);
            }
        } catch (Exception e) {
            System.err.println("Insight non generato: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ produzione del report

    /** Esegue tutti i controlli su ogni server MySQL/MariaDB e salva il report. */
    static Map<String, Object> genera() throws SQLException {
        LocalDateTime adesso = LocalDateTime.now();
        LocalDateTime prossimo = prossimaProduzione(adesso);
        List<Object> server = new ArrayList<>();
        List<String> nonAnalizzati = new ArrayList<>();
        for (JsonNode s : Main.loadServers()) {
            if (!s.path("insight").asBoolean(false) || Main.isSospeso(s)) continue;
            String tipo = Main.text(s, "kind");
            if (!tipo.equals("server") && !tipo.equals("postgres") && !tipo.equals("sqlserver") && !tipo.equals("mongodb")) continue;
            try {
                server.add(analizza(s));
            } catch (RuntimeException e) {
                // un server che fallisce non blocca il report degli altri: viene segnalato, non saltato in silenzio
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                nonAnalizzati.add(Main.text(s, "name") + ": " + msg);
                List<Map<String, Object>> controlli = new ArrayList<>();
                controlli.add(esito("Analisi", "bad", "analisi non riuscita: " + msg,
                        "Controllare la configurazione del server e il log del monitor.", null));
                server.add(riepilogo(s.get("id").asText(), Main.text(s, "name"), "", "", controlli, true));
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatoIl", adesso.toString());
        report.put("prossimo", prossimo.toString());
        report.put("server", server);
        if (!nonAnalizzati.isEmpty()) report.put("nonAnalizzati", nonAnalizzati);
        report.put("fonti", FONTI);
        // Commento dell'IA: facoltativo. Un errore del provider non blocca il report.
        if (AiConfig.abilitata()) {
            try {
                // provider e modello letti al momento della chiamata, così il report indica chi ha prodotto il commento
                report.put("analisiIAProvider", AiConfig.etichettaProvider());
                report.put("analisiIA", AiConfig.analizza(server, adesso.toString()));
            } catch (Exception e) {
                report.put("analisiIAErrore", e.getMessage());
                System.err.println("Analisi IA non riuscita: " + e.getMessage());
            }
        }
        try {
            String json = Main.JSON.writeValueAsString(report);
            try (Connection c = Store.conn();
                 PreparedStatement ps = c.prepareStatement("INSERT INTO insight_report (generato_il, prossimo, payload) VALUES (?, ?, ?)")) {
                ps.setTimestamp(1, Timestamp.valueOf(adesso));
                ps.setTimestamp(2, Timestamp.valueOf(prossimo));
                ps.setString(3, json);
                ps.executeUpdate();
            }
            try (Connection c = Store.conn();
                 Statement st = c.createStatement()) {
                // conserva gli ultimi 90 report
                st.executeUpdate("DELETE FROM insight_report WHERE id NOT IN (SELECT id FROM (SELECT id FROM insight_report "
                        + "ORDER BY id DESC LIMIT 90) x)");
            }
        } catch (Exception e) {
            throw new SQLException("Salvataggio del report non riuscito: " + e.getMessage(), e);
        }
        return report;
    }

    /** Controlli di un singolo server. */
    private static Map<String, Object> analizza(JsonNode s) {
        String id = s.get("id").asText();
        String nome = Main.text(s, "name");
        Map<String, Object> entry = Monitor.LATEST.get(id);
        Map<String, Object> rep = entry == null ? Map.of() : Monitor.map(entry.get("rep"));
        Map<String, Object> cur = entry == null ? Map.of() : Monitor.map(entry.get("cur"));
        List<Map<String, Object>> controlli = new ArrayList<>();
        String motore = "";
        String versione = "";

        boolean su = Boolean.TRUE.equals(cur.get("up"));
        if (!su) {
            controlli.add(esito("Disponibilità", "bad", "server non raggiungibile",
                    "Verificare che il server sia acceso e che il monitor abbia le credenziali corrette.", null));
            return riepilogo(id, nome, motore, versione, controlli, true);
        }
        controlli.add(esito("Disponibilità", "ok", "server raggiungibile", "", null));

        Map<String, Object> identita = Monitor.map(rep.get("identita"));
        motore = Main.str(identita.get("motore"));
        versione = Main.str(identita.get("versione"));
        controllaVersione(motore, versione, controlli);

        if ("SQL Server".equals(motore)) {
            // Controlli propri di SQL Server: topologia, risorse, query e impostazioni dei database
            if (Controlli.attivo(s, "topologia")) controllaTopologiaSqlServer(rep, controlli);
            if (Controlli.attivo(s, "risorse")) controllaRisorseSqlServer(rep, controlli);
            controllaQuery(rep, controlli, s);
            try (Connection c = Main.open(s)) {
                controllaConfigurazioneSqlServer(c, controlli, s);
                if (Controlli.attivo(s, "backup")) {
                    try {
                        controllaBackupSqlServer(c, controlli);
                    } catch (SQLException e) {
                        controlli.add(esito("Backup", "warn", "lettura di msdb non riuscita: " + e.getMessage(),
                                "Concedere all'utente del monitor la lettura di msdb.dbo.backupset.", null));
                    }
                }
            } catch (SQLException e) {
                controlli.add(esito("Configurazione", "warn", "lettura non riuscita: " + e.getMessage(),
                        "Verificare le credenziali del monitor.", null));
            }
            return riepilogo(id, nome, motore, versione, controlli, false);
        }

        if ("MongoDB".equals(motore)) {
            controllaMongoDB(rep, controlli, s);
            return riepilogo(id, nome, motore, versione, controlli, false);
        }

        // Dati già raccolti dal monitor
        controllaReplica(rep, controlli);
        controllaCluster(rep, controlli);
        if (Controlli.attivo(s, "indici")) controllaSchema(rep, controlli);
        if (Controlli.attivo(s, "utenti")) controllaUtenti(rep, controlli);
        controllaQuery(rep, controlli, s);

        // Configurazione e stato, letti in sola lettura dal server
        try (Connection c = Main.open(s)) {
            if ("PostgreSQL".equals(motore)) controllaConfigurazionePg(c, controlli, s);
            else controllaConfigurazione(c, controlli, s);
        } catch (SQLException e) {
            controlli.add(esito("Configurazione", "warn", "lettura non riuscita: " + e.getMessage(),
                    "Verificare le credenziali del monitor.", null));
        }
        return riepilogo(id, nome, motore, versione, controlli, false);
    }

    /**
     * Controlli di MongoDB sui dati del monitor (nessuna connessione in più). Replica set: stato del cluster,
     * ritardo dei secondari e finestra dell'oplog; risorse: connessioni e cache WiredTiger; operazioni lunghe.
     * Ogni controllo selezionabile viene eseguito solo se selezionato per il server.
     */
    private static void controllaMongoDB(Map<String, Object> rep, List<Map<String, Object>> controlli, JsonNode s) {
        Map<String, Object> cl = Monitor.map(rep.get("cluster"));
        if (!cl.isEmpty()) {
            String stato = Main.str(cl.get("stato"));
            int membri = Monitor.list(cl.get("membri")).size();
            if ("ONLINE".equals(stato)) {
                controlli.add(esito("Replica set", "ok", "ONLINE, " + membri + " membri", "", null));
            } else if ("DEGRADED".equals(stato)) {
                controlli.add(esito("Replica set", "warn", "replica set degradato: almeno un membro non è sano",
                        "Controllare i membri non sani con rs.status() prima di qualunque intervento.",
                        "https://www.mongodb.com/docs/manual/reference/method/rs.status/"));
            } else {
                controlli.add(esito("Replica set", "bad", "stato " + stato,
                        "Senza primario le scritture sono bloccate: verificare il quorum dei membri.",
                        "https://www.mongodb.com/docs/manual/replication/"));
            }
        }
        if (!cl.isEmpty()) {
            long votanti = 0;
            long arbitri = 0;
            for (Map<String, Object> mb : Monitor.list(cl.get("membri"))) {
                if (mb.get("voti") != null) votanti += Main.num(mb.get("voti"));
                if (Boolean.TRUE.equals(mb.get("arbitro"))) arbitri++;
            }
            if (votanti > 0 && votanti % 2 == 0 && arbitri == 0) {
                controlli.add(esito("Replica set", "warn", "numero pari di membri votanti (" + votanti + ") senza arbitro",
                        "Con un numero pari di votanti un guasto di rete può creare un pareggio: aggiungere un membro dispari o un arbitro.",
                        "https://docs.mongodb.com/manual/core/replica-set-arbiter"));
            }
        }
        if (Controlli.attivo(s, "replica")) {
            long massimo = 0;
            for (Map<String, Object> ch : Monitor.list(rep.get("replica"))) {
                massimo = Math.max(massimo, Main.num(ch.get("secondiDiRitardo")));
            }
            if (massimo > Impostazioni.intero("insight.replica_ritardo_secondi")) {
                controlli.add(esito("Replica", "warn", "ritardo di replica fino a " + massimo + " secondi",
                        "Verificare la capacità dei secondari e le operazioni pesanti sul primario.",
                        "https://www.mongodb.com/docs/manual/core/replica-set-sync/"));
            }
        }
        if (Controlli.attivo(s, "oplog") && cl.get("oplogOre") != null) {
            double ore = Double.parseDouble(String.valueOf(cl.get("oplogOre")));
            long minime = Impostazioni.intero("insight.mongo_oplog_ore_minime");
            if (ore >= 0 && ore < minime) {
                controlli.add(esito("Oplog", "warn", String.format("finestra dell'oplog di %.1f ore (minimo %d)", ore, minime),
                        "Un secondario fermato più a lungo di questa finestra richiede una risincronizzazione completa: aumentare l'oplog.",
                        "https://www.mongodb.com/docs/manual/core/replica-set-oplog/"));
            }
        }
        if (Controlli.attivo(s, "connessioni")) {
            Map<String, Object> conn = Monitor.map(rep.get("connessioni"));
            long attive = Main.num(conn.get("attive"));
            long massimo = Main.num(conn.get("massimo"));
            if (massimo > 0 && attive * 100 >= massimo * Impostazioni.intero("insight.connessioni_pct")) {
                controlli.add(esito("Connessioni", "warn", attive + " connessioni su " + massimo + " disponibili",
                        "Usare un pool di connessioni nelle applicazioni o verificare le connessioni che non si chiudono.",
                        "https://www.mongodb.com/docs/manual/reference/configuration-options/"));
            }
        }
        if (Controlli.attivo(s, "cache")) {
            Map<String, Object> cache = Monitor.map(rep.get("cacheWiredTiger"));
            long sporca = Main.num(cache.get("sporcaBytes"));
            long massima = Main.num(cache.get("massimaBytes"));
            if (massima > 0 && sporca * 100 >= massima * Impostazioni.intero("insight.mongo_cache_sporca_pct")) {
                controlli.add(esito("Cache", "warn", String.format("byte sporchi al %.0f%% della cache WiredTiger",
                                (double) sporca * 100 / massima),
                        "Molte pagine da scrivere su disco: verificare il carico di scrittura e il checkpoint, o la dimensione della cache.",
                        "https://www.mongodb.com/docs/manual/core/wiredtiger/"));
            }
        }
        controllaQuery(rep, controlli, s);
    }

    /** Controlli di configurazione e stato di PostgreSQL (sola lettura). */
    private static void controllaConfigurazionePg(Connection c, List<Map<String, Object>> controlli, JsonNode s) throws SQLException {
        Map<String, String> p = new LinkedHashMap<>();
        for (Map<String, Object> r : Main.q(c, "SELECT name, setting FROM pg_settings WHERE name IN "
                + "('wal_level','archive_mode','password_encryption','max_connections','log_min_duration_statement',"
                + "'ssl','data_checksums','autovacuum')")) {
            p.put(Main.str(r.get("name")), Main.str(r.get("setting")));
        }
        String walLevel = p.getOrDefault("wal_level", "");
        if (!walLevel.isEmpty() && !walLevel.equals("replica") && !walLevel.equals("logical")) {
            controlli.add(esito("WAL", "warn", "wal_level = " + walLevel,
                    "Per la replica in streaming wal_level deve essere replica o logical.",
                    "https://www.postgresql.org/docs/17/runtime-config-wal.html"));
        } else {
            controlli.add(esito("WAL", "ok", "wal_level = " + walLevel, "", null));
        }
        String archivio = p.getOrDefault("archive_mode", "off");
        if (!archivio.equals("on") && !archivio.equals("always")) {
            controlli.add(esito("Archiviazione WAL", "warn", "archive_mode disattivo: nessun ripristino point-in-time",
                    "Attivare l'archiviazione dei WAL (archive_mode) per il ripristino point-in-time.",
                    "https://www.postgresql.org/docs/17/continuous-archiving.html"));
        }
        String cifratura = p.getOrDefault("password_encryption", "");
        if (!cifratura.isEmpty() && !cifratura.equals("scram-sha-256")) {
            controlli.add(esito("Password", "warn", "password_encryption = " + cifratura,
                    "Usare scram-sha-256 per cifrare le password degli utenti.",
                    "https://www.postgresql.org/docs/17/runtime-config-connection.html"));
        }
        long maxConn = Main.num(p.get("max_connections"));
        if (Controlli.attivo(s, "connessioni")) {
            long attive = Main.num(Main.q(c, "SELECT count(*) AS n FROM pg_stat_activity").get(0).get("n"));
            long pct = Impostazioni.intero("insight.connessioni_pct");
            if (maxConn > 0 && attive * 100 >= maxConn * pct) {
                controlli.add(esito("Connessioni", "warn", attive + " connessioni su " + maxConn + " consentite",
                        "Usare un pool di connessioni o aumentare max_connections con cautela.", null));
            } else if (maxConn > 0) {
                controlli.add(esito("Connessioni", "ok", attive + " connessioni su " + maxConn, "", null));
            }
        }
        Map<String, Object> cache = Main.q(c, "SELECT COALESCE(SUM(blks_hit),0) AS hit, COALESCE(SUM(blks_read),0) AS letti "
                + "FROM pg_stat_database").get(0);
        long hit = Main.num(cache.get("hit"));
        long letti = Main.num(cache.get("letti"));
        if (hit + letti >= Impostazioni.intero("insight.hit_minimo_accessi")) {
            double ratio = (double) hit / (hit + letti);
            boolean basso = ratio * 100 < Impostazioni.intero("insight.cache_hit_pct");
            controlli.add(esito("Cache", basso ? "warn" : "ok", String.format("hit ratio %.1f%%", ratio * 100),
                    basso ? "Verificare shared_buffers rispetto al working set." : "", null));
        }
        if (Controlli.attivo(s, "transazioni_inattive")) {
            int minuti = Impostazioni.intero("insight.transazioni_inattive_minuti");
            long inattive = Main.num(Main.q(c, "SELECT count(*) AS n FROM pg_stat_activity WHERE state = 'idle in transaction' "
                    + "AND now() - state_change > interval '" + minuti + " minutes'").get(0).get("n"));
            if (inattive > 0) {
                controlli.add(esito("Transazioni", "warn", inattive + " transazioni inattive da oltre " + minuti + " minuti",
                        "Le transazioni aperte bloccano il vacuum: chiuderle o impostare idle_in_transaction_session_timeout.", null));
            }
        }
        String logLente = p.getOrDefault("log_min_duration_statement", "-1");
        if (logLente.equals("-1")) {
            controlli.add(esito("Query lente", "info", "log delle query lente disattivato",
                    "Impostare log_min_duration_statement (es. 1000 ms) per tracciare le query lente.", null));
        }
        if (p.containsKey("ssl") && !p.get("ssl").equals("on")) {
            controlli.add(esito("Sicurezza", "warn", "ssl = " + p.get("ssl") + ": connessioni in chiaro",
                    "Attivare ssl con certificato e richiedere TLS in pg_hba.conf (hostssl).",
                    "https://www.postgresql.org/docs/17/ssl-tcp.html"));
        }
        if (p.containsKey("autovacuum") && !p.get("autovacuum").equals("on")) {
            controlli.add(esito("Vacuum", "bad", "autovacuum disattivato",
                    "Riattivare autovacuum: senza di esso tuple morte e statistiche non vengono ripulite.",
                    "https://www.postgresql.org/docs/17/routine-vacuuming.html"));
        }
        if (p.containsKey("data_checksums") && p.get("data_checksums").equals("off")) {
            controlli.add(esito("Integrità", "info", "data_checksums disattivo: corruzioni delle pagine non rilevate",
                    "I checksum si attivano solo al initdb (-k) o con pg_checksums a cluster fermo.",
                    "https://www.postgresql.org/docs/17/app-pgchecksums.html"));
        }
        // Slot di replica inattivi: trattengono i WAL e riempiono il disco. Vale anche su replica (slot logici).
        long slotInattivi = Main.num(Main.q(c, "SELECT count(*) AS n FROM pg_replication_slots WHERE NOT active").get(0).get("n"));
        if (slotInattivi > 0) {
            controlli.add(esito("Replica", "warn", slotInattivi + " slot di replica inattivi: trattengono i WAL",
                    "Eliminare gli slot non più usati (pg_drop_replication_slot): finché esistono il WAL non viene riciclato.",
                    "https://www.postgresql.org/docs/17/warm-standby.html#STREAMING-REPLICATION-SLOTS"));
        }
        if (Controlli.attivo(s, "vacuum")) {
            try {
                long etaMax = Main.num(Main.q(c, "SELECT COALESCE(MAX(age(datfrozenxid)), 0) AS n FROM pg_database").get(0).get("n"));
                long soglia = Impostazioni.intero("insight.pg_xid_eta_milioni") * 1_000_000L;
                if (etaMax > 1_500_000_000L || etaMax > soglia) {
                    controlli.add(esito("Wraparound", etaMax > 1_500_000_000L ? "bad" : "warn",
                            "età massima dei XID: " + (etaMax / 1_000_000) + " milioni (limite circa 2.100 milioni)",
                            "Eseguire VACUUM FREEZE sui database indicati e verificare che autovacuum non sia bloccato da transazioni lunghe.",
                            "https://www.postgresql.org/docs/17/routine-vacuuming.html"));
                }
            } catch (SQLException e) {
                controlli.add(esito("Wraparound", "info", "lettura non riuscita: " + e.getMessage(), "", null));
            }
        }
        try {
            long verNum = Main.num(Main.q(c, "SHOW server_version_num").get(0).get("server_version_num"));
            Map<String, Object> cp = verNum >= 170000
                    ? Main.q(c, "SELECT num_timed AS t, num_requested AS r FROM pg_stat_checkpointer").get(0)
                    : Main.q(c, "SELECT checkpoints_timed AS t, checkpoints_req AS r FROM pg_stat_bgwriter").get(0);
            long programmati = Main.num(cp.get("t"));
            long richiesti = Main.num(cp.get("r"));
            if (programmati + richiesti >= 10 && richiesti > programmati) {
                controlli.add(esito("Checkpoint", "warn", richiesti + " checkpoint richiesti dalla quantità di WAL, contro " + programmati + " programmati",
                        "Aumentare max_wal_size: i checkpoint forzati causano picchi di I/O. I contatori sono cumulativi dall'ultimo azzeramento.",
                        "https://www.postgresql.org/docs/17/wal-configuration.html"));
            }
        } catch (SQLException e) {
            controlli.add(esito("Checkpoint", "info", "lettura non riuscita: " + e.getMessage(), "", null));
        }
        if (Controlli.attivo(s, "vacuum")) {
            long gonfie = Main.num(Main.q(c, "SELECT count(*) AS n FROM pg_stat_user_tables "
                    + "WHERE n_live_tup >= " + Impostazioni.intero("insight.vacuum_righe_minime")
                    + " AND n_dead_tup * 100 > " + Impostazioni.intero("insight.vacuum_morte_pct") + " * n_live_tup").get(0).get("n"));
            if (gonfie > 0) {
                controlli.add(esito("Vacuum", "warn", gonfie + " tabelle con oltre il " + Impostazioni.intero("insight.vacuum_morte_pct")
                        + "% di tuple morte",
                        "Verificare autovacuum_vacuum_scale_factor per le tabelle più grandi e i vacuum manuali se necessario.",
                        "https://www.postgresql.org/docs/17/routine-vacuuming.html"));
            }
        }
    }

    private static void controllaTopologiaSqlServer(Map<String, Object> rep, List<Map<String, Object>> controlli) {
        Map<String, Object> t = Monitor.map(rep.get("topologia"));
        String tipo = Main.str(t.get("tipo"));
        if (tipo.isEmpty()) return;
        String descr = Main.str(t.get("descrizione"));
        if (tipo.equals("standalone")) {
            controlli.add(esito("Alta disponibilità", "info", descr,
                    "Un'istanza singola non ha failover: valutare un gruppo di disponibilità per i servizi critici.", null));
        } else {
            controlli.add(esito("Topologia", "ok", descr, "", null));
        }
    }

    private static void controllaRisorseSqlServer(Map<String, Object> rep, List<Map<String, Object>> controlli) {
        Map<String, Object> r = Monitor.map(rep.get("risorse"));
        if (r.get("memPercent") == null) return;
        double pct = Double.parseDouble(String.valueOf(r.get("memPercent")));
        if (pct >= Impostazioni.intero("insight.memoria_sqlserver_pct")) {
            controlli.add(esito("Memoria", "warn", String.format("memoria del server al %.0f%%", pct),
                    "Verificare la memoria massima di SQL Server (max server memory) e il carico delle query.", null));
        } else {
            controlli.add(esito("Memoria", "ok", String.format("memoria del server al %.0f%%", pct), "", null));
        }
    }

    /** Impostazioni di SQL Server che incidono su sicurezza e prestazioni (sola lettura). */
    /**
     * Backup di SQL Server dalla tabella msdb.dbo.backupset: ultimo backup completo per database e, nei
     * modelli FULL e BULK_LOGGED, ultimo backup del log. Le soglie sono nelle impostazioni Insight.
     */
    private static void controllaBackupSqlServer(Connection c, List<Map<String, Object>> controlli) throws SQLException {
        List<String> senzaFull = new ArrayList<>();
        List<String> fullVecchio = new ArrayList<>();
        List<String> senzaLog = new ArrayList<>();
        long giorniFull = Impostazioni.intero("insight.backup_full_giorni");
        long oreLog = Impostazioni.intero("insight.backup_log_ore");
        try (PreparedStatement ps = c.prepareStatement("SELECT d.name, d.recovery_model_desc, "
                + "(SELECT MAX(b.backup_finish_date) FROM msdb.dbo.backupset b WHERE b.database_name = d.name AND b.type = 'D') AS ultimo_full, "
                + "(SELECT MAX(b.backup_finish_date) FROM msdb.dbo.backupset b WHERE b.database_name = d.name AND b.type = 'L') AS ultimo_log "
                + "FROM sys.databases d WHERE d.database_id > 4 AND d.state_desc = 'ONLINE'");
             ResultSet rs = ps.executeQuery()) {
            java.time.LocalDateTime adesso = java.time.LocalDateTime.now();
            while (rs.next()) {
                String nome = rs.getString(1);
                String modello = rs.getString(2);
                Timestamp full = rs.getTimestamp(3);
                Timestamp log = rs.getTimestamp(4);
                if (full == null) {
                    senzaFull.add(nome);
                } else if (full.toLocalDateTime().isBefore(adesso.minusDays(giorniFull))) {
                    fullVecchio.add(nome);
                }
                if (("FULL".equals(modello) || "BULK_LOGGED".equals(modello))
                        && (log == null || log.toLocalDateTime().isBefore(adesso.minusHours(oreLog)))) {
                    senzaLog.add(nome);
                }
            }
        }
        String raccomandazione = "Verificare i job di backup con msdb e provare il ripristino: RESTORE VERIFYONLY controlla solo l'intestazione.";
        String riferimento = "https://learn.microsoft.com/sql/relational-databases/backup-restore/back-up-and-restore-of-sql-server-databases";
        if (!senzaFull.isEmpty()) {
            controlli.add(esito("Backup", "bad", "nessun backup completo per " + String.join(", ", senzaFull), raccomandazione, riferimento));
        }
        if (!fullVecchio.isEmpty()) {
            controlli.add(esito("Backup", "warn", "backup completo più vecchio di " + giorniFull + " giorni: " + String.join(", ", fullVecchio),
                    raccomandazione, riferimento));
        }
        if (!senzaLog.isEmpty()) {
            controlli.add(esito("Backup del log", "warn", "modello FULL o BULK_LOGGED senza backup del log da " + oreLog + " ore: "
                    + String.join(", ", senzaLog),
                    "Pianificare backup del log: senza di essi il file di log cresce senza limite e il ripristino point-in-time non è possibile.",
                    riferimento));
        }
        if (senzaFull.isEmpty() && fullVecchio.isEmpty() && senzaLog.isEmpty()) {
            controlli.add(esito("Backup", "ok", "backup completi e del log entro le soglie", "", null));
        }
    }

    private static void controllaConfigurazioneSqlServer(Connection c, List<Map<String, Object>> controlli, JsonNode s) throws SQLException {
        try (Statement st = c.createStatement()) {
            if (Controlli.attivo(s, "risorse")) {
                try (ResultSet rs = st.executeQuery("SELECT CAST(value_in_use AS BIGINT) FROM sys.configurations WHERE name = 'max server memory (MB)'")) {
                    if (rs.next() && rs.getLong(1) >= 2147483647L) {
                        controlli.add(esito("Memoria massima", "warn", "max server memory non limitata",
                                "Impostare max server memory lasciando memoria al sistema operativo.", null));
                    }
                }
            }
            // Impostazioni di istanza: parallelismo, comandi del sistema operativo, file di tempdb
            long costo = 0;
            long maxdop = -1;
            try (ResultSet rs = st.executeQuery("SELECT name, CAST(value_in_use AS BIGINT) FROM sys.configurations "
                    + "WHERE name IN ('cost threshold for parallelism', 'max degree of parallelism', 'xp_cmdshell')")) {
                while (rs.next()) {
                    String nome = rs.getString(1);
                    long v = rs.getLong(2);
                    if (nome.equals("cost threshold for parallelism")) costo = v;
                    else if (nome.equals("max degree of parallelism")) maxdop = v;
                    else if (nome.equals("xp_cmdshell") && v == 1) {
                        controlli.add(esito("Sicurezza", "warn", "xp_cmdshell attivo: si possono eseguire comandi del sistema operativo",
                                "Disattivare xp_cmdshell se non serve: è un vettore comune per l'escalation di privilegi.",
                                "https://learn.microsoft.com/sql/relational-databases/system-stored-procedures/xp-cmdshell-transact-sql"));
                    }
                }
            }
            if (costo > 0 && costo <= 5) {
                controlli.add(esito("Parallelismo", "info", "cost threshold for parallelism = " + costo + " (predefinito)",
                        "Su carichi OLTP una soglia di 25-50 evita piani paralleli per query piccole.",
                        "https://learn.microsoft.com/sql/database-engine/configure-windows/configure-the-cost-threshold-for-parallelism-server-configuration-option"));
            }
            if (maxdop == 0) {
                controlli.add(esito("Parallelismo", "info", "MAXDOP = 0 (tutte le CPU per una query)",
                        "Limitare MAXDOP al numero di CPU di un nodo NUMA (massimo 8) sui server con molti core.",
                        "https://learn.microsoft.com/sql/database-engine/configure-windows/configure-the-max-degree-of-parallelism-server-configuration-option"));
            }
            long file = 0;
            long cpu = 0;
            try (ResultSet rs = st.executeQuery("SELECT (SELECT COUNT(*) FROM tempdb.sys.database_files WHERE type_desc = 'ROWS'), "
                    + "(SELECT cpu_count FROM sys.dm_os_sys_info)")) {
                if (rs.next()) {
                    file = rs.getLong(1);
                    cpu = rs.getLong(2);
                }
            }
            long attesi = Math.min(Math.max(cpu, 1), 8);
            if (file > 0 && file < attesi) {
                controlli.add(esito("tempdb", "warn", "tempdb con " + file + " file dati su " + cpu + " CPU",
                        "Portare i file dati di tempdb a " + attesi + " (uno per CPU, massimo 8), della stessa dimensione.",
                        "https://learn.microsoft.com/sql/relational-databases/databases/tempdb-database"));
            }
            if (Controlli.attivo(s, "risorse")) {
                try (ResultSet rs = st.executeQuery("SELECT cntr_value FROM sys.dm_os_performance_counters "
                        + "WHERE counter_name = 'Page life expectancy' AND object_name LIKE '%Buffer Manager%'")) {
                    if (rs.next()) {
                        long ple = rs.getLong(1);
                        if (ple < Impostazioni.intero("insight.sqlserver_ple_secondi")) {
                            controlli.add(esito("Memoria", "warn", "page life expectancy di " + ple + " secondi",
                                    "Le pagine dati escono spesso dalla cache: verificare max server memory, query che leggono grandi quantità di dati e indici mancanti.",
                                    "https://www.sqlshack.com/page-life-expectancy-ple-in-sql-server/"));
                        }
                    }
                }
            }
            if (!Controlli.attivo(s, "database")) return;
            List<String> auto = new ArrayList<>();
            List<String> verifica = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT name, is_auto_close_on, is_auto_shrink_on, page_verify_option_desc "
                    + "FROM sys.databases WHERE database_id > 4 AND state_desc = 'ONLINE'")) {
                while (rs.next()) {
                    if (rs.getBoolean(2) || rs.getBoolean(3)) auto.add(rs.getString(1));
                    if (!"CHECKSUM".equals(rs.getString(4))) verifica.add(rs.getString(1));
                }
            }
            if (!auto.isEmpty()) {
                controlli.add(esito("Auto close / auto shrink", "warn", "attivi su " + String.join(", ", auto),
                        "Disattivare AUTO_CLOSE e AUTO_SHRINK: causano chiusure e ridimensionamenti continui.", null));
            }
            if (!verifica.isEmpty()) {
                controlli.add(esito("Verifica delle pagine", "warn", "PAGE_VERIFY non CHECKSUM su " + String.join(", ", verifica),
                        "Impostare PAGE_VERIFY = CHECKSUM per rilevare la corruzione delle pagine.", null));
            }
            if (auto.isEmpty() && verifica.isEmpty()) {
                controlli.add(esito("Impostazioni dei database", "ok", "nessun problema rilevato", "", null));
            }
        }
    }

    private static void controllaVersione(String motore, String versione, List<Map<String, Object>> controlli) {
        if (motore.equals("PostgreSQL")) {
            int major = 0;
            try {
                major = Integer.parseInt(versione.split("\\.")[0].trim());
            } catch (NumberFormatException ignored) {
                // versione non riconosciuta: nessun controllo
            }
            if (major >= 17) {
                controlli.add(esito("Versione", "ok", "PostgreSQL " + versione, "", null));
            } else if (major == 14) {
                controlli.add(esito("Versione", "warn", "PostgreSQL " + versione + ": fine supporto il 12 novembre 2026",
                        "Pianificare l'aggiornamento a PostgreSQL 17 (o 16).", "https://www.postgresql.org/support/versioning/"));
            } else if (major > 0 && major < 14) {
                controlli.add(esito("Versione", "bad", "PostgreSQL " + versione + ": versione fuori supporto",
                        "Aggiornare a una versione supportata, prima possibile.", "https://www.postgresql.org/support/versioning/"));
            } else {
                controlli.add(esito("Versione", "info", "PostgreSQL " + versione, "Verificare il ciclo di supporto.", null));
            }
            return;
        }
        // major.minor: "8.4.11-commercial" -> "8.4", "11.4.5-MariaDB" -> "11.4"
        String[] parti = versione.split("[.-]");
        String mm = parti.length >= 2 ? parti[0] + "." + parti[1] : versione;
        if (motore.equals("MySQL")) {
            if (mm.startsWith("8.0")) {
                controlli.add(esito("Versione", "bad", "MySQL " + versione + ": fine vita (EOL) raggiunta",
                        "Pianificare l'aggiornamento a MySQL 8.4 LTS (supporto esteso fino al 2032).",
                        "https://endoflife.ai/mysql/8.0"));
            } else if (mm.startsWith("8.4")) {
                controlli.add(esito("Versione", "ok", "MySQL " + versione + " LTS", "", null));
            } else {
                controlli.add(esito("Versione", "info", "MySQL " + versione, "Verificare il ciclo di supporto della versione.", null));
            }
        } else if (motore.equals("MariaDB")) {
            // Date del ciclo di vita della comunità (MariaDB Foundation): 10.6 chiusa il 6 luglio 2026
            if (mm.startsWith("10.6")) {
                controlli.add(esito("Versione", "bad", "MariaDB " + versione + ": fine supporto il 6 luglio 2026",
                        "Pianificare l'aggiornamento a MariaDB 11.4 LTS o 11.8 LTS.", "https://mariadb.org/mariadb-server-10-6-reaches-end-of-life-on-july-6th/"));
            } else if (mm.startsWith("10.11")) {
                controlli.add(esito("Versione", "info", "MariaDB " + versione + " LTS: supporto fino a febbraio 2028",
                        "Pianificare l'aggiornamento entro febbraio 2028 (11.4 o 11.8 LTS).", null));
            } else if (mm.startsWith("11.4") || mm.startsWith("11.8") || mm.startsWith("12.3")) {
                controlli.add(esito("Versione", "ok", "MariaDB " + versione + " LTS", "", null));
            } else {
                controlli.add(esito("Versione", "info", "MariaDB " + versione, "Verificare il ciclo di supporto della versione.", null));
            }
        } else if (motore.equals("MongoDB")) {
            // Date dal ciclo di vita MongoDB (mongodb.com, Support Policy): 6.0 chiusa il 31/7/2025, 8.2 il 31/7/2026
            String[] p = versione.split("\\.");
            String ramo = p.length >= 2 ? p[0] + "." + p[1] : versione;
            String pagina = "https://www.mongodb.com/legal/support-policy/lifecycles";
            switch (ramo) {
                case "6.0" -> controlli.add(esito("Versione", "bad", "MongoDB " + versione + ": fine supporto il 31 luglio 2025",
                        "Pianificare l'aggiornamento a MongoDB 8.0 (supporto fino al 31 ottobre 2029).", pagina));
                case "7.0" -> controlli.add(esito("Versione", "warn", "MongoDB " + versione + ": fine supporto il 31 agosto 2027",
                        "Pianificare l'aggiornamento a MongoDB 8.0.", pagina));
                case "8.0", "8.3" -> controlli.add(esito("Versione", "ok", "MongoDB " + versione + ": supporto fino al 31 ottobre 2029", "", pagina));
                case "8.2" -> controlli.add(esito("Versione", "bad", "MongoDB " + versione + ": fine supporto il 31 luglio 2026",
                        "Aggiornare a MongoDB 8.0 o 8.3.", pagina));
                default -> controlli.add(esito("Versione", "info", "MongoDB " + versione, "Verificare il ciclo di supporto della versione.", pagina));
            }
        } else if (motore.equals("SQL Server")) {
            // Major di SQL Server da ProductVersion: 13=2016, 14=2017, 15=2019, 16=2022
            int major = 0;
            try {
                major = Integer.parseInt(versione.split("\\.")[0].trim());
            } catch (NumberFormatException ignored) {
                // versione non riconosciuta: nessun controllo
            }
            String pagina = "https://learn.microsoft.com/it-it/lifecycle/products/?products=sql-server";
            if (major >= 16) {
                controlli.add(esito("Versione", "ok", "SQL Server 2022 (" + versione + ")", "", pagina));
            } else if (major == 15) {
                controlli.add(esito("Versione", "ok", "SQL Server 2019 (" + versione + "): supporto esteso fino a gennaio 2030", "", pagina));
            } else if (major == 14) {
                controlli.add(esito("Versione", "warn", "SQL Server 2017 (" + versione + "): supporto esteso fino a ottobre 2027",
                        "Pianificare l'aggiornamento a SQL Server 2022 (o 2019).", pagina));
            } else if (major == 13) {
                controlli.add(esito("Versione", "bad", "SQL Server 2016 (" + versione + "): supporto esteso terminato a luglio 2026",
                        "Senza aggiornamenti di sicurezza: pianificare l'upgrade. Verificare sulla pagina Microsoft l'eventuale ESU.", pagina));
            } else if (major > 0) {
                controlli.add(esito("Versione", "bad", "SQL Server " + versione + ": versione fuori supporto",
                        "Aggiornare a una versione supportata, prima possibile.", pagina));
            } else {
                controlli.add(esito("Versione", "info", "SQL Server " + versione, "Verificare il ciclo di supporto della versione.", pagina));
            }
        }
    }

    private static void controllaReplica(Map<String, Object> rep, List<Map<String, Object>> controlli) {
        for (Map<String, Object> ch : Monitor.list(rep.get("replica"))) {
            String canale = Main.str(ch.get("canale"));
            String etichetta = "Replica" + (canale.isEmpty() ? "" : " " + canale);
            if ("No".equalsIgnoreCase(Main.str(ch.get("ioAttivo"))) || "No".equalsIgnoreCase(Main.str(ch.get("sqlAttivo")))) {
                controlli.add(esito(etichetta, "bad", "thread IO o SQL fermo: " + Main.str(ch.get("ultimoErrore")),
                        "Controllare SHOW REPLICA STATUS e l'errore riportato prima di riavviare la replica.", null));
            } else if (ch.get("secondiDiRitardo") != null && Main.num(ch.get("secondiDiRitardo")) > Impostazioni.intero("insight.replica_ritardo_secondi")) {
                controlli.add(esito(etichetta, "warn", "ritardo di " + ch.get("secondiDiRitardo") + " secondi",
                        "Verificare carico della repliche e transazioni lunghe sulla sorgente.", null));
            } else {
                controlli.add(esito(etichetta, "ok", "thread attivi, ritardo contenuto", "", null));
            }
        }
    }

    private static void controllaCluster(Map<String, Object> rep, List<Map<String, Object>> controlli) {
        Map<String, Object> cl = Monitor.map(rep.get("cluster"));
        if (!cl.isEmpty()) {
            String stato = Main.str(cl.get("stato"));
            if (!"ONLINE".equals(stato)) {
                controlli.add(esito("Cluster", "bad", "stato " + stato,
                        "Verificare i membri del gruppo e il quorum prima di qualunque intervento.", null));
            } else {
                controlli.add(esito("Cluster", "ok", "ONLINE " + cl.get("online") + "/" + cl.get("totale"), "", null));
            }
        }
        Map<String, Object> galera = Monitor.map(rep.get("galera"));
        if (!galera.isEmpty() && !"Synced".equals(Main.str(galera.get("statoLocale")))) {
            controlli.add(esito("Galera", "warn", "nodo non sincronizzato: " + galera.get("statoLocale"),
                    "Attendere la sincronizzazione o verificare lo stato del nodo.", null));
        }
    }

    private static void controllaSchema(Map<String, Object> rep, List<Map<String, Object>> controlli) {
        Map<String, Object> indici = Monitor.map(rep.get("indici"));
        int senzaPk = Monitor.list(indici.get("tabelleSenzaPrimaryKey")).size();
        if (senzaPk > 0) {
            controlli.add(esito("Chiave primaria", "warn", senzaPk + " tabelle senza primary key",
                    "Ogni tabella InnoDB dovrebbe avere una chiave primaria: senza di essa la replica e le modifiche sono più costose.",
                    "https://dev.mysql.com/doc/refman/8.4/en/innodb-index-types.html"));
        } else {
            controlli.add(esito("Chiave primaria", "ok", "tutte le tabelle hanno una primary key", "", null));
        }
        int inutilizzati = Monitor.list(indici.get("indiciInutilizzati")).size();
        if (inutilizzati > 0) {
            controlli.add(esito("Indici", "info", inutilizzati + " indici mai usati dall'ultimo riavvio",
                    "Valutare la rimozione: ogni indice rallenta le scritture. Controllare prima i falsi positivi (indici UNIQUE).", null));
        }
    }

    private static void controllaUtenti(Map<String, Object> rep, List<Map<String, Object>> controlli) {
        int anonimi = 0;
        boolean rootRemoto = false;
        int scaduti = 0;
        int bloccati = 0;
        for (Map<String, Object> u : Monitor.list(rep.get("utenti"))) {
            String nome = Main.str(u.get("utente"));
            if (nome.startsWith("@")) anonimi++;
            if (nome.equals("root@%")) rootRemoto = true;
            if (Boolean.TRUE.equals(u.get("passwordScaduta"))) scaduti++;
            if (Boolean.TRUE.equals(u.get("bloccato"))) bloccati++;
        }
        if (anonimi > 0) {
            controlli.add(esito("Utenti", "bad", anonimi + " utenti anonimi",
                    "Eliminare gli utenti anonimi: permettono l'accesso senza identità.",
                    "https://dev.mysql.com/doc/refman/8.4/en/password-security-admin.html"));
        }
        if (rootRemoto) {
            controlli.add(esito("Utenti", "warn", "root accessibile da qualunque host (root@%)",
                    "Limitare root a localhost o a host fidati; usare account nominativi per l'amministrazione.",
                    "https://dev.mysql.com/doc/refman/8.4/en/password-security-admin.html"));
        }
        if (scaduti > 0 || bloccati > 0) {
            controlli.add(esito("Utenti", "info", scaduti + " password scadute, " + bloccati + " account bloccati",
                    "Verificare se servono ancora: gli account scaduti bloccano i servizi che li usano.",
                    "https://dev.mysql.com/doc/refman/8.4/en/password-management.html"));
        }
        if (anonimi == 0 && !rootRemoto && scaduti == 0 && bloccati == 0) {
            controlli.add(esito("Utenti", "ok", "nessuna anomalia negli account", "", null));
        }
    }

    private static void controllaQuery(Map<String, Object> rep, List<Map<String, Object>> controlli, JsonNode s) {
        if (Controlli.attivo(s, "query_lente")) {
            long senzaIndice = 0;
            for (Map<String, Object> q : Monitor.list(rep.get("queryLente"))) {
                if (Main.num(q.get("SENZA_INDICE")) > 0) senzaIndice++;
            }
            if (senzaIndice > 0) {
                controlli.add(esito("Query", "warn", senzaIndice + " query lente senza indice utilizzabile",
                        "Aprire il piano di esecuzione delle query evidenziate e valutare un indice sulle colonne del filtro.", null));
            }
        }
        if (!Controlli.attivo(s, "query_attive")) return;
        long lunghe = Monitor.list(rep.get("queryAttive")).stream()
                .filter(q -> Main.num(q.get("SECONDI")) > Impostazioni.intero("query.attive_soglia_secondi")).count();
        if (lunghe > 0) {
            controlli.add(esito("Query attive", "warn", lunghe + " query in esecuzione da oltre " + Impostazioni.intero("query.attive_soglia_secondi") + " secondi",
                    "Verificare transazioni lunghe e blocchi; interrompere solo se confermato.", null));
        }
    }

    /** Variabili di configurazione e contatori di stato, letti con una sola connessione. */
    private static void controllaConfigurazione(Connection c, List<Map<String, Object>> controlli, JsonNode s) throws SQLException {
        Map<String, String> stato = new LinkedHashMap<>();
        for (Map<String, Object> r : Main.q(c, "SHOW GLOBAL STATUS")) {
            stato.put(Main.str(r.get("Variable_name")), Main.str(r.get("Value")));
        }
        long maxConn = Main.num(Main.varOrNull(c, "@@max_connections"));
        long connessi = Main.num(stato.get("Threads_connected"));
        long massimoUsate = Main.num(stato.get("Max_used_connections"));
        long pctConn = Impostazioni.intero("insight.connessioni_pct");
        if (maxConn > 0 && Controlli.attivo(s, "connessioni")) {
            if (massimoUsate * 100 >= maxConn * pctConn) {
                controlli.add(esito("Connessioni", "warn", "picco di " + massimoUsate + " su " + maxConn + " connessioni consentite",
                        "Aumentare max_connections con cautela o usare un pool di connessioni nelle applicazioni.", null));
            } else {
                controlli.add(esito("Connessioni", "ok", connessi + " connessioni attive, picco " + massimoUsate + " su " + maxConn, "", null));
            }
        }

        long richieste = Main.num(stato.get("Innodb_buffer_pool_read_requests"));
        long letture = Main.num(stato.get("Innodb_buffer_pool_reads"));
        // Con pochi accessi (es. appena dopo un riavvio) il rapporto non è significativo
        if (richieste >= Impostazioni.intero("insight.hit_minimo_accessi")) {
            double hit = Math.max(0.0, 1.0 - (double) letture / richieste);
            if (hit * 100 < Impostazioni.intero("insight.buffer_pool_hit_pct")) {
                controlli.add(esito("Buffer pool", "warn", String.format("hit ratio %.1f%%", hit * 100),
                        "Verificare innodb_buffer_pool_size rispetto alla memoria disponibile e al working set.",
                        "https://mariadb.com/docs/server/ha-and-performance/mariadb-memory-allocation"));
            } else {
                controlli.add(esito("Buffer pool", "ok", String.format("hit ratio %.1f%%", hit * 100), "", null));
            }
        }

        long tmp = Main.num(stato.get("Created_tmp_tables"));
        long tmpDisco = Main.num(stato.get("Created_tmp_disk_tables"));
        if (tmp > Impostazioni.intero("insight.tmp_minimo_tabelle") && tmpDisco * 100 > tmp * Impostazioni.intero("insight.tmp_disco_pct")) {
            controlli.add(esito("Tabelle temporanee", "warn", tmpDisco + " su " + tmp + " su disco",
                    "Molte tabelle temporanee su disco: rivedere query con ORDER BY/GROUP BY e tmp_table_size.", null));
        }

        Object logBin = Main.varOrNull(c, "@@log_bin");
        if (logBin != null && !isOn(logBin)) {
            controlli.add(esito("Binary log", "warn", "binary log disattivato: nessun ripristino point-in-time",
                    "Attivare log_bin per poter ripristinare fino a un istante preciso.", null));
        }
        Object flush = Main.varOrNull(c, "@@innodb_flush_log_at_trx_commit");
        if (flush != null && Main.num(flush) != 1) {
            controlli.add(esito("Durabilità", "warn", "innodb_flush_log_at_trx_commit = " + flush,
                    "Con valore diverso da 1 un crash può far perdere le ultime transazioni confermate.",
                    "https://dev.mysql.com/doc/refman/8.4/en/innodb-parameters.html"));
        }
        Object syncBin = Main.varOrNull(c, "@@sync_binlog");
        if (logBin != null && isOn(logBin) && syncBin != null && Main.num(syncBin) != 1) {
            controlli.add(esito("Durabilità", "warn", "sync_binlog = " + syncBin,
                    "Impostare sync_binlog = 1 insieme al binary log per la durabilità.", null));
        }
        Object slowLog = Main.varOrNull(c, "@@slow_query_log");
        if (slowLog != null && !isOn(slowLog)) {
            controlli.add(esito("Slow query log", "info", "slow query log disattivato",
                    "Attivarlo per individuare le query lente in modo continuo.", null));
        }
        Object sqlMode = Main.varOrNull(c, "@@sql_mode");
        if (sqlMode != null && !String.valueOf(sqlMode).contains("STRICT_TRANS_TABLES")) {
            controlli.add(esito("SQL mode", "warn", "sql_mode senza STRICT_TRANS_TABLES",
                    "Senza la modalità strict i dati non validi vengono troncati o accettati in silenzio.",
                    "https://dev.mysql.com/doc/refman/8.4/en/sql-mode.html"));
        }
        Object localInfile = Main.varOrNull(c, "@@local_infile");
        if (localInfile != null && isOn(localInfile)) {
            controlli.add(esito("Sicurezza", "warn", "local_infile attivo",
                    "Disattivare local_infile se non serve per il caricamento dei dati.", null));
        }

        // Binary log: formato e scadenza. MySQL 8 usa ROW come predefinito, MariaDB MIXED: il controllo vale per entrambi.
        if (logBin != null && isOn(logBin)) {
            Object formato = Main.varOrNull(c, "@@binlog_format");
            if (formato != null && !"ROW".equalsIgnoreCase(String.valueOf(formato))) {
                controlli.add(esito("Binary log", "warn", "binlog_format = " + formato,
                        "Usare binlog_format = ROW: con STATEMENT o MIXED alcune modifiche non si replicano o si ripristinano in modo deterministico.",
                        "https://dev.mysql.com/doc/refman/8.4/en/binary-log-formats.html"));
            }
            // MySQL 8: binlog_expire_logs_seconds; MariaDB: expire_logs_days. Zero = nessuna scadenza.
            Object scadenzaSec = Main.varOrNull(c, "@@binlog_expire_logs_seconds");
            Object scadenzaGiorni = scadenzaSec == null ? Main.varOrNull(c, "@@expire_logs_days") : null;
            if ((scadenzaSec != null && Main.num(scadenzaSec) == 0) || (scadenzaGiorni != null && Main.num(scadenzaGiorni) == 0)) {
                controlli.add(esito("Binary log", "warn", "binlog senza scadenza: i file non vengono mai rimossi",
                        "Impostare la conservazione (binlog_expire_logs_seconds o expire_logs_days) e fare backup dei binlog: il disco può riempirsi.",
                        null));
            }
        }
        // History list di InnoDB: letture o transazioni lunghe impediscono al purge di ripulire le vecchie versioni
        for (Map<String, Object> r : Main.q(c, "SHOW ENGINE INNODB STATUS")) {
            java.util.regex.Matcher hm = java.util.regex.Pattern.compile("History list length (\\d+)").matcher(Main.str(r.get("Status")));
            if (hm.find()) {
                long hll = Long.parseLong(hm.group(1));
                if (hll > Impostazioni.intero("insight.history_list_max")) {
                    controlli.add(esito("InnoDB", "warn", "history list di " + hll + " transazioni",
                            "Cercare transazioni aperte o letture lunghe (SHOW ENGINE INNODB STATUS, information_schema.INNODB_TRX): finché restano aperte il purge non ripulisce.",
                            "https://dev.mysql.com/doc/refman/8.4/en/innodb-multi-versioning.html"));
                }
            }
        }
        // Galera: il flow control rallenta tutto il cluster quando un nodo non regge il ritmo degli altri
        if (Main.num(stato.get("wsrep_cluster_size")) > 0) {
            double pausa = 0;
            try {
                pausa = Double.parseDouble(stato.getOrDefault("wsrep_flow_control_paused", "0"));
            } catch (NumberFormatException ignored) {
                // valore non numerico: nessun controllo
            }
            if (pausa > 0.1) {
                controlli.add(esito("Galera", "warn", String.format("flow control in pausa per il %.1f%% del tempo", pausa * 100),
                        "Il nodo più lento frena l'intero cluster: verificare I/O, carico e wsrep_local_recv_queue del nodo indicato.",
                        "https://mariadb.com/docs/galera-cluster/galera-management/performance-tuning/flow-control-in-galera-cluster"));
            }
            Object autoinc = Main.varOrNull(c, "@@innodb_autoinc_lock_mode");
            if (autoinc != null && Main.num(autoinc) != 2) {
                controlli.add(esito("Galera", "warn", "innodb_autoinc_lock_mode = " + autoinc + " con Galera",
                        "Galera richiede innodb_autoinc_lock_mode = 2 per generare valori auto-incrementali senza conflitti tra nodi.", null));
            }
        }
        Object fPerTable = Main.varOrNull(c, "@@innodb_file_per_table");
        if (fPerTable != null && !isOn(fPerTable)) {
            controlli.add(esito("Storage InnoDB", "warn", "innodb_file_per_table disattivo: tutte le tabelle nello stesso file condiviso",
                    "Attivare innodb_file_per_table: senza di esso lo spazio di un file condiviso non si restituisce al sistema operativo.",
                    null));
        }
        Object tls = Main.varOrNull(c, "@@require_secure_transport");
        if (tls != null && !isOn(tls)) {
            controlli.add(esito("Sicurezza", "info", "connessioni in chiaro ammesse (require_secure_transport off)",
                    "Se i client supportano TLS, impostare require_secure_transport = ON.",
                    "https://dev.mysql.com/doc/refman/8.4/en/using-encrypted-connections.html"));
        }
        // Solo MySQL 8.0: plugin di autenticazione predefinito. mysql_native_password è deprecato in 8.4.
        Object plugin = Main.varOrNull(c, "@@default_authentication_plugin");
        if (plugin != null && "mysql_native_password".equals(String.valueOf(plugin))) {
            controlli.add(esito("Password", "warn", "default_authentication_plugin = mysql_native_password",
                    "Usare caching_sha2_password: mysql_native_password è deprecato dalla 8.4 e meno sicuro.",
                    "https://dev.mysql.com/doc/refman/8.4/en/upgrading-from-previous-series.html"));
        }
    }

    private static boolean isOn(Object v) {
        String s = String.valueOf(v);
        return s.equalsIgnoreCase("ON") || s.equals("1");
    }

    private static Map<String, Object> esito(String controllo, String livello, String esito, String raccomandazione, String riferimento) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("controllo", controllo);
        m.put("livello", livello);
        m.put("esito", esito);
        m.put("raccomandazione", raccomandazione);
        m.put("riferimento", riferimento);
        return m;
    }

    /** Riepilogo di un server: punteggio da 0 a 100 (ogni problema toglie punti) e contatori per livello. */
    private static Map<String, Object> riepilogo(String id, String nome, String motore, String versione,
                                                 List<Map<String, Object>> controlli, boolean giu) {
        int punteggio = giu ? 0 : 100;
        Map<String, Integer> conteggi = new LinkedHashMap<>(Map.of("ok", 0, "info", 0, "warn", 0, "bad", 0));
        for (Map<String, Object> c : controlli) {
            String l = String.valueOf(c.get("livello"));
            conteggi.merge(l, 1, Integer::sum);
            if (giu) continue; // server spento: punteggio 0, nessun altro controllo da penalizzare
            punteggio -= switch (l) {
                case "bad" -> 20;
                case "warn" -> 8;
                case "info" -> 2;
                default -> 0;
            };
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("nome", nome);
        m.put("motore", motore);
        m.put("versione", versione);
        m.put("punteggio", Math.max(0, punteggio));
        m.put("conteggi", conteggi);
        m.put("controlli", controlli);
        return m;
    }
}
