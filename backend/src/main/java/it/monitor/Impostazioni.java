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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parametri di rilevamento modificabili dall'amministratore. I valori sono nel database (tabella
 * impostazioni); se una chiave non è salvata vale il predefinito. Le modifiche si applicano subito.
 */
final class Impostazioni {

    /** Definizione di un parametro: gruppo, descrizione, predefinito e intervallo ammesso. */
    record Def(String chiave, String gruppo, String descrizione, int predefinito, int min, int max, String unita) {
    }

    static final List<Def> DEFINIZIONI = List.of(
            new Def("rilevamento.raggiungibilita_secondi", "Rilevamento",
                    "Ogni quanto verificare se i server rispondono (un cambio di stato viene registrato subito)", 15, 5, 300, "s"),
            new Def("rilevamento.leggero_minuti", "Rilevamento",
                    "Ogni quanto rilevare lo stato generale: connessione, replica, cluster, query attive", 5, 1, 60, "min"),
            new Def("rilevamento.pesante_minuti", "Rilevamento",
                    "Ogni quanto rilevare le sezioni pesanti: indici, utenti, dimensione dati, colonne vettoriali", 15, 5, 1440, "min"),
            new Def("query.attive_limite", "Query",
                    "Numero massimo di query attive rilevate per server", 30, 5, 200, "query"),
            new Def("query.lente_limite", "Query",
                    "Numero massimo di query lente (per digest) rilevate per server", 10, 5, 100, "query"),
            new Def("query.attive_soglia_secondi", "Query",
                    "Dopo quanti secondi una query in esecuzione viene segnalata come lunga (Insight)", 30, 5, 3600, "s"),
            new Def("conservazione.snapshot_giorni", "Conservazione",
                    "Per quanti giorni conservare gli snapshot dello stato dei server", 7, 1, 90, "giorni"),
            new Def("conservazione.eventi_giorni", "Conservazione",
                    "Per quanti giorni conservare gli eventi", 90, 7, 730, "giorni"),
            new Def("insight.ora", "Insight",
                    "Ora della produzione giornaliera del report Insight", 6, 0, 23, "ora"));

    private static final Map<String, Def> PER_CHIAVE = new LinkedHashMap<>();
    private static final Map<String, Integer> VALORI = new ConcurrentHashMap<>();

    static {
        for (Def d : DEFINIZIONI) PER_CHIAVE.put(d.chiave(), d);
    }

    private Impostazioni() {
    }

    static void init() throws SQLException {
        try (Connection c = Store.conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS impostazioni ("
                    + " chiave VARCHAR(64) PRIMARY KEY,"
                    + " valore INT NOT NULL,"
                    + " modificato_il DATETIME(3) NOT NULL"
                    + ") ENGINE=InnoDB");
            try (ResultSet rs = st.executeQuery("SELECT chiave, valore FROM impostazioni")) {
                while (rs.next()) {
                    if (PER_CHIAVE.containsKey(rs.getString(1))) VALORI.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
    }

    /** Valore corrente di un parametro (salvato o predefinito). */
    static int intero(String chiave) {
        Def d = PER_CHIAVE.get(chiave);
        if (d == null) throw new IllegalArgumentException("Parametro sconosciuto: " + chiave);
        return VALORI.getOrDefault(chiave, d.predefinito());
    }

    /** Elenco di tutti i parametri, con valore attuale, predefinito e intervallo. */
    static List<Map<String, Object>> elenco() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Def d : DEFINIZIONI) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chiave", d.chiave());
            m.put("gruppo", d.gruppo());
            m.put("descrizione", d.descrizione());
            m.put("valore", intero(d.chiave()));
            m.put("predefinito", d.predefinito());
            m.put("min", d.min());
            m.put("max", d.max());
            m.put("unita", d.unita());
            out.add(m);
        }
        return out;
    }

    /**
     * Salva uno o più parametri. Ogni valore deve essere un intero nell'intervallo ammesso;
     * altrimenti nessuna modifica viene applicata.
     */
    static void salva(Map<String, String> nuovi) throws SQLException {
        Map<String, Integer> da = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : nuovi.entrySet()) {
            Def d = PER_CHIAVE.get(e.getKey());
            if (d == null) throw new IllegalArgumentException("Parametro sconosciuto: " + e.getKey());
            int v;
            try {
                v = Integer.parseInt(e.getValue().trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(d.descrizione() + ": inserire un numero intero");
            }
            if (v < d.min() || v > d.max()) {
                throw new IllegalArgumentException(d.descrizione() + ": valore ammesso da " + d.min() + " a " + d.max() + " " + d.unita());
            }
            da.put(d.chiave(), v);
        }
        try (Connection c = Store.conn()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO impostazioni (chiave, valore, modificato_il) VALUES (?, ?, NOW(3)) "
                    + "ON DUPLICATE KEY UPDATE valore = VALUES(valore), modificato_il = NOW(3)")) {
                for (Map.Entry<String, Integer> e : da.entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setInt(2, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        }
        VALORI.putAll(da);
    }

    /** Ripristina il predefinito di un parametro (cancella il valore salvato). */
    static void ripristina(String chiave) throws SQLException {
        if (!PER_CHIAVE.containsKey(chiave)) throw new IllegalArgumentException("Parametro sconosciuto: " + chiave);
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("DELETE FROM impostazioni WHERE chiave = ?")) {
            ps.setString(1, chiave);
            ps.executeUpdate();
        }
        VALORI.remove(chiave);
    }
}
