package it.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Notifiche Telegram degli eventi del monitor. Una sola configurazione (riga id = 1). Il token del bot
 * è cifrato come le chiavi IA e non viene mai restituito per intero.
 *
 * <p>Ogni notifica ha il pulsante "Presa visione". Finché una notifica non è confermata, lo stesso evento
 * (stesso server e stessa categoria) non viene inviato di nuovo. Il monitor riceve i clic interrogando
 * Telegram in uscita (getUpdates): non servono porte aperte verso il monitor. Quando un server torna
 * raggiungibile, le notifiche di mancata connessione ancora in attesa vengono chiuse automaticamente.
 */
final class Telegram {
    private Telegram() {}

    static final String CRITICAL = "CRITICAL";
    static final String WARNING = "WARNING";
    static final String INFO = "INFO";
    /** Ordine di gravità: una notifica parte se la sua gravità è pari o superiore alla minima scelta. */
    private static final Map<String, Integer> RANGO = Map.of(INFO, 0, WARNING, 1, CRITICAL, 2);

    private static final String PERDITA = "CONNECTION_LOST";
    private static final String RIPRISTINO = "CONNECTION_RESTORED";

    private static final Pattern CHAT = Pattern.compile("-?\\d{1,20}|@[A-Za-z0-9_]{4,64}");
    private static final Pattern TOKEN = Pattern.compile("\\d{5,15}:[A-Za-z0-9_-]{20,128}");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final DateTimeFormatter ORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final ExecutorService INVII = Executors.newSingleThreadExecutor(r -> daemon(r, "telegram-invii"));
    private static volatile long offset = 0;

    private static Thread daemon(Runnable r, String nome) {
        Thread t = new Thread(r, nome);
        t.setDaemon(true);
        return t;
    }

    private record Riga(boolean abilitata, String chatId, String tokenCifrato, String gravitaMinima) {}

    /** Creazione delle tabelle, della riga di configurazione (una sola) e avvio dell'ascolto dei clic. */
    static void init() throws SQLException {
        try (Connection c = Store.conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS telegram_config ("
                    + " id INT PRIMARY KEY,"
                    + " abilitata TINYINT(1) NOT NULL DEFAULT 0,"
                    + " chat_id VARCHAR(64) NOT NULL DEFAULT '',"
                    + " token TEXT,"
                    + " gravita_minima VARCHAR(16) NOT NULL DEFAULT 'CRITICAL')");
            st.execute("INSERT IGNORE INTO telegram_config (id) VALUES (1)");
            st.execute("CREATE TABLE IF NOT EXISTS telegram_notifiche ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " chiave VARCHAR(255) NOT NULL,"
                    + " messaggio TEXT NOT NULL,"
                    + " message_id BIGINT NULL,"
                    + " inviato_il DATETIME(3) NOT NULL,"
                    + " confermato_il DATETIME(3) NULL,"
                    + " confermato_da VARCHAR(128) NULL,"
                    + " INDEX idx_tg_chiave (chiave, confermato_il))");
        }
        Thread ascolto = daemon(Telegram::ciclaAscolto, "telegram-ascolto");
        ascolto.start();
    }

    private static Riga leggi() throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT abilitata, chat_id, token, gravita_minima "
                     + "FROM telegram_config WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("Configurazione Telegram mancante");
            return new Riga(rs.getBoolean(1), rs.getString(2), rs.getString(3), rs.getString(4));
        }
    }

    /** Stato da mostrare in interfaccia: il token solo mascherato, più le notifiche in attesa di conferma. */
    static Map<String, Object> stato() throws SQLException {
        Riga r = leggi();
        String in = tokenInChiaro(r);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("abilitata", r.abilitata());
        m.put("chatId", r.chatId());
        m.put("tokenConfigurato", in != null);
        m.put("tokenMascherato", in == null || in.length() < 4 ? null : "…" + in.substring(in.length() - 4));
        m.put("gravitaMinima", r.gravitaMinima());
        m.put("gravitaOpzioni", List.of(CRITICAL, WARNING, INFO));
        m.put("inAttesa", inAttesa());
        return m;
    }

    /** Numero di notifiche inviate e non ancora confermate. */
    private static int inAttesa() throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM telegram_notifiche WHERE confermato_il IS NULL");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Salva la configurazione. Campi assenti lasciano invariato il valore salvato. Un token non vuoto lo
     * sostituisce; {@code cancellaToken} lo rimuove.
     */
    static void salva(JsonNode b) throws SQLException {
        Riga att = leggi();
        boolean abilitata = b.has("abilitata") ? b.get("abilitata").asBoolean() : att.abilitata();
        String chat = b.has("chatId") && !b.get("chatId").isNull() ? b.get("chatId").asText().trim() : att.chatId();
        String gravita = b.has("gravitaMinima") ? b.get("gravitaMinima").asText() : att.gravitaMinima();
        if (!RANGO.containsKey(gravita)) {
            throw new IllegalArgumentException("Gravità non valida: usare CRITICAL, WARNING o INFO");
        }
        if (!chat.isEmpty() && !CHAT.matcher(chat).matches()) {
            throw new IllegalArgumentException("Chat ID non valido: un numero (es. -1001234567890) o @nomecanale");
        }
        String token = att.tokenCifrato();
        if (b.path("cancellaToken").asBoolean(false)) {
            token = null;
        } else if (b.has("token") && !b.get("token").isNull() && !b.get("token").asText().isBlank()) {
            String nuovo = b.get("token").asText().trim();
            if (!TOKEN.matcher(nuovo).matches()) {
                throw new IllegalArgumentException("Token del bot non valido: formato 123456789:AA...");
            }
            token = Crypto.encrypt(nuovo);
        }
        if (abilitata && (token == null || chat.isEmpty())) {
            throw new IllegalArgumentException("Per abilitare Telegram servono il token del bot e il chat ID");
        }
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("UPDATE telegram_config SET abilitata = ?, chat_id = ?, "
                     + "token = ?, gravita_minima = ? WHERE id = 1")) {
            ps.setBoolean(1, abilitata);
            ps.setString(2, chat);
            ps.setString(3, token);
            ps.setString(4, gravita);
            ps.executeUpdate();
        }
    }

    private static String tokenInChiaro(Riga r) {
        return r.tokenCifrato() == null ? null : Crypto.decrypt(r.tokenCifrato());
    }

    /**
     * Chiama un metodo dell'API Telegram. Il token sta nell'URL: non va mai riportato nei messaggi di errore.
     * Lancia IllegalStateException con la descrizione restituita da Telegram.
     */
    private static JsonNode api(String token, String metodo, ObjectNode corpo, int timeoutSec) {
        try {
            HttpRequest richiesta = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/" + metodo))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Main.JSON.writeValueAsString(corpo)))
                    .build();
            HttpResponse<String> risposta = HTTP.send(richiesta, HttpResponse.BodyHandlers.ofString());
            JsonNode json = Main.JSON.readTree(risposta.body());
            if (risposta.statusCode() != 200 || !json.path("ok").asBoolean(false)) {
                throw new IllegalStateException("Telegram: " + json.path("description").asText("risposta HTTP " + risposta.statusCode()));
            }
            return json.path("result");
        } catch (IOException e) {
            throw new IllegalStateException("connessione a Telegram non riuscita: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            // si ripristina il flag: chi ha interrotto il thread deve poterlo vedere
            Thread.currentThread().interrupt();
            throw new IllegalStateException("operazione Telegram interrotta");
        }
    }

    /** Messaggio di prova con la configurazione salvata. */
    static String prova() throws SQLException {
        Riga r = leggi();
        String token = tokenInChiaro(r);
        if (token == null || r.chatId().isEmpty()) throw new IllegalStateException("Token del bot o chat ID non configurati");
        ObjectNode corpo = Main.JSON.createObjectNode();
        corpo.put("chat_id", r.chatId());
        corpo.put("text", "DBGuardian: notifica di prova. Se leggi questo messaggio, Telegram è configurato correttamente.");
        api(token, "sendMessage", corpo, 20);
        return "Messaggio inviato";
    }

    /**
     * Notifica un evento registrato dal monitor, se Telegram è attivo, la gravità raggiunge la soglia e
     * non c'è già una notifica in attesa di conferma per lo stesso server e categoria.
     * Chiamato da {@link Store#event}: l'invio avviene in asincrono e un errore non interrompe il monitor.
     */
    static void evento(String gravita, String server, String categoria, String messaggio) {
        INVII.submit(() -> {
            try {
                Riga r = leggi();
                if (!r.abilitata()) return;
                int soglia = RANGO.getOrDefault(r.gravitaMinima(), RANGO.get(CRITICAL));
                boolean sopraSoglia = RANGO.getOrDefault(gravita, 0) >= soglia;
                if (RIPRISTINO.equals(categoria)) {
                    // il ritorno del server chiude le mancate connessioni aperte e si notifica sempre
                    int chiusi = chiudiAvvisiAperti(r, server);
                    if (chiusi > 0 || sopraSoglia) {
                        inviaSemplice(r, "🔁 [RIPRISTINO] " + server + " è di nuovo raggiungibile\n" + messaggio);
                    }
                    return;
                }
                if (!sopraSoglia) return;
                String chiave = server + "|" + categoria;
                if (inAttesaDi(chiave)) return;
                String testo = "[" + gravita + "] " + server + " · " + categoria + "\n" + messaggio;
                long id = registra(chiave, testo);
                try {
                    ObjectNode corpo = Main.JSON.createObjectNode();
                    corpo.put("chat_id", r.chatId());
                    corpo.put("text", testo);
                    corpo.put("disable_web_page_preview", true);
                    corpo.set("reply_markup", pulsanteConferma(id));
                    JsonNode esito = api(tokenInChiaro(r), "sendMessage", corpo, 20);
                    aggiornaMessageId(id, esito.path("message_id").asLong());
                } catch (RuntimeException e) {
                    elimina(id);
                    throw e;
                }
            } catch (Exception e) {
                System.err.println("Notifica Telegram non inviata: " + e.getMessage());
            }
        });
    }

    private static ObjectNode pulsanteConferma(long id) {
        ObjectNode markup = Main.JSON.createObjectNode();
        ArrayNode righe = markup.putArray("inline_keyboard");
        ArrayNode riga = righe.addArray();
        riga.addObject().put("text", "✅ Presa visione").put("callback_data", "ack:" + id);
        return markup;
    }

    private static boolean inAttesaDi(String chiave) throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM telegram_notifiche WHERE chiave = ? AND confermato_il IS NULL")) {
            ps.setString(1, chiave);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static long registra(String chiave, String testo) throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("INSERT INTO telegram_notifiche (chiave, messaggio, inviato_il) VALUES (?, ?, NOW(3))",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, chiave);
            ps.setString(2, testo);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void aggiornaMessageId(long id, long messageId) throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("UPDATE telegram_notifiche SET message_id = ? WHERE id = ?")) {
            ps.setLong(1, messageId);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private static void elimina(long id) {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("DELETE FROM telegram_notifiche WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Telegram: pulizia non riuscita: " + e.getMessage());
        }
    }

    /**
     * Chiude le notifiche di mancata connessione ancora aperte di un server e aggiorna il messaggio su Telegram
     * (senza pulsante, con la nota di ripristino). Restituisce il numero di notifiche chiuse.
     */
    private static int chiudiAvvisiAperti(Riga r, String server) throws SQLException {
        String chiave = server + "|" + PERDITA;
        String token = tokenInChiaro(r);
        List<Map<String, Object>> aperte = new java.util.ArrayList<>();
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT id, messaggio, message_id FROM telegram_notifiche "
                     + "WHERE chiave = ? AND confermato_il IS NULL")) {
            ps.setString(1, chiave);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    aperte.add(Map.of("id", rs.getLong(1), "messaggio", rs.getString(2), "messageId", rs.getLong(3)));
                }
            }
            for (Map<String, Object> n : aperte) {
                try (PreparedStatement up = c.prepareStatement("UPDATE telegram_notifiche SET confermato_il = NOW(3), "
                        + "confermato_da = 'ripristino automatico' WHERE id = ?")) {
                    up.setLong(1, (Long) n.get("id"));
                    up.executeUpdate();
                }
            }
        }
        if (token != null) {
            for (Map<String, Object> n : aperte) {
                try {
                    ObjectNode corpo = Main.JSON.createObjectNode();
                    corpo.put("chat_id", r.chatId());
                    corpo.put("message_id", (Long) n.get("messageId"));
                    corpo.put("text", n.get("messaggio") + "\n\n🔁 Ripristinato automaticamente alle " + LocalTime.now().format(ORA));
                    corpo.putObject("reply_markup").putArray("inline_keyboard");
                    api(token, "editMessageText", corpo, 20);
                } catch (RuntimeException e) {
                    System.err.println("Telegram: aggiornamento messaggio non riuscito: " + e.getMessage());
                }
            }
        }
        return aperte.size();
    }

    /** Invio di un messaggio senza pulsante (per notifiche informative come il ripristino). */
    private static void inviaSemplice(Riga r, String testo) {
        String token = tokenInChiaro(r);
        if (token == null || r.chatId().isEmpty()) return;
        ObjectNode corpo = Main.JSON.createObjectNode();
        corpo.put("chat_id", r.chatId());
        corpo.put("text", testo);
        corpo.put("disable_web_page_preview", true);
        api(token, "sendMessage", corpo, 20);
    }

    // ------------------------------------------------------------ ascolto dei clic

    /** Ciclo di ascolto: interroga Telegram per i clic sui pulsanti finché la notifica è attiva. */
    private static void ciclaAscolto() {
        while (true) {
            try {
                Riga r = leggi();
                String token = tokenInChiaro(r);
                if (!r.abilitata() || token == null) {
                    Thread.sleep(10_000);
                    continue;
                }
                ObjectNode corpo = Main.JSON.createObjectNode();
                corpo.put("timeout", 25);
                corpo.put("offset", offset);
                corpo.putArray("allowed_updates").add("callback_query");
                for (JsonNode u : api(token, "getUpdates", corpo, 40)) {
                    offset = Math.max(offset, u.path("update_id").asLong() + 1);
                    if (u.has("callback_query")) gestisciConferma(token, u.get("callback_query"), r.chatId());
                }
            } catch (Exception e) {
                System.err.println("Telegram ascolto: " + e.getMessage());
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /** Gestisce il clic su "Presa visione": accetta solo dalla chat configurata. */
    private static void gestisciConferma(String token, JsonNode cb, String chatAttesa) throws SQLException {
        String dati = cb.path("data").asText("");
        String idCallback = cb.path("id").asText();
        if (!dati.startsWith("ack:")) return;
        long chat = cb.path("message").path("chat").path("id").asLong();
        if (!chatAttesa.startsWith("@") && !String.valueOf(chat).equals(chatAttesa)) {
            rispondi(token, idCallback, "Chat non autorizzata");
            return;
        }
        long id;
        try {
            id = Long.parseLong(dati.substring(4));
        } catch (NumberFormatException e) {
            return;
        }
        String chi = cb.path("from").path("first_name").asText("utente");
        String utente = cb.path("from").path("username").asText("");
        if (!utente.isEmpty()) chi = chi + " (@" + utente + ")";
        try (Connection c = Store.conn()) {
            int n;
            try (PreparedStatement ps = c.prepareStatement("UPDATE telegram_notifiche SET confermato_il = NOW(3), "
                    + "confermato_da = ? WHERE id = ? AND confermato_il IS NULL")) {
                ps.setString(1, chi);
                ps.setLong(2, id);
                n = ps.executeUpdate();
            }
            if (n == 0) {
                rispondi(token, idCallback, "Già confermata");
                return;
            }
            String testo;
            long messageId;
            try (PreparedStatement ps = c.prepareStatement("SELECT messaggio, message_id FROM telegram_notifiche WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return;
                    testo = rs.getString(1);
                    messageId = rs.getLong(2);
                }
            }
            rispondi(token, idCallback, "Presa visione registrata");
            // il messaggio resta nella chat, senza pulsante, con la conferma in fondo
            ObjectNode corpo = Main.JSON.createObjectNode();
            corpo.put("chat_id", chat);
            corpo.put("message_id", messageId);
            corpo.put("text", testo + "\n\n✅ Presa visione da " + chi + " alle " + LocalTime.now().format(ORA));
            corpo.putObject("reply_markup").putArray("inline_keyboard");
            try {
                api(token, "editMessageText", corpo, 20);
            } catch (RuntimeException e) {
                // la conferma è già salvata: un messaggio non più modificabile (per esempio troppo vecchio) non è un errore
                System.err.println("Telegram: aggiornamento messaggio non riuscito: " + e.getMessage());
            }
        }
    }

    private static void rispondi(String token, String idCallback, String testo) {
        ObjectNode corpo = Main.JSON.createObjectNode();
        corpo.put("callback_query_id", idCallback);
        corpo.put("text", testo);
        try {
            api(token, "answerCallbackQuery", corpo, 10);
        } catch (RuntimeException e) {
            System.err.println("Telegram: risposta al clic non inviata: " + e.getMessage());
        }
    }

}
