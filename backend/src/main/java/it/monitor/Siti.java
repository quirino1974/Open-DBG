package it.monitor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anagrafica dei siti (es. Roma, Viterbo, AWS cloud) dove si trovano i server. Ogni server indica il
 * proprio sito per nome. Un sito in uso da uno o più server non può essere eliminato.
 */
final class Siti {

    static final int MAX_NOME = 64;

    private Siti() {
    }

    static void init() throws SQLException {
        try (Connection c = Store.conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS siti ("
                    + " id VARCHAR(36) PRIMARY KEY,"
                    + " nome VARCHAR(64) NOT NULL UNIQUE,"
                    + " descrizione VARCHAR(255) NOT NULL DEFAULT '',"
                    + " creato_il DATETIME(3) NOT NULL"
                    + ") ENGINE=InnoDB");
        }
    }

    static List<Map<String, Object>> elenco() throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT s.id, s.nome, s.descrizione, "
                     + "(SELECT COUNT(*) FROM servers v WHERE v.sito = s.nome) AS server "
                     + "FROM siti s ORDER BY s.nome");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id"));
                m.put("nome", rs.getString("nome"));
                m.put("descrizione", rs.getString("descrizione"));
                m.put("server", rs.getLong("server"));
                out.add(m);
            }
        }
        return out;
    }

    /** Il sito esiste nell'anagrafica (confronto esatto sul nome). */
    static boolean esiste(String nome) throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM siti WHERE nome = ?")) {
            ps.setString(1, nome);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static void crea(String nome, String descrizione) throws SQLException {
        String n = valida(nome);
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("INSERT INTO siti (id, nome, descrizione, creato_il) VALUES (?, ?, ?, NOW(3))")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, n);
            ps.setString(3, descrizione == null ? "" : descrizione.trim());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) throw new IllegalArgumentException("Esiste già un sito con questo nome");
            throw e;
        }
    }

    /** Modifica nome e descrizione. Se il nome cambia, i server che usano il sito seguono il nuovo nome. */
    static void aggiorna(String id, String nome, String descrizione) throws SQLException {
        String n = valida(nome);
        String precedente = nomeDi(id);
        try (Connection c = Store.conn()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("UPDATE siti SET nome = ?, descrizione = ? WHERE id = ?")) {
                ps.setString(1, n);
                ps.setString(2, descrizione == null ? "" : descrizione.trim());
                ps.setString(3, id);
                ps.executeUpdate();
                if (!precedente.equals(n)) {
                    try (PreparedStatement sv = c.prepareStatement("UPDATE servers SET sito = ? WHERE sito = ?")) {
                        sv.setString(1, n);
                        sv.setString(2, precedente);
                        sv.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                if (e.getErrorCode() == 1062) throw new IllegalArgumentException("Esiste già un sito con questo nome");
                throw e;
            }
        }
    }

    static void elimina(String id) throws SQLException {
        String nome = nomeDi(id);
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM servers WHERE sito = ?")) {
            ps.setString(1, nome);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getLong(1) > 0) {
                    throw new IllegalArgumentException("Il sito \"" + nome + "\" è usato da " + rs.getLong(1)
                            + " server: cambiarne il sito prima di eliminarlo");
                }
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM siti WHERE id = ?")) {
                del.setString(1, id);
                del.executeUpdate();
            }
        }
    }

    private static String nomeDi(String id) throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT nome FROM siti WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Sito non trovato");
                return rs.getString(1);
            }
        }
    }

    private static String valida(String nome) {
        String n = nome == null ? "" : nome.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("Il nome del sito è obbligatorio");
        if (n.length() > MAX_NOME) throw new IllegalArgumentException("Il nome del sito non può superare " + MAX_NOME + " caratteri");
        return n;
    }
}
