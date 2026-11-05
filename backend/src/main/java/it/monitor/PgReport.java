package it.monitor;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rilevamento di un server PostgreSQL. Produce la stessa struttura del rilevamento MySQL/MariaDB
 * (identita, replica, queryAttive, queryLente, indici, utenti...), così dashboard, eventi e Insight
 * trattano i server allo stesso modo. Sola lettura: solo viste di sistema e funzioni di stato.
 */
final class PgReport {

    private PgReport() {
    }

    static Map<String, Object> report(JsonNode cfg, boolean pesante, Map<String, Object> precedente) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("generatoAlle", java.time.Instant.now().toString());
        r.put("server", Main.m("id", Main.text(cfg, "id"), "name", Main.text(cfg, "name"), "jdbcUrl", Main.jdbcUrl(cfg), "kind", "postgres"));
        Map<String, String> errori = new LinkedHashMap<>();
        r.put("errori", errori);
        long t0 = System.currentTimeMillis();
        try (Connection c = Main.open(cfg)) {
            r.put("connessione", Main.m("ok", true, "ms", System.currentTimeMillis() - t0));

            // Identità e ruolo del nodo
            Map<String, Object> v = Main.q(c, "SELECT current_setting('server_version') AS version, "
                    + "inet_server_addr()::text AS addr, current_setting('port') AS porta, pg_is_in_recovery() AS in_recovery").get(0);
            String version = Main.str(v.get("version"));
            boolean replica = Boolean.TRUE.equals(v.get("in_recovery"));
            r.put("identita", Main.m("edizione", "PostgreSQL", "motore", "PostgreSQL", "versione", version,
                    "patch", version.split(" ")[0], "commento", "PostgreSQL " + version,
                    "hostname", Main.text(cfg, "host"), "porta", Main.num(v.get("porta")),
                    "readOnly", replica, "superReadOnly", replica, "gtidEseguito", null, "serverUuid", null));
            r.put("ruoloNodo", replica ? "REPLICA" : "PRIMARY");

            // Replica in streaming: lato repliche lo stato del ricevitore; lato primario le repliche collegate
            List<Map<String, Object>> replica_ = new ArrayList<>();
            List<Object> repliche = new ArrayList<>();
            if (replica) {
                for (Map<String, Object> w : Main.q(c, "SELECT status, sender_host, "
                        + "CASE WHEN pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() THEN 0 "
                        + "ELSE COALESCE(EXTRACT(EPOCH FROM now() - pg_last_xact_replay_timestamp())::int, 0) END AS ritardo "
                        + "FROM pg_stat_wal_receiver")) {
                    boolean attivo = "streaming".equals(Main.str(w.get("status")));
                    replica_.add(Main.m("canale", "streaming", "sorgente", Main.str(w.get("sender_host")),
                            "ioAttivo", attivo ? "Yes" : "No", "sqlAttivo", attivo ? "Yes" : "No",
                            "secondiDiRitardo", Main.num(w.get("ritardo")), "relayLogBytes", 0L,
                            "ultimoErrore", attivo ? "" : "ricevitore non in streaming: " + w.get("status")));
                }
                if (replica_.isEmpty()) {
                    replica_.add(Main.m("canale", "streaming", "sorgente", "", "ioAttivo", "No", "sqlAttivo", "No",
                            "secondiDiRitardo", null, "relayLogBytes", 0L, "ultimoErrore", "ricevitore assente"));
                }
            } else {
                for (Map<String, Object> s : Main.q(c, "SELECT application_name, client_addr::text AS addr, state, sync_state, "
                        + "EXTRACT(EPOCH FROM replay_lag)::int AS ritardo FROM pg_stat_replication ORDER BY application_name")) {
                    repliche.add(Main.m("nome", s.get("application_name"), "indirizzo", s.get("addr"),
                            "stato", s.get("state"), "sincrono", s.get("sync_state"), "ritardoSec", s.get("ritardo")));
                }
            }
            r.put("replica", replica_);
            r.put("repliche", repliche);
            r.put("relayLogTotaleBytes", 0L);

            String tipo = replica
                    ? "Replica PostgreSQL in streaming"
                    : "Primario PostgreSQL" + (repliche.isEmpty() ? "" : " con " + repliche.size() + " repliche");
            r.put("topologia", Main.m("tipo", tipo, "cluster", null));
            r.put("cluster", null);
            r.put("galera", null);

            // Query attive (pg_stat_activity)
            r.put("queryAttive", !Main.controllo(cfg, "query_attive") ? null : safe(errori, "queryAttive", () -> Main.q(c,
                    "SELECT pid AS \"ID\", usename AS \"UTENTE\", COALESCE(client_addr::text, 'locale') AS \"HOST\", "
                            + "datname AS \"DB\", state AS \"STATO\", "
                            + "ROUND(EXTRACT(EPOCH FROM now() - query_start)::numeric, 3) AS \"SECONDI\", "
                            + "left(query, 1000) AS \"QUERY\" "
                            + "FROM pg_stat_activity WHERE state IS NOT NULL AND state <> 'idle' AND pid <> pg_backend_pid() "
                            + "AND backend_type = 'client backend' "
                            + "ORDER BY query_start NULLS LAST LIMIT " + Impostazioni.intero("query.attive_limite"))));

            // Transazioni bloccate: sessioni in attesa di un lock e il pid che lo tiene (pg_blocking_pids)
            r.put("bloccate", !Main.controllo(cfg, "transazioni_bloccate") ? null : safe(errori, "bloccate", () -> Main.q(c, "SELECT a.pid AS \"ID\", a.usename AS \"UTENTE\", a.datname AS \"DB\", "
                    + "ROUND(EXTRACT(EPOCH FROM now() - a.query_start)::numeric, 1) AS \"SECONDI\", left(a.query, 300) AS \"QUERY\", "
                    + "(pg_blocking_pids(a.pid))[1] AS \"BLOCCANTE_ID\", left(b.query, 300) AS \"QUERY_BLOCCANTE\" "
                    + "FROM pg_stat_activity a LEFT JOIN pg_stat_activity b ON b.pid = (pg_blocking_pids(a.pid))[1] "
                    + "WHERE cardinality(pg_blocking_pids(a.pid)) > 0 ORDER BY \"SECONDI\" DESC LIMIT 100")));

            // Query più lente (pg_stat_statements, se installato)
            r.put("queryLente", !Main.controllo(cfg, "query_lente") ? null : Main.filtraPianificabili(safe(errori, "queryLente", () -> {
                if (!estensioneInstallata(c, "pg_stat_statements")) return List.of();
                return Main.q(c, "SELECT datname AS \"SCHEMA_NAME\", left(query, 300) AS \"QUERY\", calls AS \"ESECUZIONI\", "
                        + "ROUND((mean_exec_time / 1000)::numeric, 4) AS \"MEDIA_SEC\", "
                        + "ROUND((max_exec_time / 1000)::numeric, 4) AS \"MAX_SEC\", rows AS \"RIGHE_ESAMINATE\", "
                        + "0 AS \"SENZA_INDICE\" "
                        + "FROM pg_stat_statements s LEFT JOIN pg_database d ON d.oid = s.dbid "
                        + "WHERE query NOT ILIKE '%pg_stat_statements%' AND query NOT ILIKE 'SET %' "
                        + "ORDER BY mean_exec_time DESC LIMIT " + Impostazioni.intero("query.lente_limite"));
            }))); 

            // Indici e chiavi primarie
if (Main.controllo(cfg, "indici")) {
            Map<String, Object> indici = new LinkedHashMap<>();
            if (pesante || precedente == null) {
                indici.put("elenco", safe(errori, "indici", () -> Main.q(c,
                        "SELECT schemaname AS object_schema, tablename AS table_name, indexname AS index_name, indexdef AS definizione "
                                + "FROM pg_indexes WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY 1,2,3 LIMIT 300")));
                indici.put("tabelleSenzaPrimaryKey", safe(errori, "tabelleSenzaPk", () -> Main.q(c,
                        "SELECT n.nspname AS table_schema, c.relname AS table_name FROM pg_class c "
                                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                                + "WHERE c.relkind IN ('r','p') AND n.nspname NOT IN ('pg_catalog','information_schema','pg_toast') "
                                + "AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conrelid = c.oid AND k.contype = 'p')")));
                indici.put("indiciInutilizzati", safe(errori, "indiciInutilizzati", () -> Main.q(c,
                        "SELECT s.schemaname AS object_schema, s.relname AS object_name, s.indexrelname AS index_name "
                                + "FROM pg_stat_user_indexes s JOIN pg_index i ON i.indexrelid = s.indexrelid "
                                + "WHERE s.idx_scan = 0 AND NOT i.indisunique AND NOT i.indisprimary LIMIT 100")));
            } else {
                // rilevamento leggero: si riusano le sezioni pesanti dell'ultimo rilevamento completo
                Object prec = precedente.get("indici");
                if (prec != null) indici = Monitor.map(prec);
            }
            r.put("indici", indici);
            }

            // Utenti e ruoli
            // Transazioni inattive oltre la soglia configurabile (insight.transazioni_inattive_minuti): bloccano il vacuum
            r.put("transazioniInattive", !Main.controllo(cfg, "transazioni_inattive") ? null : safe(errori, "transazioniInattive", () -> Main.q(c,
                    "SELECT count(*) AS n FROM pg_stat_activity WHERE state LIKE 'idle in transaction%' AND now() - state_change > interval '"
                            + Impostazioni.intero("insight.transazioni_inattive_minuti") + " minutes'").get(0)));
            r.put("connessioni", !Main.controllo(cfg, "connessioni") ? null : safe(errori, "connessioni", () -> Main.q(c,
                    "SELECT count(*) AS attive FROM pg_stat_activity WHERE backend_type = 'client backend'").get(0)));
            // Utenti: scadenza dei ruoli (rolvaliduntil). PostgreSQL non registra la data di creazione dei ruoli.
            r.put("utentiDettaglio", !Main.controllo(cfg, "utenti") ? null : safe(errori, "utentiDettaglio", () -> Main.q(c,
                    "SELECT rolname AS utente, CASE WHEN rolcanlogin THEN 'login' ELSE 'ruolo' END AS tipo, "
                    + "'non tracciata da PostgreSQL' AS creazione, "
                    + "CASE WHEN rolvaliduntil IS NULL THEN 'non applicabile' WHEN rolvaliduntil < now() THEN 'scaduta' "
                    + "ELSE 'scade il ' || rolvaliduntil::date END AS scadenza "
                    + "FROM pg_roles WHERE rolname NOT LIKE 'pg\\_%' ORDER BY rolname")));
            r.put("databaseDettaglio", !Main.controllo(cfg, "database") ? null : safe(errori, "database", () -> Main.q(c,
                    "SELECT datname AS database, 'non tracciata da PostgreSQL' AS creazione, 'online' AS stato, "
                    + "pg_database_size(oid) / 1048576 AS \"dimensioneMB\" FROM pg_database WHERE datallowconn ORDER BY datname")));
            r.put("utenti", safe(errori, "utenti", () -> {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> u : Main.q(c, "SELECT rolname, rolcanlogin, rolsuper, "
                        + "(rolvaliduntil IS NOT NULL AND rolvaliduntil < now()) AS scaduta FROM pg_roles "
                        + "WHERE rolname NOT LIKE 'pg\\_%' ORDER BY rolname")) {
                    List<String> privilegi = new ArrayList<>();
                    if (Boolean.TRUE.equals(u.get("rolsuper"))) privilegi.add("SUPERUSER");
                    out.add(Main.m("utente", u.get("rolname") + "@postgres", "plugin", "scram-sha-256",
                            "bloccato", !Boolean.TRUE.equals(u.get("rolcanlogin")),
                            "passwordScaduta", Boolean.TRUE.equals(u.get("scaduta")),
                            "privilegiGlobali", privilegi, "database", List.of()));
                }
                return out;
            }));

            // Dimensione dati e colonne vettoriali (pgvector)
            if (pesante || precedente == null) {
                safe(errori, "dati", () -> {
                    Map<String, Object> d = Main.q(c, "SELECT COALESCE(SUM(pg_database_size(oid)), 0) AS bytes "
                            + "FROM pg_database WHERE datallowconn").get(0);
                    r.put("datiBytes", Main.num(d.get("bytes")));
                    return null;
                });
                safe(errori, "vettoriale", () -> {
                    Map<String, Object> n = Main.q(c, "SELECT COUNT(*) AS n FROM pg_attribute a "
                            + "JOIN pg_type t ON t.oid = a.atttypid WHERE t.typname = 'vector' AND NOT a.attisdropped").get(0);
                    r.put("colonneVettoriali", Main.num(n.get("n")));
                    return null;
                });
            } else {
                for (String k : List.of("datiBytes", "colonneVettoriali")) {
                    if (precedente.containsKey(k)) r.put(k, precedente.get(k));
                }
            }

            // Risorse dell'host (come per gli altri server, solo modalità host)
if (Main.controllo(cfg, "risorse")) {
            Map<String, Object> risorse = new LinkedHashMap<>();
            try {
                if ("host".equals(Main.text(cfg, "resourceMode"))) {
                    risorse.putAll(HostMetrics.stats(Main.text(cfg, "metricsUrl")));
                }
            } catch (Exception e) {
                errori.put("risorseContainer", e.getMessage());
            }
            r.put("risorse", risorse);
            }
        } catch (SQLException e) {
            r.put("connessione", Main.m("ok", false, "errore", e.getMessage()));
        }
        return r;
    }

    private static boolean estensioneInstallata(Connection c, String nome) throws SQLException {
        return !Main.q(c, "SELECT 1 AS x FROM pg_extension WHERE extname = '" + nome + "'").isEmpty();
    }

    /** Esegue una sezione; se fallisce registra l'errore senza interrompere il resto. */
    private static Object safe(Map<String, String> errori, String nome, Main.Section s) {
        try {
            return s.run();
        } catch (Exception e) {
            errori.put(nome, e.getMessage());
            return List.of();
        }
    }
}
