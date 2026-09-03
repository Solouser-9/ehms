package ehms.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection:
 *  - an account (role + email) is locked for 5 minutes after 5 failed logins
 *  - an IP address is locked for 5 minutes after 15 failed logins (any account)
 */
public final class LoginGuard {

    private static final int MAX_ACCOUNT_FAILURES = 5;
    private static final int MAX_IP_FAILURES = 15;
    private static final long LOCKOUT_MILLIS = 5 * 60 * 1000L;
    private static final long RETENTION_MILLIS = 60 * 60 * 1000L;

    private static final class Attempts {
        int failures;
        long lastFailureAt;
        long lockedUntil;
    }

    private final Map<String, Attempts> byAccount = new ConcurrentHashMap<>();
    private final Map<String, Attempts> byIp = new ConcurrentHashMap<>();

    public static final class LockedException extends RuntimeException {
        public LockedException(long retryInSeconds) {
            super("Too many failed login attempts. Please try again in " + human(retryInSeconds) + ".");
        }
        private static String human(long seconds) {
            long m = seconds / 60, s = seconds % 60;
            if (m <= 0) return s + " second" + (s == 1 ? "" : "s");
            return m + " minute" + (m == 1 ? "" : "s")
                    + (s > 0 ? " " + s + " second" + (s == 1 ? "" : "s") : "");
        }
    }

    public void checkAllowed(String role, String email, String ip) {
        long now = System.currentTimeMillis();
        long wait = Math.max(
                remaining(byAccount.get(accountKey(role, email)), now),
                remaining(byIp.get(ip), now));
        if (wait > 0) throw new LockedException((wait + 999) / 1000);
    }

    public void recordFailure(String role, String email, String ip) {
        long now = System.currentTimeMillis();
        bump(byAccount, accountKey(role, email), now, MAX_ACCOUNT_FAILURES);
        bump(byIp, ip, now, MAX_IP_FAILURES);
    }

    public void recordSuccess(String role, String email, String ip) {
        byAccount.remove(accountKey(role, email));
        byIp.remove(ip);
    }

    public void purgeStale() {
        long now = System.currentTimeMillis();
        byAccount.values().removeIf(a -> stale(a, now));
        byIp.values().removeIf(a -> stale(a, now));
    }

    private static boolean stale(Attempts a, long now) {
        synchronized (a) {
            return a.lockedUntil <= now && a.lastFailureAt > 0 && now - a.lastFailureAt > RETENTION_MILLIS;
        }
    }

    private static long remaining(Attempts a, long now) {
        if (a == null) return 0;
        synchronized (a) { return Math.max(0, a.lockedUntil - now); }
    }

    private static void bump(Map<String, Attempts> map, String key, long now, int maxFailures) {
        Attempts a = map.compute(key, (k, existing) -> existing == null ? new Attempts() : existing);
        synchronized (a) {
            if (a.lockedUntil <= now && a.lastFailureAt > 0 && now - a.lastFailureAt > LOCKOUT_MILLIS) {
                a.failures = 0;
            }
            a.failures++;
            a.lastFailureAt = now;
            if (a.failures >= maxFailures) {
                a.lockedUntil = now + LOCKOUT_MILLIS;
                a.failures = 0;
            }
        }
    }

    private static String accountKey(String role, String email) {
        return (role == null ? "" : role.trim().toUpperCase())
                + ":" + (email == null ? "" : email.trim().toLowerCase());
    }
}