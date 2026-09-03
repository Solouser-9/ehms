package ehms.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing with PBKDF2-HMAC-SHA256 (pure JDK).
 * Stored format: pbkdf2_sha256$<iterations>$<base64url-salt>$<base64url-hash>
 */
public final class PasswordHasher {

    private static final String PREFIX = "pbkdf2_sha256$";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENC = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getDecoder();

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] dk = pbkdf2(password, salt, ITERATIONS, KEY_BITS);
        return PREFIX + ITERATIONS + "$" + ENC.encodeToString(salt) + "$" + ENC.encodeToString(dk);
    }

    public static boolean verify(String password, String stored) {
        if (password == null || stored == null || stored.isEmpty()) return false;
        if (isHashed(stored)) {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) return false;
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = DEC.decode(parts[2]);
                byte[] expected = DEC.decode(parts[3]);
                byte[] actual = pbkdf2(password, salt, iterations, expected.length * 8);
                return MessageDigest.isEqual(expected, actual);
            } catch (IllegalArgumentException badFormat) {
                return false;
            }
        }
        return stored.equals(password);   // legacy plaintext (auto-upgraded at next login)
    }

    public static boolean isHashed(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 hashing failed", e);
        } finally {
            spec.clearPassword();
        }
    }
}