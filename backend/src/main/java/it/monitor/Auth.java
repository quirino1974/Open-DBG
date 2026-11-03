package it.monitor;

import com.sun.net.httpserver.HttpExchange;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Utenti, profili di permesso, server assegnati e sessioni di accesso.
 *
 * <p>Ogni utente ha un profilo. Il profilo determina i permessi (vedi {@link #PERMESSI_PER_PROFILO}); i server
 * visibili sono un ambito separato: l'amministratore vede tutti i server, gli altri solo quelli assegnati.
 *
 * <p>La sessione è un token casuale nel cookie HttpOnly; le sessioni vivono in memoria e scadono dopo {@link #TTL_MS}.
 */
final class Auth {

    static final String COOKIE = "dbg_session";
    static final long TTL_MS = 8L * 60 * 60 * 1000;
    static final int MIN_PASSWORD_LENGTH = 6;

    // Profili
    static final String UTENTE = "utente";
    static final String AMMINISTRATORE = "amministratore";
    /** Due ruoli: l'amministratore configura tutto; l'utente vede e fa le verifiche sui server assegnati. */
    static final List<String> PROFILI = List.of(UTENTE, AMMINISTRATORE);

    // Permessi: monitoraggio (dashboard, eventi, storico, report), diagnostica (test connessione, piano EXPLAIN),
    // configurazione (server, utenti, storico configurazioni)
    static final String P_MONITORAGGIO = "monitoraggio";
    static final String P_DIAGNOSTICA = "diagnostica";
    static final String P_CONFIGURAZIONE = "configurazione";

    static final Map<String, Set<String>> PERMESSI_PER_PROFILO = Map.of(
            UTENTE, Set.of(P_MONITORAGGIO, P_DIAGNOSTICA),
            AMMINISTRATORE, Set.of(P_MONITORAGGIO, P_DIAGNOSTICA, P_CONFIGURAZIONE));

    private static final Pattern NOME_UTENTE = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private static final SecureRandom RND = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    /** Utente collegato: id, nome e profilo letti dal database a ogni richiesta. */
    record Principal(long id, String username, String profilo, boolean cambioPassword) {
        boolean ha(String permesso) {
            return PERMESSI_PER_PROFILO.getOrDefault(profilo, Set.of()).contains(permesso);
        }

        /** Vede tutti i server (solo amministratore). */
        boolean vedeTuttiISever() {
            return AMMINISTRATORE.equals(profilo);
        }
    }

    /** Utente in elenco, con profilo e server assegnati. */
    record Utente(long id, String username, String profilo, List<String> servers) {
    }

    private record Session(String user, long expiresAt) {
    }

    private Auth() {
    }

    // ------------------------------------------------------------ avvio

    /**
     * Crea le tabelle utenti e l'account admin/admin se non esiste alcun utente.
     * Migra il vecchio flag is_admin nel profilo amministratore.
     */
    static void init() throws SQLException {
        try (Connection c = Store.conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS app_users ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " username VARCHAR(64) NOT NULL UNIQUE,"
                    + " password_hash VARCHAR(255) NOT NULL,"
                    + " created_at DATETIME(3) NOT NULL"
                    + ") ENGINE=InnoDB");
            st.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS profilo VARCHAR(16) NOT NULL DEFAULT 'utente'");
            // Obbligo di cambiare la password al prossimo accesso (account nuovi, password reimpostate, admin/admin)
            st.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS cambio_password TINYINT(1) NOT NULL DEFAULT 0");
            // Ruoli ridotti a due: osservatore e operatore diventano utente
            st.executeUpdate("UPDATE app_users SET profilo = 'utente' WHERE profilo IN ('osservatore', 'operatore')");
            st.execute("CREATE TABLE IF NOT EXISTS app_user_servers ("
                    + " user_id BIGINT NOT NULL,"
                    + " server_id VARCHAR(36) NOT NULL,"
                    + " PRIMARY KEY (user_id, server_id)"
                    + ") ENGINE=InnoDB");
            if (colonnaEsiste(c, "is_admin")) {
                st.executeUpdate("UPDATE app_users SET profilo = 'amministratore' WHERE is_admin = 1");
                st.execute("ALTER TABLE app_users DROP COLUMN is_admin");
                System.out.println("Migrazione permessi: flag admin convertito nel profilo amministratore");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO app_users (username, password_hash, profilo, created_at) "
                    + "SELECT 'admin', ?, 'amministratore', NOW(3) FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM app_users)")) {
                ps.setString(1, Crypto.hashPassword("admin"));
                if (ps.executeUpdate() > 0) System.out.println("Creato utente iniziale admin: cambiare la password al primo accesso");
            }
            // L'account admin con la password predefinita deve cambiarla al primo accesso
            if (checkPassword("admin", "admin")) {
                st.executeUpdate("UPDATE app_users SET cambio_password = 1 WHERE username = 'admin'");
            }
            // Deve restare almeno un amministratore
            st.executeUpdate("UPDATE app_users SET profilo = 'amministratore' WHERE username = 'admin' AND NOT EXISTS "
                    + "(SELECT 1 FROM (SELECT id FROM app_users WHERE profilo = 'amministratore') x)");
        }
    }

    private static boolean colonnaEsiste(Connection c, String colonna) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_users' AND COLUMN_NAME = ?")) {
            ps.setString(1, colonna);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    // ------------------------------------------------------------ sessioni

    /** Verifica le credenziali e apre una sessione. Restituisce il token, oppure null se non valide. */
    static String login(String username, String password) throws SQLException {
        eliminaSessioniScadute();
        if (!checkPassword(username, password)) return null;
        byte[] raw = new byte[32];
        RND.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        SESSIONS.put(token, new Session(username, System.currentTimeMillis() + TTL_MS));
        return token;
    }

    /** Utente collegato alla richiesta (profilo incluso), oppure null se la sessione non è valida. */
    static Principal principal(HttpExchange ex) throws SQLException {
        String token = cookie(ex, COOKIE);
        if (token == null) return null;
        Session s = SESSIONS.get(token);
        if (s == null) return null;
        if (s.expiresAt() < System.currentTimeMillis()) {
            SESSIONS.remove(token);
            return null;
        }
        return find(s.user());
    }

    static void logout(HttpExchange ex) {
        String token = cookie(ex, COOKIE);
        if (token != null) SESSIONS.remove(token);
    }

    /**
     * Cookie di sessione. Con connessione HTTPS (o COOKIE_SECURE=true) il cookie ha l'attributo Secure,
     * così il browser non lo invia mai in chiaro.
     */
    static String sessionCookie(String token, boolean sicuro) {
        return COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (TTL_MS / 1000) + (sicuro ? "; Secure" : "");
    }

    static String clearCookie(boolean sicuro) {
        return COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + (sicuro ? "; Secure" : "");
    }

    static boolean connessioneSicura(HttpExchange ex) {
        return "https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto"))
                || Boolean.parseBoolean(Main.env("COOKIE_SECURE", "false"));
    }

    // ------------------------------------------------------------ limite dei tentativi di accesso

    /** Tentativi di accesso falliti per chiave (utente o IP), ordinati dal più vecchio. */
    private static final Map<String, Deque<Long>> FALLIMENTI = new ConcurrentHashMap<>();
    static final int MAX_TENTATIVI = 4;
    static final long FINESTRA_MS = 5L * 60 * 1000;

    /**
     * Secondi di attesa prima di poter tentare di nuovo, oppure 0 se l'accesso è consentito.
     * Il blocco scatta dopo {@link #MAX_TENTATIVI} tentativi falliti nella finestra di {@link #FINESTRA_MS}
     * e dura fino a cinque minuti dall'ultimo fallimento.
     */
    static long attesaLogin(String utente, String ip) {
        long attesa = 0;
        for (String chiave : chiavi(utente, ip)) {
            Deque<Long> d = FALLIMENTI.get(chiave);
            if (d == null) continue;
            synchronized (d) {
                long adesso = System.currentTimeMillis();
                d.removeIf(t -> adesso - t > FINESTRA_MS);
                if (d.size() >= MAX_TENTATIVI) {
                    long residuo = d.peekLast() + FINESTRA_MS - adesso;
                    attesa = Math.max(attesa, (residuo + 999) / 1000);
                }
            }
        }
        return attesa;
    }

    static void fallimentoLogin(String utente, String ip) {
        long adesso = System.currentTimeMillis();
        for (String chiave : chiavi(utente, ip)) {
            Deque<Long> d = FALLIMENTI.computeIfAbsent(chiave, k -> new ArrayDeque<>());
            synchronized (d) {
                d.removeIf(t -> adesso - t > FINESTRA_MS);
                d.addLast(adesso);
                while (d.size() > MAX_TENTATIVI) d.removeFirst();
            }
        }
    }

    /** Dopo un accesso riuscito si azzera il contatore dell'utente, non quello dell'IP. */
    static void successoLogin(String utente) {
        FALLIMENTI.remove("utente:" + utente.toLowerCase());
    }

    private static List<String> chiavi(String utente, String ip) {
        return List.of("utente:" + (utente == null ? "" : utente.toLowerCase()), "ip:" + ip);
    }

    /** Dati dell'utente per il frontend: nome, profilo e permessi (per mostrare o nascondere le funzioni). */
    static Map<String, Object> info(Principal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", p.username());
        m.put("profilo", p.profilo());
        m.put("permessi", new ArrayList<>(PERMESSI_PER_PROFILO.getOrDefault(p.profilo(), Set.of())));
        m.put("cambioPassword", p.cambioPassword());
        return m;
    }

    /** Dati dell'utente appena autenticato (usati dalla risposta di login). */
    static Map<String, Object> infoDi(String username) throws SQLException {
        return info(find(username));
    }

    /** Rimuove le sessioni scadute: altrimenti restano in memoria anche se non vengono più usate. */
    private static void eliminaSessioniScadute() {
        long adesso = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(e -> e.getValue().expiresAt() < adesso);
    }

    private static void chiudiSessioniDi(String username) {
        SESSIONS.entrySet().removeIf(e -> e.getValue().user().equals(username));
    }

    // ------------------------------------------------------------ password

    /** Cambio della password da parte dell'utente stesso, dopo aver verificato quella attuale. */
    static void changeOwnPassword(String username, String current, String next) throws SQLException {
        controllaLunghezza(next);
        if (!checkPassword(username, current)) {
            throw new IllegalArgumentException("Password attuale non corretta");
        }
        if (next.equals(current)) {
            throw new IllegalArgumentException("La nuova password deve essere diversa da quella attuale");
        }
        long id = find(username).id();
        impostaHash(id, next);
        impostaCambioObbligatorio(id, false);
    }

    // ------------------------------------------------------------ utenti (configurazione)

    static List<Utente> listUsers() throws SQLException {
        List<Utente> out = new ArrayList<>();
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT id, username, profilo FROM app_users ORDER BY username");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("id");
                out.add(new Utente(id, rs.getString("username"), rs.getString("profilo"), serversOf(c, id)));
            }
        }
        return out;
    }

    static Utente createUser(String username, String password, String profilo, List<String> servers) throws SQLException {
        String nome = username == null ? "" : username.trim();
        if (!NOME_UTENTE.matcher(nome).matches()) {
            throw new IllegalArgumentException("Username: da 3 a 64 caratteri, lettere, numeri, punto, trattino o underscore");
        }
        controllaLunghezza(password);
        controllaProfilo(profilo);
        try (Connection c = Store.conn()) {
            c.setAutoCommit(false);
            try {
                long id;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO app_users (username, password_hash, profilo, created_at) "
                        + "VALUES (?, ?, ?, NOW(3))", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, nome);
                    ps.setString(2, Crypto.hashPassword(password));
                    ps.setString(3, profilo);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        id = keys.getLong(1);
                    }
                }
                salvaServers(c, id, profilo, servers);
                // la password la sceglie chi crea l'account: il nuovo utente la cambia al primo accesso
                try (PreparedStatement ps = c.prepareStatement("UPDATE app_users SET cambio_password = 1 WHERE id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                if (e.getErrorCode() == 1062) throw new IllegalArgumentException("Esiste già un utente con questo username");
                throw e;
            }
        }
        return listUsers().stream().filter(u -> u.username().equals(nome)).findFirst().orElseThrow();
    }

    /** Aggiorna profilo e server assegnati. Non può togliere l'ultimo amministratore. */
    static void updateUser(long id, String profilo, List<String> servers) throws SQLException {
        controllaProfilo(profilo);
        Utente u = trovaUtente(id);
        if (AMMINISTRATORE.equals(u.profilo()) && !AMMINISTRATORE.equals(profilo) && contaAmministratori() <= 1) {
            throw new IllegalArgumentException("Non si può togliere il profilo amministratore all'ultimo amministratore");
        }
        try (Connection c = Store.conn()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("UPDATE app_users SET profilo = ? WHERE id = ?")) {
                    ps.setString(1, profilo);
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
                salvaServers(c, id, profilo, servers);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Reimpostazione della password da parte dell'amministratore: chiude le sessioni aperte dell'utente. */
    static void resetPassword(long id, String password) throws SQLException {
        controllaLunghezza(password);
        Utente u = trovaUtente(id);
        impostaHash(id, password);
        impostaCambioObbligatorio(id, true);
        chiudiSessioniDi(u.username());
    }

    static void deleteUser(long id, Principal me) throws SQLException {
        if (me.id() == id) throw new IllegalArgumentException("Non si può eliminare il proprio utente");
        Utente u = trovaUtente(id);
        if (AMMINISTRATORE.equals(u.profilo()) && contaAmministratori() <= 1) {
            throw new IllegalArgumentException("Non si può eliminare l'ultimo amministratore");
        }
        try (Connection c = Store.conn()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM app_user_servers WHERE user_id = ?");
                 PreparedStatement del = c.prepareStatement("DELETE FROM app_users WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
                del.setLong(1, id);
                del.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
        chiudiSessioniDi(u.username());
    }

    /** Rimuove un server da tutte le assegnazioni, quando viene eliminato dall'anagrafica. */
    static void dimenticaServer(String serverId) throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("DELETE FROM app_user_servers WHERE server_id = ?")) {
            ps.setString(1, serverId);
            ps.executeUpdate();
        }
    }

    /** Server visibili all'utente: null se vede tutti i server (amministratore), altrimenti quelli assegnati. */
    static Set<String> serversVisibili(Principal me) throws SQLException {
        if (me.vedeTuttiISever()) return null;
        try (Connection c = Store.conn()) {
            return new LinkedHashSet<>(serversOf(c, me.id()));
        }
    }

    // ------------------------------------------------------------ helper

    private static Utente trovaUtente(long id) throws SQLException {
        return listUsers().stream().filter(x -> x.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Utente non trovato"));
    }

    private static void controllaLunghezza(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("La password deve avere almeno " + MIN_PASSWORD_LENGTH + " caratteri");
        }
    }

    private static void controllaProfilo(String profilo) {
        if (!PROFILI.contains(profilo)) {
            throw new IllegalArgumentException("Profilo non valido: " + profilo);
        }
    }

    private static long contaAmministratori() throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM app_users WHERE profilo = 'amministratore'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Principal find(String username) throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT id, username, profilo, cambio_password FROM app_users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Principal(rs.getLong("id"), rs.getString("username"), rs.getString("profilo"),
                        rs.getBoolean("cambio_password")) : null;
            }
        }
    }

    private static void impostaCambioObbligatorio(long id, boolean obbligatorio) throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("UPDATE app_users SET cambio_password = ? WHERE id = ?")) {
            ps.setBoolean(1, obbligatorio);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private static void impostaHash(long id, String password) throws SQLException {
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("UPDATE app_users SET password_hash = ? WHERE id = ?")) {
            ps.setString(1, Crypto.hashPassword(password));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private static List<String> serversOf(Connection c, long userId) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT server_id FROM app_user_servers WHERE user_id = ? ORDER BY server_id")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    /**
     * Salva i server assegnati. Per l'amministratore l'ambito è implicito (tutti i server), quindi non si salva nulla.
     */
    private static void salvaServers(Connection c, long userId, String profilo, List<String> servers) throws SQLException {
        try (PreparedStatement del = c.prepareStatement("DELETE FROM app_user_servers WHERE user_id = ?")) {
            del.setLong(1, userId);
            del.executeUpdate();
        }
        if (AMMINISTRATORE.equals(profilo) || servers == null || servers.isEmpty()) return;
        try (PreparedStatement ins = c.prepareStatement("INSERT IGNORE INTO app_user_servers (user_id, server_id) VALUES (?, ?)")) {
            for (String sid : new LinkedHashSet<>(servers)) {
                ins.setLong(1, userId);
                ins.setString(2, sid);
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    private static boolean checkPassword(String username, String password) throws SQLException {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) return false;
        String stored = null;
        try (Connection c = Store.conn();
             PreparedStatement ps = c.prepareStatement("SELECT password_hash FROM app_users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) stored = rs.getString(1);
            }
        }
        return stored != null && Crypto.verifyPassword(password, stored);
    }

    private static String cookie(HttpExchange ex, String name) {
        String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    /** Rappresentazione JSON di un utente, con i permessi del suo profilo. */
    static Map<String, Object> toMap(Utente u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("username", u.username());
        m.put("profilo", u.profilo());
        m.put("permessi", new ArrayList<>(PERMESSI_PER_PROFILO.getOrDefault(u.profilo(), Set.of())));
        m.put("servers", AMMINISTRATORE.equals(u.profilo()) ? List.of() : u.servers());
        return m;
    }
}
