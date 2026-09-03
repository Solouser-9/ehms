package ehms.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side sessions. After login the browser receives an unguessable
 * 256-bit token in an HttpOnly cookie; every API call is matched against
 * this table. Sessions expire after 8 hours and live only in memory.
 */
public final class SessionManager {

    public static final String COOKIE_NAME = "EHMS_SESSION";
    public static final long TTL_MILLIS = 8 * 60 * 60 * 1000L;   // 8 hours

    public record Session(String token, String role, String accountId, String name,
                          long createdAt, long expiresAt) {}

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public Session create(String role, String accountId, String name) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long now = System.currentTimeMillis();
        Session s = new Session(token, role, accountId, name, now, now + TTL_MILLIS);
        sessions.put(token, s);
        return s;
    }

    public Session get(String token) {
        if (token == null || token.isBlank()) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (System.currentTimeMillis() >= s.expiresAt()) {
            sessions.remove(token);
            return null;
        }
        return s;
    }

    public boolean destroy(String token) {
        return token != null && sessions.remove(token) != null;
    }

    public int purgeExpired() {
        long now = System.currentTimeMillis();
        int before = sessions.size();
        sessions.values().removeIf(s -> now >= s.expiresAt());
        return before - sessions.size();
    }

    public int activeCount() { return sessions.size(); }

    public static String cookieHeader(String token, boolean secure) {
        String h = COOKIE_NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (TTL_MILLIS / 1000);
        return secure ? h + "; Secure" : h;
    }

    public static String clearCookieHeader(boolean secure) {
        String h = COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";
        return secure ? h + "; Secure" : h;
    }
}