package it.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Configurazione dell'analisi Insight tramite IA (Anthropic o OpenAI). Una sola riga nella tabella
 * ai_config. Le chiavi API sono cifrate con {@link Crypto} e non vengono mai restituite per intero.
 * Prima dell'invio al provider il report viene ridotto: niente indirizzi di rete, solo nomi, esiti e raccomandazioni.
 */
final class AiConfig {
    private AiConfig() {}

    static final String ANTHROPIC = "anthropic";
    static final String OPENAI = "openai";
    /** Usa entrambi i provider: il commento è la somma delle due risposte. */
    static final String ENTRAMBI = "entrambi";
    static final String MODELLO_ANTHROPIC_PREDEFINITO = "claude-sonnet-5-5";
    static final String MODELLO_OPENAI_PREDEFINITO = "gpt-4.1-mini"; // da verificare sul listino OpenAI

    private static final Pattern MODELLO = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern CHIAVE = Pattern.compile("\\S{8,512}");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private static final String ISTRUZIONI = """
            Sei un DBA esperto di MySQL, MariaDB e PostgreSQL. Ricevi l'esito di un health check giornaliero \
            calcolato da un monitor: per ogni server il punteggio e i controlli con livello (bad, warn, info, ok), \
            esito e raccomandazione. Rispondi in italiano, in questo ordine:
            1. Sintesi in al massimo tre frasi.
            2. Problemi prioritari, dal più grave, indicando il server.
            3. Azioni consigliate, concrete e in ordine di urgenza.
            Usa solo i dati ricevuti: non inventare valori, non suggerire modifiche senza una verifica preventiva, \
            e segnala quando un dato non basta per una conclusione.""";

    /** Creazione della tabella e della riga di configurazione (una sola). */
    static void init() throws SQLException {
        try (Connection c = Store.conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS ai_config ("
                    + " id INT PRIMARY KEY,"
                    + " abilitata TINYINT(1) NOT NULL DEFAULT 0,"
                    + " provider VARCHAR(16) NOT NULL DEFAULT 'anthropic',"
                    + " modello_anthropic VARCHAR(128) NOT NULL DEFAULT '" + MODELLO_ANTHROPIC_PREDEFINITO + "',"
                    + " modello_openai VARCHAR(128) NOT NULL DEFAULT '" + MODELLO_OPENAI_PREDEFINITO + "',"
                    + " chiave_anthropic TEXT,"
                    + " chiave_openai TEXT,"
                    + " aggiornato_il DATETIME(3) NOT NULL"
                    + ") ENGINE=InnoDB");
            st.execute("INSERT IGNORE INTO ai_config (id, aggiornato_il) VALUES (1, NOW(3))");
        }
    }

    /** Riga corrente della configurazione. */
    private record Riga(boolean abilitata, String provider, String modelloAnthropic, String modelloOpenai,
                        String chiaveAnthropic, String chiaveOpenai) {
        /** Stessa configurazione con un solo provider da usare (per le chiamate singole). */
        Riga conProvider(String p) {
            return new Riga(abilitata, p, modelloAnthropic, modelloOpenai, chiaveAnthropic, chiaveOpenai);
        }
    }

    /** Provider da interrogare per una scelta: uno solo, oppure entrambi. */
    static List<String> providersAttivi(String scelta) {
        return ENTRAMBI.equals(scelta) ? List.of(ANTHROPIC, OPENAI) : List.of(scelta);
    }

    private static String nomeProvider(String p) {
        return ANTHROPIC.equals(p) ? "Anthropic" : "OpenAI";
    }

    private static Riga leggi() throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT abilitata, provider, modello_anthropic, modello_openai, "
                     + "chiave_anthropic, chiave_openai FROM ai_config WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("Configurazione IA mancante");
            return new Riga(rs.getBoolean(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6));
        }
    }

    /** True se l'analisi IA è abilitata e il provider scelto ha una chiave. */
    static boolean abilitata() {
        try {
            Riga r = leggi();
            return r.abilitata() && providersAttivi(r.provider()).stream().allMatch(p -> chiaveDi(r, p) != null);
        } catch (SQLException | IllegalStateException e) {
            // IllegalStateException: chiave salvata non decifrabile (APP_SECRET_KEY diversa)
            return false;
        }
    }

    /** Etichetta del provider attivo con il suo modello, es. "Anthropic · claude-sonnet-5-5". */
    static String etichettaProvider() throws SQLException {
        Riga r = leggi();
        return providersAttivi(r.provider()).stream()
                .map(p -> nomeProvider(p) + " · " + (ANTHROPIC.equals(p) ? r.modelloAnthropic() : r.modelloOpenai()))
                .collect(java.util.stream.Collectors.joining(" + "));
    }

    /** Stato da mostrare in interfaccia: nessuna chiave in chiaro, solo la presenza e le ultime 4 cifre. */
    static Map<String, Object> stato() throws SQLException {
        Riga r = leggi();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("abilitata", r.abilitata());
        m.put("provider", r.provider());
        m.put("modelloAnthropic", r.modelloAnthropic());
        m.put("modelloOpenai", r.modelloOpenai());
        m.put("anthropicConfigurata", r.chiaveAnthropic() != null);
        m.put("anthropicMascherata", maschera(r.chiaveAnthropic()));
        m.put("openaiConfigurata", r.chiaveOpenai() != null);
        m.put("openaiMascherata", maschera(r.chiaveOpenai()));
        m.put("modelliPredefiniti", Map.of(ANTHROPIC, MODELLO_ANTHROPIC_PREDEFINITO, OPENAI, MODELLO_OPENAI_PREDEFINITO));
        return m;
    }

    /**
     * Salva la configurazione. Campi assenti o vuoti lasciano invariato il valore salvato; una chiave
     * non vuota la sostituisce; {@code cancellaChiaveAnthropic} / {@code cancellaChiaveOpenai} la rimuovono.
     */
    static void salva(JsonNode b) throws SQLException {
        Riga att = leggi();
        boolean abilitata = b.has("abilitata") ? b.get("abilitata").asBoolean() : att.abilitata();
        String provider = b.has("provider") ? b.get("provider").asText() : att.provider();
        if (!ANTHROPIC.equals(provider) && !OPENAI.equals(provider) && !ENTRAMBI.equals(provider)) {
            throw new IllegalArgumentException("Provider non valido: scegliere Anthropic, OpenAI o entrambi");
        }
        String modAnt = testo(b, "modelloAnthropic", att.modelloAnthropic());
        String modOai = testo(b, "modelloOpenai", att.modelloOpenai());
        if (!MODELLO.matcher(modAnt).matches() || !MODELLO.matcher(modOai).matches()) {
            throw new IllegalArgumentException("Nome del modello non valido (lettere, cifre, . _ : -)");
        }
        String chiaveAnt = aggiorna(b, "chiaveAnthropic", "cancellaChiaveAnthropic", att.chiaveAnthropic(), "Anthropic");
        String chiaveOai = aggiorna(b, "chiaveOpenai", "cancellaChiaveOpenai", att.chiaveOpenai(), "OpenAI");

        Riga nuova = new Riga(abilitata, provider, modAnt, modOai, chiaveAnt, chiaveOai);
        if (abilitata) {
            for (String p : providersAttivi(provider)) {
                if (chiaveDi(nuova, p) == null) {
                    throw new IllegalArgumentException("Per abilitare l'analisi IA inserire la chiave API di " + nomeProvider(p));
                }
            }
        }
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("UPDATE ai_config SET abilitata = ?, provider = ?, "
                     + "modello_anthropic = ?, modello_openai = ?, chiave_anthropic = ?, chiave_openai = ?, "
                     + "aggiornato_il = NOW(3) WHERE id = 1")) {
            ps.setBoolean(1, abilitata);
            ps.setString(2, provider);
            ps.setString(3, modAnt);
            ps.setString(4, modOai);
            ps.setString(5, chiaveAnt);
            ps.setString(6, chiaveOai);
            ps.executeUpdate();
        }
    }

    /**
     * Analisi del report Insight tramite il provider scelto. Restituisce il testo prodotto dal modello.
     * Lancia IllegalStateException con il motivo se il provider non risponde o non è configurato.
     */
    static String analizza(List<Object> server, String generatoIl) throws SQLException, com.fasterxml.jackson.core.JsonProcessingException {
        Riga r = leggi();
        ObjectNode sintesi = Main.JSON.createObjectNode();
        sintesi.put("generatoIl", generatoIl);
        ArrayNode srv = sintesi.putArray("server");
        for (Object o : server) {
            if (!(o instanceof Map<?, ?> mappa)) continue;
            ObjectNode n = srv.addObject();
            n.put("nome", Main.str(mappa.get("nome")));
            n.put("motore", Main.str(mappa.get("motore")));
            n.put("versione", Main.str(mappa.get("versione")));
            n.put("punteggio", Main.num(mappa.get("punteggio")));
            ArrayNode ctl = n.putArray("controlli");
            for (Object co : Monitor.list(mappa.get("controlli"))) {
                Map<String, Object> c = Monitor.map(co);
                ObjectNode x = ctl.addObject();
                x.put("controllo", Main.str(c.get("controllo")));
                x.put("livello", Main.str(c.get("livello")));
                x.put("esito", Main.str(c.get("esito")));
                x.put("raccomandazione", Main.str(c.get("raccomandazione")));
            }
        }
        String dati = Main.JSON.writeValueAsString(sintesi);
        return chiamaScelti(r, ISTRUZIONI, "Esito dell'health check:\n" + dati);
    }

    /** Prova di connessione con il provider scelto: chiede una risposta di una parola. */
    static String prova() throws SQLException {
        return chiamaScelti(leggi(), "Rispondi solo con la parola OK.", "Test di connessione.");
    }

    // ------------------------------------------------------------ provider

    private static String chiama(Riga r, String chiave, String sistema, String utente) {
        try {
            HttpRequest richiesta;
            if (ANTHROPIC.equals(r.provider())) {
                ObjectNode corpo = Main.JSON.createObjectNode();
                corpo.put("model", r.modelloAnthropic());
                corpo.put("max_tokens", 1500);
                corpo.put("system", sistema);
                corpo.putArray("messages").addObject().put("role", "user").put("content", utente);
                richiesta = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                        .timeout(TIMEOUT)
                        .header("content-type", "application/json")
                        .header("x-api-key", chiave)
                        .header("anthropic-version", "2023-06-01")
                        .POST(HttpRequest.BodyPublishers.ofString(Main.JSON.writeValueAsString(corpo)))
                        .build();
            } else {
                ObjectNode corpo = Main.JSON.createObjectNode();
                corpo.put("model", r.modelloOpenai());
                corpo.put("max_completion_tokens", 1500);
                ArrayNode msg = corpo.putArray("messages");
                msg.addObject().put("role", "system").put("content", sistema);
                msg.addObject().put("role", "user").put("content", utente);
                richiesta = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                        .timeout(TIMEOUT)
                        .header("content-type", "application/json")
                        .header("authorization", "Bearer " + chiave)
                        .POST(HttpRequest.BodyPublishers.ofString(Main.JSON.writeValueAsString(corpo)))
                        .build();
            }
            HttpResponse<String> risposta = HTTP.send(richiesta, HttpResponse.BodyHandlers.ofString());
            JsonNode json = Main.JSON.readTree(risposta.body());
            if (risposta.statusCode() / 100 != 2) {
                String msg = json.path("error").path("message").asText("risposta HTTP " + risposta.statusCode());
                throw new IllegalStateException(ANTHROPIC.equals(r.provider()) ? "Anthropic: " + msg : "OpenAI: " + msg);
            }
            String testo = ANTHROPIC.equals(r.provider())
                    ? testoBlocchi(json.path("content"))
                    : json.path("choices").path(0).path("message").path("content").asText("");
            if (testo.isBlank()) throw new IllegalStateException("Risposta vuota dal provider");
            return testo.strip();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Chiamata al provider non riuscita: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()), e);
        }
    }

    /**
     * Chiama tutti i provider scelti. Con un solo provider restituisce la sua risposta; con entrambi
     * unisce le due risposte, ciascuna con il nome del provider. Un provider in errore non blocca l'altro:
     * l'errore è riportato nel testo. Se tutti falliscono, lancia l'errore del primo.
     */
    private static String chiamaScelti(Riga r, String sistema, String utente) {
        List<String> ps = providersAttivi(r.provider());
        StringBuilder sb = new StringBuilder();
        IllegalStateException primo = null;
        int riusciti = 0;
        for (String p : ps) {
            String chiave = chiaveDi(r, p);
            if (chiave == null) throw new IllegalStateException("Chiave API di " + nomeProvider(p) + " non configurata");
            try {
                String testo = chiama(r.conProvider(p), chiave, sistema, utente);
                if (ps.size() > 1) sb.append(nomeProvider(p)).append(":\n");
                sb.append(testo).append("\n\n");
                riusciti++;
            } catch (IllegalStateException e) {
                if (primo == null) primo = e;
                if (ps.size() > 1) sb.append(nomeProvider(p)).append(": errore: ").append(e.getMessage()).append("\n\n");
            }
        }
        if (riusciti == 0) throw primo;
        return sb.toString().strip();
    }

    private static String testoBlocchi(JsonNode blocchi) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : blocchi) {
            if ("text".equals(b.path("type").asText())) sb.append(b.path("text").asText());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ utilità

    /** Chiave in chiaro del provider scelto: in tabella è cifrata, quindi va sempre decifrata prima dell'uso. */
    private static String chiaveDi(Riga r, String provider) {
        return chiaveInChiaro(ANTHROPIC.equals(provider) ? r.chiaveAnthropic() : r.chiaveOpenai());
    }

    /** Valore in chiaro di una chiave salvata (cifrata), oppure null se assente. */
    private static String chiaveInChiaro(String salvata) {
        return salvata == null ? null : Crypto.decrypt(salvata);
    }

    private static String maschera(String salvata) {
        String in = chiaveInChiaro(salvata);
        if (in == null || in.length() < 4) return null;
        return "…" + in.substring(in.length() - 4);
    }

    private static String testo(JsonNode b, String campo, String predefinito) {
        if (!b.has(campo) || b.get(campo).isNull()) return predefinito;
        String v = b.get(campo).asText().trim();
        return v.isEmpty() ? predefinito : v;
    }

    /** Aggiorna o rimuove una chiave; restituisce il valore da salvare (cifrato) oppure null. */
    private static String aggiorna(JsonNode b, String campo, String campoCancella, String attuale, String nome) {
        if (b.path(campoCancella).asBoolean(false)) return null;
        String nuova = b.has(campo) && !b.get(campo).isNull() ? b.get(campo).asText().trim() : "";
        if (nuova.isEmpty()) return attuale;
        if (!CHIAVE.matcher(nuova).matches()) {
            throw new IllegalArgumentException("Chiave API " + nome + " non valida");
        }
        return Crypto.encrypt(nuova);
    }
}
