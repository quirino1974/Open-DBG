package it.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Tracciamento delle variabili di configurazione globali (SHOW GLOBAL VARIABLES) di MySQL e MariaDB.
 * Ad ogni rilevamento pesante legge tutte le variabili, le confronta con l'ultima lettura salvata e
 * registra ogni modifica (valore precedente e nuovo) con un evento. La prima lettura fa solo da base
 * di confronto e non genera eventi. Sola lettura sul server. I valori sensibili non vengono mai salvati.
 */
final class ConfigVars {
    private ConfigVars() {}

    /** Nomi di variabili il cui valore non viene mai memorizzato in chiaro. */
    static final Pattern SENSIBILE = Pattern.compile(
            "password|passwd|secret|token|keyring|_key$", Pattern.CASE_INSENSITIVE);

    /** Variabili la cui modifica è un avviso (WARNING); tutte le altre generano un evento INFO. */
    static final Set<String> CRITICHE = Set.of(
            "read_only", "super_read_only", "log_bin", "sync_binlog", "innodb_flush_log_at_trx_commit",
            "max_connections", "sql_mode", "binlog_format", "gtid_mode", "enforce_gtid_consistency",
            "innodb_buffer_pool_size", "skip_name_resolve", "require_secure_transport", "local_infile",
            "default_authentication_plugin", "server_id", "expire_logs_days", "binlog_expire_logs_seconds",
            "wsrep_on", "wsrep_cluster_address", "wsrep_sst_method");

    /** Oltre questo numero di modifiche in un solo rilevamento (tipicamente dopo un upgrade) si genera un solo evento riassuntivo. */
    static final int MAX_EVENTI_SINGOLI = 50;

    /**
     * Confronta le variabili globali del server con l'ultima lettura salvata e registra le modifiche.
     * Va chiamato solo per server MySQL/MariaDB raggiungibili.
     */
    static void rileva(JsonNode s, String cluster) throws SQLException {
        String id = s.get("id").asText();
        String name = Main.text(s, "name");

        Map<String, String> attuali = new TreeMap<>();
        try (Connection c = Main.open(s)) {
            for (Map<String, Object> r : Main.q(c, "SHOW GLOBAL VARIABLES")) {
                String nome = Main.str(r.get("Variable_name"));
                attuali.put(nome, SENSIBILE.matcher(nome).find() ? "***" : Main.str(r.get("Value")));
            }
        }

        Map<String, String> precedenti = new LinkedHashMap<>();
        boolean primaLettura;
        List<String[]> modifiche = new ArrayList<>();   // {nome, vecchio, nuovo}
        try (Connection c = Store.conn()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT var_name, var_value FROM config_vars WHERE server_id = ?")) {
                ps.setString(1, id);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) precedenti.put(rs.getString(1), rs.getString(2));
                }
            }
            primaLettura = precedenti.isEmpty();

            Map<String, String> daSalvare = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : attuali.entrySet()) {
                String vecchio = precedenti.get(e.getKey());
                if (primaLettura) {
                    daSalvare.put(e.getKey(), e.getValue());
                } else if (!e.getValue().equals(vecchio)) {
                    modifiche.add(new String[] {e.getKey(), vecchio, e.getValue()});
                    daSalvare.put(e.getKey(), e.getValue());
                }
            }

            c.setAutoCommit(false);
            try {
                if (!daSalvare.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO config_vars (server_id, var_name, var_value, updated_at) VALUES (?, ?, ?, NOW(3)) "
                                    + "ON DUPLICATE KEY UPDATE var_value = VALUES(var_value), updated_at = VALUES(updated_at)")) {
                        for (Map.Entry<String, String> e : daSalvare.entrySet()) {
                            ps.setString(1, id);
                            ps.setString(2, e.getKey());
                            ps.setString(3, e.getValue());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                if (!modifiche.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO config_var_changes (changed_at, server_id, server_name, var_name, "
                                    + "old_value, new_value, severity) VALUES (NOW(3), ?, ?, ?, ?, ?, ?)")) {
                        for (String[] m : modifiche) {
                            ps.setString(1, id);
                            ps.setString(2, name);
                            ps.setString(3, m[0]);
                            ps.setString(4, m[1]);
                            ps.setString(5, m[2]);
                            ps.setString(6, gravita(m[0]));
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }

        if (modifiche.size() > MAX_EVENTI_SINGOLI) {
            Store.event(id, name, cluster, "INFO", "CONFIG_VARIABLE",
                    modifiche.size() + " variabili di configurazione modificate (vedi storico)");
        } else {
            for (String[] m : modifiche) {
                Store.event(id, name, cluster, gravita(m[0]), "CONFIG_VARIABLE",
                        m[0] + ": " + etichetta(m[1]) + " -> " + etichetta(m[2]));
            }
        }
    }

    static String gravita(String nomeVariabile) {
        return CRITICHE.contains(nomeVariabile) ? "WARNING" : "INFO";
    }

    private static String etichetta(String valore) {
        return valore == null ? "(non presente)" : valore;
    }
}
