package it.monitor;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Ricerca di database su tutti i server MySQL/MariaDB dell'anagrafica visibili all'utente.
 * Sola lettura: interroga information_schema.SCHEMATA in parallelo, con un limite di tempo per server.
 */
final class Ricerca {

    static final int MIN_CARATTERI = 2;
    static final int MAX_RISULTATI = 500;

    private Ricerca() {
    }

    static Map<String, Object> cerca(String termine, Set<String> visibili) throws Exception {
        String t = termine == null ? "" : termine.trim();
        if (t.length() < MIN_CARATTERI) {
            throw new IllegalArgumentException("Inserire almeno " + MIN_CARATTERI + " caratteri");
        }
        // Escape dei caratteri speciali di LIKE: la ricerca è una ricerca di testo, non un pattern
        String modello = "%" + t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";

        List<JsonNode> server = new ArrayList<>();
        for (JsonNode s : Main.loadServers()) {
            String tipo = Main.text(s, "kind");
            if (!tipo.equals("server") && !tipo.equals("postgres") && !tipo.equals("sqlserver")) continue;
            if (visibili != null && !visibili.contains(s.get("id").asText())) continue;
            // Monitoraggio sospeso: il server non viene interrogato né segnalato tra i non interrogati
            if (Main.isSospeso(s)) continue;
            server.add(s);
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, Math.max(1, server.size())));
        List<Future<Map<String, Object>>> attese = new ArrayList<>();
        try {
            for (JsonNode s : server) {
                attese.add(pool.submit(() -> cercaSuUnServer(s, modello)));
            }
            List<Map<String, Object>> risultati = new ArrayList<>();
            List<Object> nonRaggiungibili = new ArrayList<>();
            int totale = 0;
            for (Future<Map<String, Object>> f : attese) {
                Map<String, Object> esito;
                try {
                    esito = f.get(20, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // server che non risponde nel tempo massimo: segnalato come non raggiungibile
                    nonRaggiungibili.add(m("nome", "(server non risposto)", "errore", "tempo massimo superato"));
                    continue;
                }
                if (!Boolean.TRUE.equals(esito.get("raggiungibile"))) {
                    nonRaggiungibili.add(m("nome", esito.get("nome"), "errore", esito.get("errore")));
                    continue;
                }
                for (Object db : (List<?>) esito.get("database")) {
                    totale++;
                    if (risultati.size() < MAX_RISULTATI) {
                        risultati.add(m("database", db, "server", esito.get("nome"), "serverId", esito.get("id"),
                                "ambiente", esito.get("ambiente"), "sito", esito.get("sito"),
                                "host", esito.get("host"), "motore", esito.get("motore")));
                    }
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("termine", t);
            out.put("totale", totale);
            out.put("troncati", totale > risultati.size());
            out.put("risultati", risultati);
            out.put("nonRaggiungibili", nonRaggiungibili);
            out.put("serverInterrogati", server.size());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, Object> cercaSuUnServer(JsonNode s, String modello) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", s.get("id").asText());
        r.put("nome", Main.text(s, "name"));
        r.put("host", Main.text(s, "host"));
        r.put("ambiente", Main.text(s, "ambiente"));
        r.put("sito", Main.text(s, "sito"));
        // PostgreSQL: i database sono in pg_database (information_schema elenca solo gli schemi)
        // SQL Server: solo i database online che il login può aprire (gli altri non si possono interrogare)
        boolean sqlServer = "sqlserver".equals(Main.text(s, "kind"));
        String sql = Main.isPostgres(s)
                ? "SELECT datname FROM pg_database WHERE NOT datistemplate AND datname LIKE ? ORDER BY datname"
                : sqlServer
                ? "SELECT name FROM sys.databases WHERE state_desc = 'ONLINE' AND HAS_DBACCESS(name) = 1 AND name LIKE ? ESCAPE '\\' ORDER BY name"
                : "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE ? ORDER BY SCHEMA_NAME";
        try (Connection c = Main.open(s);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, modello);
            List<String> db = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) db.add(rs.getString(1));
            }
            r.put("raggiungibile", true);
            r.put("database", db);
            r.put("motore", Main.isPostgres(s) ? "PostgreSQL" : sqlServer ? "SQL Server"
                    : Main.str(Main.varOrNull(c, "@@version_comment")).toLowerCase().contains("mariadb") ? "MariaDB" : "MySQL");
        } catch (Exception e) {
            r.put("raggiungibile", false);
            r.put("errore", e.getMessage() == null ? "errore sconosciuto" : e.getMessage().split("\n")[0]);
            r.put("database", List.of());
        }
        return r;
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }
}
