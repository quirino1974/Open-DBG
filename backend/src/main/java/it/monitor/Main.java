package it.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Backend del monitor MySQL: anagrafica e storico su MariaDB, test JDBC, report di monitoraggio,
 * raccolta periodica ed eventi. Le risposte sono JSON; il frontend React è servito dalla stessa porta.
 */
public class Main {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path STATIC_DIR = Paths.get(env("STATIC_DIR", "/app/static"));
    /** Ambienti ammessi per i server; stringa vuota = non definito. */
    static final Set<String> AMBIENTI = Set.of("collaudo", "certificazione", "produzione");
    static final String SYSTEM_SCHEMAS = "'mysql','sys','information_schema','performance_schema'";

    public static void main(String[] args) throws Exception {
        Crypto.init(env("APP_SECRET_KEY", null));
        Store.init();
        Store.migrateSecrets();
        Auth.init();
        Impostazioni.init();
        AiConfig.init();
        Telegram.init();
        Siti.init();
        Insight.init();
        int port = Integer.parseInt(env("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api", Main::api);
        server.createContext("/", Main::staticFiles);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // Il ciclo di controllo si riprogramma da solo: l'intervallo è un parametro modificabile
        Runnable ciclo = new Runnable() {
            @Override
            public void run() {
                try {
                    Monitor.pollAll();
                } finally {
                    scheduler.schedule(this, Impostazioni.intero("rilevamento.raggiungibilita_secondi"), TimeUnit.SECONDS);
                }
            }
        };
        scheduler.schedule(ciclo, 5, TimeUnit.SECONDS);
        // Insight su uno scheduler separato: un health check lungo non ritarda il rilevamento
        ScheduledExecutorService insight = Executors.newSingleThreadScheduledExecutor();
        insight.scheduleWithFixedDelay(Insight::controllaPianificazione, 120, 300, TimeUnit.SECONDS);
        System.out.println("Monitor MySQL in ascolto sulla porta " + port + ", verifica di raggiungibilità ogni " + Impostazioni.intero("rilevamento.raggiungibilita_secondi") + " s");
    }

    // ------------------------------------------------------------------ HTTP

    static void api(HttpExchange ex) throws IOException {
        Object out = null;
        int code = 200;
        try {
            String m = ex.getRequestMethod();
            String p = ex.getRequestURI().getPath().substring("/api".length());
            String[] seg = p.split("/");
            // Accesso, verifica sessione e uscita sono pubblici; le altre API richiedono una sessione valida
            boolean pubblica = p.equals("/login") || p.equals("/session") || p.equals("/logout");
            Auth.Principal me = pubblica ? null : Auth.principal(ex);
            if (!pubblica && me == null) {
                send(ex, 401, m("error", "Sessione non valida: effettuare l'accesso"));
                return;
            }
            // Obbligo di cambio password: finché non viene cambiata, sono consentite solo sessione, uscita e cambio
            if (me != null && me.cambioPassword() && !p.equals("/password") && !p.equals("/session") && !p.equals("/logout")) {
                send(ex, 403, m("error", "Devi cambiare la password prima di continuare", "codice", "CAMBIO_PASSWORD"));
                return;
            }
            // Server visibili: null per l'admin (tutti)
            Set<String> visibili = me == null ? null : Auth.serversVisibili(me);

            if (p.equals("/login") && m.equals("POST")) {
                JsonNode b = readBody(ex);
                String user = rawText(b, "username").trim();
                String ip = ex.getRemoteAddress().getAddress().getHostAddress();
                long attesa = Auth.attesaLogin(user, ip);
                if (attesa > 0) {
                    code = 429;
                    out = m("error", "Troppi tentativi di accesso: riprova tra " + Math.max(1, (attesa + 59) / 60) + " minuti");
                } else {
                    String token = Auth.login(user, rawText(b, "password"));
                    if (token == null) {
                        Auth.fallimentoLogin(user, ip);
                        code = 401;
                        out = m("error", "Utente o password non corretti");
                    } else {
                        Auth.successoLogin(user);
                        ex.getResponseHeaders().add("Set-Cookie", Auth.sessionCookie(token, Auth.connessioneSicura(ex)));
                        out = Auth.infoDi(user);
                    }
                }
            } else if (p.equals("/session") && m.equals("GET")) {
                Auth.Principal u = Auth.principal(ex);
                if (u == null) {
                    code = 401;
                    out = m("error", "Sessione non valida: effettuare l'accesso");
                } else {
                    out = Auth.info(u);
                }
            } else if (p.equals("/logout") && m.equals("POST")) {
                Auth.logout(ex);
                ex.getResponseHeaders().add("Set-Cookie", Auth.clearCookie(Auth.connessioneSicura(ex)));
                out = m("ok", true);
            } else if (p.equals("/password") && m.equals("POST")) {
                JsonNode b = readBody(ex);
                Auth.changeOwnPassword(me.username(), rawText(b, "old"), rawText(b, "new"));
                out = m("ok", true);

            // ---- utenti: solo admin
            } else if (p.equals("/users") && m.equals("GET")) {
                richiedeAdmin(me);
                List<Object> lista = new ArrayList<>();
                for (Auth.Utente u : Auth.listUsers()) lista.add(Auth.toMap(u));
                out = lista;
            } else if (p.equals("/users") && m.equals("POST")) {
                richiedeAdmin(me);
                JsonNode b = readBody(ex);
                code = 201;
                out = Auth.toMap(Auth.createUser(rawText(b, "username"), rawText(b, "password"),
                        rawText(b, "profilo"), stringhe(b, "servers")));
            } else if (seg.length == 3 && seg[1].equals("users") && m.equals("PUT")) {
                richiedeAdmin(me);
                JsonNode b = readBody(ex);
                Auth.updateUser(idLungo(seg[2]), rawText(b, "profilo"), stringhe(b, "servers"));
                out = m("ok", true);
            } else if (seg.length == 4 && seg[1].equals("users") && seg[3].equals("password") && m.equals("POST")) {
                richiedeAdmin(me);
                Auth.resetPassword(idLungo(seg[2]), rawText(readBody(ex), "password"));
                out = m("ok", true);
            } else if (seg.length == 3 && seg[1].equals("users") && m.equals("DELETE")) {
                richiedeAdmin(me);
                Auth.deleteUser(idLungo(seg[2]), me);
                out = m("ok", true);

            // ---- server
            } else if (p.equals("/servers/test") && m.equals("POST")) {
                richiedeAdmin(me);
                out = test(conPasswordSalvata(readBody(ex)));
            } else if (p.equals("/servers") && m.equals("GET")) {
                out = listMasked(visibili);
            } else if (p.equals("/servers") && m.equals("POST")) {
                richiedeAdmin(me);
                code = 201;
                out = create(readBody(ex));
            } else if (seg.length == 3 && seg[1].equals("servers") && m.equals("PUT")) {
                richiedeAdmin(me);
                out = update(seg[2], readBody(ex));
            } else if (seg.length == 3 && seg[1].equals("servers") && m.equals("DELETE")) {
                richiedeAdmin(me);
                remove(seg[2]);
                Auth.dimenticaServer(seg[2]);
                out = m("ok", true);
            } else if (seg.length == 4 && seg[1].equals("servers") && seg[3].equals("test") && m.equals("POST")) {
                richiedi(me, Auth.P_DIAGNOSTICA);
                richiedeVisibile(visibili, seg[2]);
                out = test(find(seg[2]));
            } else if (seg.length == 4 && seg[1].equals("servers") && seg[3].equals("explain") && m.equals("POST")) {
                richiedi(me, Auth.P_DIAGNOSTICA);
                richiedeVisibile(visibili, seg[2]);
                out = explain(find(seg[2]), readBody(ex));
            } else if (seg.length == 4 && seg[1].equals("servers") && seg[3].equals("report") && m.equals("GET")) {
                richiedi(me, Auth.P_MONITORAGGIO);
                richiedeVisibile(visibili, seg[2]);
                out = Monitor.collect(find(seg[2]));

            // ---- monitoraggio
            } else if (p.equals("/dashboard") && m.equals("GET")) {
                richiedi(me, Auth.P_MONITORAGGIO);
                out = Monitor.dashboard(visibili, ambienteDi(ex), sitoDi(ex));
            } else if (p.equals("/poll") && m.equals("POST")) {
                richiedi(me, Auth.P_DIAGNOSTICA);
                Monitor.pollAll(true);
                out = m("ok", true);
            } else if (p.equals("/events") && m.equals("GET")) {
                out = Store.events(200, visibili);
            } else if (p.equals("/summary") && m.equals("GET")) {
                int ore = 24;
                String qs = ex.getRequestURI().getQuery();
                if (qs != null && qs.startsWith("ore=")) ore = Math.min(Integer.parseInt(qs.substring(4)), 24 * 30);
                out = m("ore", ore, "disponibilita", Store.disponibilitaPerOra(ore, visibili),
                        "eventi", Store.eventiPerOra(ore, visibili));
            } else if (p.equals("/transazioni-bloccate") && m.equals("GET")) {
                richiedi(me, Auth.P_MONITORAGGIO);
                List<Object> righe = new ArrayList<>();
                for (Map<String, Object> e : Monitor.LATEST.values()) {
                    String id = String.valueOf(e.get("id"));
                    if (visibili != null && !visibili.contains(id)) continue;
                    List<Map<String, Object>> bloc = Monitor.list(Monitor.map(e.get("rep")).get("bloccate"));
                    boolean terminabile = killAbilitato(id, String.valueOf(e.get("kind")));
                    for (Map<String, Object> riga : bloc) {
                        Map<String, Object> x = new LinkedHashMap<>(riga);
                        x.put("SERVER", e.get("name"));
                        x.put("SERVER_ID", id);
                        x.put("TERMINABILE", terminabile);
                        x.put("TESTA_CATENA", testaCatena(bloc, riga.get("ID")));
                        righe.add(x);
                    }
                }
                out = m("righe", righe);
            } else if (p.equals("/transazioni-bloccate/termina") && m.equals("POST")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                JsonNode b = readBody(ex);
                out = terminaSessione(b.path("server").asText(), b.path("sessione").asLong(-1));
            } else if (p.equals("/insights") && m.equals("GET")) {
                richiedi(me, Auth.P_MONITORAGGIO);
                Map<String, Object> ultimo = Insight.ultimo(visibili);
                out = ultimo != null ? ultimo : m("nessunReport", true);
            } else if (p.equals("/insights/genera") && m.equals("POST")) {
                richiedi(me, Auth.P_DIAGNOSTICA);
                Insight.genera();
                out = Insight.ultimo(visibili);
            } else if (p.equals("/ricerca") && m.equals("GET")) {
                richiedi(me, Auth.P_MONITORAGGIO);
                out = Ricerca.cerca(queryParam(ex, "q"), visibili);
            } else if (p.equals("/esportazione") && m.equals("GET")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                ex.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"dbguardian-configurazione-" + java.time.LocalDate.now() + ".json\"");
                out = esporta();
            } else if (p.equals("/importazione") && m.equals("POST")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                out = importa(readBody(ex));
            } else if (p.equals("/siti") && m.equals("GET")) {
                richiedi(me, Auth.P_MONITORAGGIO);
                out = Siti.elenco();
            } else if (p.equals("/siti") && m.equals("POST")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                JsonNode b = readBody(ex);
                Siti.crea(rawText(b, "nome"), rawText(b, "descrizione"));
                code = 201;
                out = Siti.elenco();
            } else if (seg.length == 3 && seg[1].equals("siti") && m.equals("PUT")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                JsonNode b = readBody(ex);
                Siti.aggiorna(seg[2], rawText(b, "nome"), rawText(b, "descrizione"));
                out = Siti.elenco();
            } else if (seg.length == 3 && seg[1].equals("siti") && m.equals("DELETE")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                Siti.elimina(seg[2]);
                out = Siti.elenco();
            } else if (p.equals("/frequenze") && m.equals("GET")) {
                // Sola lettura per la pagina Help: nessuna modifica, solo i valori correnti
                richiedi(me, Auth.P_MONITORAGGIO);
                out = Impostazioni.elenco();
            } else if (p.equals("/controlli-catalogo") && m.equals("GET")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                out = Controlli.catalogo();
            } else if (p.equals("/impostazioni") && m.equals("GET")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                out = Impostazioni.elenco();
            } else if (p.equals("/impostazioni") && m.equals("PUT")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                JsonNode b = readBody(ex);
                Map<String, String> nuovi = new LinkedHashMap<>();
                b.fields().forEachRemaining(e -> nuovi.put(e.getKey(), e.getValue().asText()));
                Impostazioni.salva(nuovi);
                out = Impostazioni.elenco();
            } else if (p.equals("/ai-config") && m.equals("GET")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                out = AiConfig.stato();
            } else if (p.equals("/ai-config") && m.equals("PUT")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                AiConfig.salva(readBody(ex));
                out = AiConfig.stato();
            } else if (p.equals("/ai-config/prova") && m.equals("POST")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                out = m("ok", true, "risposta", AiConfig.prova());
            } else if (p.equals("/ai/stato") && m.equals("GET")) {
                richiedi(me, Auth.P_MONITORAGGIO);
                out = m("abilitata", SpiegaIA.abilitata());
            } else if (p.equals("/ai/evento") && m.equals("POST")) {
                richiedi(me, Auth.P_MONITORAGGIO);
                out = m("risposta", SpiegaIA.spiegaEvento(readBody(ex)));
            } else if (p.equals("/ai/query/spiega") && m.equals("POST")) {
                richiedi(me, Auth.P_DIAGNOSTICA);
                out = m("risposta", SpiegaIA.spiegaQuery(readBody(ex)));
            } else if (p.equals("/ai/query/suggerisci") && m.equals("POST")) {
                richiedi(me, Auth.P_DIAGNOSTICA);
                out = m("risposta", SpiegaIA.suggerisciQuery(readBody(ex)));
            } else if (p.equals("/ai/incidenti") && m.equals("GET")) {
                richiedi(me, Auth.P_MONITORAGGIO);
                out = m("risposta", SpiegaIA.incidenti(visibili));
            } else if (p.equals("/ai/domanda") && m.equals("POST")) {
                richiedi(me, Auth.P_MONITORAGGIO);
                out = m("risposta", SpiegaIA.domanda(readBody(ex), visibili));
            } else if (p.equals("/ai/anomalie") && m.equals("GET")) {
                richiedi(me, Auth.P_MONITORAGGIO);
                out = SpiegaIA.anomalie(visibili);
            } else if (p.equals("/telegram-config") && m.equals("GET")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                out = Telegram.stato();
            } else if (p.equals("/telegram-config") && m.equals("PUT")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                Telegram.salva(readBody(ex));
                out = Telegram.stato();
            } else if (p.equals("/telegram-config/prova") && m.equals("POST")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                out = m("ok", true, "risposta", Telegram.prova());
            } else if (p.equals("/impostazioni/ripristina") && m.equals("POST")) {
                richiedi(me, Auth.P_CONFIGURAZIONE);
                Impostazioni.ripristina(rawText(readBody(ex), "chiave"));
                out = Impostazioni.elenco();
            } else if (p.startsWith("/statistiche")) {
                // controllo degli indici e topologia SQL Server (server → database → indici)
                out = Statistiche.api(ex, m, p, me);
            } else if (p.equals("/config-log") && m.equals("GET")) {
                richiedeAdmin(me);
                out = Store.configLog(200);
            } else if (p.equals("/config-vars") && m.equals("GET")) {
                // valori delle variabili di configurazione: riservato all'amministratore
                richiedeAdmin(me);
                out = Store.configVarChanges(300);
            }
            if (out == null) {
                code = 404;
                out = m("error", "Percorso non trovato: " + m + " " + p);
            }
        } catch (Forbidden e) {
            code = 403;
            out = m("error", e.getMessage());
        } catch (IllegalArgumentException e) {
            code = 400;
            out = m("error", e.getMessage());
        } catch (Exception e) {
            code = 500;
            out = m("error", String.valueOf(e.getMessage()));
        }
        send(ex, code, out);
    }

    /** Valore di un parametro della query string, oppure stringa vuota. */
    static String queryParam(HttpExchange ex, String nome) {
        String qs = ex.getRequestURI().getRawQuery();
        if (qs == null) return "";
        for (String parte : qs.split("&")) {
            String[] kv = parte.split("=", 2);
            if (kv[0].equals(nome) && kv.length == 2) {
                return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /**
     * Filtro ambiente della dashboard: null = tutti, "nessuno" = non definito (ambiente vuoto),
     * altrimenti il nome dell'ambiente.
     */
    static String ambienteDi(HttpExchange ex) {
        String a = queryParam(ex, "ambiente");
        if (a.isEmpty() || a.equals("tutti")) return null;
        if (a.equals("nessuno")) return "";
        if (!AMBIENTI.contains(a)) throw new IllegalArgumentException("Ambiente non valido: " + a);
        return a;
    }

    /** Filtro sito della dashboard: null = tutti i siti, altrimenti il nome del sito. */
    static String sitoDi(HttpExchange ex) {
        String s = queryParam(ex, "sito");
        return s.isEmpty() || s.equals("tutti") ? null : s;
    }

    static void staticFiles(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = STATIC_DIR.resolve(path.substring(1)).normalize();
        if (!file.startsWith(STATIC_DIR) || !Files.isRegularFile(file)) {
            file = STATIC_DIR.resolve("index.html");
        }
        if (!Files.exists(file)) {
            send(ex, 404, m("error", "Frontend non trovato"));
            return;
        }
        byte[] body = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.toString()));
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(body);
        }
    }

    static String contentType(String name) {
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    static JsonNode readBody(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readAllBytes();
        if (b.length == 0) throw new IllegalArgumentException("Corpo della richiesta vuoto");
        return JSON.readTree(b);
    }

    static void send(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    // ------------------------------------------------------------ anagrafica

    /**
     * Importazione di un file esportato da {@link #esporta()}. I server con lo stesso nome vengono aggiornati
     * (la password attuale resta), gli altri creati senza password: il file non la contiene.
     * Gli utenti non si importano: il file non contiene le loro password, quindi si creano dalla sezione Utenti.
     * Ogni server è indipendente: un errore su uno non blocca gli altri.
     */
    static Map<String, Object> importa(JsonNode b) throws SQLException {
        if (b == null || !b.isObject() || !b.path("server").isArray()) {
            throw new IllegalArgumentException("File non valido: manca l'elenco dei server. Usare un file esportato da dbguardian.");
        }
        int creati = 0;
        int aggiornati = 0;
        List<String> errori = new ArrayList<>();
        List<String> senzaPassword = new ArrayList<>();
        ArrayNode esistenti = loadServers();
        for (JsonNode s : b.get("server")) {
            String nome = text(s, "nome");
            try {
                ObjectNode in = JSON.createObjectNode();
                in.put("name", nome);
                in.put("kind", text(s, "tipo"));
                in.put("host", text(s, "host"));
                if (s.has("porta")) in.put("port", s.get("porta").asInt());
                in.put("user", text(s, "utente"));
                in.put("database", text(s, "database"));
                in.put("ambiente", text(s, "ambiente"));
                in.put("galeraCluster", text(s, "galeraCluster"));
                in.put("resourceMode", text(s, "sorgenteRisorse"));
                in.put("metricsUrl", text(s, "metricsUrl"));
                in.put("controlli", text(s, "controlli"));
                if (s.has("insight")) in.put("insight", s.get("insight").asBoolean(false));
                if (s.has("telegram")) in.put("telegram", s.get("telegram").asBoolean(false));
                String idEsistente = null;
                for (JsonNode e : esistenti) {
                    if (nome.equals(text(e, "name"))) idEsistente = e.get("id").asText();
                }
                if (idEsistente != null) {
                    update(idEsistente, in);
                    aggiornati++;
                } else {
                    create(in);
                    creati++;
                    senzaPassword.add(nome);
                }
            } catch (Exception e) {
                errori.add(nome + ": " + e.getMessage());
            }
        }
        Map<String, String> parametri = new LinkedHashMap<>();
        for (JsonNode p : b.path("parametri")) {
            parametri.put(text(p, "chiave"), text(p, "valore"));
        }
        int parametriAggiornati = 0;
        if (!parametri.isEmpty()) {
            try {
                Impostazioni.salva(parametri);
                parametriAggiornati = parametri.size();
            } catch (IllegalArgumentException e) {
                errori.add("parametri: " + e.getMessage());
            }
        }
        Store.logConfig("IMPORT", "configurazione", "server creati " + creati + ", aggiornati " + aggiornati
                + ", parametri " + parametriAggiornati + ", errori " + errori.size());
        return m("serverCreati", creati, "serverAggiornati", aggiornati, "serverSenzaPassword", senzaPassword,
                "parametriAggiornati", parametriAggiornati, "utentiNonImportati", b.path("utenti").size(),
                "errori", errori);
    }

    /**
     * Configurazione completa: server, utenti (con i server assegnati) e parametri di rilevamento.
     * Le password non vengono esportate né in chiaro né come hash.
     */
    static Map<String, Object> esporta() throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("esportatoIl", java.time.LocalDateTime.now().toString());
        out.put("nota", "Password escluse: vanno reinserite dopo l'importazione");
        Map<String, String> nomePerId = new LinkedHashMap<>();
        List<Object> server = new ArrayList<>();
        for (JsonNode s : loadServers()) {
            nomePerId.put(s.get("id").asText(), Main.text(s, "name"));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", s.get("id").asText());
            c.put("nome", text(s, "name"));
            c.put("tipo", text(s, "kind"));
            c.put("host", text(s, "host"));
            c.put("porta", s.get("port").asInt());
            c.put("utente", text(s, "user"));
            c.put("database", text(s, "database"));
            c.put("ambiente", text(s, "ambiente"));
            c.put("galeraCluster", text(s, "galeraCluster"));
            c.put("sorgenteRisorse", text(s, "resourceMode"));
            c.put("metricsUrl", text(s, "metricsUrl"));
            c.put("controlli", text(s, "controlli"));
            c.put("insight", s.path("insight").asBoolean(true));
            c.put("telegram", s.path("telegram").asBoolean(false));
            c.put("sospeso", s.path("sospeso").asBoolean(false));
            server.add(c);
        }
        out.put("server", server);
        List<Object> utenti = new ArrayList<>();
        for (Auth.Utente u : Auth.listUsers()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("username", u.username());
            c.put("profilo", u.profilo());
            List<Object> assegnati = new ArrayList<>();
            for (String id : u.servers()) assegnati.add(nomePerId.getOrDefault(id, id));
            c.put("serverAssegnati", assegnati);
            utenti.add(c);
        }
        out.put("utenti", utenti);
        List<Object> parametri = new ArrayList<>();
        for (Map<String, Object> p : Impostazioni.elenco()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("chiave", p.get("chiave"));
            c.put("valore", p.get("valore"));
            c.put("unita", p.get("unita"));
            parametri.add(c);
        }
        out.put("parametri", parametri);
        return out;
    }

    /** Errore di autorizzazione: risposta 403. */
    static class Forbidden extends RuntimeException {
        Forbidden(String messaggio) {
            super(messaggio);
        }
    }

    /** Verifica il permesso del profilo dell'utente; altrimenti risposta 403. */
    static void richiedi(Auth.Principal me, String permesso) {
        if (me != null && me.ha(permesso)) return;
        if (Auth.P_CONFIGURAZIONE.equals(permesso)) throw new Forbidden("Operazione riservata all'amministratore");
        throw new Forbidden("Operazione non consentita dal profilo dell'utente (permesso: " + permesso + ")");
    }

    static void richiedeAdmin(Auth.Principal me) {
        richiedi(me, Auth.P_CONFIGURAZIONE);
    }

    static void richiedeVisibile(Set<String> visibili, String id) {
        if (visibili != null && !visibili.contains(id)) throw new Forbidden("Server non assegnato a questo utente");
    }

    static long idLungo(String testo) {
        try {
            return Long.parseLong(testo);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Identificativo non valido: " + testo);
        }
    }

    /** Lista di stringhe da un campo JSON (es. server assegnati), vuota se assente. */
    static List<String> stringhe(JsonNode b, String campo) {
        List<String> out = new ArrayList<>();
        if (b.has(campo) && b.get(campo).isArray()) {
            for (JsonNode n : b.get(campo)) out.add(n.asText());
        }
        return out;
    }

    static ArrayNode loadServers() throws SQLException {
        return Store.loadAll();
    }

    static void saveServers(ArrayNode list) throws SQLException {
        Store.saveAll(list);
    }

    /** Anagrafica senza password; {@code visibili} limita ai server assegnati (null = tutti). */
    static Object listMasked(Set<String> visibili) throws SQLException {
        List<Object> out = new ArrayList<>();
        for (JsonNode s : loadServers()) {
            if (visibili != null && !visibili.contains(s.get("id").asText())) continue;
            ObjectNode c = ((ObjectNode) s).deepCopy();
            c.put("password", "********");
            if (!c.path("killPassword").asText("").isEmpty()) c.put("killPassword", "********");
            c.put("jdbcUrl", jdbcUrl(s));
            out.add(c);
        }
        return out;
    }

    static JsonNode find(String id) throws SQLException {
        for (JsonNode s : loadServers()) {
            if (id.equals(s.get("id").asText())) return s;
        }
        throw new IllegalArgumentException("Server non trovato: " + id);
    }

    static ObjectNode normalize(JsonNode in, JsonNode existing) throws SQLException {
        String name = text(in, "name");
        String host = text(in, "host");
        String user = text(in, "user");
        if (name.isEmpty() || host.isEmpty() || user.isEmpty()) {
            throw new IllegalArgumentException("Nome, host e utente sono obbligatori");
        }
        int portaPredefinita = text(in, "kind").equals("mongodb") ? 27017 : 3306;
        int port = in.has("port") && !in.get("port").asText().isBlank() ? in.get("port").asInt() : portaPredefinita;
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("Porta non valida");
        String password = text(in, "password");
        if (password.isEmpty() && existing != null) password = existing.get("password").asText();
        ObjectNode o = JSON.createObjectNode();
        o.put("id", existing != null ? existing.get("id").asText() : UUID.randomUUID().toString());
        o.put("name", name);
        o.put("host", host);
        o.put("port", port);
        o.put("user", user);
        o.put("password", password);
        // Utenza per terminare le sessioni: la password vuota mantiene quella salvata
        String killPassword = text(in, "killPassword");
        if (killPassword.isEmpty() && existing != null && existing.has("killPassword")) killPassword = existing.get("killPassword").asText();
        o.put("killUser", text(in, "killUser"));
        o.put("killPassword", killPassword);
        o.put("database", text(in, "database"));
        String kind = text(in, "kind");
        o.put("kind", kind.equals("router") ? "router" : kind.equals("monitor") ? "monitor"
                : kind.equals("postgres") ? "postgres" : kind.equals("sqlserver") ? "sqlserver"
                : kind.equals("mongodb") ? "mongodb" : "server");
        String mode = text(in, "resourceMode");
        o.put("resourceMode", mode.equals("host") || mode.equals("none") ? mode : "container");
        o.put("metricsUrl", text(in, "metricsUrl"));
        // Lista vuota = nessun controllo selezionato; campo assente = tutti i controlli tipici
        o.put("controlli", in.has("controlli") && in.get("controlli").isArray()
                ? (in.get("controlli").isEmpty() ? "nessuno"
                    : String.join(",", java.util.stream.StreamSupport.stream(in.get("controlli").spliterator(), false).map(JsonNode::asText).toList()))
                : text(in, "controlli"));
        o.put("galeraCluster", text(in, "galeraCluster"));
        // Insight: se il campo manca si mantiene il valore attuale (modifica) o si esclude (nuovo server)
        boolean insight = in.has("insight") && !in.get("insight").isNull() ? in.get("insight").asBoolean()
                : existing != null && existing.has("insight") ? existing.get("insight").asBoolean() : false;
        o.put("insight", insight);
        // Telegram per server: se il campo manca si mantiene il valore attuale; un server nuovo nasce con le notifiche disattivate
        boolean telegram = in.has("telegram") && !in.get("telegram").isNull() ? in.get("telegram").asBoolean()
                : existing != null && existing.has("telegram") ? existing.get("telegram").asBoolean() : false;
        o.put("telegram", telegram);
        // Sospensione: se il campo manca si mantiene il valore attuale; un server nuovo nasce attivo
        boolean sospeso = in.has("sospeso") && !in.get("sospeso").isNull() ? in.get("sospeso").asBoolean()
                : existing != null && existing.has("sospeso") && existing.get("sospeso").asBoolean();
        o.put("sospeso", sospeso);
        String ambiente = text(in, "ambiente");
        if (!ambiente.isEmpty() && !AMBIENTI.contains(ambiente)) {
            throw new IllegalArgumentException("Ambiente non valido: usare collaudo, certificazione o produzione");
        }
        o.put("ambiente", ambiente);
        String sito = text(in, "sito");
        if (!sito.isEmpty() && !Siti.esiste(sito)) {
            throw new IllegalArgumentException("Il sito \"" + sito + "\" non è nell'anagrafica dei siti");
        }
        o.put("sito", sito);
        return o;
    }

    static Object create(JsonNode in) throws SQLException {
        ObjectNode s = normalize(in, null);
        ArrayNode list = loadServers();
        list.add(s);
        saveServers(list);
        Store.logConfig("CREATE", s.get("name").asText(), describe(s));
        return maskedCopy(s);
    }

    static Object update(String id, JsonNode in) throws SQLException {
        ArrayNode list = loadServers();
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).get("id").asText())) {
                ObjectNode s = normalize(in, list.get(i));
                list.set(i, s);
                saveServers(list);
                Store.logConfig("UPDATE", s.get("name").asText(), describe(s));
                return maskedCopy(s);
            }
        }
        throw new IllegalArgumentException("Server non trovato: " + id);
    }

    static void remove(String id) throws SQLException {
        ArrayNode list = loadServers();
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).get("id").asText())) {
                String name = list.get(i).get("name").asText();
                list.remove(i);
                saveServers(list);
                Store.logConfig("DELETE", name, "");
                return;
            }
        }
        throw new IllegalArgumentException("Server non trovato: " + id);
    }

    /** Descrizione della configurazione per lo storico, senza password. */
    static String describe(JsonNode s) {
        return s.get("kind").asText() + " " + s.get("host").asText() + ":" + s.get("port").asInt()
                + " utente=" + s.get("user").asText() + " db=" + s.get("database").asText();
    }

    static ObjectNode maskedCopy(JsonNode s) {
        ObjectNode c = ((ObjectNode) s).deepCopy();
        c.put("password", "********");
        if (!c.path("killPassword").asText("").isEmpty()) c.put("killPassword", "********");
        c.put("jdbcUrl", jdbcUrl(s));
        return c;
    }

    static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText().trim() : "";
    }

    /** Valore di un campo senza rimuovere gli spazi: per le password. */
    static String rawText(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : "";
    }

    static String jdbcUrl(JsonNode s) {
        String db = text(s, "database");
        if (isMongo(s)) {
            // Solo informativo: MongoDB non usa JDBC. Nessuna credenziale nell'URL.
            return "mongodb://" + s.get("host").asText() + ":" + s.get("port").asInt() + "/";
        }
        if ("sqlserver".equals(text(s, "kind"))) {
            return "jdbc:sqlserver://" + s.get("host").asText() + ":" + s.get("port").asInt()
                    + ";encrypt=true;trustServerCertificate=true;loginTimeout=5;"
                    + (db.isEmpty() ? "" : "databaseName=" + db + ";");
        }
        if (isPostgres(s)) {
            return "jdbc:postgresql://" + s.get("host").asText() + ":" + s.get("port").asInt() + "/"
                    + (db.isEmpty() ? "postgres" : db) + "?connectTimeout=5&socketTimeout=30";
        }
        return "jdbc:mysql://" + s.get("host").asText() + ":" + s.get("port").asInt() + "/" + db
                + "?sslMode=PREFERRED&trustServerCertificate=true&allowPublicKeyRetrieval=true"
                + "&connectTimeout=5000&socketTimeout=30000";
    }

    // ----------------------------------------------------------- connessione

    static Connection open(JsonNode cfg) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(cfg), text(cfg, "user"), text(cfg, "password"));
    }

    /**
     * Controllo selezionato per il server. Se nessun controllo è selezionato esplicitamente (campo vuoto)
     * valgono tutti i controlli tipici della tecnologia.
     */
    /** Monitoraggio sospeso per il server in anagrafica. */
    static boolean isSospeso(JsonNode s) {
        return s != null && s.path("sospeso").asBoolean(false);
    }

    static boolean controllo(JsonNode s, String chiave) {
        // Monitoraggio sospeso: nessun controllo del server, né nel rilevamento né nell'Insight
        if (isSospeso(s)) return false;
        String sel = text(s, "controlli");
        if (sel.isBlank()) return true;
        if (sel.equals("nessuno")) return false;
        for (String k : sel.split(",")) if (k.trim().equals(chiave)) return true;
        return false;
    }

    /**
     * Vero se il testo contiene più di un'istruzione. Un punto e virgola dentro un testo tra apici, un
     * identificatore tra virgolette o parentesi quadre, o un commento non separa istruzioni e non conta.
     */
    static boolean piuIstruzioni(String sql) {
        String t = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ")
                .replaceAll("'(?:[^']|'')*'", "''")
                .replaceAll("\"(?:[^\"]|\"\")*\"", "\"\"")
                .replaceAll("`(?:[^`]|``)*`", "``")
                .replaceAll("\\[(?:[^\\]]|\\]\\])*\\]", "[]");
        return t.replaceAll(";+\\s*$", "").contains(";");
    }

    static boolean isPostgres(JsonNode s) {
        return "postgres".equals(text(s, "kind"));
    }

    static boolean isMongo(JsonNode s) {
        return "mongodb".equals(text(s, "kind"));
    }

    /**
     * Test di un server già in anagrafica: se la password nel form è vuota, come in modifica con "invariata se vuota",
     * usa quella salvata. Senza id (nuovo server) il valore arriva così com'è.
     */
    static JsonNode conPasswordSalvata(JsonNode in) throws SQLException {
        String id = text(in, "id");
        if (id.isEmpty() || !text(in, "password").isEmpty()) return in;
        JsonNode salvato = find(id);
        ObjectNode copia = ((ObjectNode) in).deepCopy();
        copia.put("password", text(salvato, "password"));
        return copia;
    }

    static Map<String, Object> test(JsonNode cfg) {
        long t0 = System.currentTimeMillis();
        if (isMongo(cfg)) return MongoReport.test(cfg);
        if (isPostgres(cfg)) {
            try (Connection c = open(cfg)) {
                Map<String, Object> r = q(c, "SELECT version() AS v").get(0);
                return m("ok", true, "message", "Connessione riuscita", "version", r.get("v"),
                        "edizione", "PostgreSQL", "ms", System.currentTimeMillis() - t0);
            } catch (SQLException e) {
                return m("ok", false, "message", e.getMessage(), "ms", System.currentTimeMillis() - t0);
            }
        }
        try (Connection c = open(cfg)) {
            Map<String, Object> r = q(c, "SELECT @@version AS v, @@version_comment AS vc").get(0);
            return m("ok", true, "message", "Connessione riuscita", "version", r.get("v"),
                    "edizione", edition(str(r.get("vc"))), "ms", System.currentTimeMillis() - t0);
        } catch (SQLException e) {
            return m("ok", false, "message", e.getMessage(), "ms", System.currentTimeMillis() - t0);
        }
    }

    // ------------------------------------------------------------- utilities

    static List<Map<String, Object>> q(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rows(rs);
        }
    }

    static List<Map<String, Object>> rows(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<Map<String, Object>> out = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (int i = 1; i <= n; i++) {
                Object v = rs.getObject(i);
                r.put(md.getColumnLabel(i), v == null ? null : v instanceof byte[] ? new String((byte[]) v, StandardCharsets.UTF_8) : v);
            }
            out.add(r);
        }
        return out;
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    static long num(Object o) {
        if (o == null) return 0;
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Motore del server: MariaDB o MySQL, dalla versione e dal commento di versione. */
    static String engine(String version, String versionComment) {
        return (version + " " + versionComment).toLowerCase().contains("mariadb") ? "MariaDB" : "MySQL";
    }

    /** Valore di una variabile di sistema, oppure null se la variabile non esiste in questo motore. */
    static Object varOrNull(Connection c, String var) {
        try {
            return q(c, "SELECT " + var + " AS v").get(0).get("v");
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Il kill è consentito solo se Telegram è attivo nella configurazione generale e per il server in anagrafica,
     * e per i tipi che hanno un comando di terminazione (MySQL, MariaDB, SQL Server, PostgreSQL).
     */
    static boolean killAbilitato(String serverId, String kind) throws SQLException {
        return !"mongodb".equals(kind) && Telegram.abilitato() && Telegram.serverInviaTelegram(serverId);
    }

    /** Testa della catena di blocchi: si segue il bloccante finché è a sua volta in attesa nell'elenco. */
    static Object testaCatena(List<Map<String, Object>> bloc, Object partenza) {
        Object corrente = partenza;
        java.util.Set<String> viste = new java.util.HashSet<>();
        while (corrente != null && viste.add(String.valueOf(corrente))) {
            Object successivo = null;
            for (Map<String, Object> r : bloc) {
                if (String.valueOf(r.get("ID")).equals(String.valueOf(corrente))) successivo = r.get("BLOCCANTE_ID");
            }
            if (successivo == null) break;
            corrente = successivo;
        }
        return corrente;
    }

    /** Termina la sessione testa di una catena di blocchi. Solo se la sessione blocca altre e ne è la testa. */
    static Map<String, Object> terminaSessione(String serverId, long sessione) throws SQLException {
        if (sessione <= 0) throw new IllegalArgumentException("Sessione non valida");
        Map<String, Object> e = Monitor.LATEST.get(serverId);
        if (e == null) throw new IllegalArgumentException("Server non rilevato");
        String kind = String.valueOf(e.get("kind"));
        if (!killAbilitato(serverId, kind)) {
            throw new IllegalStateException("Termina sessione disponibile solo con Telegram attivo nella configurazione generale e per questo server");
        }
        List<Map<String, Object>> bloc = Monitor.list(Monitor.map(e.get("rep")).get("bloccate"));
        boolean blocca = bloc.stream().anyMatch(r -> String.valueOf(r.get("BLOCCANTE_ID")).equals(String.valueOf(sessione)));
        if (!blocca) throw new IllegalArgumentException("La sessione " + sessione + " non blocca altre sessioni");
        Object testa = testaCatena(bloc, sessione);
        if (!String.valueOf(testa).equals(String.valueOf(sessione))) {
            throw new IllegalArgumentException("Termina la testa della catena: sessione " + testa);
        }
        JsonNode cfg = null;
        for (JsonNode s : loadServers()) {
            if (text(s, "id").equals(serverId)) cfg = s;
        }
        if (cfg == null) throw new IllegalArgumentException("Server non trovato in anagrafica");
        String killUser = text(cfg, "killUser");
        if (killUser.isEmpty()) throw new IllegalStateException("Utenza per terminare le sessioni non configurata per questo server");
        ObjectNode killCfg = ((ObjectNode) cfg).deepCopy();
        killCfg.put("user", killUser);
        killCfg.put("password", text(cfg, "killPassword"));
        // Il kill non richiede un database utente: SQL Server si collega a master, dove l'utenza di kill ha accesso
        if ("sqlserver".equals(kind)) killCfg.put("database", "master");
        try (Connection c = open(killCfg)) {
            if ("postgres".equals(kind)) {
                // pg_terminate_backend accetta un integer (pid): il cast evita l'errore "function ... (bigint) does not exist"
                try (PreparedStatement ps = c.prepareStatement("SELECT pg_terminate_backend(CAST(? AS integer))")) {
                    ps.setLong(1, sessione);
                    ps.executeQuery();
                }
            } else {
                try (Statement st = c.createStatement()) {
                    st.execute("KILL " + sessione);
                }
            }
        }
        String nome = String.valueOf(e.get("name"));
        Store.logConfig("KILL", nome, "sessione " + sessione + " terminata dal monitor");
        Store.event(serverId, nome, null, "INFO", "SESSIONE_TERMINATA", "Sessione " + sessione + " terminata dal monitor");
        return m("ok", true, "messaggio", "Sessione " + sessione + " terminata su " + nome);
    }

    /** Sessioni in attesa di un lock InnoDB. MySQL: sys.innodb_lock_waits. MariaDB non ha lo schema sys: information_schema. */
    static List<Map<String, Object>> bloccateMysql(Connection c) throws SQLException {
        try {
            return q(c, "SELECT waiting_pid AS ID, waiting_query AS QUERY, wait_age_secs AS SECONDI, blocking_pid AS BLOCCANTE_ID, "
                    + "blocking_query AS QUERY_BLOCCANTE FROM sys.innodb_lock_waits ORDER BY wait_age_secs DESC LIMIT 100");
        } catch (SQLException e) {
            return q(c, "SELECT rt.trx_mysql_thread_id AS ID, rt.trx_query AS QUERY, "
                    + "TIMESTAMPDIFF(SECOND, rt.trx_wait_started, NOW()) AS SECONDI, bt.trx_mysql_thread_id AS BLOCCANTE_ID, "
                    + "bt.trx_query AS QUERY_BLOCCANTE FROM information_schema.INNODB_LOCK_WAITS w "
                    + "JOIN information_schema.INNODB_TRX rt ON rt.trx_id = w.requesting_trx_id "
                    + "JOIN information_schema.INNODB_TRX bt ON bt.trx_id = w.blocking_trx_id LIMIT 100");
        }
    }

    static String edition(String versionComment) {
        String v = versionComment.toLowerCase();
        if (v.contains("enterprise")) return "MySQL Enterprise";
        if (v.contains("community")) return "MySQL Community";
        if (v.contains("percona")) return "Percona Server";
        if (v.contains("mariadb")) return "MariaDB";
        return "Non determinata (" + versionComment + ")";
    }

    /** Esegue una sezione del report; se fallisce restituisce l'errore senza interrompere il resto. */
    interface Section {
        Object run() throws Exception;
    }

    static Object safe(Map<String, String> errors, String name, Section s) {
        try {
            return s.run();
        } catch (Exception e) {
            errors.put(name, e.getMessage());
            return null;
        }
    }

    /** Insiemi GTID per sorgente: uuid -> intervalli [inizio, fine]. */
    static Map<String, List<long[]>> parseGtid(String set) {
        Map<String, List<long[]>> out = new LinkedHashMap<>();
        if (set == null) return out;
        for (String block : set.replace("\n", "").replace("\r", "").split(",")) {
            String b = block.trim();
            if (b.isEmpty()) continue;
            String[] parts = b.split(":");
            List<long[]> ivs = out.computeIfAbsent(parts[0], k -> new ArrayList<>());
            for (int i = 1; i < parts.length; i++) {
                String r = parts[i];
                if (r.contains("-")) {
                    String[] ab = r.split("-");
                    ivs.add(new long[]{Long.parseLong(ab[0]), Long.parseLong(ab[1])});
                } else {
                    long v = Long.parseLong(r);
                    ivs.add(new long[]{v, v});
                }
            }
        }
        return out;
    }

    /** Differenza a meno b, per sorgente. */
    static Map<String, List<long[]>> sottrai(Map<String, List<long[]>> a, Map<String, List<long[]>> b) {
        Map<String, List<long[]>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<long[]>> e : a.entrySet()) {
            List<long[]> cur = e.getValue();
            for (long[] y : b.getOrDefault(e.getKey(), List.of())) {
                List<long[]> next = new ArrayList<>();
                for (long[] x : cur) {
                    if (y[1] < x[0] || y[0] > x[1]) {
                        next.add(x);
                        continue;
                    }
                    if (x[0] < y[0]) next.add(new long[]{x[0], y[0] - 1});
                    if (x[1] > y[1]) next.add(new long[]{y[1] + 1, x[1]});
                }
                cur = next;
            }
            out.put(e.getKey(), cur);
        }
        return out;
    }

    /** Numero di transazioni in un insieme di intervalli. */
    static long contaSet(Map<String, List<long[]>> set) {
        long n = 0;
        for (List<long[]> ivs : set.values()) {
            for (long[] x : ivs) n += x[1] - x[0] + 1;
        }
        return n;
    }

    /** Conta le transazioni in un set GTID, es. "uuid:1-5:7,uuid2:1-3". */
    static long gtidCount(String set) {
        if (set == null || set.isBlank()) return 0;
        long total = 0;
        for (String block : set.replace("\n", "").replace("\r", "").split(",")) {
            String[] parts = block.trim().split(":");
            for (int i = 1; i < parts.length; i++) {
                String r = parts[i];
                if (r.contains("-")) {
                    String[] ab = r.split("-");
                    total += Long.parseLong(ab[1]) - Long.parseLong(ab[0]) + 1;
                } else {
                    total += 1;
                }
            }
        }
        return total;
    }

    // ----------------------------------------------------------------- report

    static Map<String, Object> report(JsonNode cfg) {
        return report(cfg, true, null);
    }

    /**
     * Rilevamento di un server. Le sezioni pesanti (indici, utenti, dimensione dati, colonne vettoriali)
     * si calcolano solo se {@code pesante} o se manca un rilevamento precedente; altrimenti si riusano
     * i valori dell'ultimo rilevamento completo ({@code precedente}).
     */
    static Map<String, Object> report(JsonNode cfg, boolean pesante, Map<String, Object> precedente) {
        if (isPostgres(cfg)) return PgReport.report(cfg, pesante, precedente);
        if (isMongo(cfg)) return MongoReport.report(cfg, pesante, precedente);
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("generatoAlle", java.time.Instant.now().toString());
        r.put("server", m("id", text(cfg, "id"), "name", text(cfg, "name"), "jdbcUrl", jdbcUrl(cfg)));

        long t0 = System.currentTimeMillis();
        try (Connection c = open(cfg)) {
            r.put("connessione", m("ok", true, "ms", System.currentTimeMillis() - t0));

            // 1. Edizione e versione
            Map<String, Object> v = q(c, "SELECT @@version AS version, @@version_comment AS comment, @@hostname AS hostname, "
                    + "@@port AS port, @@read_only AS read_only").get(0);
            // Variabili presenti solo in MySQL: se mancano (es. MariaDB) restano vuote
            v.put("server_uuid", varOrNull(c, "@@server_uuid"));
            v.put("super_read_only", varOrNull(c, "@@super_read_only"));
            v.put("gtid", varOrNull(c, "@@global.gtid_executed"));
            String version = str(v.get("version"));
            r.put("identita", m("edizione", edition(str(v.get("comment"))), "motore", engine(version, str(v.get("comment"))),
                    "versione", version,
                    "patch", version.split("-")[0], "commento", v.get("comment"), "hostname", v.get("hostname"),
                    "serverUuid", v.get("server_uuid"), "porta", v.get("port"),
                    "readOnly", v.get("read_only"), "superReadOnly", v.get("super_read_only"),
                    "gtidEseguito", v.get("gtid")));

            // 2. Membri Group Replication (vuoto se il plugin non è attivo)
            List<Map<String, Object>> gr = List.of();
            try {
                gr = q(c, "SELECT MEMBER_HOST, MEMBER_PORT, MEMBER_STATE, MEMBER_ROLE, MEMBER_VERSION "
                        + "FROM performance_schema.replication_group_members ORDER BY MEMBER_HOST");
            } catch (SQLException e) {
                // MariaDB non ha Group Replication: non è un errore
                if (!String.valueOf(e.getMessage()).contains("doesn't exist")) errors.put("groupReplication", e.getMessage());
            }

            // Ritardo di allineamento dei membri: transazioni in coda nell'applier di ciascun membro
            try {
                Map<String, Object> coda = new HashMap<>();
                for (Map<String, Object> row : q(c, "SELECT m.MEMBER_HOST, s.COUNT_TRANSACTIONS_REMOTE_IN_APPLIER_QUEUE AS CODA "
                        + "FROM performance_schema.replication_group_members m "
                        + "JOIN performance_schema.replication_group_member_stats s ON s.MEMBER_ID = m.MEMBER_ID")) {
                    coda.put(str(row.get("MEMBER_HOST")), row.get("CODA"));
                }
                for (Map<String, Object> membro : gr) membro.put("codaTransazioni", coda.get(str(membro.get("MEMBER_HOST"))));
            } catch (SQLException ignored) {
                // statistiche di gruppo non disponibili
            }

            // 3. Stato di replica (sorgente configurata)
            List<Map<String, Object>> replica = new ArrayList<>();
            try {
                for (Map<String, Object> row : q(c, "SHOW REPLICA STATUS")) {
                    replica.add(m("canale", row.get("Channel_Name"), "sorgente", row.get("Source_Host"),
                            "ioAttivo", row.get("Replica_IO_Running"), "sqlAttivo", row.get("Replica_SQL_Running"),
                            "secondiDiRitardo", row.get("Seconds_Behind_Source"),
                            "relayLogBytes", num(row.get("Relay_Log_Space")),
                            "ultimoErrore", row.get("Last_Error")));
                }
            } catch (SQLException e) {
                errors.put("replica", e.getMessage());
            }

            // 4. Tipo di topologia
            String tipo;
            String clusterName = null;
            if (!gr.isEmpty() && gr.size() > 1) {
                tipo = "InnoDB Cluster (Group Replication, " + gr.size() + " membri)";
                clusterName = str(safe(errors, "nomeCluster", () -> {
                    List<Map<String, Object>> rs = q(c, "SELECT cluster_name FROM mysql_innodb_cluster_metadata.clusters");
                    return rs.isEmpty() ? "" : rs.get(0).get("cluster_name");
                }));
            } else if (!gr.isEmpty()) {
                tipo = "Group Replication a membro singolo";
            } else if (!replica.isEmpty()) {
                tipo = "Replica asincrona";
            } else {
                tipo = "Nodo singolo";
            }
            // Galera (MariaDB): stato wsrep_* del nodo; il cluster è identificato dal nome
            Map<String, Object> galera = new LinkedHashMap<>();
            try {
                Map<String, Object> wsrep = new HashMap<>();
                for (Map<String, Object> row : q(c, "SHOW GLOBAL STATUS LIKE 'wsrep_%'")) {
                    wsrep.put(str(row.get("Variable_name")), row.get("Value"));
                }
                if (num(wsrep.get("wsrep_cluster_size")) > 0) {
                    // nome del cluster e del nodo sono variabili di configurazione, non di stato
                    Map<String, Object> vars = new HashMap<>();
                    for (Map<String, Object> row : q(c, "SHOW VARIABLES LIKE 'wsrep_%name'")) {
                        vars.put(str(row.get("Variable_name")), row.get("Value"));
                    }
                    galera.put("dimensione", num(wsrep.get("wsrep_cluster_size")));
                    galera.put("nomeCluster", str(vars.get("wsrep_cluster_name")));
                    galera.put("statoCluster", str(wsrep.get("wsrep_cluster_status")));
                    galera.put("statoLocale", str(wsrep.get("wsrep_local_state_comment")));
                    galera.put("nodo", str(vars.get("wsrep_node_name")));
                    tipo = "Galera Cluster (" + num(wsrep.get("wsrep_cluster_size")) + " nodi)";
                    clusterName = str(vars.get("wsrep_cluster_name"));
                }
            } catch (SQLException ignored) {
                // nodo senza Galera
            }
            r.put("galera", galera.isEmpty() ? null : galera);
            r.put("topologia", m("tipo", tipo, "cluster", clusterName));

            // 5. Stato del cluster e confronto aggiornamento
            if (!gr.isEmpty()) {
                r.put("cluster", clusterSection(c, cfg, gr, errors));
            } else {
                r.put("cluster", null);
            }

            // 6. Relay log / replica
            r.put("replica", replica);
            r.put("relayLogTotaleBytes", replica.stream().mapToLong(x -> num(x.get("relayLogBytes"))).sum());

            // 7. Query attive
            // Ordinate per consumo: tempo di esecuzione, poi righe esaminate.
            r.put("connessioni", !controllo(cfg, "connessioni") ? null : safe(errors, "connessioni", () -> q(c,
                    "SELECT COUNT(*) AS attive FROM information_schema.PROCESSLIST WHERE COMMAND <> 'Daemon'").get(0)));
            r.put("queryAttive", !controllo(cfg, "query_attive") ? null : safe(errors, "queryAttive", () -> q(c,
                    "SELECT t.PROCESSLIST_ID AS ID, t.PROCESSLIST_USER AS UTENTE, t.PROCESSLIST_HOST AS HOST, "
                            + "esc.CURRENT_SCHEMA AS DB, t.PROCESSLIST_STATE AS STATO, "
                            + "ROUND(esc.TIMER_WAIT / 1000000000000, 3) AS SECONDI, "
                            + "esc.ROWS_EXAMINED AS RIGHE_ESAMINATE, esc.ROWS_SENT AS RIGHE_INVIATE, "
                            + "esc.CREATED_TMP_DISK_TABLES AS TMP_DISCO, esc.CREATED_TMP_TABLES AS TMP_TABELLE, "
                            + "esc.NO_INDEX_USED AS SENZA_INDICE, esc.SQL_TEXT AS QUERY "
                            + "FROM performance_schema.events_statements_current esc "
                            + "JOIN performance_schema.threads t ON t.THREAD_ID = esc.THREAD_ID "
                            + "WHERE esc.END_EVENT_ID IS NULL AND t.PROCESSLIST_ID IS NOT NULL "
                            + "AND t.PROCESSLIST_ID <> CONNECTION_ID() AND t.PROCESSLIST_COMMAND = 'Query' "
                            + "AND esc.SQL_TEXT IS NOT NULL "
                            + "ORDER BY esc.TIMER_WAIT DESC, esc.ROWS_EXAMINED DESC LIMIT " + Impostazioni.intero("query.attive_limite"))));

            // Transazioni bloccate: sessioni in attesa di un lock e sessione che lo tiene
            r.put("bloccate", !controllo(cfg, "transazioni_bloccate") ? null : safe(errors, "bloccate", () -> bloccateMysql(c)));

            // 8. Query più lente
            r.put("queryLente", !controllo(cfg, "query_lente") ? null : safe(errors, "queryLente", () -> q(c,
                    "SELECT SCHEMA_NAME, LEFT(DIGEST_TEXT, 300) AS QUERY, COUNT_STAR AS ESECUZIONI, "
                            + "ROUND(AVG_TIMER_WAIT / 1000000000000, 4) AS MEDIA_SEC, "
                            + "ROUND(MAX_TIMER_WAIT / 1000000000000, 4) AS MAX_SEC, "
                            + "SUM_ROWS_EXAMINED AS RIGHE_ESAMINATE, SUM_NO_INDEX_USED AS SENZA_INDICE "
                            + "FROM performance_schema.events_statements_summary_by_digest "
                            + "WHERE DIGEST_TEXT IS NOT NULL AND SCHEMA_NAME NOT IN (" + SYSTEM_SCHEMAS + ") "
                            + "ORDER BY AVG_TIMER_WAIT DESC LIMIT " + Impostazioni.intero("query.lente_limite"))));

            r.put("queryLente", filtraPianificabili(r.get("queryLente")));
            // Utenti con scadenza della password (MySQL 8). La data di creazione dell'utenza non è registrata da MySQL.
            r.put("utentiDettaglio", !controllo(cfg, "utenti") ? null : safe(errors, "utentiDettaglio", () -> {
                try {
                    return q(c,
                    "SELECT CONCAT(user, '@', host) AS utente, 'account' AS tipo, 'non tracciata da MySQL' AS creazione, "
                    + "CASE WHEN account_locked = 'Y' THEN 'bloccato' WHEN password_lifetime IS NULL OR password_lifetime = 0 THEN 'non applicabile' "
                    + "ELSE CONCAT('scade il ', DATE_FORMAT(DATE_ADD(password_last_changed, INTERVAL password_lifetime DAY), '%Y-%m-%d')) END AS scadenza "
                    + "FROM mysql.user WHERE user <> '' ORDER BY user, host");
                } catch (SQLException e) {
                    // MariaDB non ha account_locked né password_lifetime: utenti senza scadenza tracciata
                    return q(c, "SELECT CONCAT(user, '@', host) AS utente, 'account' AS tipo, 'non tracciata da MariaDB' AS creazione, "
                    + "'non applicabile' AS scadenza FROM mysql.user WHERE user <> '' ORDER BY user, host");
                }
            }));
            r.put("databaseDettaglio", !controllo(cfg, "database") ? null : safe(errors, "database", () -> q(c,
                    "SELECT SCHEMA_NAME AS `database`, 'non tracciata da MySQL' AS creazione, 'online' AS stato, "
                    + "(SELECT ROUND(SUM(DATA_LENGTH + INDEX_LENGTH) / 1048576) FROM information_schema.TABLES t WHERE t.TABLE_SCHEMA = SCHEMA_NAME) AS dimensioneMB "
                    + "FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN (" + SYSTEM_SCHEMAS + ") ORDER BY SCHEMA_NAME")));

            // 8b. Risorse: CPU e memoria dell'host (solo modalità host, node_exporter) e memoria allocata da mysqld
if (controllo(cfg, "risorse")) {
            Map<String, Object> risorse = new LinkedHashMap<>();
            try {
                String modo = text(cfg, "resourceMode");
                if ("host".equals(modo)) {
                    risorse.putAll(HostMetrics.stats(text(cfg, "metricsUrl")));
                }
            } catch (Exception e) {
                errors.put("risorseContainer", e.getMessage());
            }
            safe(errors, "memoriaMysqld", () -> {
                Map<String, Object> mem = q(c, "SELECT CAST(total_allocated AS UNSIGNED) AS allocata "
                        + "FROM sys.memory_global_total").get(0);
                risorse.put("memoriaMysqldAllocataBytes", num(mem.get("allocata")));
                risorse.put("bufferPoolBytes", num(q(c, "SELECT @@innodb_buffer_pool_size AS b").get(0).get("b")));
                return null;
            });
            r.put("risorse", risorse);
            }

            // 8c. Sezioni pesanti: dimensione dati, colonne vettoriali, indici, utenti.
            // Nei rilevamenti leggeri si riusano i valori dell'ultimo rilevamento completo.
            if (pesante || precedente == null) {
                safe(errors, "dati", () -> {
                    Map<String, Object> d = q(c, "SELECT COALESCE(SUM(data_length + index_length), 0) AS bytes "
                            + "FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA NOT IN (" + SYSTEM_SCHEMAS + ", 'mysql_innodb_cluster_metadata') "
                            + "AND TABLE_TYPE = 'BASE TABLE'").get(0);
                    r.put("datiBytes", num(d.get("bytes")));
                    return null;
                });

                // Vector DB: colonne di tipo vettoriale negli schemi utente
                safe(errors, "vettoriale", () -> {
                    Map<String, Object> vec = q(c, "SELECT COUNT(*) AS n FROM information_schema.COLUMNS "
                            + "WHERE DATA_TYPE = 'vector' AND TABLE_SCHEMA NOT IN (" + SYSTEM_SCHEMAS + ", 'mysql_innodb_cluster_metadata')").get(0);
                    r.put("colonneVettoriali", num(vec.get("n")));
                    return null;
                });

                if (controllo(cfg, "indici")) r.put("indici", indexSection(c, errors));
                r.put("utenti", !controllo(cfg, "utenti") ? null : safe(errors, "utenti", () -> userSection(c)));
            } else {
                for (String chiave : List.of("datiBytes", "colonneVettoriali", "indici", "utenti")) {
                    if (precedente.containsKey(chiave)) r.put(chiave, precedente.get(chiave));
                }
            }

        } catch (SQLException e) {
            r.put("connessione", m("ok", false, "errore", e.getMessage()));
        }
        if (!controllo(cfg, "utenti")) r.put("utenti", null); // anche se ricopiato dal rilevamento precedente
        r.put("errori", errors);
        return r;
    }

    static Map<String, Object> clusterSection(Connection c, JsonNode cfg, List<Map<String, Object>> gr,
                                              Map<String, String> errors) {
        int online = 0;
        for (Map<String, Object> mb : gr) {
            if ("ONLINE".equals(str(mb.get("MEMBER_STATE")))) online++;
        }
        // Nodi configurati: dai metadati InnoDB Cluster se presenti, altrimenti dai membri visibili.
        List<String> addresses = new ArrayList<>();
        try {
            for (Map<String, Object> row : q(c, "SELECT address FROM mysql_innodb_cluster_metadata.instances")) {
                addresses.add(str(row.get("address")));
            }
        } catch (SQLException ignored) {
            // metadati non presenti
        }
        if (addresses.isEmpty()) {
            for (Map<String, Object> mb : gr) addresses.add(str(mb.get("MEMBER_HOST")) + ":" + str(mb.get("MEMBER_PORT")));
        }
        int total = Math.max(addresses.size(), gr.size());

        String stato;
        if (online == total) stato = "ONLINE";
        else if (online == 0) stato = "OFFLINE";
        else if (online * 2 <= total) stato = "SENZA QUORUM";
        else stato = "DEGRADED";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stato", stato);
        out.put("online", online);
        out.put("totale", total);
        out.put("primario", gr.stream().filter(x -> "PRIMARY".equals(x.get("MEMBER_ROLE")))
                .map(x -> str(x.get("MEMBER_HOST"))).findFirst().orElse(null));
        out.put("membri", gr);

        // GTID di ogni nodo (sempre) e transazioni errant: presenti su un nodo ma assenti da tutti gli altri
        List<Map<String, Object>> nodi = new ArrayList<>();
        Map<String, Map<String, List<long[]>>> insiemi = new LinkedHashMap<>();
        for (String addr : addresses) {
            String[] hp = addr.split(":");
            Map<String, Object> nodo = new LinkedHashMap<>();
            nodo.put("nodo", addr);
            try {
                ObjectNode copy = ((ObjectNode) cfg).deepCopy();
                copy.put("host", hp[0]);
                copy.put("port", hp.length > 1 ? Integer.parseInt(hp[1]) : 3306);
                try (Connection nc = open(copy)) {
                    String gtid = str(q(nc, "SELECT @@global.gtid_executed AS g").get(0).get("g"));
                    nodo.put("transazioni", gtidCount(gtid));
                    nodo.put("gtidExecuted", gtid.replace("\n", ""));
                    nodo.put("raggiungibile", true);
                    insiemi.put(addr, parseGtid(gtid));
                }
            } catch (Exception e) {
                nodo.put("transazioni", null);
                nodo.put("raggiungibile", false);
                nodo.put("errore", e.getMessage());
            }
            nodi.add(nodo);
        }
        for (Map<String, Object> nodo : nodi) {
            String addr = str(nodo.get("nodo"));
            if (!insiemi.containsKey(addr)) continue;
            Map<String, List<long[]>> errant = insiemi.get(addr);
            for (Map.Entry<String, Map<String, List<long[]>>> e : insiemi.entrySet()) {
                if (!e.getKey().equals(addr)) errant = sottrai(errant, e.getValue());
            }
            nodo.put("errant", contaSet(errant));
        }
        // Dati per membro del gruppo: GTID eseguiti ed errant, per la topologia
        for (Map<String, Object> mb : gr) {
            String addr = str(mb.get("MEMBER_HOST")) + ":" + str(mb.get("MEMBER_PORT"));
            for (Map<String, Object> nodo : nodi) {
                if (addr.equals(nodo.get("nodo"))) {
                    mb.put("gtidTransazioni", nodo.get("transazioni"));
                    mb.put("errant", nodo.get("errant"));
                }
            }
        }
        if (!"ONLINE".equals(stato)) {
            List<Map<String, Object>> ok = nodi.stream().filter(x -> Boolean.TRUE.equals(x.get("raggiungibile"))).toList();
            Map<String, Object> piu = ok.stream().max((a, b) -> Long.compare(num(a.get("transazioni")), num(b.get("transazioni")))).orElse(null);
            Map<String, Object> meno = ok.stream().min((a, b) -> Long.compare(num(a.get("transazioni")), num(b.get("transazioni")))).orElse(null);
            out.put("aggiornamento", m("piuAggiornato", piu == null ? null : piu.get("nodo"),
                    "menoAggiornato", meno == null ? null : meno.get("nodo"),
                    "differenzaTransazioni", piu == null || meno == null ? null
                            : num(piu.get("transazioni")) - num(meno.get("transazioni")),
                    "nodi", nodi));
        }
        return out;
    }

    static Map<String, Object> indexSection(Connection c, Map<String, String> errors) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("elenco", safe(errors, "indici", () -> q(c,
                "SELECT TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, INDEX_TYPE, "
                        + "CASE NON_UNIQUE WHEN 0 THEN 'UNIQUE' ELSE 'NON UNIQUE' END AS UNICITA, "
                        + "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS COLONNE "
                        + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA NOT IN (" + SYSTEM_SCHEMAS + ") "
                        + "GROUP BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, INDEX_TYPE, NON_UNIQUE "
                        + "ORDER BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME LIMIT 300")));
        out.put("tabelleSenzaPrimaryKey", safe(errors, "tabelleSenzaPk", () -> q(c,
                "SELECT t.TABLE_SCHEMA, t.TABLE_NAME FROM information_schema.TABLES t "
                        + "LEFT JOIN information_schema.STATISTICS s ON s.TABLE_SCHEMA = t.TABLE_SCHEMA "
                        + "AND s.TABLE_NAME = t.TABLE_NAME AND s.INDEX_NAME = 'PRIMARY' "
                        + "WHERE t.TABLE_TYPE = 'BASE TABLE' AND t.TABLE_SCHEMA NOT IN (" + SYSTEM_SCHEMAS + ") "
                        + "AND s.INDEX_NAME IS NULL")));
        out.put("indiciInutilizzati", safe(errors, "indiciInutilizzati", () -> q(c,
                "SELECT object_schema, object_name, index_name FROM sys.schema_unused_indexes "
                        + "WHERE object_schema NOT IN (" + SYSTEM_SCHEMAS + ") LIMIT 100")));
        return out;
    }

    static List<Map<String, Object>> userSection(Connection c) throws SQLException {
        Map<String, List<String>> dbByUser = new LinkedHashMap<>();
        for (Map<String, Object> d : q(c, "SELECT User, Host, Db FROM mysql.db")) {
            dbByUser.computeIfAbsent(str(d.get("User")) + "@" + str(d.get("Host")), k -> new ArrayList<>())
                    .add(str(d.get("Db")));
        }
        String[] privs = {"Select_priv", "Insert_priv", "Update_priv", "Delete_priv", "Create_priv",
                "Drop_priv", "Grant_priv", "Super_priv"};
        List<Map<String, Object>> out = new ArrayList<>();
        // MySQL ha account_locked e password_expired; MariaDB no: in quel caso le colonne restano vuote
        List<Map<String, Object>> utenti;
        try {
            utenti = q(c, "SELECT User, Host, plugin, account_locked, password_expired, "
                + "Select_priv, Insert_priv, Update_priv, Delete_priv, Create_priv, Drop_priv, Grant_priv, Super_priv "
                + "FROM mysql.user ORDER BY User, Host");
        } catch (SQLException e) {
            utenti = q(c, "SELECT User, Host, plugin, "
                + "Select_priv, Insert_priv, Update_priv, Delete_priv, Create_priv, Drop_priv, Grant_priv, Super_priv "
                + "FROM mysql.user ORDER BY User, Host");
        }
        for (Map<String, Object> u : utenti) {
            String key = str(u.get("User")) + "@" + str(u.get("Host"));
            List<String> globali = new ArrayList<>();
            for (String p : privs) {
                if ("Y".equals(u.get(p))) globali.add(p.replace("_priv", "").toUpperCase());
            }
            out.add(m("utente", key, "plugin", u.get("plugin"), "bloccato", "Y".equals(u.get("account_locked")),
                    "passwordScaduta", "Y".equals(u.get("password_expired")),
                    "privilegiGlobali", globali,
                    "database", dbByUser.getOrDefault(key, List.of())));
        }
        return out;
    }

    /**
     * Piano di esecuzione di una query. Usa solo EXPLAIN (la query non viene eseguita):
     * EXPLAIN ANALYZE non è offerto perché eseguirebbe anche le istruzioni DML.
     */
    static Map<String, Object> explain(JsonNode s, JsonNode body) throws SQLException {
        String sql = text(body, "sql");
        String schema = text(body, "schema");
        if (sql.isEmpty()) throw new IllegalArgumentException("Query vuota");
        if ("sqlserver".equals(text(s, "kind"))) return SqlServerReport.explain(s, sql, schema);
        if (isPostgres(s)) return explainPg(s, sql, schema);
        // Il testo del digest è troncato da MySQL a 300 caratteri e termina con "...": il piano non è calcolabile
        if (sql.endsWith("...")) {
            throw new IllegalArgumentException("Query troncata nel digest di performance_schema: il testo completo non è disponibile");
        }
        // Nel digest i valori sono segnaposto "?": per il piano si usa un valore fittizio (1)
        boolean conParametri = sql.contains("?");
        sql = sql.replace("?", "1");
        // Il digest di MySQL riscrive alcune parole chiave con nomi interni (es. SQL_TSI_MINUTE per MINUTE)
        sql = sql.replaceAll("SQL_TSI_([A-Z]+)", "$1");
        // Il digest separa il nome di funzione dalla parentesi (es. DATE_ADD ( ...): MySQL non lo accetta
        sql = sql.replaceAll("(\\w)\\s+\\(", "$1(");
        if (piuIstruzioni(sql)) throw new IllegalArgumentException("Sono ammesse solo istruzioni singole");
        String up = sql.toUpperCase();
        if (!(up.startsWith("SELECT") || up.startsWith("WITH") || up.startsWith("INSERT")
                || up.startsWith("REPLACE") || up.startsWith("UPDATE") || up.startsWith("DELETE"))) {
            throw new IllegalArgumentException("Piano disponibile solo per SELECT, WITH, INSERT, REPLACE, UPDATE, DELETE");
        }
        ObjectNode cfg = ((ObjectNode) s).deepCopy();
        if (!schema.isEmpty()) cfg.put("database", schema);
        try (Connection c = open(cfg)) {
            List<Map<String, Object>> rows = q(c, "EXPLAIN " + sql);
            String json = str(q(c, "EXPLAIN FORMAT=JSON " + sql).get(0).get("EXPLAIN"));
            Map<String, Object> out = m("sql", sql, "schema", schema, "tabella", rows, "json", json);
            if (conParametri) out.put("note", "Parametri della query sostituiti con 1: i valori di filtro e le stime di righe sono indicativi.");
            return out;
        }
    }

    /**
     * Query che hanno senso per il piano di esecuzione: SELECT, WITH, INSERT ... SELECT, UPDATE, DELETE.
     * Esclude le query del monitor e dei driver (EXPLAIN, SELECT @@, SET), le istruzioni di manutenzione e
     * i digest troncati o con più valori (VALUES ..., che MySQL riassume con "...").
     */
    static boolean pianificabile(String sql) {
        if (sql == null || sql.isBlank()) return false;
        String t = sql.trim().toUpperCase();
        if (t.contains("...") || t.contains("EXPLAIN") || t.startsWith("SELECT @@")) return false;
        if (t.startsWith("INSERT") && !t.contains(" SELECT ")) return false;
        return t.startsWith("SELECT") || t.startsWith("WITH") || t.startsWith("INSERT")
                || t.startsWith("UPDATE") || t.startsWith("DELETE");
    }

    /** Filtra una lista di query lente lasciando solo quelle pianificabili. */
    static Object filtraPianificabili(Object lista) {
        if (!(lista instanceof List<?> l)) return lista;
        List<Object> out = new ArrayList<>();
        for (Object o : l) {
            if (o instanceof Map<?, ?> m && pianificabile(str(m.get("QUERY")))) out.add(o);
        }
        return out;
    }

    /**
     * Piano di una query PostgreSQL: EXPLAIN (FORMAT JSON) letto come tabella dei nodi, più il testo.
     * La query non viene eseguita. Per UPDATE, DELETE e INSERT ... SELECT si pianifica la parte di lettura.
     */
    static Map<String, Object> explainPg(JsonNode s, String sql, String schema) throws SQLException {
        // Parametri ($1, $2...): sostituiti con NULL, che non ha tipo e non richiede valori
        // Con un tipo davanti (TIMESTAMP $1, INTERVAL $2) NULL non è un'espressione valida: si usa un cast tipizzato
        String base = sql.trim()
                .replaceAll("(?i)\\b(TIMESTAMPTZ|TIMESTAMP|DATE|TIME|INTERVAL)\\s+\\$\\d+", "CAST(NULL AS $1)");
        if (piuIstruzioni(base)) throw new IllegalArgumentException("Sono ammesse solo istruzioni singole");
        ObjectNode cfg = ((ObjectNode) s).deepCopy();
        if (!schema.isEmpty()) cfg.put("database", schema);
        // I parametri senza tipo (es. dentro generate_series) non si deducono dal digest: si provano tipi comuni
        // in ordine; il primo che PostgreSQL accetta è quello dichiarato nel piano
        String[] tentativi = base.matches("(?s).*\\$\\d+.*") ? new String[]{"NULL", "int", "timestamp", "text", "numeric"} : new String[]{"NULL"};
        String originale = null, lettura = null, json = null, tipoUsato = null, testo = "";
        String errore = null;
        // Prima strada: PostgreSQL deduce il tipo di ogni parametro dal contesto (es. "codice LIKE $1" dà text)
        // con PREPARE, poi EXPLAIN EXECUTE con valori NULL. Funziona anche per i parametri usati nel digest.
        if (base.matches("(?s).*\\$\\d+.*")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$(\\d+)").matcher(base);
            int max = 0;
            while (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
            String argomenti = String.join(",", java.util.Collections.nCopies(max, "NULL"));
            String lettPrep = parteDiLettura(base);
            try (Connection c = open(cfg); Statement st = c.createStatement()) {
                st.execute("PREPARE mon_piano AS " + lettPrep);
                try {
                    String j = str(q(c, "EXPLAIN (FORMAT JSON) EXECUTE mon_piano(" + argomenti + ")").get(0).values().iterator().next());
                    StringBuilder tb = new StringBuilder();
                    for (Map<String, Object> riga : q(c, "EXPLAIN EXECUTE mon_piano(" + argomenti + ")")) {
                        tb.append(str(riga.values().iterator().next())).append('\n');
                    }
                    json = j;
                    testo = tb.toString();
                    originale = base;
                    lettura = lettPrep;
                    tipoUsato = "NULL";
                } finally {
                    st.execute("DEALLOCATE mon_piano");
                }
            } catch (SQLException e) {
                errore = e.getMessage() == null ? "" : e.getMessage();
            }
        }
        // Errore della prima strada: se anche la riserva fallisce, è il motivo più utile da mostrare
        final String errorePreparato = json == null ? errore : null;
        // Seconda strada (riserva): parametri sostituiti con NULL o con un tipo comune
        if (json == null) for (String tipo : tentativi) {
            String cand = tipo.equals("NULL") ? base.replaceAll("\\$\\d+", "NULL") : base.replaceAll("\\$\\d+", "CAST(NULL AS " + tipo + ")");
            String lett = parteDiLettura(cand);
            try (Connection c = open(cfg)) {
                String j = str(q(c, "EXPLAIN (FORMAT JSON) " + lett).get(0).values().iterator().next());
                StringBuilder tb = new StringBuilder();
                for (Map<String, Object> riga : q(c, "EXPLAIN " + lett)) {
                    tb.append(str(riga.values().iterator().next())).append('\n');
                }
                json = j;
                testo = tb.toString();
                originale = cand;
                lettura = lett;
                tipoUsato = tipo;
                break;
            } catch (SQLException e) {
                errore = e.getMessage() == null ? "" : e.getMessage();
            }
        }
        if (json == null) {
            if (errore != null && (errore.contains("is not unique") || errore.contains("could not determine"))) {
                throw new SQLException("Piano non calcolabile: PostgreSQL non riesce a dedurre il tipo dei parametri "
                        + "usati dentro una funzione (es. generate_series), e il digest non conserva i valori. Errore: " + errore);
            }
            throw new SQLException("Piano non calcolabile: " + (errorePreparato != null ? errorePreparato : errore));
        }
        String up = originale.toUpperCase();
        String nota = lettura.equals(originale) ? null
                : "Scrittura non eseguita: il piano è quello della parte di lettura (la stessa che PostgreSQL deve leggere).";
        if (!(lettura.toUpperCase().startsWith("SELECT") || lettura.toUpperCase().startsWith("WITH"))) {
            throw new IllegalArgumentException("Piano disponibile solo per SELECT, WITH, INSERT, UPDATE, DELETE, MERGE");
        }
        if (tipoUsato != null && !tipoUsato.equals("NULL")) {
            nota = (nota == null ? "" : nota + " ")
                    + "Tipo dei parametri dedotto per tentativi (" + tipoUsato + "): stime indicative.";
        }
        List<Map<String, Object>> passi = new ArrayList<>();
        try {
            JsonNode radice = JSON.readTree(json);
            JsonNode piano = radice.isArray() ? radice.get(0).path("Plan") : radice.path("Plan");
            passiPg(piano, 0, passi);
        } catch (Exception e) {
            // se il JSON non si legge, resta il testo del piano
        }
        Map<String, Object> out = m("sql", sql, "schema", schema, "tabella", new ArrayList<>(), "json", null,
                "testo", testo, "passi", passi);
        // Con i parametri sostituiti da NULL PostgreSQL semplifica i confronti (es. = NULL): il piano è indicativo
        if (sql.matches("(?s).*\\$\\d+.*")) {
            String avviso = "Parametri sostituiti con NULL: il piano è indicativo, perché PostgreSQL semplifica i confronti con NULL.";
            nota = nota == null ? avviso : nota + " " + avviso;
        }
        if (nota != null) out.put("note", nota);
        return out;
    }

    private static void passiPg(JsonNode nodo, int profondita, List<Map<String, Object>> out) {
        if (nodo == null || nodo.isMissingNode()) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("profondita", profondita);
        p.put("operatore", nodo.path("Node Type").asText(""));
        String oggetto = nodo.path("Relation Name").asText("");
        if (oggetto.isEmpty()) oggetto = nodo.path("Index Name").asText("");
        p.put("oggetto", oggetto);
        p.put("logico", nodo.path("Join Type").asText(""));
        p.put("righeStimate", nodo.path("Plan Rows").asDouble(0));
        p.put("costo", nodo.path("Total Cost").asDouble(0));
        String filtro = nodo.path("Filter").asText("");
        if (filtro.isEmpty()) filtro = nodo.path("Index Cond").asText("");
        p.put("dettaglio", filtro);
        out.add(p);
        if (nodo.has("Plans")) for (JsonNode figlio : nodo.get("Plans")) passiPg(figlio, profondita + 1, out);
    }

    /** Parte di lettura di una scrittura: il SELECT del WHERE per UPDATE/DELETE, il SELECT per INSERT ... SELECT. */
    static String parteDiLettura(String sql) {
        String t = sql.trim();
        String up = t.toUpperCase();
        if (up.startsWith("UPDATE")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)^UPDATE\\s+(\\S+)\\s+SET\\s+.*?(\\s+WHERE\\s+.*)?$").matcher(t);
            if (m.matches()) return "SELECT * FROM " + m.group(1) + (m.group(2) == null ? "" : m.group(2));
        }
        if (up.startsWith("DELETE")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)^DELETE\\s+FROM\\s+(\\S+)(\\s+WHERE\\s+.*)?$").matcher(t);
            if (m.matches()) return "SELECT * FROM " + m.group(1) + (m.group(2) == null ? "" : m.group(2));
        }
        if (up.startsWith("INSERT")) {
            int i = up.indexOf(" SELECT ");
            if (i > 0) return t.substring(i + 1);
        }
        return t;
    }

    static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }
}
