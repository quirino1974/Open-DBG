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
                    "Ora del giorno (0-23) della produzione giornaliera del report Insight", 6, 0, 23, ""),
            new Def("insight.connessioni_pct", "Insight",
                    "Percentuale di connessioni usate oltre la quale segnalare (MySQL, MariaDB, PostgreSQL, MongoDB)", 90, 50, 100, "%"),
            new Def("insight.buffer_pool_hit_pct", "Insight",
                    "Hit ratio minimo del buffer pool InnoDB, sotto il quale segnalare (MySQL, MariaDB)", 95, 50, 100, "%"),
            new Def("insight.cache_hit_pct", "Insight",
                    "Hit ratio minimo della cache di PostgreSQL, sotto il quale segnalare", 95, 50, 100, "%"),
            new Def("insight.transazioni_inattive_minuti", "Insight",
                    "Dopo quanti minuti una transazione inattiva viene segnalata (PostgreSQL)", 5, 1, 1440, "min"),
            new Def("insight.replica_ritardo_secondi", "Insight",
                    "Ritardo della replica oltre il quale segnalare (MySQL, MariaDB, PostgreSQL, MongoDB)", 60, 1, 86400, "s"),
            new Def("insight.memoria_sqlserver_pct", "Insight",
                    "Memoria di sistema occupata oltre la quale segnalare (SQL Server)", 90, 50, 100, "%"),
            new Def("insight.backup_full_giorni", "Insight",
                    "Dopo quanti giorni senza backup completo segnalare un database (SQL Server)", 1, 1, 30, "giorni"),
            new Def("insight.backup_log_ore", "Insight",
                    "Dopo quante ore senza backup del log segnalare un database in modello FULL (SQL Server)", 24, 1, 720, "ore"),
            new Def("insight.tmp_disco_pct", "Insight",
                    "Quota di tabelle temporanee create su disco oltre la quale segnalare (MySQL, MariaDB)", 25, 5, 100, "%"),
            new Def("insight.tmp_minimo_tabelle", "Insight",
                    "Numero minimo di tabelle temporanee create prima di valutare la quota su disco (MySQL, MariaDB)", 100, 10, 100000, "tabelle"),
            new Def("insight.hit_minimo_accessi", "Insight",
                    "Numero minimo di accessi alla cache prima di calcolare l'hit ratio (MySQL, MariaDB, PostgreSQL)", 10000, 100, 10000000, "accessi"),
            new Def("insight.vacuum_morte_pct", "Insight",
                    "Quota di tuple morte oltre la quale segnalare una tabella (PostgreSQL)", 20, 5, 100, "%"),
            new Def("insight.vacuum_righe_minime", "Insight",
                    "Numero minimo di righe di una tabella perché conti per il controllo vacuum (PostgreSQL)", 1000, 100, 1000000, "righe"),
            new Def("insight.mongo_oplog_ore_minime", "Insight",
                    "Ore minime di storico nell'oplog sotto le quali segnalare il replica set (MongoDB)", 24, 1, 720, "ore"),
            new Def("insight.mongo_cache_sporca_pct", "Insight",
                    "Quota di byte sporchi nella cache WiredTiger oltre la quale segnalare (MongoDB)", 20, 1, 100, "%"),
            new Def("insight.history_list_max", "Insight",
                    "Lunghezza della history list di InnoDB oltre la quale segnalare (MySQL, MariaDB): letture lunghe bloccano il purge", 100000, 1000, 100000000, "transazioni"),
            new Def("insight.sqlserver_ple_secondi", "Insight",
                    "Page life expectancy minima di SQL Server, sotto la quale segnalare la pressione sulla memoria", 300, 10, 86400, "s"),
            new Def("insight.pg_xid_eta_milioni", "Insight",
                    "Età massima dei XID di un database (in milioni) oltre la quale segnalare il rischio di wraparound (PostgreSQL)", 1000, 100, 2000, "milioni"));

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
