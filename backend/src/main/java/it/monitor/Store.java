package it.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Persistenza su MariaDB: anagrafica server, storico configurazioni, snapshot ed eventi.
 */
public class Store {

    static final String URL = "jdbc:mariadb://" + Main.env("DB_HOST", "mon_monitor_db") + ":"
            + Main.env("DB_PORT", "3306") + "/" + Main.env("DB_NAME", "monitor")
            + "?connectTimeout=5000&socketTimeout=30000";
    static final String USER = Main.env("DB_USER", "monitor");
    static final String PASSWORD = Main.env("DB_PASSWORD", "pippo");

    static Connection conn() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    /** Crea le tabelle; riprova finché il database non è pronto. */
    static void init() throws InterruptedException {
        SQLException last = null;
        for (int i = 0; i < 60; i++) {
            try (Connection c = conn(); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS servers ("
                        + " id VARCHAR(36) PRIMARY KEY,"
                        + " name VARCHAR(128) NOT NULL,"
                        + " kind VARCHAR(16) NOT NULL DEFAULT 'server',"
                        + " host VARCHAR(255) NOT NULL,"
                        + " port INT NOT NULL,"
                        + " user_name VARCHAR(128) NOT NULL,"
                        + " password VARCHAR(255) NOT NULL,"
                        + " database_name VARCHAR(128) NOT NULL DEFAULT '',"
                        + " position INT NOT NULL DEFAULT 0"
                        + ") ENGINE=InnoDB");
                st.execute("ALTER TABLE servers ADD COLUMN IF NOT EXISTS resource_mode VARCHAR(16) NOT NULL DEFAULT 'container'");
                st.execute("ALTER TABLE servers ADD COLUMN IF NOT EXISTS metrics_url VARCHAR(255) NOT NULL DEFAULT ''");
                // controlli selezionati per server (chiavi separate da virgola; vuoto = tutti i controlli tipici)
                st.execute("ALTER TABLE servers ADD COLUMN IF NOT EXISTS controlli VARCHAR(512) NOT NULL DEFAULT ''");
                st.execute("ALTER TABLE servers ADD COLUMN IF NOT EXISTS galera_cluster VARCHAR(128) NOT NULL DEFAULT ''");
                st.execute("ALTER TABLE servers ADD COLUMN IF NOT EXISTS ambiente VARCHAR(16) NOT NULL DEFAULT ''");
                st.execute("ALTER TABLE servers ADD COLUMN IF NOT EXISTS sito VARCHAR(64) NOT NULL DEFAULT ''");
                // server inclusi nel report Insight; il default 1 mantiene i server già esistenti nel report
                st.execute("ALTER TABLE servers ADD COLUMN IF NOT EXISTS insight TINYINT(1) NOT NULL DEFAULT 1");
                // notifiche Telegram per questo server; hanno effetto solo se Telegram è attivo nella configurazione generale
                st.execute("ALTER TABLE servers ADD COLUMN IF NOT EXISTS telegram TINYINT(1) NOT NULL DEFAULT 1");
                // utenza separata per terminare le sessioni bloccanti (il monitor resta in sola lettura con la sua)
                st.execute("ALTER TABLE servers ADD COLUMN IF NOT EXISTS kill_user VARCHAR(128) NOT NULL DEFAULT ''");
                st.execute("ALTER TABLE servers ADD COLUMN IF NOT EXISTS kill_password VARCHAR(1024) NOT NULL DEFAULT ''");
                // sospensione del monitoraggio: spenti rilevamento, controlli, Insight e notifiche per il server
                st.execute("ALTER TABLE servers ADD COLUMN IF NOT EXISTS sospeso TINYINT(1) NOT NULL DEFAULT 0");
                // spazio per la password cifrata (IV + testo + tag, in base64)
                st.execute("ALTER TABLE servers MODIFY password VARCHAR(1024) NOT NULL");
                st.execute("CREATE TABLE IF NOT EXISTS config_log ("
                        + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " changed_at DATETIME(3) NOT NULL,"
                        + " action VARCHAR(16) NOT NULL,"
                        + " server_name VARCHAR(128) NOT NULL,"
                        + " details TEXT,"
                        + " INDEX idx_config_time (changed_at)"
                        + ") ENGINE=InnoDB");
                // variabili globali correnti per server (ultima lettura, base di confronto)
                st.execute("CREATE TABLE IF NOT EXISTS config_vars ("
                        + " server_id VARCHAR(36) NOT NULL,"
                        + " var_name VARCHAR(128) NOT NULL,"
                        + " var_value TEXT,"
                        + " updated_at DATETIME(3) NOT NULL,"
                        + " PRIMARY KEY (server_id, var_name)"
                        + ") ENGINE=InnoDB");
                // storico delle modifiche alle variabili di configurazione
                st.execute("CREATE TABLE IF NOT EXISTS config_var_changes ("
                        + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " changed_at DATETIME(3) NOT NULL,"
                        + " server_id VARCHAR(36) NOT NULL,"
                        + " server_name VARCHAR(128) NOT NULL,"
                        + " var_name VARCHAR(128) NOT NULL,"
                        + " old_value TEXT,"
                        + " new_value TEXT,"
                        + " severity VARCHAR(16) NOT NULL,"
                        + " INDEX idx_cvc_time (changed_at)"
                        + ") ENGINE=InnoDB");
                st.execute("CREATE TABLE IF NOT EXISTS snapshots ("
                        + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " server_id VARCHAR(36) NOT NULL,"
                        + " collected_at DATETIME(3) NOT NULL,"
                        + " is_up TINYINT(1) NOT NULL,"
                        + " cluster_name VARCHAR(128),"
                        + " cluster_status VARCHAR(32),"
                        + " payload LONGTEXT,"
                        + " INDEX idx_snap_server (server_id, collected_at),"
                        + " INDEX idx_snap_time (collected_at)"
                        + ") ENGINE=InnoDB");
                st.execute("CREATE TABLE IF NOT EXISTS events ("
                        + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " occurred_at DATETIME(3) NOT NULL,"
                        + " server_id VARCHAR(36),"
                        + " server_name VARCHAR(128) NOT NULL,"
                        + " cluster_name VARCHAR(128),"
                        + " severity VARCHAR(16) NOT NULL,"
                        + " category VARCHAR(40) NOT NULL,"
                        + " message TEXT,"
                        + " INDEX idx_ev_time (occurred_at),"
                        + " INDEX idx_ev_server (server_id)"
                        + ") ENGINE=InnoDB");
                System.out.println("Database monitor pronto: " + URL);
                return;
            } catch (SQLException e) {
                last = e;
                System.out.println("Database non ancora pronto (" + e.getMessage() + "), riprovo...");
                Thread.sleep(2000);
            }
        }
        throw new IllegalStateException("Database monitor non raggiungibile", last);
    }

    // ----------------------------------------------------------- anagrafica

    static ArrayNode loadAll() throws SQLException {
        ArrayNode out = Main.JSON.createArrayNode();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT id, name, kind, host, port, user_name, password, "
                     + "database_name, resource_mode, metrics_url, galera_cluster, ambiente, sito, controlli, insight, telegram, kill_user, kill_password, sospeso FROM servers ORDER BY position, name");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                ObjectNode o = out.addObject();
                o.put("id", rs.getString("id"));
                o.put("name", rs.getString("name"));
                o.put("kind", rs.getString("kind"));
                o.put("host", rs.getString("host"));
                o.put("port", rs.getInt("port"));
                o.put("user", rs.getString("user_name"));
                o.put("password", Crypto.decrypt(rs.getString("password")));
                o.put("database", rs.getString("database_name"));
                o.put("resourceMode", rs.getString("resource_mode"));
                o.put("metricsUrl", rs.getString("metrics_url"));
                o.put("galeraCluster", rs.getString("galera_cluster"));
                o.put("ambiente", rs.getString("ambiente"));
                o.put("sito", rs.getString("sito"));
                o.put("controlli", rs.getString("controlli"));
                o.put("insight", rs.getBoolean("insight"));
                o.put("telegram", rs.getBoolean("telegram"));
                o.put("sospeso", rs.getBoolean("sospeso"));
                o.put("killUser", rs.getString("kill_user"));
                String kp = rs.getString("kill_password");
                o.put("killPassword", kp == null || kp.isEmpty() ? "" : Crypto.decrypt(kp));
            }
        }
        return out;
    }

    /** Sostituisce l'anagrafica in una sola transazione. */
    static void saveAll(ArrayNode list) throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM servers");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO servers (id, name, kind, host, port, "
                    + "user_name, password, database_name, resource_mode, metrics_url, galera_cluster, ambiente, sito, position, controlli, insight, telegram, kill_user, kill_password, sospeso) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                int pos = 0;
                for (JsonNode s : list) {
                    ps.setString(1, s.get("id").asText());
                    ps.setString(2, s.get("name").asText());
                    ps.setString(3, s.hasNonNull("kind") ? s.get("kind").asText() : "server");
                    ps.setString(4, s.get("host").asText());
                    ps.setInt(5, s.get("port").asInt());
                    ps.setString(6, s.get("user").asText());
                    ps.setString(7, Crypto.encrypt(s.get("password").asText()));
                    ps.setString(8, s.hasNonNull("database") ? s.get("database").asText() : "");
                    ps.setString(9, s.hasNonNull("resourceMode") ? s.get("resourceMode").asText() : "container");
                    ps.setString(10, s.hasNonNull("metricsUrl") ? s.get("metricsUrl").asText() : "");
                    ps.setString(11, s.hasNonNull("galeraCluster") ? s.get("galeraCluster").asText() : "");
                    ps.setString(12, s.hasNonNull("ambiente") ? s.get("ambiente").asText() : "");
                    ps.setString(13, s.hasNonNull("sito") ? s.get("sito").asText() : "");
                    ps.setInt(14, pos++);
                    ps.setString(15, s.hasNonNull("controlli") && s.get("controlli").isArray()
                            ? (s.get("controlli").isEmpty() ? "nessuno"
                                : String.join(",", java.util.stream.StreamSupport.stream(s.get("controlli").spliterator(), false).map(JsonNode::asText).toList()))
                            : (s.hasNonNull("controlli") ? s.get("controlli").asText() : ""));
                    ps.setBoolean(16, s.path("insight").asBoolean(true));
                    ps.setBoolean(17, s.path("telegram").asBoolean(false));
                    ps.setString(18, s.hasNonNull("killUser") ? s.get("killUser").asText() : "");
                    String kp = s.hasNonNull("killPassword") ? s.get("killPassword").asText() : "";
                    ps.setString(19, kp.isEmpty() ? "" : Crypto.encrypt(kp));
                    ps.setBoolean(20, s.path("sospeso").asBoolean(false));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        }
    }

    /** Cifra le password dell'anagrafica salvate in chiaro da versioni precedenti. Eseguita all'avvio. */
    static void migrateSecrets() throws SQLException {
        List<Map.Entry<String, String>> daCifrare = new ArrayList<>();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT id, password FROM servers");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                if (!Crypto.isEncrypted(rs.getString("password"))) {
                    daCifrare.add(Map.entry(rs.getString("id"), rs.getString("password")));
                }
            }
        }
        if (daCifrare.isEmpty()) return;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("UPDATE servers SET password = ? WHERE id = ?")) {
            for (Map.Entry<String, String> e : daCifrare) {
                ps.setString(1, Crypto.encrypt(e.getValue()));
                ps.setString(2, e.getKey());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        System.out.println("Password dei server cifrate: " + daCifrare.size());
    }

    static void logConfig(String action, String serverName, String details) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("INSERT INTO config_log (changed_at, action, server_name, details) "
                     + "VALUES (NOW(3), ?, ?, ?)")) {
            ps.setString(1, action);
            ps.setString(2, serverName);
            ps.setString(3, details);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Impossibile salvare lo storico configurazioni: " + e.getMessage());
        }
    }

    static List<Map<String, Object>> configLog(int limit) throws SQLException {
        return query("SELECT changed_at, action, server_name, details FROM config_log ORDER BY id DESC LIMIT " + limit);
    }

    /** Ultime modifiche alle variabili di configurazione dei server, dalla più recente. */
    static List<Map<String, Object>> configVarChanges(int limit) throws SQLException {
        return query("SELECT changed_at, server_name, var_name, old_value, new_value, severity "
                + "FROM config_var_changes ORDER BY id DESC LIMIT " + limit);
    }

    // ------------------------------------------------------ snapshot ed eventi

    static void snapshot(String serverId, boolean up, String cluster, String clusterStatus, String payload) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("INSERT INTO snapshots (server_id, collected_at, is_up, "
                     + "cluster_name, cluster_status, payload) VALUES (?, NOW(3), ?, ?, ?, ?)")) {
            ps.setString(1, serverId);
            ps.setBoolean(2, up);
            ps.setString(3, cluster);
            ps.setString(4, clusterStatus);
            ps.setString(5, payload);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Impossibile salvare lo snapshot: " + e.getMessage());
        }
    }

    static void event(String serverId, String serverName, String cluster, String severity, String category,
                      String message) {
        event(serverId, serverName, cluster, severity, category, message, null);
    }

    /** Come {@link #event(String, String, String, String, String, String)}; con {@code testa} la notifica Telegram offre il kill. */
    static void event(String serverId, String serverName, String cluster, String severity, String category,
                      String message, Long testa) {
        event(serverId, serverName, cluster, severity, category, message, testa, null);
    }

    /**
     * Come sopra. {@code contesto} (JSON con host, topologia e sessioni) serve alla notifica Telegram per il
     * messaggio scritto dall'IA, se attivo. Può essere null.
     */
    static void event(String serverId, String serverName, String cluster, String severity, String category,
                      String message, Long testa, String contesto) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("INSERT INTO events (occurred_at, server_id, server_name, "
                     + "cluster_name, severity, category, message) VALUES (NOW(3), ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, serverId);
            ps.setString(2, serverName);
            ps.setString(3, cluster);
            ps.setString(4, severity);
            ps.setString(5, category);
            ps.setString(6, message);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Impossibile salvare l'evento: " + e.getMessage());
            return;
        }
        // notifica facoltativa su Telegram, in asincrono
        Telegram.evento(serverId, severity, serverName, category, message, testa, contesto);
    }

    /** Ultimi eventi; con {@code ids} limitati ai server indicati (null = tutti). */
    static List<Map<String, Object>> events(int limit, Collection<String> ids) throws SQLException {
        String base = "SELECT occurred_at, server_name, cluster_name, severity, category, message FROM events ";
        if (ids == null) return query(base + "ORDER BY id DESC LIMIT " + limit);
        if (ids.isEmpty()) return List.of();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(base + "WHERE server_id IN " + inClause(ids)
                     + " ORDER BY id DESC LIMIT " + limit)) {
            bind(ps, 1, ids);
            try (var rs = ps.executeQuery()) {
                return Main.rows(rs);
            }
        }
    }

    /** Segnaposto "(?,?,...)" per un IN con n valori. */
    private static String inClause(Collection<String> valori) {
        return "(" + String.join(",", Collections.nCopies(valori.size(), "?")) + ")";
    }

    private static void bind(PreparedStatement ps, int primo, Collection<String> valori) throws SQLException {
        int i = primo;
        for (String v : valori) ps.setString(i++, v);
    }

    /** Conserva snapshot per 7 giorni ed eventi per 90 giorni. */
    static void purge() {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM snapshots WHERE collected_at < NOW() - INTERVAL "
                    + Impostazioni.intero("conservazione.snapshot_giorni") + " DAY");
            st.executeUpdate("DELETE FROM events WHERE occurred_at < NOW() - INTERVAL "
                    + Impostazioni.intero("conservazione.eventi_giorni") + " DAY");
            st.executeUpdate("DELETE FROM config_var_changes WHERE changed_at < NOW() - INTERVAL "
                    + Impostazioni.intero("conservazione.eventi_giorni") + " DAY");
        } catch (SQLException e) {
            System.err.println("Pulizia storico non riuscita: " + e.getMessage());
        }
    }

    /** Snapshot per ora: raggiungibili e non raggiungibili. */
    static List<Map<String, Object>> disponibilitaPerOra(int ore, Collection<String> ids) throws SQLException {
        if (ids != null && ids.isEmpty()) return List.of();
        String filtro = ids == null ? "" : " AND server_id IN " + inClause(ids);
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT DATE_FORMAT(collected_at, '%Y-%m-%d %H:00') AS ora, "
                     + "CAST(SUM(is_up) AS UNSIGNED) AS su, CAST(COUNT(*) - SUM(is_up) AS UNSIGNED) AS giu "
                     + "FROM snapshots WHERE collected_at >= NOW() - INTERVAL ? HOUR" + filtro
                     + " GROUP BY ora ORDER BY ora")) {
            ps.setInt(1, ore);
            if (ids != null) bind(ps, 2, ids);
            try (var rs = ps.executeQuery()) {
                return Main.rows(rs);
            }
        }
    }

    /** Eventi per ora e gravità, limitati ai server indicati (null = tutti). */
    static List<Map<String, Object>> eventiPerOra(int ore, Collection<String> ids) throws SQLException {
        if (ids != null && ids.isEmpty()) return List.of();
        String filtro = ids == null ? "" : " AND server_id IN " + inClause(ids);
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT DATE_FORMAT(occurred_at, '%Y-%m-%d %H:00') AS ora, "
                     + "severity, CAST(COUNT(*) AS UNSIGNED) AS n FROM events "
                     + "WHERE occurred_at >= NOW() - INTERVAL ? HOUR" + filtro
                     + " GROUP BY ora, severity ORDER BY ora")) {
            ps.setInt(1, ore);
            if (ids != null) bind(ps, 2, ids);
            try (var rs = ps.executeQuery()) {
                return Main.rows(rs);
            }
        }
    }

    static List<Map<String, Object>> query(String sql) throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return Main.rows(rs);
        }
    }
}
