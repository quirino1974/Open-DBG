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
            if (s.path("insight").asBoolean(false)) out.add(s.get("id").asText());
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
            if (!s.path("insight").asBoolean(false)) continue;
            String tipo = Main.text(s, "kind");
            if (!tipo.equals("server") && !tipo.equals("postgres") && !tipo.equals("sqlserver")) continue;
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
            controllaTopologiaSqlServer(rep, controlli);
            controllaRisorseSqlServer(rep, controlli);
            controllaQuery(rep, controlli);
            try (Connection c = Main.open(s)) {
                controllaConfigurazioneSqlServer(c, controlli);
            } catch (SQLException e) {
                controlli.add(esito("Configurazione", "warn", "lettura non riuscita: " + e.getMessage(),
                        "Verificare le credenziali del monitor.", null));
            }
            return riepilogo(id, nome, motore, versione, controlli, false);
        }

        // Dati già raccolti dal monitor
        controllaReplica(rep, controlli);
        controllaCluster(rep, controlli);
        controllaSchema(rep, controlli);
        controllaUtenti(rep, controlli);
        controllaQuery(rep, controlli);

        // Configurazione e stato, letti in sola lettura dal server
        try (Connection c = Main.open(s)) {
            if ("PostgreSQL".equals(motore)) controllaConfigurazionePg(c, controlli);
            else controllaConfigurazione(c, controlli);
        } catch (SQLException e) {
            controlli.add(esito("Configurazione", "warn", "lettura non riuscita: " + e.getMessage(),
                    "Verificare le credenziali del monitor.", null));
        }
        return riepilogo(id, nome, motore, versione, controlli, false);
    }

    /** Controlli di configurazione e stato di PostgreSQL (sola lettura). */
    private static void controllaConfigurazionePg(Connection c, List<Map<String, Object>> controlli) throws SQLException {
        Map<String, String> p = new LinkedHashMap<>();
        for (Map<String, Object> r : Main.q(c, "SELECT name, setting FROM pg_settings WHERE name IN "
                + "('wal_level','archive_mode','password_encryption','max_connections','log_min_duration_statement')")) {
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
        long attive = Main.num(Main.q(c, "SELECT count(*) AS n FROM pg_stat_activity").get(0).get("n"));
        if (maxConn > 0 && attive >= maxConn * 0.9) {
            controlli.add(esito("Connessioni", "warn", attive + " connessioni su " + maxConn + " consentite",
                    "Usare un pool di connessioni o aumentare max_connections con cautela.", null));
        } else if (maxConn > 0) {
            controlli.add(esito("Connessioni", "ok", attive + " connessioni su " + maxConn, "", null));
        }
        Map<String, Object> cache = Main.q(c, "SELECT COALESCE(SUM(blks_hit),0) AS hit, COALESCE(SUM(blks_read),0) AS letti "
                + "FROM pg_stat_database").get(0);
        long hit = Main.num(cache.get("hit"));
        long letti = Main.num(cache.get("letti"));
        if (hit + letti >= 10_000) {
            double ratio = (double) hit / (hit + letti);
            controlli.add(esito("Cache", ratio < 0.95 ? "warn" : "ok", String.format("hit ratio %.1f%%", ratio * 100),
                    ratio < 0.95 ? "Verificare shared_buffers rispetto al working set." : "", null));
        }
        long inattive = Main.num(Main.q(c, "SELECT count(*) AS n FROM pg_stat_activity WHERE state = 'idle in transaction' "
                + "AND now() - state_change > interval '5 minutes'").get(0).get("n"));
        if (inattive > 0) {
            controlli.add(esito("Transazioni", "warn", inattive + " transazioni inattive da oltre 5 minuti",
                    "Le transazioni aperte bloccano il vacuum: chiuderle o impostare idle_in_transaction_session_timeout.", null));
        }
        String logLente = p.getOrDefault("log_min_duration_statement", "-1");
        if (logLente.equals("-1")) {
            controlli.add(esito("Query lente", "info", "log delle query lente disattivato",
                    "Impostare log_min_duration_statement (es. 1000 ms) per tracciare le query lente.", null));
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
        if (pct >= 90) {
            controlli.add(esito("Memoria", "warn", String.format("memoria del server al %.0f%%", pct),
                    "Verificare la memoria massima di SQL Server (max server memory) e il carico delle query.", null));
        } else {
            controlli.add(esito("Memoria", "ok", String.format("memoria del server al %.0f%%", pct), "", null));
        }
    }

    /** Impostazioni di SQL Server che incidono su sicurezza e prestazioni (sola lettura). */
    private static void controllaConfigurazioneSqlServer(Connection c, List<Map<String, Object>> controlli) throws SQLException {
        try (Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT CAST(value_in_use AS BIGINT) FROM sys.configurations WHERE name = 'max server memory (MB)'")) {
                if (rs.next() && rs.getLong(1) >= 2147483647L) {
                    controlli.add(esito("Memoria massima", "warn", "max server memory non limitata",
                            "Impostare max server memory lasciando memoria al sistema operativo.", null));
                }
            }
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
                controlli.add(esito("Versione", "warn", "PostgreSQL " + versione + ": fine supporto nel novembre 2026",
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
            if (mm.startsWith("11.4")) {
                controlli.add(esito("Versione", "ok", "MariaDB " + versione + " LTS", "", null));
            } else if (mm.startsWith("10.11")) {
                controlli.add(esito("Versione", "warn", "MariaDB " + versione + ": ciclo di supporto in scadenza",
                        "Pianificare l'aggiornamento a MariaDB 11.4 LTS.", null));
            } else {
                controlli.add(esito("Versione", "info", "MariaDB " + versione, "Verificare il ciclo di supporto della versione.", null));
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
            } else if (ch.get("secondiDiRitardo") != null && Main.num(ch.get("secondiDiRitardo")) > 60) {
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

    private static void controllaQuery(Map<String, Object> rep, List<Map<String, Object>> controlli) {
        long senzaIndice = 0;
        for (Map<String, Object> q : Monitor.list(rep.get("queryLente"))) {
            if (Main.num(q.get("SENZA_INDICE")) > 0) senzaIndice++;
        }
        if (senzaIndice > 0) {
            controlli.add(esito("Query", "warn", senzaIndice + " query lente senza indice utilizzabile",
                    "Aprire il piano di esecuzione delle query evidenziate e valutare un indice sulle colonne del filtro.", null));
        }
        long lunghe = Monitor.list(rep.get("queryAttive")).stream()
                .filter(q -> Main.num(q.get("SECONDI")) > Impostazioni.intero("query.attive_soglia_secondi")).count();
        if (lunghe > 0) {
            controlli.add(esito("Query attive", "warn", lunghe + " query in esecuzione da oltre " + Impostazioni.intero("query.attive_soglia_secondi") + " secondi",
                    "Verificare transazioni lunghe e blocchi; interrompere solo se confermato.", null));
        }
    }

    /** Variabili di configurazione e contatori di stato, letti con una sola connessione. */
    private static void controllaConfigurazione(Connection c, List<Map<String, Object>> controlli) throws SQLException {
        Map<String, String> stato = new LinkedHashMap<>();
        for (Map<String, Object> r : Main.q(c, "SHOW GLOBAL STATUS")) {
            stato.put(Main.str(r.get("Variable_name")), Main.str(r.get("Value")));
        }
        long maxConn = Main.num(Main.varOrNull(c, "@@max_connections"));
        long connessi = Main.num(stato.get("Threads_connected"));
        long massimoUsate = Main.num(stato.get("Max_used_connections"));
        if (maxConn > 0) {
            if (massimoUsate >= maxConn * 0.9) {
                controlli.add(esito("Connessioni", "warn", "picco di " + massimoUsate + " su " + maxConn + " connessioni consentite",
                        "Aumentare max_connections con cautela o usare un pool di connessioni nelle applicazioni.", null));
            } else {
                controlli.add(esito("Connessioni", "ok", connessi + " connessioni attive, picco " + massimoUsate + " su " + maxConn, "", null));
            }
        }

        long richieste = Main.num(stato.get("Innodb_buffer_pool_read_requests"));
        long letture = Main.num(stato.get("Innodb_buffer_pool_reads"));
        // Con pochi accessi (es. appena dopo un riavvio) il rapporto non è significativo
        if (richieste >= 10_000) {
            double hit = Math.max(0.0, 1.0 - (double) letture / richieste);
            if (hit < 0.95) {
                controlli.add(esito("Buffer pool", "warn", String.format("hit ratio %.1f%%", hit * 100),
                        "Verificare innodb_buffer_pool_size rispetto alla memoria disponibile e al working set.",
                        "https://mariadb.com/docs/server/ha-and-performance/mariadb-memory-allocation"));
            } else {
                controlli.add(esito("Buffer pool", "ok", String.format("hit ratio %.1f%%", hit * 100), "", null));
            }
        }

        long tmp = Main.num(stato.get("Created_tmp_tables"));
        long tmpDisco = Main.num(stato.get("Created_tmp_disk_tables"));
        if (tmp > 100 && tmpDisco * 4 > tmp) {
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
