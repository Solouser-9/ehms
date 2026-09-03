package ehms.boot;

import ehms.db.Database;
import ehms.model.Account;
import ehms.security.SessionManager.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Body-field helpers + session access - 1:1 with WebServer's private helpers. */
public final class Params {

    public static final String SESSION_ATTR = "ehms.session";

    private Params() {}

    public static Session session(HttpServletRequest r) { return (Session) r.getAttribute(SESSION_ATTR); }

    public static Session requireRole(HttpServletRequest r, String role) {
        Session s = session(r);
        if (s == null) throw new AuthException("Your session has expired. Please sign in again.");
        if (!role.equals(s.role()))
            throw new AuthException("This action is only available to signed-in " + role.toLowerCase() + "s.");
        return s;
    }

    public static Session requireAnyRole(HttpServletRequest r, String... roles) {
        Session s = session(r);
        if (s == null) throw new AuthException("Please sign in to use this feature.");
        for (String role : roles) if (role.equals(s.role())) return s;
        throw new AuthException("This action is not available for your account type.");
    }

    public static String clientIp(HttpServletRequest r, boolean trustProxy) {
        if (trustProxy) {
            String xff = r.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",")[0].trim();
                if (!first.isEmpty()) return first;
            }
        }
        String ip = r.getRemoteAddr();
        return "0:0:0:0:0:0:0:1".equals(ip) ? "127.0.0.1" : ip;
    }

    public static Map<String, Object> accountPayload(Database db, Session s) {
        Account acc = account(db, s);
        Map<String, Object> m = acc != null ? acc.toMap() : new LinkedHashMap<>();
        m.put("role", s.role());
        return m;
    }

    public static String accountEmail(Database db, Session s) {
        Account acc = account(db, s);
        return acc == null ? null : acc.getEmail();
    }

    private static Account account(Database db, Session s) {
        return switch (s.role()) {
            case "DOCTOR" -> db.doctors.get(s.accountId());
            case "PATIENT" -> db.patients.get(s.accountId());
            case "HOSPITAL" -> db.hospitals.get(s.accountId());
            case "ADMIN" -> db.admins.get(s.accountId());
            default -> null;
        };
    }

    // ----- body field helpers -----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapVal(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v == null) return new LinkedHashMap<>();
        if (v instanceof Map) return (Map<String, Object>) v;
        throw new IllegalArgumentException("Field '" + key + "' must be a JSON object.");
    }

    public static String str(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v == null) throw new IllegalArgumentException("Missing field: " + key);
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Field '" + key + "' must not be empty.");
        return s;
    }

    public static String opt(Map<String, Object> b, String key) {
        Object v = b.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    public static int intVal(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null && !String.valueOf(v).isBlank()) {
            try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException ignored) { }
        }
        throw new IllegalArgumentException("Field '" + key + "' must be a whole number.");
    }

    public static int intOpt(Map<String, Object> b, String key, int defaultValue) {
        Object v = b.get(key);
        if (v == null || String.valueOf(v).isBlank()) return defaultValue;
        return intVal(b, key);
    }

    public static long longOpt(Map<String, Object> b, String key, long defaultValue) {
        Object v = b.get(key);
        if (v == null || String.valueOf(v).isBlank()) return defaultValue;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    public static double dbl(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v != null && !String.valueOf(v).isBlank()) {
            try { return Double.parseDouble(String.valueOf(v).trim()); } catch (NumberFormatException ignored) { }
        }
        throw new IllegalArgumentException("Field '" + key + "' must be a number.");
    }

    public static boolean bool(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v instanceof Boolean bo) return bo;
        if (v != null && !String.valueOf(v).isBlank()) return Boolean.parseBoolean(String.valueOf(v));
        throw new IllegalArgumentException("Field '" + key + "' must be true or false.");
    }
}