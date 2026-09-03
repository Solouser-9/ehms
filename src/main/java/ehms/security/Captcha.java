package ehms.security;

import ehms.util.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ALTCHA-style proof-of-work: the client must find n such that
 * SHA-256(salt + ":" + n) starts with `difficulty` hex zeros. Each salt is
 * single-use and expires after 10 minutes; difficulty 3 averages ~4096 hashes.
 * Difficulty 0 disables (scripted/CI use).
 */
public final class Captcha {

    private static final long TTL_MS = 10 * 60_000L;

    private final int difficulty;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> issued = new ConcurrentHashMap<>();

    public Captcha(int difficulty) { this.difficulty = Math.max(0, Math.min(5, difficulty)); }

    public boolean enabled() { return difficulty > 0; }

    public Map<String, Object> challenge() {
        if (!enabled()) return Json.obj("enabled", false);
        byte[] b = new byte[12];
        random.nextBytes(b);
        String salt = HexFormat.of().formatHex(b);
        issued.put(salt, System.currentTimeMillis() + TTL_MS);
        if (issued.size() > 1000) issued.values().removeIf(exp -> exp < System.currentTimeMillis());
        return Json.obj("enabled", true, "salt", salt, "difficulty", difficulty);
    }

    public boolean verify(String salt, long answer) {
        if (!enabled()) return true;
        if (salt == null) return false;
        Long expiresAt = issued.remove(salt);
        if (expiresAt == null || expiresAt < System.currentTimeMillis()) return false;
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((salt + ":" + answer).getBytes(StandardCharsets.UTF_8)));
            for (int i = 0; i < difficulty; i++) if (hex.charAt(i) != '0') return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}