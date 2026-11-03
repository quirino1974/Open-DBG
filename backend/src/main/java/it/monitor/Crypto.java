package it.monitor;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifratura delle password salvate nel database del monitor (AES-256-GCM) e hash delle password
 * degli utenti dell'applicazione (PBKDF2-HMAC-SHA256). La chiave di cifratura viene dalla variabile
 * d'ambiente APP_SECRET_KEY e non è mai scritta nel database.
 */
final class Crypto {

    private static final String PREFIX = "enc:v1:";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RND = new SecureRandom();
    private static volatile SecretKey key;

    private Crypto() {
    }

    /** Imposta la chiave di cifratura; senza chiave il monitor non parte. */
    static void init(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Variabile APP_SECRET_KEY non impostata: impossibile cifrare le password");
        }
        try {
            byte[] k = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            key = new SecretKeySpec(k, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** Cifra un valore; il risultato contiene IV e tag di autenticazione. */
    static String encrypt(String plain) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RND.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct);
            return PREFIX + Base64.getEncoder().encodeToString(buf.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cifratura non riuscita", e);
        }
    }

    /** Decifra un valore prodotto da {@link #encrypt}. I valori senza prefisso sono restituiti così come sono. */
    static String decrypt(String stored) {
        if (!isEncrypted(stored)) return stored;
        try {
            ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(stored.substring(PREFIX.length())));
            byte[] iv = new byte[GCM_IV_BYTES];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Password salvata non decifrabile: APP_SECRET_KEY diversa da quella usata per salvarla", e);
        }
    }

    /** Hash per le password degli utenti: formato pbkdf2$iterazioni$sale$hash. */
    static String hashPassword(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RND.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, PBKDF2_ITERATIONS);
        Base64.Encoder enc = Base64.getEncoder();
        return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + enc.encodeToString(salt) + "$" + enc.encodeToString(hash);
    }

    static boolean verifyPassword(String password, String stored) {
        if (stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !"pbkdf2".equals(p[0])) return false;
        byte[] expected = Base64.getDecoder().decode(p[3]);
        byte[] actual = pbkdf2(password, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Hash della password non riuscito", e);
        }
    }

    private static SecretKey requireKey() {
        if (key == null) throw new IllegalStateException("Chiave di cifratura non inizializzata");
        return key;
    }
}
