package it.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sonde di SQL Server per il rilevamento del monitor: connessione, identità, topologia (standalone, FCI,
 * Always On), risorse, query attive e query più lente (dynamic management views).
 * Sola lettura. Ogni sonda è isolata: un errore su una sonda non blocca le altre.
 * Il risultato usa le stesse chiavi del rilevamento MySQL/PostgreSQL, così dashboard e eventi lo trattano allo stesso modo.
 */
final class SqlServerReport {
    private SqlServerReport() {}

    static Map<String, Object> report(JsonNode cfg, boolean pesante, Map<String, Object> precedente) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("generatoAlle", Instant.now().toString());
        Map<String, String> errori = new LinkedHashMap<>();
        r.put("server", Main.m("id", Main.text(cfg, "id"), "name", Main.text(cfg, "name"), "jdbcUrl", Main.jdbcUrl(cfg)));
        long t0 = System.currentTimeMillis();
        try (Connection c = Main.open(cfg); Statement st = c.createStatement()) {
            r.put("connessione", mappa("ok", true, "ms", System.currentTimeMillis() - t0));
            r.put("identita", safe(errori, "identita", () -> identita(st)));
            Map<String, Object> id = Monitor.map(r.get("identita"));
            r.put("ruoloNodo", id.get("ruoloAg"));
            r.put("topologia", !Main.controllo(cfg, "topologia") ? null : safe(errori, "topologia", () -> {
                Map<String, Object> t = Statistiche.topologia(cfg);
                return mappa("tipo", t.get("tipo"), "descrizione", t.get("descrizione"), "geografica", t.get("geografica"));
            }));
            r.put("risorse", !Main.controllo(cfg, "risorse") ? null : safe(errori, "risorse", () -> risorse(st)));
            // Ogni controllo viene eseguito solo se selezionato per il server in anagrafica
            r.put("connessioni", Main.controllo(cfg, "connessioni") ? safe(errori, "connessioni", () -> connessioni(st)) : null);
            r.put("queryAttive", Main.controllo(cfg, "query_attive") ? safe(errori, "queryAttive", () -> queryAttive(st)) : null);
            r.put("bloccate", Main.controllo(cfg, "transazioni_bloccate") ? safe(errori, "bloccate", () -> bloccate(st)) : null);
            if (!Main.controllo(cfg, "query_lente")) {
                r.put("queryLente", null);
            } else if (pesante || precedente == null) {
                r.put("queryLente", Main.filtraPianificabili(safe(errori, "queryLente", () -> queryLente(st))));
            } else {
                r.put("queryLente", precedente.get("queryLente"));
            }
            r.put("utentiDettaglio", Main.controllo(cfg, "utenti") ? safe(errori, "utenti", () -> utenti(st)) : null);
            r.put("databaseDettaglio", Main.controllo(cfg, "database") ? safe(errori, "database", () -> databaseDettaglio(st)) : null);
            r.put("replica", new ArrayList<>());
            r.put("utenti", new ArrayList<>());
        } catch (SQLException e) {
            r.put("connessione", mappa("ok", false, "errore", String.valueOf(e.getMessage())));
        }
        if (!errori.isEmpty()) r.put("erroriSonde", errori);
        return r;
    }

    private interface Sonda {
        Object esegui() throws SQLException;
    }

    private static Object safe(Map<String, String> errori, String nome, Sonda s) {
        try {
            return s.esegui();
        } catch (Exception e) {
            errori.put(nome, String.valueOf(e.getMessage()));
            return null;
        }
    }

    private static Map<String, Object> connessioni(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sys.dm_exec_sessions WHERE is_user_process = 1")) {
            rs.next();
            return mappa("attive", rs.getLong(1));
        }
    }

    /** Utenti del server: data di creazione e giorni alla scadenza della password (solo login SQL). */
    private static List<Map<String, Object>> utenti(Statement st) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT p.name, p.type_desc, p.create_date, p.is_disabled, "
                + "CAST(LOGINPROPERTY(p.name, 'DaysUntilExpiration') AS INT) AS giorni "
                + "FROM sys.server_principals p WHERE p.type IN ('S', 'U', 'G') AND p.name NOT LIKE '##%' ORDER BY p.name")) {
            while (rs.next()) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("utente", rs.getString(1));
                u.put("tipo", rs.getString(2));
                u.put("creazione", rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant().toString());
                u.put("disabilitato", rs.getBoolean(4));
                Object giorni = rs.getObject(5);
                u.put("scadenza", giorni == null ? "non applicabile" : (Integer.parseInt(giorni.toString()) < 0 ? "scaduta" : "tra " + giorni + " giorni"));
                out.add(u);
            }
        }
        return out;
    }

    /** Database: data di creazione e dimensione. */
    private static List<Map<String, Object>> databaseDettaglio(Statement st) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT d.name, d.create_date, d.state_desc, "
                + "(SELECT SUM(CAST(f.size AS BIGINT)) * 8 / 1024 FROM sys.master_files f WHERE f.database_id = d.database_id) AS mb "
                + "FROM sys.databases d ORDER BY d.name")) {
            while (rs.next()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("database", rs.getString(1));
                d.put("creazione", rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant().toString());
                d.put("stato", rs.getString(3));
                d.put("dimensioneMB", rs.getObject(4));
                out.add(d);
            }
        }
        return out;
    }

    private static Map<String, Object> identita(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT CAST(SERVERPROPERTY('Edition') AS NVARCHAR(128)), CAST(SERVERPROPERTY('ProductVersion') AS NVARCHAR(32)), "
                + "CAST(SERVERPROPERTY('MachineName') AS NVARCHAR(128)), CAST(SERVERPROPERTY('IsHadrEnabled') AS INT), "
                + "CAST(SERVERPROPERTY('IsClustered') AS INT)")) {
            rs.next();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("motore", "SQL Server");
            m.put("edizione", rs.getString(1));
            m.put("versione", rs.getString(2));
            m.put("hostname", rs.getString(3));
            // ruolo nel gruppo di disponibilità, se il server ne fa parte
            String ruolo = null;
            if (rs.getInt(4) == 1) {
                try (ResultSet r2 = st.executeQuery("SELECT TOP 1 ars.role_desc FROM sys.dm_hadr_availability_replica_states ars "
                        + "WHERE ars.is_local = 1")) {
                    if (r2.next()) ruolo = r2.getString(1);
                }
            }
            m.put("ruoloAg", ruolo);
            m.put("fci", rs.getInt(5) == 1);
            return m;
        }
    }

    private static Map<String, Object> risorse(Statement st) throws SQLException {
        long totale = 0, inUso = 0;
        int cpu = 0;
        try (ResultSet rs = st.executeQuery("SELECT total_physical_memory_kb, (total_physical_memory_kb - available_physical_memory_kb) "
                + "FROM sys.dm_os_sys_memory")) {
            if (rs.next()) { totale = rs.getLong(1) * 1024; inUso = rs.getLong(2) * 1024; }
        }
        try (ResultSet rs = st.executeQuery("SELECT cpu_count FROM sys.dm_os_sys_info")) {
            if (rs.next()) cpu = rs.getInt(1);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("memUsedBytes", inUso);
        m.put("memLimitBytes", totale);
        m.put("memPercent", totale == 0 ? null : 100.0 * inUso / totale);
        m.put("cpuPercent", null);
        m.put("cpu", cpu);
        try (ResultSet rs = st.executeQuery("SELECT physical_memory_in_use_kb * 1024 FROM sys.dm_os_process_memory")) {
            m.put("memoriaMysqldAllocataBytes", rs.next() ? rs.getLong(1) : null);
        }
        return m;
    }

    /** Richieste in attesa di un lock (blocking_session_id) e l'ultima query della sessione che lo tiene. */
    private static List<Map<String, Object>> bloccate(Statement st) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT TOP 100 r.session_id AS ID, s.login_name AS UTENTE, DB_NAME(r.database_id) AS DB, "
                + "r.wait_time / 1000 AS SECONDI, SUBSTRING(t.text, 1, 300) AS QUERY, r.blocking_session_id AS BLOCCANTE_ID, "
                + "(SELECT TOP 1 SUBSTRING(tb.text, 1, 300) FROM sys.dm_exec_connections cb "
                + "CROSS APPLY sys.dm_exec_sql_text(cb.most_recent_sql_handle) tb WHERE cb.session_id = r.blocking_session_id) AS QUERY_BLOCCANTE "
                + "FROM sys.dm_exec_requests r JOIN sys.dm_exec_sessions s ON s.session_id = r.session_id "
                + "CROSS APPLY sys.dm_exec_sql_text(r.sql_handle) t WHERE r.blocking_session_id <> 0 ORDER BY SECONDI DESC")) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ID", rs.getObject(1));
                m.put("UTENTE", rs.getString(2));
                m.put("DB", rs.getString(3));
                m.put("SECONDI", rs.getObject(4));
                m.put("QUERY", rs.getString(5));
                m.put("BLOCCANTE_ID", rs.getObject(6));
                m.put("QUERY_BLOCCANTE", rs.getString(7));
                out.add(m);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> queryAttive(Statement st) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT TOP 30 r.session_id, DB_NAME(r.database_id) AS db, s.login_name, r.status, "
                + "DATEDIFF(second, r.start_time, GETDATE()) AS secondi, "
                + "SUBSTRING(t.text, (r.statement_start_offset / 2) + 1, 1000) AS testo "
                + "FROM sys.dm_exec_requests r JOIN sys.dm_exec_sessions s ON s.session_id = r.session_id "
                + "CROSS APPLY sys.dm_exec_sql_text(r.sql_handle) t WHERE r.session_id <> @@SPID ORDER BY secondi DESC")) {
            while (rs.next()) {
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("ID", rs.getInt(1));
                q.put("DB", rs.getString(2));
                q.put("UTENTE", rs.getString(3));
                q.put("STATO", rs.getString(4));
                q.put("SECONDI", rs.getLong(5));
                q.put("RIGHE_ESAMINATE", null);
                q.put("RIGHE_INVIATE", null);
                q.put("TMP_DISCO", null);
                q.put("SENZA_INDICE", false);
                q.put("QUERY", rs.getString(6));
                out.add(q);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> queryLente(Statement st) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        // Il database si legge dagli attributi del piano: t.dbid di dm_exec_sql_text è spesso vuoto
        try (ResultSet rs = st.executeQuery("SELECT TOP 10 DB_NAME(CAST(pa.value AS int)) AS db, "
                + "SUM(qs.execution_count) AS esecuzioni, "
                + "(SUM(qs.total_elapsed_time) / NULLIF(SUM(qs.execution_count), 0)) / 1000 AS tempo_medio_ms, "
                + "SUBSTRING(t.text, (qs.statement_start_offset / 2) + 1, 1000) AS testo "
                + "FROM sys.dm_exec_query_stats qs CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) t "
                + "CROSS APPLY sys.dm_exec_plan_attributes(qs.plan_handle) pa "
                + "WHERE pa.attribute = 'dbid' "
                + "AND t.text NOT LIKE '%sys.%' AND t.text NOT LIKE '%msdb.%' "
                + "AND t.text NOT LIKE '%[[]sys].%' AND t.text NOT LIKE '%[[]msdb].%' "
                + "GROUP BY pa.value, qs.statement_start_offset, qs.statement_end_offset, t.text, qs.sql_handle "
                + "ORDER BY tempo_medio_ms DESC")) {
            while (rs.next()) {
                // Le chiavi sono quelle della tabella "Query più lente" (come per MySQL e PostgreSQL)
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("SCHEMA_NAME", rs.getString(1));
                q.put("ESECUZIONI", rs.getLong(2));
                q.put("MEDIA_SEC", rs.getLong(3) / 1000.0);
                q.put("MAX_SEC", null);
                q.put("RIGHE_ESAMINATE", null);
                q.put("SENZA_INDICE", null);
                q.put("QUERY", rs.getString(4));
                out.add(q);
            }
        }
        return out;
    }

    /**
     * Piano stimato di una query SQL Server (SHOWPLAN_XML): la query non viene eseguita, né le istruzioni DML.
     * Non si usa lo storico dei piani: il piano è ricalcolato sul database scelto.
     */
    static Map<String, Object> explain(JsonNode s, String sql, String db) throws SQLException {
        // Per le scritture si pianifica la parte di lettura: SHOWPLAN richiederebbe il permesso di scrittura
        String lettura = Main.parteDiLettura(sql.trim());
        String nota = lettura.equals(sql.trim()) ? null
                : "Scrittura non eseguita: il piano è quello della parte di lettura (la stessa che SQL Server deve leggere).";
        sql = lettura;
        String pulito = sql.trim().replaceAll(";+\\s*$", "");
        if (Main.piuIstruzioni(pulito)) throw new IllegalArgumentException("Sono ammesse solo istruzioni singole");
        sql = pulito;
        String up = sql.toUpperCase();
        if (!(up.startsWith("SELECT") || up.startsWith("WITH") || up.startsWith("INSERT")
                || up.startsWith("UPDATE") || up.startsWith("DELETE") || up.startsWith("MERGE"))) {
            throw new IllegalArgumentException("Piano disponibile solo per SELECT, WITH, INSERT, UPDATE, DELETE, MERGE");
        }
        ObjectNode cfg = ((ObjectNode) s).deepCopy();
        // Database candidati: prima quello del digest, poi tutti i database online (il digest non sempre lo indica)
        List<String> candidati = new ArrayList<>();
        if (db != null && !db.isEmpty()) candidati.add(db);
        try (Connection c = Main.open(cfg); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sys.databases WHERE database_id > 4 AND state_desc = 'ONLINE' AND HAS_DBACCESS(name) = 1 ORDER BY name")) {
            while (rs.next()) if (!candidati.contains(rs.getString(1))) candidati.add(rs.getString(1));
        }
        if (candidati.isEmpty()) candidati.add(null);
        String xml = null, usato = null, sqlPiano = sql;
        boolean sostituiti = false;
        SQLException ultimo = null;
        for (String d : candidati) {
            ObjectNode cd = cfg.deepCopy();
            if (d != null) cd.put("database", d);
            try (Connection c = Main.open(cd); Statement st = c.createStatement()) {
                String preparata = tipiParametri(st, sql);
                xml = leggiPiano(st, preparata);
                usato = d;
                sqlPiano = preparata;
                sostituiti = !preparata.equals(sql);
                ultimo = null;
                break;
            } catch (SQLException e) {
                // Oggetto non presente, o database non accessibile con questo login: si prova il successivo.
                // Un database non accessibile non deve bloccare gli altri (il login non vede tutti i database).
                String msg = String.valueOf(e.getMessage());
                if (!msg.contains("Invalid object name") && !msg.contains("Cannot open database")) throw e;
                ultimo = e;
            }
        }
        if (ultimo != null) {
            throw new IllegalArgumentException("Piano non disponibile: nessun database online accessibile con questo login contiene gli oggetti della query. " + ultimo.getMessage());
        }
        sql = sqlPiano;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sql", sql);
        out.put("schema", usato);
        out.put("tabella", new ArrayList<>());
        out.put("json", null);
        out.put("xml", xml);
        out.put("passi", passiXml(xml));
        String avviso = "Piano stimato di SQL Server (SHOWPLAN_XML): la query non viene eseguita.";
        if (usato != null && db != null && !db.isEmpty() && !usato.equals(db)) {
            avviso += " Database usato: " + usato + " (nel digest il database indicato non conteneva gli oggetti).";
        }
        if (sostituiti) {
            avviso += " Parametri sostituiti con il tipo dichiarato dal server: il piano è indicativo sui valori.";
        }
        out.put("note", nota != null ? nota + " " + avviso : avviso);
        return out;
    }

    /** Sostituisce ogni parametro @n con un cast tipizzato (CAST(NULL AS tipo)), con il tipo che il server dichiara. */
    static String tipiParametri(Statement st, String sql) {
        Map<String, String> tipi = new LinkedHashMap<>();
        try (ResultSet rs = st.executeQuery("EXEC sp_describe_undeclared_parameters @tsql = N'" + sql.replace("'", "''") + "'")) {
            while (rs.next()) {
                String nome = rs.getString("name");
                String tipo = rs.getString("suggested_system_type_name");
                if (nome != null && tipo != null) tipi.put(nome, tipo);
            }
        } catch (SQLException e) {
            return sql; // senza tipi resta la query originale: l'errore di SHOWPLAN sarà quello reale
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("@\\w+").matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tipo = tipi.get(m.group());
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(tipo == null ? m.group() : "CAST(NULL AS " + tipo + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Piano stimato (XML SHOWPLAN) della query, senza eseguirla. */
    static String leggiPiano(Statement st, String sql) throws SQLException {
        String xml = null;
        st.execute("SET SHOWPLAN_XML ON");
        try {
            boolean risultato = st.execute(sql);
            if (risultato) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs.next()) xml = rs.getString(1);
                }
            }
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("Must declare the scalar variable")) {
                throw new IllegalArgumentException("Query con parametri non risolti: piano non disponibile da questa vista");
            }
            throw e;
        } finally {
            st.execute("SET SHOWPLAN_XML OFF");
        }
        return xml;
    }

    /** Nodi del piano (RelOp) letti dallo XML di SHOWPLAN, con il livello di annidamento. */
    static List<Map<String, Object>> passiXml(String xml) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (xml == null) return out;
        try {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            org.w3c.dom.Document d = f.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
            visitaRelOp(d.getDocumentElement(), 0, out);
        } catch (Exception e) {
            // XML non leggibile: resta il testo grezzo
        }
        return out;
    }

    private static void visitaRelOp(org.w3c.dom.Element e, int profondita, List<Map<String, Object>> out) {
        for (org.w3c.dom.Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof org.w3c.dom.Element el)) continue;
            if (el.getTagName().equals("RelOp")) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("profondita", profondita);
                p.put("operatore", el.getAttribute("PhysicalOp"));
                p.put("logico", el.getAttribute("LogicalOp"));
                p.put("righeStimate", numero(el.getAttribute("EstimateRows")));
                p.put("costo", numero(el.getAttribute("EstimatedTotalSubtreeCost")));
                p.put("oggetto", oggettoDi(el));
                p.put("dettaglio", "");
                out.add(p);
                visitaRelOp(el, profondita + 1, out);
            } else {
                visitaRelOp(el, profondita, out);
            }
        }
    }

    /** Tabella o indice a cui fa riferimento un operatore, senza scendere nei sotto-operatori. */
    private static String oggettoDi(org.w3c.dom.Element e) {
        for (org.w3c.dom.Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof org.w3c.dom.Element el)) continue;
            if (el.getTagName().equals("RelOp")) continue;
            if (el.getTagName().equals("Object")) {
                String tabella = el.getAttribute("Table").replaceAll("[\\[\\]]", "");
                String indice = el.getAttribute("Index").replaceAll("[\\[\\]]", "");
                return indice.isEmpty() ? tabella : tabella + " (" + indice + ")";
            }
            String r = oggettoDi(el);
            if (!r.isEmpty()) return r;
        }
        return "";
    }

    private static double numero(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static Map<String, Object> mappa(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
