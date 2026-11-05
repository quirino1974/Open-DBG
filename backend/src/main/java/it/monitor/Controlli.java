package it.monitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogo unico dei controlli selezionabili per tecnologia. È la fonte per l'interfaccia (scheda del server)
 * e per Insight: un controllo non presente qui non viene offerto, e uno deselezionato per un server non viene
 * eseguito né dal rilevamento né dal report Insight. Tipi di server: "server" (MySQL, MariaDB, Galera,
 * InnoDB Cluster), "postgres", "sqlserver", "mongodb". Router e monitor non hanno controlli selezionabili.
 */
final class Controlli {
    private Controlli() {
    }

    /** Un controllo: chiave (quella salvata in anagrafica), etichetta e nota sui limiti di disponibilità. */
    record Voce(String chiave, String etichetta, String nota) {
    }

    static final String NOTA_RISORSE_HOST = "CPU e memoria dell'host solo in modalità host (node_exporter)";

    static final Map<String, List<Voce>> PER_TECNOLOGIA = Map.of(
            "server", List.of(
                    new Voce("connessioni", "Connessioni: attive e picco rispetto a max_connections", ""),
                    new Voce("transazioni_bloccate", "Transazioni bloccate: sessioni in attesa di un lock", ""),
                    new Voce("query_attive", "Query in esecuzione, ordinate per durata", ""),
                    new Voce("query_lente", "Query più lente per digest, con verifica degli indici", ""),
                    new Voce("utenti", "Utenti: anonimi, root da qualunque host, password scadute, account bloccati", ""),
                    new Voce("database", "Database: dimensione (la data di creazione non è tracciata)", ""),
                    new Voce("risorse", "Memoria allocata da mysqld e buffer pool", NOTA_RISORSE_HOST),
                    new Voce("indici", "Indici inutilizzati e tabelle senza chiave primaria", "")),
            "postgres", List.of(
                    new Voce("connessioni", "Connessioni attive rispetto a max_connections", ""),
                    new Voce("transazioni_bloccate", "Transazioni bloccate: sessioni in attesa di un lock", ""),
                    new Voce("query_attive", "Query in esecuzione, ordinate per durata", ""),
                    new Voce("query_lente", "Query più lente", ""),
                    new Voce("utenti", "Utenti e ruoli: data di scadenza", ""),
                    new Voce("database", "Database: dimensione (la data di creazione non è tracciata)", ""),
                    new Voce("risorse", "Memoria e CPU dell'host", NOTA_RISORSE_HOST),
                    new Voce("indici", "Indici inutilizzati e tabelle senza chiave primaria", ""),
                    new Voce("transazioni_inattive", "Transazioni inattive oltre la soglia (bloccano il vacuum)", ""),
                    new Voce("vacuum", "Vacuum e autovacuum: tabelle con troppe tuple morte", "")),
            "sqlserver", List.of(
                    new Voce("connessioni", "Connessioni utente attive", ""),
                    new Voce("transazioni_bloccate", "Transazioni bloccate: sessioni in attesa di un lock", ""),
                    new Voce("query_attive", "Query in esecuzione, ordinate per durata", ""),
                    new Voce("query_lente", "Query più lente", ""),
                    new Voce("utenti", "Utenti: data di creazione e scadenza", ""),
                    new Voce("database", "Database: dimensione e data di creazione", ""),
                    new Voce("risorse", "Memoria di sistema e numero di CPU", ""),
                    new Voce("topologia", "Topologia: standalone, FCI, Always On", ""),
                    new Voce("backup", "Backup: ultimo backup completo e di log per database", "")),
            "mongodb", List.of(
                    new Voce("connessioni", "Connessioni correnti rispetto al massimo disponibile", ""),
                    new Voce("transazioni_bloccate", "Transazioni bloccate: sessioni in attesa di un lock", ""),
                    new Voce("query_attive", "Operazioni in esecuzione oltre la soglia di durata", ""),
                    new Voce("query_lente", "Query più lente dal profiler (system.profile, se attivo)", ""),
                    new Voce("replica", "Replica set: ritardo di replica dei secondari (canali e membri)", ""),
                    new Voce("oplog", "Finestra dell'oplog: ore di storico per recuperare un secondario", ""),
                    new Voce("cache", "Cache WiredTiger: byte sporchi rispetto alla cache configurata", ""),
                    new Voce("database", "Database: dimensione su disco", ""),
                    new Voce("risorse", "Memoria residente di mongod", NOTA_RISORSE_HOST)));

    /** Elenco per l'interfaccia: tecnologia -> controlli (chiave, etichetta, nota). */
    static Map<String, Object> catalogo() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Voce>> e : PER_TECNOLOGIA.entrySet()) {
            List<Map<String, Object>> voci = new ArrayList<>();
            for (Voce v : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("k", v.chiave());
                m.put("l", v.etichetta());
                m.put("nota", v.nota());
                voci.add(m);
            }
            out.put(e.getKey(), voci);
        }
        return out;
    }

    /** Il controllo è selezionato per il server? Vuoto = tutti, "nessuno" = nessuno (vedi Main.controllo). */
    static boolean attivo(com.fasterxml.jackson.databind.JsonNode server, String chiave) {
        return Main.controllo(server, chiave);
    }
}
