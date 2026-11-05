package it.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ClusterConnectionMode;
import org.bson.BsonTimestamp;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Rilevamento di un nodo MongoDB (standalone o membro di un replica set). Produce la stessa struttura
 * degli altri tipi (identita, topologia, cluster, replica, queryAttive, database, risorse), così dashboard
 * ed eventi trattano i server allo stesso modo.
 *
 * <p>Controlli specifici di MongoDB: stato dei membri del replica set con ritardo di replica, finestra
 * dell'oplog (quanto storico resta per recuperare un secondario), operazioni in esecuzione oltre la soglia
 * (via $currentOp), connessioni, dimensione dei database, memoria e cache WiredTiger.
 *
 * <p>Sola lettura: richiede il ruolo {@code clusterMonitor} e {@code readAnyDatabase} sul database admin.
 * Il campo "database" del server è il database di autenticazione (authSource), di default admin.
 */
final class MongoReport {

    private MongoReport() {
    }

    /**
     * Client diretto verso il nodo configurato (modalità SINGLE): ogni server viene rilevato per quello che è.
     * Con la modalità MULTIPLE i comandi andrebbero al primario e il secondario risulterebbe primario.
     * Lo stato degli altri membri arriva comunque da replSetGetStatus.
     */
    static MongoClient client(JsonNode cfg) {
        String authSource = Main.text(cfg, "database").isEmpty() ? "admin" : Main.text(cfg, "database");
        MongoCredential cred = MongoCredential.createCredential(Main.text(cfg, "user"), authSource,
                Main.rawText(cfg, "password").toCharArray());
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyToClusterSettings(b -> b
                        .hosts(List.of(new ServerAddress(Main.text(cfg, "host"), cfg.get("port").asInt())))
                        .mode(ClusterConnectionMode.SINGLE)
                        .serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS))
                .credential(cred)
                .build();
        return MongoClients.create(settings);
    }

    /** Verifica di connessione per il pulsante "Prova connessione". */
    static Map<String, Object> test(JsonNode cfg) {
        long t0 = System.currentTimeMillis();
        try (MongoClient c = client(cfg)) {
            Document info = c.getDatabase("admin").runCommand(new Document("buildInfo", 1));
            return Main.m("ok", true, "message", "Connessione riuscita", "version", info.getString("version"),
                    "edizione", edizione(info), "ms", System.currentTimeMillis() - t0);
        } catch (RuntimeException e) {
            return Main.m("ok", false, "message", String.valueOf(e.getMessage()), "ms", System.currentTimeMillis() - t0);
        }
    }

    static Map<String, Object> report(JsonNode cfg, boolean pesante, Map<String, Object> precedente) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("generatoAlle", java.time.Instant.now().toString());
        r.put("server", Main.m("id", Main.text(cfg, "id"), "name", Main.text(cfg, "name"),
                "jdbcUrl", Main.jdbcUrl(cfg), "kind", "mongodb"));
        Map<String, String> errori = new LinkedHashMap<>();
        r.put("errori", errori);
        long t0 = System.currentTimeMillis();
        try (MongoClient c = client(cfg)) {
            Document admin = c.getDatabase("admin").runCommand(new Document("hello", 1));
            r.put("connessione", Main.m("ok", true, "ms", System.currentTimeMillis() - t0));

            Document info = c.getDatabase("admin").runCommand(new Document("buildInfo", 1));
            String version = str(info.getString("version"));
            r.put("identita", Main.m("edizione", edizione(info), "motore", "MongoDB", "versione", version,
                    "patch", version, "commento", "MongoDB " + version,
                    "hostname", Main.text(cfg, "host"), "porta", n(cfg.get("port").asInt()),
                    "readOnly", false, "superReadOnly", false, "gtidEseguito", null, "serverUuid", null));

            // Replica set: stato di tutti i membri (non disponibile su un nodo standalone)
            Document rs = null;
            try {
                rs = c.getDatabase("admin").runCommand(new Document("replSetGetStatus", 1));
            } catch (RuntimeException e) {
                // nodo standalone: nessun replica set
            }
            if (rs != null) {
                sezioneReplicaSet(c, cfg, r, errori, rs, pesante, precedente);
            } else {
                boolean primario = Boolean.TRUE.equals(admin.getBoolean("isWritablePrimary", false));
                r.put("ruoloNodo", primario ? "PRIMARY" : "STANDALONE");
                r.put("topologia", Main.m("tipo", "Standalone", "cluster", null));
                r.put("cluster", null);
                r.put("replica", new ArrayList<>());
                r.put("repliche", new ArrayList<>());
            }
            r.put("relayLogTotaleBytes", 0L);
            r.put("galera", null);

            // Operazioni in esecuzione oltre la soglia di durata
            r.put("queryAttive", !Main.controllo(cfg, "query_attive") ? null : safe(errori, "queryAttive", () -> {
                List<Object> out = new ArrayList<>();
                long soglia = Impostazioni.intero("query.attive_soglia_secondi");
                for (Document op : c.getDatabase("admin").aggregate(List.of(
                        new Document("$currentOp", new Document("allUsers", true).append("idleConnections", false)),
                        new Document("$match", new Document("active", true).append("secs_running", new Document("$gte", soglia))),
                        new Document("$limit", Impostazioni.intero("query.attive_limite"))))) {
                    out.add(Main.m("ID", String.valueOf(op.get("opid")), "UTENTE", utenteOperazione(op),
                            "HOST", String.valueOf(op.getOrDefault("client", "")), "DB", String.valueOf(op.getOrDefault("ns", "")),
                            "STATO", String.valueOf(op.getOrDefault("op", "")),
                            "SECONDI", op.get("secs_running"),
                            "QUERY", troncata(op.get("command"), 1000)));
                }
                return out;
            }));
            // Query più lente dal profiler (system.profile dei database utente), se il profiler è attivo sul nodo
            // Operazioni in attesa di un lock: MongoDB non indica la sessione che lo tiene
            r.put("bloccate", !Main.controllo(cfg, "transazioni_bloccate") ? null : safe(errori, "bloccate", () -> {
                List<Object> out = new ArrayList<>();
                for (Document op : c.getDatabase("admin").aggregate(List.of(
                        new Document("$currentOp", new Document("allUsers", true).append("idleConnections", false)),
                        new Document("$match", new Document("waitingForLock", true)),
                        new Document("$limit", 100)))) {
                    out.add(Main.m("ID", String.valueOf(op.get("opid")), "UTENTE", utenteOperazione(op),
                            "DB", String.valueOf(op.getOrDefault("ns", "")), "SECONDI", op.get("secs_running"),
                            "QUERY", troncata(op.get("command"), 300), "BLOCCANTE_ID", null, "QUERY_BLOCCANTE", null));
                }
                return out;
            }));
            r.put("queryLente", !Main.controllo(cfg, "query_lente") ? null : safe(errori, "queryLente", () -> queryLente(c)));

            // Connessioni e memoria dal serverStatus
            Document stato = safe(errori, "serverStatus", () ->
                    c.getDatabase("admin").runCommand(new Document("serverStatus", 1)));
            Document conn = stato == null ? null : stato.get("connections", Document.class);
            r.put("connessioni", !Main.controllo(cfg, "connessioni") || conn == null ? null : Main.m(
                    "attive", n(conn.get("current")),
                    "disponibili", n(conn.get("available")),
                    "massimo", n(conn.get("current")) + n(conn.get("available"))));

            // Database e dimensione su disco
            r.put("databaseDettaglio", !Main.controllo(cfg, "database") ? null : safe(errori, "database", () -> {
                List<Object> out = new ArrayList<>();
                for (Document d : c.listDatabases()) {
                    out.add(Main.m("database", d.getString("name"), "creazione", "non tracciata da MongoDB",
                            "stato", "online", "dimensioneMB", n(d.get("sizeOnDisk")) / 1048576));
                }
                return out;
            }));
            r.put("utenti", List.of());

            // Dimensione dati complessiva (per la scheda del server)
            long dati = 0;
            for (Document d : c.listDatabases()) dati += n(d.get("sizeOnDisk"));
            r.put("datiBytes", dati);
            // Vector database: collezioni con un campo vettoriale (array di almeno 64 numeri, verificato su un documento).
            // Il server Community non ha indici di ricerca vettoriale: il controllo è sul contenuto. Nei rilevamenti leggeri si riusa il valore precedente.
            long vettoriali = 0;
            if (pesante || precedente == null) {
                for (String nomeDb : c.listDatabaseNames()) {
                    if (List.of("admin", "config", "local").contains(nomeDb)) continue;
                    for (String col : c.getDatabase(nomeDb).listCollectionNames()) {
                        Document d = c.getDatabase(nomeDb).getCollection(col).find().limit(1).first();
                        if (d != null && haCampoVettoriale(d)) vettoriali++;
                    }
                }
            } else {
                vettoriali = Main.num(precedente.get("colonneVettoriali"));
            }
            r.put("colonneVettoriali", vettoriali);

            // Risorse: memoria residente e cache WiredTiger; CPU e memoria dell'host solo in modalità host
            Map<String, Object> risorse = new LinkedHashMap<>();
            if (Main.controllo(cfg, "risorse") && stato != null) {
                Document mem = stato.get("mem", Document.class);
                if (mem != null) risorse.put("memoriaResidenteBytes", n(mem.get("resident")) * 1048576);
                Document wt = stato.get("wiredTiger", Document.class);
                if (wt != null) {
                    Document cache = wt.get("cache", Document.class);
                    if (cache != null) risorse.put("cacheBytes", n(cache.get("bytes currently in the cache")));
                }
                try {
                    if ("host".equals(Main.text(cfg, "resourceMode"))) {
                        risorse.putAll(HostMetrics.stats(Main.text(cfg, "metricsUrl")));
                    }
                } catch (Exception e) {
                    errori.put("risorseContainer", e.getMessage());
                }
            }
            r.put("risorse", Main.controllo(cfg, "risorse") ? risorse : null);
            // Cache WiredTiger: byte "sporchi" (da scrivere su disco) rispetto alla cache configurata
            Document wtCache = null;
            if (stato != null && stato.get("wiredTiger", Document.class) != null) {
                wtCache = stato.get("wiredTiger", Document.class).get("cache", Document.class);
            }
            r.put("cacheWiredTiger", Main.controllo(cfg, "cache") && wtCache != null ? Main.m(
                    "sporcaBytes", n(wtCache.get("tracked dirty bytes in the cache")),
                    "massimaBytes", n(wtCache.get("maximum bytes configured")),
                    "inCacheBytes", n(wtCache.get("bytes currently in the cache"))) : null);
            r.put("indici", null);
        } catch (RuntimeException e) {
            r.put("connessione", Main.m("ok", false, "errore", String.valueOf(e.getMessage())));
        }
        return r;
    }

    /** Membri del replica set: topologia, cluster con i membri, ritardo dei secondari e finestra dell'oplog. */
    private static void sezioneReplicaSet(MongoClient c, JsonNode cfg, Map<String, Object> r, Map<String, String> errori,
                                          Document rs, boolean pesante, Map<String, Object> precedente) {
        String setName = str(rs.getString("set"));
        List<Document> membri = new ArrayList<>();
        Object lista = rs.get("members");
        if (lista instanceof List<?> l) for (Object o : l) membri.add((Document) o);

        Document primario = null;
        for (Document m : membri) if ("PRIMARY".equals(m.getString("stateStr"))) primario = m;
        String primarioHost = primario == null ? "" : hostSenzaPorta(primario.getString("name"));
        // Controllo "replica": canali e secondari con il ritardo. Gli altri dati della topologia restano sempre.
        boolean replica = Main.controllo(cfg, "replica");
        // Priorità, voti, nascosto e arbitro non stanno in replSetGetStatus: si leggono dalla configurazione
        Map<String, Document> configMembri = new java.util.HashMap<>();
        try {
            Document conf = c.getDatabase("admin").runCommand(new Document("replSetGetConfig", 1)).get("config", Document.class);
            if (conf != null && conf.get("members") instanceof List<?> lc) {
                for (Object o : lc) {
                    Document d = (Document) o;
                    configMembri.put(d.getString("host"), d);
                }
            }
        } catch (RuntimeException e) {
            errori.put("configurazioneReplica", String.valueOf(e.getMessage()));
        }

        String ruolo = "UNKNOWN";
        List<Map<String, Object>> membriCluster = new ArrayList<>();
        List<Object> repliche = new ArrayList<>();
        List<Map<String, Object>> canali = new ArrayList<>();
        boolean tuttiSani = true;
        for (Document m : membri) {
            String host = hostSenzaPorta(m.getString("name"));
            String stato = str(m.getString("stateStr"));
            boolean sano = n(m.get("health")) == 1;
            tuttiSani &= sano;
            boolean self = Boolean.TRUE.equals(m.getBoolean("self", false));
            if (self) ruolo = stato;
            // Dettagli del membro per la topologia: priorità, voti, nascosto, arbitro, ritardo dal primario
            Document cm = configMembri.getOrDefault(m.getString("name"), new Document());
            membriCluster.add(Main.m("MEMBER_HOST", host, "MEMBER_PORT", portaDi(m.getString("name")),
                    "MEMBER_STATE", stato, "MEMBER_ROLE", stato, "codaTransazioni", null,
                    "gtidTransazioni", null, "errant", null,
                    "priorita", cm.get("priority") instanceof Number pr ? pr.doubleValue() : null,
                    "voti", cm.get("votes") instanceof Number vo ? vo.longValue() : null,
                    "nascosto", Boolean.TRUE.equals(cm.getBoolean("hidden", false)),
                    "arbitro", Boolean.TRUE.equals(cm.getBoolean("arbiterOnly", false)),
                    "sano", sano,
                    "ritardoSec", ritardoRispettoAl(m, primario)));
            // Canale di replica: solo dal punto di vista del nodo rilevato, se è un secondario
            if (replica && self && primario != null && !"PRIMARY".equals(stato) && !"ARBITER".equals(stato)) {
                long ritardo = 0;
                if (m.get("optimeDate") instanceof Date d && primario.get("optimeDate") instanceof Date p) {
                    ritardo = Math.max(0, (p.getTime() - d.getTime()) / 1000);
                }
                boolean attivo = sano && "SECONDARY".equals(stato);
                canali.add(Main.m("canale", setName, "sorgente", primarioHost,
                        "ioAttivo", attivo ? "Yes" : "No", "sqlAttivo", attivo ? "Yes" : "No",
                        "secondiDiRitardo", ritardo, "relayLogBytes", 0L,
                        "ultimoErrore", attivo ? "" : "membro non sano: " + stato));
            }
            if (replica && "PRIMARY".equals(stato) && self) {
                for (Document o : membri) {
                    if (o == m) continue;
                    long ritardo = 0;
                    if (o.get("optimeDate") instanceof Date d && m.get("optimeDate") instanceof Date p) {
                        ritardo = Math.max(0, (p.getTime() - d.getTime()) / 1000);
                    }
                    repliche.add(Main.m("nome", hostSenzaPorta(o.getString("name")), "indirizzo", hostSenzaPorta(o.getString("name")),
                            "stato", o.getString("stateStr"), "sincrono", "sì", "ritardoSec", ritardo));
                }
            }
        }
        r.put("ruoloNodo", ruolo);
        r.put("replica", canali);
        r.put("repliche", repliche);

        double oplogOre = -1;
        if (Main.controllo(cfg, "oplog")) {
            try {
                Document primoOplog = c.getDatabase("local").getCollection("oplog.rs")
                        .find().sort(new Document("$natural", 1)).limit(1).first();
                Document ultimoOplog = c.getDatabase("local").getCollection("oplog.rs")
                        .find().sort(new Document("$natural", -1)).limit(1).first();
                if (primoOplog != null && ultimoOplog != null) {
                    long primo = ((BsonTimestamp) primoOplog.get("ts")).getTime();
                    long ultimo = ((BsonTimestamp) ultimoOplog.get("ts")).getTime();
                    // ore con due decimali: un oplog piccolo resta leggibile invece di 0
                    oplogOre = Math.round((ultimo - primo) / 36.0) / 100.0;
                }
            } catch (RuntimeException e) {
                errori.put("oplog", String.valueOf(e.getMessage()));
            }
        }

        String stato = !tuttiSani ? "DEGRADED" : primario == null ? "SENZA PRIMARY" : "ONLINE";
        long online = membri.stream().filter(m -> n(m.get("health")) == 1).count();
        Map<String, Object> cluster = Main.m("stato", stato, "membri", membriCluster, "oplogOre", oplogOre,
                "etichetta", "Replica set", "online", online, "totale", membri.size(),
                "primario", primario == null ? null : primarioHost);
        r.put("cluster", cluster);
        r.put("topologia", Main.m("tipo", "Replica set " + setName + ", " + membri.size() + " membri",
                "cluster", setName));
    }

    // ------------------------------------------------------------- utilità

    /** Numero da un valore BSON: MongoDB restituisce spesso Double (es. sizeOnDisk), che Main.num non legge. */
    private static long n(Object o) {
        return o instanceof Number x ? x.longValue() : Main.num(o);
    }

    private static String edizione(Document info) {
        Object moduli = info.get("modules");
        return moduli instanceof List<?> l && l.contains("enterprise") ? "MongoDB Enterprise" : "MongoDB Community";
    }

    private static String utenteOperazione(Document op) {
        Object utenti = op.get("effectiveUsers");
        if (utenti instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Document d) {
            return String.valueOf(d.getString("user"));
        }
        return "";
    }

    private static String troncata(Object comando, int max) {
        String s = comando == null ? "" : comando.toString();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String hostSenzaPorta(String nome) {
        if (nome == null) return "";
        int i = nome.lastIndexOf(':');
        return i < 0 ? nome : nome.substring(0, i);
    }

    /**
     * Query più lente dal profiler: le voci di system.profile dei database utente, raggruppate per namespace,
     * operazione e piano. Il profiler lo attiva l'amministratore del database (db.setProfilingLevel); il monitor
     * non lo modifica. Senza profiler attivo l'elenco è vuoto.
     */
    private static List<Map<String, Object>> queryLente(MongoClient c) {
        final int massimo = Impostazioni.intero("query.lente_limite");
        Map<String, long[]> conteggi = new LinkedHashMap<>();      // esecuzioni, millisecondi totali, massimo, righe esaminate
        Map<String, Document> prime = new LinkedHashMap<>();
        for (String db : c.listDatabaseNames()) {
            if (List.of("admin", "config", "local").contains(db)) continue;
            for (Document p : c.getDatabase(db).getCollection("system.profile")
                    .find().sort(new Document("ts", -1)).limit(2000)) {
                String ns = String.valueOf(p.getOrDefault("ns", ""));
                String op = String.valueOf(p.getOrDefault("op", ""));
                String piano = String.valueOf(p.getOrDefault("planSummary", ""));
                String chiave = ns + "|" + op + "|" + piano;
                long[] s = conteggi.computeIfAbsent(chiave, k -> new long[4]);
                long ms = n(p.get("millis"));
                s[0]++;
                s[1] += ms;
                s[2] = Math.max(s[2], ms);
                s[3] += n(p.get("docsExamined"));
                prime.putIfAbsent(chiave, p);
            }
        }
        List<Map<String, Object>> righe = new ArrayList<>();
        for (Map.Entry<String, long[]> e : conteggi.entrySet()) {
            long[] s = e.getValue();
            Document p = prime.get(e.getKey());
            String ns = String.valueOf(p.getOrDefault("ns", ""));
            String piano = String.valueOf(p.getOrDefault("planSummary", ""));
            String descrizione = ns + " " + p.getOrDefault("op", "") + (piano.isEmpty() ? "" : " [" + piano + "]");
            righe.add(Main.m("SCHEMA_NAME", ns.contains(".") ? ns.substring(0, ns.indexOf('.')) : ns,
                    "QUERY", troncata(descrizione, 300),
                    "ESECUZIONI", s[0],
                    "MEDIA_SEC", Math.round(s[1] * 10.0 / s[0]) / 10000.0,
                    "MAX_SEC", Math.round(s[2] * 10.0) / 10000.0,
                    "RIGHE_ESAMINATE", s[3],
                    "SENZA_INDICE", piano.startsWith("COLLSCAN") ? s[0] : 0L));
        }
        righe.sort((a, b) -> Double.compare(((Number) b.get("MEDIA_SEC")).doubleValue(), ((Number) a.get("MEDIA_SEC")).doubleValue()));
        return new ArrayList<>(righe.subList(0, Math.min(massimo, righe.size())));
    }

    /** Un documento ha un campo vettoriale: un array di almeno 64 numeri (dimensione tipica degli embedding). */
    private static boolean haCampoVettoriale(Document d) {
        for (Object v : d.values()) {
            if (v instanceof List<?> l && l.size() >= 64 && l.stream().allMatch(x -> x instanceof Number)) return true;
        }
        return false;
    }

    /** Ritardo in secondi del membro rispetto al primario (optime); null se il primario o l'optime non sono noti. */
    private static Long ritardoRispettoAl(Document membro, Document primario) {
        if (primario == null) return null;
        if (membro.get("optimeDate") instanceof Date d && primario.get("optimeDate") instanceof Date p) {
            return Math.max(0, (p.getTime() - d.getTime()) / 1000);
        }
        return null;
    }

    private static long portaDi(String nome) {
        if (nome == null || nome.lastIndexOf(':') < 0) return 27017;
        return Long.parseLong(nome.substring(nome.lastIndexOf(':') + 1));
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    /** Esegue una sezione; se fallisce registra l'errore senza interrompere il resto. */
    private static <T> T safe(Map<String, String> errori, String nome, java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            errori.put(nome, String.valueOf(e.getMessage()));
            return null;
        }
    }
}
