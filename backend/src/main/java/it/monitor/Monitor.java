package it.monitor;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Raccolta periodica dei server: riassunto dello stato, generazione degli eventi
 * (confrontando con il rilevamento precedente) e dati per la dashboard.
 */
public class Monitor {

    /**
     * Stati di membro considerati sani: InnoDB/Galera usano ONLINE, MongoDB PRIMARY, SECONDARY e ARBITER.
     * Qualunque altro stato (RECOVERING, DOWN, OFFLINE...) non è sano.
     */
    static final java.util.Set<String> STATI_MEMBRO_SANI = java.util.Set.of("ONLINE", "Synced", "PRIMARY", "SECONDARY", "ARBITER");

    /** Ultimo rilevamento per server: {"rep": report completo, "cur": riassunto, "server": nodo}. */
    static final Map<String, Map<String, Object>> LATEST = new ConcurrentHashMap<>();

    // ------------------------------------------------------------ raccolta

    /** Esegue un rilevamento completo di un server, genera eventi e salva lo snapshot. */
    static Map<String, Object> collect(JsonNode s) {
        return collect(s, true);
    }

    /** Un oggetto di blocco per server: lo stesso server non viene rilevato in parallelo con sé stesso. */
    private static final Map<String, Object> BLOCCHI = new ConcurrentHashMap<>();

    /**
     * Rilevamento di un server; {@code pesante} decide se rilevare anche le sezioni pesanti.
     * Server diversi si rilevano in parallelo (il ciclo li affida a un pool): prima un lock di classe
     * li metteva tutti in fila, e dopo un riavvio la prima raccolta durava minuti.
     */
    static Map<String, Object> collect(JsonNode s, boolean pesante) {
        String id = s.get("id").asText();
        synchronized (BLOCCHI.computeIfAbsent(id, k -> new Object())) {
            return raccogli(s, pesante);
        }
    }

    private static Map<String, Object> raccogli(JsonNode s, boolean pesante) {
        String id = s.get("id").asText();
        String name = Main.text(s, "name");
        Map<String, Object> precedente = LATEST.get(id);
        Map<String, Object> repPrecedente = precedente == null ? null : map(precedente.get("rep"));
        Map<String, Object> rep = "sqlserver".equals(Main.text(s, "kind"))
                ? SqlServerReport.report(s, pesante, repPrecedente)
                : Main.report(s, pesante, repPrecedente);
        Map<String, Object> cur = summarize(rep, s);
        // Sessioni bloccate: nel riassunto per confrontarle con il rilevamento precedente
        cur.put("bloccate", rep.get("bloccate"));
        boolean up = Boolean.TRUE.equals(cur.get("up"));

        Map<String, Object> prev = LATEST.get(id);
        Map<String, Object> prevCur = prev == null ? null : map(prev.get("cur"));
        if (!up && prevCur != null) {
            // Il nodo è giù: conserva l'ultimo cluster noto, così resta nel gruppo in dashboard.
            cur.put("cluster", prevCur.get("cluster"));
            cur.put("clusterInfo", prevCur.get("clusterInfo"));
            cur.put("motore", prevCur.get("motore"));
            cur.put("sorgente", prevCur.get("sorgente"));
            cur.put("gtidEseguito", prevCur.get("gtidEseguito"));
        }
        if (pesante && up && "server".equals(Main.text(s, "kind"))) {
            try {
                ConfigVars.rileva(s, Main.str(cur.get("cluster")));
            } catch (Exception e) {
                System.err.println("Variabili di configurazione non rilevate per " + name + ": " + e.getMessage());
            }
        }
        if (prevCur != null) {
            events(id, name, prevCur, cur);
        } else if (!up) {
            Store.event(id, name, null, "CRITICAL", "CONNECTION_LOST",
                    "Server non raggiungibile: " + firstLine(Main.str(cur.get("errore"))));
        }

        long adesso = System.currentTimeMillis();
        long pesanteAt = pesante || precedente == null ? adesso : longVal(precedente.get("pesanteAt"));
        LATEST.put(id, m("rep", rep, "cur", cur, "id", id, "name", name, "kind", Main.text(s, "kind"),
                "leggeroAt", adesso, "pesanteAt", pesanteAt));
        String payload;
        try {
            payload = Main.JSON.writeValueAsString(rep);
        } catch (Exception e) {
            payload = "{}";
        }
        Store.snapshot(id, up, Main.str(cur.get("cluster")), Main.str(cur.get("clusterStato")), payload);
        return rep;
    }

    /** Intervalli di rilevamento: stato leggero ogni 5 minuti, sezioni pesanti ogni 15 minuti. */
    static final long INTERVALLO_PURGE_MS = 60 * 60_000L;
    static long intervalloLeggeroMs() { return Impostazioni.intero("rilevamento.leggero_minuti") * 60_000L; }
    static long intervalloPesanteMs() { return Impostazioni.intero("rilevamento.pesante_minuti") * 60_000L; }
    private static volatile long ultimoPurge = 0;

    /** Chiamato dallo scheduler ogni minuto: rileva solo i server per cui è scaduto il turno. */
    static void pollAll() {
        pollAll(false);
    }

    /**
     * Rilevamento dei server. {@code forza} = rilevamento completo di tutti i server (pulsante "Rileva ora").
     * Senza forza: verifica di raggiungibilità ad ogni ciclo; un cambio di stato (host o cluster giù/su)
     * viene registrato subito; altrimenti lo stato leggero si rileva ogni 5 minuti e quello pesante ogni 15.
     */
    static void pollAll(boolean forza) {
        long adesso = System.currentTimeMillis();
        try {
            List<JsonNode> server = new ArrayList<>();
            // Server con monitoraggio sospeso: nessuna connessione, nessun rilevamento
            Main.loadServers().forEach((JsonNode s) -> { if (!Main.isSospeso(s)) server.add(s); });
            // Raggiungibilità di tutti i server in parallelo: un server giù non ritarda gli altri
            Map<String, Boolean> raggiungibili = new ConcurrentHashMap<>();
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, Math.max(1, server.size())));
            try {
                List<Future<?>> attese = new ArrayList<>();
                for (JsonNode s : server) {
                    attese.add(pool.submit(() -> raggiungibili.put(s.get("id").asText(),
                            Boolean.TRUE.equals(Main.test(s).get("ok")))));
                }
                for (Future<?> f : attese) f.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            // Quali server rilevare e con quale profondità; poi la raccolta in parallelo
            List<Runnable> lavori = new ArrayList<>();
            for (JsonNode s : server) {
                try {
                    Map<String, Object> prev = LATEST.get(s.get("id").asText());
                    if (forza || prev == null) {
                        lavori.add(rilevamento(s, true));
                        continue;
                    }
                    boolean eraSu = Boolean.TRUE.equals(map(prev.get("cur")).get("up"));
                    boolean ora = Boolean.TRUE.equals(raggiungibili.get(s.get("id").asText()));
                    if (ora != eraSu) {
                        lavori.add(rilevamento(s, false));
                    } else if (adesso - longVal(prev.get("leggeroAt")) >= intervalloLeggeroMs()) {
                        lavori.add(rilevamento(s, adesso - longVal(prev.get("pesanteAt")) >= intervalloPesanteMs()));
                    }
                } catch (Exception e) {
                    System.err.println("Rilevamento fallito per " + s.get("name").asText() + ": " + e.getMessage());
                }
            }
            eseguiInParallelo(lavori);
            if (forza || adesso - ultimoPurge >= INTERVALLO_PURGE_MS) {
                Store.purge();
                ultimoPurge = adesso;
            }
        } catch (Exception e) {
            System.err.println("Ciclo di monitoraggio non riuscito: " + e.getMessage());
        }
    }

    /** Un rilevamento come lavoro: un errore su un server viene registrato e non ferma gli altri. */
    private static Runnable rilevamento(JsonNode s, boolean pesante) {
        return () -> {
            try {
                collect(s, pesante);
            } catch (Exception e) {
                System.err.println("Rilevamento fallito per " + s.get("name").asText() + ": " + e.getMessage());
            }
        };
    }

    /** Esegue i rilevamenti su un pool limitato (come la verifica di raggiungibilità) e aspetta che finiscano. */
    private static void eseguiInParallelo(List<Runnable> lavori) {
        if (lavori.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, lavori.size()));
        try {
            List<Future<?>> attese = new ArrayList<>();
            for (Runnable r : lavori) attese.add(pool.submit(r));
            for (Future<?> f : attese) {
                try {
                    f.get(10, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    System.err.println("Rilevamento oltre il tempo massimo: prosegue il ciclo");
                } catch (Exception e) {
                    System.err.println("Rilevamento interrotto: " + e.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    static long longVal(Object o) {
        return o == null ? 0L : Long.parseLong(o.toString());
    }

    static Map<String, Object> summarize(Map<String, Object> rep, JsonNode s) {
        Map<String, Object> c = new LinkedHashMap<>();
        Map<String, Object> conn = map(rep.get("connessione"));
        boolean up = Boolean.TRUE.equals(conn.get("ok"));
        c.put("up", up);
        c.put("errore", conn.get("errore"));
        c.put("rilevatoAlle", rep.get("generatoAlle"));
        if (!up) return c;

        Map<String, Object> risorse = map(rep.get("risorse"));
        c.put("cpuPercent", risorse.get("cpuPercent"));
        c.put("memUsedBytes", risorse.get("memUsedBytes"));
        c.put("memLimitBytes", risorse.get("memLimitBytes"));
        c.put("memPercent", risorse.get("memPercent"));
        c.put("memoriaMysqldBytes", risorse.get("memoriaMysqldAllocataBytes"));

        Map<String, Object> id = map(rep.get("identita"));
        c.put("edizione", id.get("edizione"));
        c.put("motore", id.get("motore"));
        c.put("versione", id.get("versione"));
        c.put("hostname", id.get("hostname"));
        c.put("gtidEseguito", id.get("gtidEseguito"));

        Map<String, Object> top = map(rep.get("topologia"));
        c.put("tipo", top.get("tipo"));
        // Ruolo del nodo per i server senza cluster (es. PostgreSQL: PRIMARY o REPLICA)
        if (rep.get("ruoloNodo") != null) c.put("membroRuolo", rep.get("ruoloNodo"));
        Map<String, Object> cl = map(rep.get("cluster"));
        String clusterName = Main.str(top.get("cluster"));
        if (!cl.isEmpty()) {
            if (clusterName.isEmpty()) clusterName = "Group Replication";
            c.put("clusterInfo", cl);
            c.put("clusterStato", cl.get("stato"));
            for (Map<String, Object> mb : list(cl.get("membri"))) {
                if (Main.str(mb.get("MEMBER_HOST")).equals(Main.text(s, "host"))) {
                    c.put("codaTransazioni", mb.get("codaTransazioni"));
                    c.put("gtidTransazioni", mb.get("gtidTransazioni"));
                    c.put("errant", mb.get("errant"));
                    c.put("membroStato", mb.get("MEMBER_STATE"));
                    c.put("membroRuolo", mb.get("MEMBER_ROLE"));
                }
            }
        }
        c.put("cluster", clusterName.isEmpty() ? null : clusterName);

        c.put("datiBytes", rep.get("datiBytes"));
        c.put("vettoriale", num(rep.get("colonneVettoriali")) > 0);
        Map<String, Object> gal = map(rep.get("galera"));
        if (!gal.isEmpty()) {
            c.put("galeraDimensione", gal.get("dimensione"));
            c.put("galeraStatoCluster", gal.get("statoCluster"));
            c.put("galeraLocale", gal.get("statoLocale"));
            c.put("membroRuolo", "GALERA");
            c.put("membroStato", "Synced".equals(gal.get("statoLocale")) ? "ONLINE" : gal.get("statoLocale"));
        }
        c.put("replicaCanali", rep.get("replica"));
        String sorgente = null;
        for (Map<String, Object> ch : list(rep.get("replica"))) {
            if (sorgente == null && ch.get("sorgente") != null) sorgente = Main.str(ch.get("sorgente"));
        }
        c.put("sorgente", sorgente);
        Object ritardo = null;
        for (Map<String, Object> ch : list(rep.get("replica"))) {
            if (ritardo == null) ritardo = ch.get("secondiDiRitardo");
        }
        c.put("ritardoSec", ritardo);
        // Stato della replica: true se tutti i canali hanno IO e SQL attivi senza errori, null se non è una replica
        boolean any = false, allOk = true;
        for (Map<String, Object> ch : list(rep.get("replica"))) {
            any = true;
            allOk &= "Yes".equals(ch.get("ioAttivo")) && "Yes".equals(ch.get("sqlAttivo"))
                    && Main.str(ch.get("ultimoErrore")).isEmpty();
        }
        c.put("replicaOk", any ? allOk : null);
        c.put("relayLogBytes", rep.get("relayLogTotaleBytes"));
        c.put("queryAttive", list(rep.get("queryAttive")).size());
        Set<String> users = new HashSet<>();
        for (Map<String, Object> u : list(rep.get("utenti"))) users.add(Main.str(u.get("utente")));
        c.put("utenti", new ArrayList<>(users));
        c.put("tabelleSenzaPk", list(map(rep.get("indici")).get("tabelleSenzaPrimaryKey")).size());
        return c;
    }

    // -------------------------------------------------------------- eventi

    static void events(String id, String name, Map<String, Object> prev, Map<String, Object> cur) {
        String cluster = Main.str(cur.get("cluster"));
        boolean wasUp = Boolean.TRUE.equals(prev.get("up"));
        boolean isUp = Boolean.TRUE.equals(cur.get("up"));
        if (wasUp != isUp) {
            System.out.println(Instant.now() + " cambio raggiungibilita " + name + ": " + (wasUp ? "SU" : "GIU") + " -> " + (isUp ? "SU" : "GIU"));
        }
        if (wasUp && !isUp) {
            Store.event(id, name, cluster, "CRITICAL", "CONNECTION_LOST",
                    "Server non raggiungibile: " + firstLine(Main.str(cur.get("errore"))));
            return;
        }
        if (!wasUp && isUp) {
            Store.event(id, name, cluster, "INFO", "CONNECTION_RESTORED", "Server di nuovo raggiungibile");
            return; // lo stato precedente è sconosciuto: il confronto ricomincia dal prossimo rilevamento
        }
        if (!isUp) return;

        if (changed(prev, cur, "versione")) {
            Store.event(id, name, cluster, "WARNING", "VERSION_CHANGE",
                    "Versione cambiata da " + prev.get("versione") + " a " + cur.get("versione"));
        }
        if (changed(prev, cur, "edizione")) {
            Store.event(id, name, cluster, "WARNING", "EDITION_CHANGE",
                    "Edizione cambiata da " + prev.get("edizione") + " a " + cur.get("edizione"));
        }
        if (changed(prev, cur, "tipo")) {
            Store.event(id, name, cluster, "WARNING", "TOPOLOGY_CHANGE",
                    "Topologia cambiata da \"" + prev.get("tipo") + "\" a \"" + cur.get("tipo") + "\"");
        }
        if (changed(prev, cur, "clusterStato")) {
            String sev = "ONLINE".equals(cur.get("clusterStato")) ? "INFO" : "CRITICAL";
            Store.event(id, name, cluster, sev, "CLUSTER_STATUS",
                    "Stato cluster da " + prev.get("clusterStato") + " a " + cur.get("clusterStato"));
        }
        // Membri del cluster visti da questo nodo
        Map<String, Object> pm = membersByHost(map(prev.get("clusterInfo")));
        Map<String, Object> cm = membersByHost(map(cur.get("clusterInfo")));
        for (String host : cm.keySet()) {
            Map<String, Object> now = map(cm.get(host));
            Map<String, Object> before = map(pm.get(host));
            if (!before.isEmpty() && !Main.str(before.get("MEMBER_STATE")).equals(Main.str(now.get("MEMBER_STATE")))) {
                String sev = STATI_MEMBRO_SANI.contains(Main.str(now.get("MEMBER_STATE"))) ? "INFO" : "WARNING";
                Store.event(id, name, cluster, sev, "MEMBER_STATE",
                        "Membro " + host + ": " + before.get("MEMBER_STATE") + " → " + now.get("MEMBER_STATE"));
            }
        }
        // Thread di replica e relay log
        // Transazioni bloccate: un evento per ogni coppia sessione in attesa / sessione bloccante non ancora segnalata
        java.util.Set<String> giaSegnalate = new java.util.HashSet<>();
        for (Map<String, Object> b : list(prev.get("bloccate"))) giaSegnalate.add(b.get("ID") + ">" + b.get("BLOCCANTE_ID"));
        List<Map<String, Object>> tutteBloccate = list(cur.get("bloccate"));
        for (Map<String, Object> b : tutteBloccate) {
            if (!giaSegnalate.contains(b.get("ID") + ">" + b.get("BLOCCANTE_ID"))) {
                Object testa = b.get("BLOCCANTE_ID") == null ? null : Main.testaCatena(tutteBloccate, b.get("ID"));
                String msg = "Sessione " + b.get("ID") + " (" + Main.str(b.get("UTENTE")) + ", " + Main.str(b.get("DB")) + ") in attesa da "
                        + Main.num(b.get("SECONDI")) + " s della sessione " + b.get("BLOCCANTE_ID")
                        + ". Bloccante: " + firstLine(Main.str(b.get("QUERY_BLOCCANTE")));
                if (testa != null) msg += "\nTesta della catena: " + testa;
                Store.event(id, name, cluster, "WARNING", "BLOCKED_TRANSACTION", msg,
                        testa == null ? null : Long.valueOf(String.valueOf(testa)),
                        contestoBlocco(name, cur, b, testa));
            }
        }
        Map<String, Map<String, Object>> prevCh = channels(prev);
        for (Map<String, Object> ch : list(cur.get("replicaCanali"))) {
            String canale = Main.str(ch.get("canale"));
            Map<String, Object> before = prevCh.get(canale);
            if (before != null) {
                if (!Main.str(before.get("ioAttivo")).equals(Main.str(ch.get("ioAttivo")))
                        || !Main.str(before.get("sqlAttivo")).equals(Main.str(ch.get("sqlAttivo")))) {
                    boolean ok = "Yes".equals(ch.get("ioAttivo")) && "Yes".equals(ch.get("sqlAttivo"));
                    Store.event(id, name, cluster, ok ? "INFO" : "CRITICAL", "REPLICA_THREAD",
                            "Canale " + canale + ": IO=" + ch.get("ioAttivo") + " SQL=" + ch.get("sqlAttivo"));
                }
            }
            String err = Main.str(ch.get("ultimoErrore"));
            if (!err.isEmpty() && !err.equals(Main.str(before == null ? null : before.get("ultimoErrore")))) {
                Store.event(id, name, cluster, "WARNING", "REPLICA_ERROR", "Canale " + canale + ": " + err);
            }
        }
        // Utenti aggiunti o rimossi
        Set<String> pu = new HashSet<>(strings(prev.get("utenti")));
        Set<String> cu = new HashSet<>(strings(cur.get("utenti")));
        if (!pu.isEmpty() && !pu.equals(cu)) {
            List<String> added = new ArrayList<>(cu);
            added.removeAll(pu);
            List<String> removed = new ArrayList<>(pu);
            removed.removeAll(cu);
            Store.event(id, name, cluster, "INFO", "USERS_CHANGED",
                    "Utenti aggiunti: " + added + " — rimossi: " + removed);
        }
        if (changed(prev, cur, "tabelleSenzaPk") && num(cur.get("tabelleSenzaPk")) > num(prev.get("tabelleSenzaPk"))) {
            Store.event(id, name, cluster, "WARNING", "PK_MISSING",
                    "Tabelle senza primary key: " + num(prev.get("tabelleSenzaPk")) + " → " + num(cur.get("tabelleSenzaPk")));
        }
    }

    // ----------------------------------------------------------- dashboard

    /**
     * Dashboard. {@code visibili}: server che l'utente può vedere; null per l'admin (tutti).
     * Il monitoraggio continua su tutti i server: qui si decide solo cosa mostrare.
     */
    static Map<String, Object> dashboard(Set<String> visibili, String ambiente, String sito) throws Exception {
        Map<String, Map<String, Object>> clusters = new LinkedHashMap<>();
        List<Object> singoli = new ArrayList<>();
        List<Object> monitor = new ArrayList<>();
        Set<String> registeredHosts = new HashSet<>();
        for (JsonNode s : Main.loadServers()) {
            registeredHosts.add(Main.text(s, "host"));
            // Il gruppo InnoDB riporta i membri con il nome host del nodo: lo si riconosce anche dal nome in anagrafica
            registeredHosts.add(Main.text(s, "name"));
            Map<String, Object> ultimo = LATEST.get(s.get("id").asText());
            if (ultimo != null) registeredHosts.add(Main.str(map(ultimo.get("cur")).get("hostname")));
        }
        for (JsonNode s : Main.loadServers()) {
            if (visibili != null && !visibili.contains(s.get("id").asText())) continue;
            if (ambiente != null && !ambiente.equals(Main.text(s, "ambiente"))) continue;
            if (sito != null && !sito.equals(Main.text(s, "sito"))) continue;
            Map<String, Object> e = LATEST.get(s.get("id").asText());
            Map<String, Object> cur = e == null ? null : map(e.get("cur"));
            Map<String, Object> node = nodeCard(s, cur);

            if ("monitor".equals(Main.text(s, "kind"))) {
                monitor.add(node);
                continue;
            }
            String cl = cur == null ? null : Main.str(cur.get("cluster"));
            // Cluster Galera dichiarato in anagrafica: il nodo resta nel gruppo anche se è giù o non primary
            String galeraCfg = Main.text(s, "galeraCluster");
            if ((cl == null || cl.isEmpty()) && !galeraCfg.isEmpty()) cl = galeraCfg;
            if (cl != null && !cl.isEmpty()) {
                Map<String, Object> g = clusters.computeIfAbsent(cl, k -> group(k));
                if (!galeraCfg.isEmpty()) g.put("galeraConfigurato", true);
                if (cur != null) fillGroup(g, cur);
                if ("router".equals(Main.text(s, "kind"))) {
                    raw(g.get("endpoint")).add(node);
                } else {
                    raw(g.get("nodi")).add(node);
                }
            } else {
                singoli.add(node);
            }
        }
        // Membri del cluster che non sono in anagrafica
        for (Map<String, Object> g : clusters.values()) {
            for (Map<String, Object> mb : list(g.get("membri"))) {
                String host = Main.str(mb.get("MEMBER_HOST"));
                if (visibili == null && ambiente == null && sito == null && !registeredHosts.contains(host)) {
                    Map<String, Object> ghost = new LinkedHashMap<>();
                    ghost.put("id", null);
                    ghost.put("name", host);
                    ghost.put("host", host);
                    ghost.put("port", mb.get("MEMBER_PORT"));
                    ghost.put("kind", "non-in-anagrafica");
                    ghost.put("up", null);
                    ghost.put("ruolo", mb.get("MEMBER_ROLE"));
                    ghost.put("statoMembro", mb.get("MEMBER_STATE"));
                    ghost.put("versione", mb.get("MEMBER_VERSION"));
                    ghost.put("codaTransazioni", mb.get("codaTransazioni"));
                    raw(g.get("nodi")).add(ghost);
                }
            }
        }
        for (Map<String, Object> g : clusters.values()) {
            int online = 0;
            for (Object o : list(g.get("nodi"))) {
                if (STATI_MEMBRO_SANI.contains(Main.str(map(o).get("statoMembro")))) online++;
            }
            g.put("onlineNodi", online);
            // Galera: tutti i nodi scrivono; il numero di membri è quello dichiarato dal cluster
            boolean galera = Boolean.TRUE.equals(g.get("galeraConfigurato"))
                    || list(g.get("nodi")).stream().anyMatch(x -> x.get("galeraDimensione") != null);
            if (galera) {
                long dichiarati = list(g.get("nodi")).stream().mapToLong(x -> Main.num(x.get("galeraDimensione"))).max().orElse(0);
                int totale = (int) Math.max(dichiarati, list(g.get("nodi")).size());
                g.put("galera", true);
                g.put("totale", totale);
                g.put("online", online);
                g.put("stato", online == totale ? "ONLINE" : online == 0 ? "OFFLINE"
                        : online * 2 <= totale ? "SENZA QUORUM" : "DEGRADED");
            }
            // Ripristino necessario: tutti i nodi giù, oppure il gruppo è OFFLINE (blackout completo del cluster)
            boolean tuttiGiu = !list(g.get("nodi")).isEmpty()
                    && list(g.get("nodi")).stream().allMatch(x -> Boolean.FALSE.equals(x.get("up")));
            // Senza nessun rilevamento (dopo un riavvio del monitor) il cluster è "in attesa", non fermo:
            // non si propone nessun bootstrap finché non è stato letto almeno un nodo
            boolean conDati = list(g.get("nodi")).stream().anyMatch(x -> x.get("up") != null);
            if (!conDati && Boolean.TRUE.equals(g.get("galera"))) g.put("stato", "IN ATTESA");
            g.put("richiedeRipristino", conDati && (tuttiGiu || "OFFLINE".equals(g.get("stato"))));
            if (Boolean.TRUE.equals(g.get("richiedeRipristino"))) {
                g.put("candidatoRipristino", galera ? candidatoGalera(g) : candidatoRipristino(g));
            }
        }
        List<Map<String, Object>> eventi = Store.events(50, visibili);
        return m("generatoAlle", Instant.now().toString(), "cluster", new ArrayList<>(clusters.values()),
                "singoli", singoli, "monitor", monitor, "eventi", eventi, "queryAttive", topQueries(visibili != null ? visibili : registeredIds()));
    }

    static Set<String> registeredIds() throws Exception {
        Set<String> ids = new HashSet<>();
        for (JsonNode s : Main.loadServers()) ids.add(s.get("id").asText());
        return ids;
    }

    /** Query attive di tutti i server registrati, dalla più costosa alla meno costosa. */
    static List<Map<String, Object>> topQueries(Set<String> ids) {
        // Prima i server, poi i router: un router mostra le stesse sessioni del primario.
        List<Map<String, Object>> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (boolean router : new boolean[]{false, true}) {
            for (Map.Entry<String, Map<String, Object>> e : LATEST.entrySet()) {
                if (!ids.contains(e.getKey()) || ("router".equals(e.getValue().get("kind")) != router)) continue;
                for (Map<String, Object> q : list(map(e.getValue().get("rep")).get("queryAttive"))) {
                    String key = Main.str(q.get("ID")) + "|" + Main.str(q.get("UTENTE")) + "|" + Main.str(q.get("QUERY"));
                    if (!seen.add(key)) continue;
                    Map<String, Object> row = new LinkedHashMap<>(q);
                    row.put("serverId", e.getKey());
                    row.put("server", e.getValue().get("name"));
                    all.add(row);
                }
            }
        }
        all.sort((a, b) -> {
            int c = Double.compare(num2(b.get("SECONDI")), num2(a.get("SECONDI")));
            return c != 0 ? c : Long.compare(num(b.get("RIGHE_ESAMINATE")), num(a.get("RIGHE_ESAMINATE")));
        });
        return all.size() > 25 ? new ArrayList<>(all.subList(0, 25)) : all;
    }

    static double num2(Object o) {
        try {
            return o == null ? 0 : Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Nodo da cui eseguire il ripristino: tra i nodi con GTID noto, quello senza transazioni errant
     * (presenti solo su di lui) e con più transazioni eseguite. Usa l'ultimo GTID rilevato di ciascun nodo.
     */
    static Map<String, Object> candidatoRipristino(Map<String, Object> g) {
        List<Map<String, Object>> conosciuti = new ArrayList<>();
        for (Map<String, Object> n : list(g.get("nodi"))) {
            if (n.get("id") == null) continue;
            Map<String, Object> entry = LATEST.get(Main.str(n.get("id")));
            Object gv = entry == null ? null : map(entry.get("cur")).get("gtidEseguito");
            String gtid = gv == null ? null : gv.toString();
            if (gtid == null || gtid.isEmpty() || "null".equals(gtid)) continue;
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("nome", n.get("name"));
            rec.put("host", n.get("host"));
            rec.put("insieme", Main.parseGtid(gtid));
            rec.put("transazioni", Main.gtidCount(gtid));
            conosciuti.add(rec);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (conosciuti.isEmpty()) {
            out.put("nome", null);
            out.put("motivo", "nessun nodo con GTID noto: impossibile scegliere il candidato");
            return out;
        }
        for (Map<String, Object> rec : conosciuti) {
            Map<String, List<long[]>> errant = (Map<String, List<long[]>>) rec.get("insieme");
            for (Map<String, Object> altro : conosciuti) {
                if (altro != rec) errant = Main.sottrai(errant, (Map<String, List<long[]>>) altro.get("insieme"));
            }
            rec.put("errant", Main.contaSet(errant));
        }
        conosciuti.sort((a, b) -> {
            int e = Long.compare(Main.num(a.get("errant")), Main.num(b.get("errant")));
            return e != 0 ? e : Long.compare(Main.num(b.get("transazioni")), Main.num(a.get("transazioni")));
        });
        Map<String, Object> w = conosciuti.get(0);
        out.put("nome", w.get("nome"));
        out.put("host", w.get("host"));
        out.put("transazioni", w.get("transazioni"));
        out.put("errant", w.get("errant"));
        out.put("nodiConosciuti", conosciuti.size());
        String motivo = Main.num(w.get("errant")) == 0
                ? "nessuna transazione errant, " + w.get("transazioni") + " transazioni eseguite (il più completo)"
                : "nessun nodo senza errant: scelto il più completo con " + w.get("transazioni") + " transazioni";
        out.put("motivo", motivo);
        return out;
    }

    /**
     * Stato Galera del nodo letto via JDBC (wsrep_*): UUID del cluster e posizione dell'ultima transazione
     * confermata (seqno). Restituisce null se il nodo non risponde. Il flag safe_to_bootstrap, che Galera
     * scrive solo su disco, non è disponibile via SQL: la scelta del candidato resta quindi sul seqno.
     */
    static Map<String, Object> leggiStatoGalera(String serverId) {
        try {
            JsonNode cfg = Main.find(serverId);
            Map<String, String> v = new LinkedHashMap<>();
            try (Connection c = Main.open(cfg);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN "
                         + "('wsrep_last_committed', 'wsrep_cluster_state_uuid')")) {
                while (rs.next()) v.put(rs.getString(1), rs.getString(2));
            }
            if (!v.containsKey("wsrep_last_committed")) return null;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("uuid", v.get("wsrep_cluster_state_uuid"));
            out.put("seqno", Long.parseLong(v.get("wsrep_last_committed")));
            out.put("safe_to_bootstrap", "0");
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Nodo candidato al bootstrap di un cluster Galera fermo: quello marcato come sicuro, altrimenti quello
     * con il seqno più alto. Se tutti i nodi hanno seqno -1 (arresto non pulito) la scelta è provvisoria.
     */
    static Map<String, Object> candidatoGalera(Map<String, Object> g) {
        List<Map<String, Object>> nodi = new ArrayList<>();
        List<String> nonLetti = new ArrayList<>();
        for (Map<String, Object> n : list(g.get("nodi"))) {
            if (n.get("id") == null) continue;
            String host = Main.str(n.get("host"));
            Map<String, Object> st = leggiStatoGalera(Main.str(n.get("id")));
            if (st == null) {
                nonLetti.add(host);
                continue;
            }
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("nome", n.get("name"));
            rec.put("host", host);
            rec.put("seqno", Main.num(st.get("seqno")));
            rec.put("uuid", st.get("uuid"));
            rec.put("sicuro", "1".equals(st.get("safe_to_bootstrap")));
            nodi.add(rec);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodiConosciuti", nodi.size());
        if (!nonLetti.isEmpty()) out.put("nonLetti", nonLetti);
        if (nodi.isEmpty()) {
            out.put("nome", null);
            out.put("motivo", "nessun nodo del cluster risponde: impossibile leggere la posizione delle transazioni");
            return out;
        }
        Map<String, Object> w = null;
        boolean provvisorio = false;
        String motivo;
        for (Map<String, Object> rec : nodi) {
            if (Boolean.TRUE.equals(rec.get("sicuro"))) {
                w = rec;
                break;
            }
        }
        if (w != null) {
            motivo = "marcato come sicuro per il bootstrap (safe_to_bootstrap: 1)";
        } else {
            long max = nodi.stream().mapToLong(x -> Main.num(x.get("seqno"))).max().orElse(-1);
            List<Map<String, Object>> top = nodi.stream().filter(x -> Main.num(x.get("seqno")) == max).toList();
            w = top.get(0);
            if (max < 0) {
                provvisorio = true;
                motivo = "tutti i nodi hanno seqno -1 (arresto non pulito): nessun dato dimostra quale sia il più aggiornato, "
                        + "scelta provvisoria da verificare prima del bootstrap";
            } else if (top.size() > 1) {
                provvisorio = true;
                motivo = "più nodi allo stesso seqno " + max + ": scelta provvisoria, primo in elenco";
            } else {
                motivo = "seqno più alto (" + max + ")";
            }
        }
        out.put("nome", w.get("nome"));
        out.put("host", w.get("host"));
        out.put("seqno", w.get("seqno"));
        out.put("provvisorio", provvisorio);
        out.put("motivo", motivo);
        return out;
    }

    static Map<String, Object> group(String name) {
        return m("nome", name, "stato", null, "online", null, "totale", null, "primario", null,
                "membri", new ArrayList<>(), "nodi", new ArrayList<>(), "endpoint", new ArrayList<>());
    }

    /** Usa le informazioni di cluster del nodo più recente; un nodo giù fornisce solo l'ultimo dato noto. */
    static void fillGroup(Map<String, Object> g, Map<String, Object> cur) {
        Map<String, Object> info = map(cur.get("clusterInfo"));
        if (info.isEmpty()) return;
        boolean up = Boolean.TRUE.equals(cur.get("up"));
        if (g.get("stato") == null || up) {
            g.put("stato", info.get("stato"));
            g.put("online", info.get("online"));
            g.put("totale", info.get("totale"));
            g.put("primario", info.get("primario"));
            g.put("membri", info.get("membri"));
            g.put("etichetta", info.get("etichetta"));
        }
    }

    /** Lista reale (modificabile) dentro una mappa del gruppo. */
    @SuppressWarnings("unchecked")
    static List<Object> raw(Object o) {
        return (List<Object>) o;
    }

    static Map<String, Object> nodeCard(JsonNode s, Map<String, Object> cur) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", s.get("id").asText());
        n.put("name", Main.text(s, "name"));
        n.put("host", Main.text(s, "host"));
        n.put("sito", Main.text(s, "sito"));
        n.put("port", s.get("port").asInt());
        n.put("kind", Main.text(s, "kind").isEmpty() ? "server" : Main.text(s, "kind"));
        if (cur == null) {
            n.put("up", null);
            n.put("errore", "in attesa del primo rilevamento");
            return n;
        }
        n.put("up", cur.get("up"));
        n.put("errore", cur.get("errore"));
        n.put("rilevatoAlle", cur.get("rilevatoAlle"));
        n.put("edizione", cur.get("edizione"));
        n.put("motore", cur.get("motore"));
        n.put("sorgente", cur.get("sorgente"));
        n.put("galeraDimensione", cur.get("galeraDimensione"));
        n.put("replicaOk", cur.get("replicaOk"));
        n.put("codaTransazioni", cur.get("codaTransazioni"));
        n.put("gtidTransazioni", cur.get("gtidTransazioni"));
        n.put("errant", cur.get("errant"));
        n.put("datiBytes", cur.get("datiBytes"));
        n.put("vettoriale", cur.get("vettoriale"));
        n.put("ritardoSec", cur.get("ritardoSec"));
        n.put("versione", cur.get("versione"));
        n.put("tipo", cur.get("tipo"));
        n.put("ruolo", cur.get("membroRuolo") != null ? cur.get("membroRuolo") : cur.get("tipo"));
        n.put("statoMembro", cur.get("membroStato"));
        n.put("relayLogBytes", cur.get("relayLogBytes"));
        n.put("queryAttive", cur.get("queryAttive"));
        n.put("cpuPercent", cur.get("cpuPercent"));
        n.put("memUsedBytes", cur.get("memUsedBytes"));
        n.put("memPercent", cur.get("memPercent"));
        return n;
    }

    // ------------------------------------------------------------- utility

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object o) {
        if (o instanceof List) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object x : (List<Object>) o) {
                if (x instanceof Map) out.add((Map<String, Object>) x);
            }
            return out;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<Object>) o) out.add(Main.str(x));
        return out;
    }

    static Map<String, Object> membersByHost(Map<String, Object> info) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> mb : list(info.get("membri"))) out.put(Main.str(mb.get("MEMBER_HOST")), mb);
        return out;
    }

    static Map<String, Map<String, Object>> channels(Map<String, Object> cur) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> ch : list(cur.get("replicaCanali"))) out.put(Main.str(ch.get("canale")), ch);
        return out;
    }

    static long num(Object o) {
        return Main.num(o);
    }

    static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    /**
     * Contesto di un blocco per il messaggio IA su Telegram: nome e host del server (hostname riportato dal
     * server, non l'indirizzo di rete), motore, topologia e ruolo del nodo, sessione in attesa e bloccante.
     * Non contiene chiavi, indirizzi IP né l'elenco dei membri con i loro indirizzi.
     */
    static String contestoBlocco(String name, Map<String, Object> cur, Map<String, Object> b, Object testa) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode n = Main.JSON.createObjectNode();
            n.put("server", name);
            n.put("hostname", Main.str(cur.get("hostname")));
            n.put("motore", Main.str(cur.get("motore")));
            n.put("edizione", Main.str(cur.get("edizione")));
            n.put("versione", Main.str(cur.get("versione")));
            com.fasterxml.jackson.databind.node.ObjectNode t = n.putObject("topologia");
            t.put("tipo", Main.str(cur.get("tipo")));
            t.put("cluster", Main.str(cur.get("cluster")));
            t.put("ruoloNodo", Main.str(cur.get("membroRuolo")));
            t.put("statoMembro", Main.str(cur.get("membroStato")));
            t.put("statoCluster", Main.str(cur.get("clusterStato")));
            Map<String, Object> info = map(cur.get("clusterInfo"));
            t.put("membriOnline", Main.str(info.get("online")));
            t.put("membriTotali", Main.str(info.get("totale")));
            com.fasterxml.jackson.databind.node.ObjectNode s = n.putObject("blocco");
            s.put("sessioneInAttesa", Main.str(b.get("ID")));
            s.put("utente", Main.str(b.get("UTENTE")));
            s.put("database", Main.str(b.get("DB")));
            s.put("secondiInAttesa", Main.str(b.get("SECONDI")));
            s.put("sessioneBloccante", Main.str(b.get("BLOCCANTE_ID")));
            s.put("testaCatena", Main.str(testa));
            s.put("queryInAttesa", SpiegaIA.pulisci(Main.str(b.get("QUERY"))));
            s.put("queryBloccante", SpiegaIA.pulisci(Main.str(b.get("QUERY_BLOCCANTE"))));
            return Main.JSON.writeValueAsString(n);
        } catch (Exception e) {
            return null; // senza contesto la notifica resta quella standard
        }
    }

    static boolean changed(Map<String, Object> a, Map<String, Object> b, String key) {
        return !Main.str(a.get(key)).equals(Main.str(b.get(key)));
    }

    static Map<String, Object> m(Object... kv) {
        return Main.m(kv);
    }
}
