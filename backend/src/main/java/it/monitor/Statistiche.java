package it.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Controllo degli indici per MySQL/MariaDB, PostgreSQL e SQL Server (frammentazione, letture e scritture),
 * con la topologia dell'istanza SQL Server. Usa i server dell'anagrafica. Solo lettura.
 */
final class Statistiche {
    private Statistiche() {}

    /** Tipi di server che hanno il controllo degli indici. */
    static boolean supportato(JsonNode s) {
        String k = Main.text(s, "kind");
        return k.equals("server") || k.equals("postgres") || k.equals("sqlserver");
    }

    /** Instrada /api/statistiche/... Restituisce null se il percorso non esiste. */
    static Object api(HttpExchange ex, String m, String p, Auth.Principal me) throws Exception {
        if (!m.equals("GET")) return null;
        Main.richiedi(me, Auth.P_DIAGNOSTICA);
        String[] seg = p.substring("/statistiche".length()).replaceFirst("^/", "").split("/");
        if (seg.length == 1 && seg[0].equals("server")) return elencoServer();
        if (seg.length != 2) return null;
        String id = seg[0];
        String db = Main.queryParam(ex, "db");
        switch (seg[1]) {
            case "database": return pulisci(databases(cfg(id, null)));
            case "indici": return pulisci(indici(cfg(id, db), db));
            case "topologia": return pulisci(topologia(cfg(id, null)));
            default: return null;
        }
    }

    /** Date e orari come testo ISO, così il frontend li mostra in modo uniforme. */
    @SuppressWarnings("unchecked")
    static Object pulisci(Object o) {
        if (o instanceof java.util.Date d) return d.toInstant().toString();
        if (o instanceof java.time.temporal.TemporalAccessor t && !(o instanceof Number)) return o.toString();
        if (o instanceof List<?> l) {
            for (Object x : l) pulisci(x);
            return o;
        }
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                Object v = e.getValue();
                if (v instanceof java.util.Date || v instanceof java.time.temporal.TemporalAccessor) e.setValue(v instanceof java.util.Date d ? d.toInstant().toString() : v.toString());
                else if (v instanceof List<?> || v instanceof Map<?, ?>) pulisci(v);
            }
        }
        return o;
    }

    static List<Map<String, Object>> elencoServer() throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode s : Main.loadServers()) {
            if (!supportato(s)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", Main.text(s, "id"));
            m.put("name", Main.text(s, "name"));
            m.put("kind", Main.text(s, "kind"));
            m.put("tecnologia", etichetta(s));
            m.put("host", Main.text(s, "host"));
            m.put("port", s.get("port").asInt());
            out.add(m);
        }
        return out;
    }

    static String etichetta(JsonNode s) {
        return switch (Main.text(s, "kind")) {
            case "postgres" -> "PostgreSQL";
            case "sqlserver" -> "SQL Server";
            default -> "MySQL / MariaDB";
        };
    }

    /** Configurazione del server dall'anagrafica, con il database scelto (se indicato). */
    static JsonNode cfg(String id, String db) throws SQLException {
        for (JsonNode s : Main.loadServers()) {
            if (!Main.text(s, "id").equals(id)) continue;
            if (!supportato(s)) throw new IllegalArgumentException("Il server non supporta il controllo delle statistiche");
            ObjectNode c = (ObjectNode) s.deepCopy();
            if (db != null) c.put("database", db);
            return c;
        }
        throw new IllegalArgumentException("Server non trovato");
    }
    static List<Map<String, Object>> databases(JsonNode cfg) throws SQLException {
        String sql;
        if (Main.isPostgres(cfg)) sql = "SELECT datname AS name FROM pg_database WHERE datallowconn AND NOT datistemplate ORDER BY 1";
        // Solo i database che il login può aprire: gli altri darebbero "Cannot open database ... requested by the login"
        else if (Main.text(cfg, "kind").equals("sqlserver")) sql = "SELECT name FROM sys.databases WHERE state_desc = 'ONLINE' AND name <> 'tempdb' AND HAS_DBACCESS(name) = 1 ORDER BY name";
        else sql = "SELECT SCHEMA_NAME AS name FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN ('mysql','information_schema','performance_schema','sys') ORDER BY 1";
        try (Connection c = Main.open(cfg); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return Main.rows(rs);
        }
    }

    // ============================================================ indici: frammentazione e scritture/letture

    /**
     * Dieci indici più frammentati e dieci con più scritture che letture, per il database scelto.
     * Le misure dipendono dal motore: vedere le note nel risultato.
     */
    static Map<String, Object> indici(JsonNode cfg, String db) throws SQLException {
        List<Map<String, Object>> frammentazione = new ArrayList<>();
        List<Map<String, Object>> uso = new ArrayList<>();
        List<String> note = new ArrayList<>();
        if (Main.isPostgres(cfg)) indiciPg(cfg, frammentazione, uso, note);
        else if (Main.text(cfg, "kind").equals("sqlserver")) indiciSqlServer(cfg, frammentazione, uso, note);
        else indiciMysql(cfg, db, frammentazione, uso, note);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("frammentati", topFrammentati(frammentazione));
        out.put("scritture", topScritture(uso));
        out.put("note", note);
        out.put("tecnologia", etichetta(cfg));
        return out;
    }

    private static Map<String, Object> riga(String oggetto, String indice, Double frammentazione, Long letture, Long scritture, String dettaglio) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("oggetto", oggetto);
        r.put("indice", indice);
        r.put("frammentazione", frammentazione);
        r.put("letture", letture);
        r.put("scritture", scritture);
        r.put("rapporto", letture == null || scritture == null || letture == 0 ? null : (double) scritture / letture);
        r.put("dettaglio", dettaglio);
        return r;
    }

    static List<Map<String, Object>> topFrammentati(List<Map<String, Object>> righe) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Map<String, Object> r : righe) if (r.get("frammentazione") != null) l.add(r);
        l.sort((a, b) -> Double.compare(dbl(b.get("frammentazione")), dbl(a.get("frammentazione"))));
        return new ArrayList<>(l.subList(0, Math.min(10, l.size())));
    }

    /** Prima gli indici mai letti con scritture, poi per rapporto scritture/letture decrescente. */
    static List<Map<String, Object>> topScritture(List<Map<String, Object>> righe) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Map<String, Object> r : righe) if (dbl(r.get("scritture")) > 0) l.add(r);
        l.sort((a, b) -> {
            boolean senzaLetture = a.get("letture") == null || dbl(a.get("letture")) == 0;
            boolean senzaLettureB = b.get("letture") == null || dbl(b.get("letture")) == 0;
            if (senzaLetture != senzaLettureB) return senzaLetture ? -1 : 1;
            int c = Double.compare(dbl(b.get("rapporto")), dbl(a.get("rapporto")));
            return c != 0 ? c : Double.compare(dbl(b.get("scritture")), dbl(a.get("scritture")));
        });
        return new ArrayList<>(l.subList(0, Math.min(10, l.size())));
    }

    private static void indiciSqlServer(JsonNode cfg, List<Map<String, Object>> frammentazione, List<Map<String, Object>> uso, List<String> note) throws SQLException {
        try (Connection c = Main.open(cfg); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT s.name + '.' + t.name AS oggetto, i.name AS indice, ps.avg_fragmentation_in_percent AS frammentazione, ps.page_count AS pagine "
                    + "FROM sys.dm_db_index_physical_stats(DB_ID(), NULL, NULL, NULL, 'LIMITED') ps "
                    + "JOIN sys.indexes i ON i.object_id = ps.object_id AND i.index_id = ps.index_id "
                    + "JOIN sys.tables t ON t.object_id = ps.object_id JOIN sys.schemas s ON s.schema_id = t.schema_id "
                    + "WHERE ps.index_id > 0 AND ps.page_count >= 100")) {
                for (Map<String, Object> r : Main.rows(rs)) {
                    frammentazione.add(riga(String.valueOf(r.get("oggetto")), String.valueOf(r.get("indice")),
                            dbl(r.get("frammentazione")), null, null, "pagine " + r.get("pagine")));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT s.name + '.' + t.name AS oggetto, i.name AS indice, "
                    + "ISNULL(u.user_seeks, 0) + ISNULL(u.user_scans, 0) + ISNULL(u.user_lookups, 0) AS letture, ISNULL(u.user_updates, 0) AS scritture "
                    + "FROM sys.indexes i JOIN sys.tables t ON t.object_id = i.object_id JOIN sys.schemas s ON s.schema_id = t.schema_id "
                    + "LEFT JOIN sys.dm_db_index_usage_stats u ON u.object_id = i.object_id AND u.index_id = i.index_id AND u.database_id = DB_ID() "
                    + "WHERE i.index_id > 0 AND i.is_hypothetical = 0")) {
                for (Map<String, Object> r : Main.rows(rs)) {
                    long l = (long) dbl(r.get("letture")), w = (long) dbl(r.get("scritture"));
                    uso.add(riga(String.valueOf(r.get("oggetto")), String.valueOf(r.get("indice")), null, l, w,
                            "operazioni di scrittura dal riavvio del server"));
                }
            }
            note.add("Frammentazione: percentuale media di frammentazione delle pagine (modalità LIMITED), per indici con almeno 100 pagine.");
            note.add("Scritture e letture: contatori di sys.dm_db_index_usage_stats, azzerati al riavvio del server.");
        }
    }

    private static void indiciMysql(JsonNode cfg, String db, List<Map<String, Object>> frammentazione, List<Map<String, Object>> uso, List<String> note) throws SQLException {
        try (Connection c = Main.open(cfg)) {
            try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME, DATA_FREE, DATA_LENGTH + INDEX_LENGTH + DATA_FREE AS dimensione FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' AND DATA_LENGTH + INDEX_LENGTH + DATA_FREE > 0")) {
                ps.setString(1, db);
                try (ResultSet rs = ps.executeQuery()) {
                    for (Map<String, Object> r : Main.rows(rs)) {
                        double libero = dbl(r.get("DATA_FREE")), dim = dbl(r.get("dimensione"));
                        frammentazione.add(riga(String.valueOf(r.get("TABLE_NAME")), "(tabella)", Math.min(100.0, 100.0 * libero / dim), null, null,
                                "spazio libero " + Math.round(libero / 1048576.0) + " MB"));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT table_name, index_name, rows_selected, rows_inserted, rows_updated, rows_deleted "
                    + "FROM sys.schema_index_statistics WHERE table_schema = ? AND index_name IS NOT NULL")) {
                ps.setString(1, db);
                try (ResultSet rs = ps.executeQuery()) {
                    for (Map<String, Object> r : Main.rows(rs)) {
                        long l = (long) dbl(r.get("rows_selected"));
                        long w = (long) (dbl(r.get("rows_inserted")) + dbl(r.get("rows_updated")) + dbl(r.get("rows_deleted")));
                        uso.add(riga(String.valueOf(r.get("table_name")), String.valueOf(r.get("index_name")), null, l, w, "righe"));
                    }
                }
            } catch (SQLException e) {
                note.add("Scritture e letture non disponibili: serve SELECT su sys e performance_schema (" + e.getMessage() + ").");
            }
            note.add("Frammentazione a livello di tabella: spazio libero (DATA_FREE) sul totale di dati, indici e spazio libero, stima.");
            if (uso.isEmpty()) {
                note.add("Nessuna scrittura o lettura registrata per gli indici: performance_schema può essere disattivato o senza attività dal riavvio.");
            }
            note.add("Scritture e letture: righe per indice da sys.schema_index_statistics, azzerate al riavvio del server.");
        }
    }

    private static void indiciPg(JsonNode cfg, List<Map<String, Object>> frammentazione, List<Map<String, Object>> uso, List<String> note) throws SQLException {
        Map<String, Double> scritturePerTabella = new LinkedHashMap<>();
        try (Connection c = Main.open(cfg); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT schemaname || '.' || relname AS oggetto, n_live_tup, n_dead_tup, "
                    + "n_tup_ins + n_tup_upd + n_tup_del AS scritture FROM pg_stat_user_tables")) {
                for (Map<String, Object> r : Main.rows(rs)) {
                    double vivi = dbl(r.get("n_live_tup")), morti = dbl(r.get("n_dead_tup"));
                    String oggetto = String.valueOf(r.get("oggetto"));
                    if (vivi + morti > 0) {
                        frammentazione.add(riga(oggetto, "(tabella)", 100.0 * morti / (vivi + morti), null, null,
                                "righe morte " + Math.round(morti)));
                    }
                    scritturePerTabella.put(oggetto, dbl(r.get("scritture")));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT schemaname || '.' || relname AS oggetto, indexrelname, idx_scan FROM pg_stat_user_indexes")) {
                for (Map<String, Object> r : Main.rows(rs)) {
                    String oggetto = String.valueOf(r.get("oggetto"));
                    Double w = scritturePerTabella.get(oggetto);
                    uso.add(riga(oggetto, String.valueOf(r.get("indexrelname")), null, (long) dbl(r.get("idx_scan")),
                            w == null ? 0L : (long) (double) w, "scritture della tabella"));
                }
            }
        }
        note.add("Frammentazione stimata: righe morte sul totale delle righe della tabella (pg_stat_user_tables). Un valore alto indica spazio da recuperare con VACUUM.");
        note.add("Letture: idx_scan di ciascun indice. Scritture: inserimenti, aggiornamenti e cancellazioni della tabella, perché PostgreSQL non le conta per indice.");
    }

    // ============================================================ topologia SQL Server

    /**
     * Topologia di un'istanza SQL Server: standalone, cluster di failover (FCI), Always On locale,
     * Always On multi-subnet (listener su più subnet, tipicamente geografico) o gruppo distribuito.
     * Solo lettura: proprietà dell'istanza e viste dei gruppi di disponibilità.
     */
    static Map<String, Object> topologia(JsonNode cfg) throws SQLException {
        if (!Main.text(cfg, "kind").equals("sqlserver")) {
            throw new IllegalArgumentException("La topologia è disponibile per i server SQL Server");
        }
        try (Connection c = Main.open(cfg); Statement st = c.createStatement()) {
            boolean hadr = proprieta(st, "IsHadrEnabled") == 1;
            boolean fci = proprieta(st, "IsClustered") == 1;
            List<Map<String, Object>> gruppi = new ArrayList<>();
            List<Map<String, Object>> repliche = new ArrayList<>();
            List<Map<String, Object>> listener = new ArrayList<>();
            Set<String> subnet = new java.util.TreeSet<>();
            boolean distribuito = false;
            if (hadr) {
                try (ResultSet rs = st.executeQuery("SELECT ag.name AS gruppo, ag.is_distributed AS distribuito, ar.replica_server_name AS replica, "
                        + "ar.availability_mode_desc AS modalita, ar.failover_mode_desc AS failover, ar.endpoint_url AS endpoint, "
                        + "ISNULL(ars.role_desc, '') AS ruolo, ISNULL(ars.connected_state_desc, '') AS connessione, "
                        + "ISNULL(ars.synchronization_health_desc, '') AS sincronizzazione "
                        + "FROM sys.availability_groups ag JOIN sys.availability_replicas ar ON ar.group_id = ag.group_id "
                        + "LEFT JOIN sys.dm_hadr_availability_replica_states ars ON ars.replica_id = ar.replica_id")) {
                    for (Map<String, Object> r : Main.rows(rs)) {
                        repliche.add(r);
                        if (Boolean.TRUE.equals(r.get("distribuito")) || "1".equals(String.valueOf(r.get("distribuito")))) distribuito = true;
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT ag.name AS gruppo, l.dns_name AS listener, ip.ip_address AS indirizzo, "
                        + "ip.network_subnet_ip AS subnet, ip.network_subnet_prefix_length AS prefisso "
                        + "FROM sys.availability_group_listeners l JOIN sys.availability_groups ag ON ag.group_id = l.group_id "
                        + "JOIN sys.availability_group_listener_ip_addresses ip ON ip.listener_id = l.listener_id")) {
                    for (Map<String, Object> r : Main.rows(rs)) {
                        listener.add(r);
                        subnet.add(String.valueOf(r.get("subnet")));
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT name AS gruppo, is_distributed AS distribuito FROM sys.availability_groups")) {
                    gruppi.addAll(Main.rows(rs));
                }
            }
            return classificaTopologia(hadr, fci, gruppi.size(), distribuito, subnet, gruppi, repliche, listener);
        }
    }

    private static int proprieta(Statement st, String nome) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT CAST(SERVERPROPERTY('" + nome + "') AS INT)")) {
            return rs.next() && rs.getObject(1) != null ? rs.getInt(1) : 0;
        }
    }

    /** Decisione sulla topologia, separata dalla lettura per poterla verificare con casi simulati. */
    static Map<String, Object> classificaTopologia(boolean hadr, boolean fci, int numeroGruppi, boolean distribuito,
                                                   Set<String> subnetListener, List<Map<String, Object>> gruppi,
                                                   List<Map<String, Object>> repliche, List<Map<String, Object>> listener) {
        String tipo;
        String descrizione;
        if (!hadr && !fci) {
            tipo = "standalone";
            descrizione = "Istanza singola, senza alta disponibilità";
        } else if (!hadr) {
            tipo = "fci";
            descrizione = "Cluster di failover (FCI): un'istanza su più nodi con storage condiviso, nella stessa sede";
        } else if (distribuito) {
            tipo = "ag_distribuito";
            descrizione = "Always On: gruppo di disponibilità distribuito fra più cluster (topologia geografica)";
        } else if (numeroGruppi == 0) {
            tipo = "always_on_vuoto";
            descrizione = "Always On attivo senza gruppi di disponibilità";
        } else if (subnetListener.size() > 1) {
            tipo = "always_on_multisubnet";
            descrizione = "Always On multi-subnet: il listener è su più subnet, tipicamente due siti (topologia geografica)";
        } else {
            tipo = "always_on_locale";
            descrizione = "Always On su una sola subnet: repliche nella stessa sede";
        }
        if (fci && hadr) {
            descrizione += ". Anche un'istanza FCI con Always On: verificare il ruolo di ciascun nodo";
        }

        List<String> avvisi = new ArrayList<>();
        for (Map<String, Object> r : repliche) {
            String nome = String.valueOf(r.get("replica"));
            if (!"CONNECTED".equals(r.get("connessione")) && !String.valueOf(r.get("ruolo")).isEmpty()) {
                avvisi.add("replica " + nome + " non connessa (" + r.get("connessione") + ")");
            }
            if (!"HEALTHY".equals(r.get("sincronizzazione")) && !String.valueOf(r.get("ruolo")).isEmpty()) {
                avvisi.add("replica " + nome + " con sincronizzazione " + r.get("sincronizzazione"));
            }
        }
        if (("always_on_multisubnet".equals(tipo) || "ag_distribuito".equals(tipo))) {
            boolean asincrona = repliche.stream().anyMatch(r -> "ASYNCHRONOUS_COMMIT".equals(r.get("modalita")));
            if (asincrona) avvisi.add("in una topologia geografica, le repliche asincrone possono perdere dati in caso di guasto");
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", tipo);
        m.put("descrizione", descrizione);
        m.put("geografica", "always_on_multisubnet".equals(tipo) || "ag_distribuito".equals(tipo));
        m.put("gruppi", gruppi);
        m.put("repliche", repliche);
        m.put("listener", listener);
        m.put("avvisi", avvisi);
        return m;
    }

    private static double dbl(Object o) {
        if (o == null) return 0;
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
