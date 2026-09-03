package ehms.boot;

import ehms.db.Database;
import ehms.model.Account;
import ehms.security.RateLimiter;
import ehms.security.SessionManager;
import ehms.security.SessionManager.Session;
import ehms.util.Json;
import ehms.util.Log;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Rate limit + cookie-session resolution + blocked-account kill, exactly like serveApi. */
@Component
public class ApiFilter extends OncePerRequestFilter {

    private final SessionManager sessions;
    private final RateLimiter rateLimiter;
    private final Database db;
    private final boolean trustProxy;

    public ApiFilter(SessionManager sessions, RateLimiter rateLimiter, Database db, EhmsProperties props) {
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
        this.db = db;
        this.trustProxy = Boolean.TRUE.equals(props.trustProxy());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equals(req.getMethod())) {
            res.setStatus(204);
            return;
        }
        String ip = Params.clientIp(req, trustProxy);
        if (!rateLimiter.tryAcquire(ip)) {
            write(res, 429, Json.obj("ok", false, "error",
                    "Too many requests from your address. Please wait a minute and try again."));
            return;
        }
        Session session = sessions.get(readCookie(req, SessionManager.COOKIE_NAME));
        if (session != null) {
            Account acc = switch (session.role()) {
                case "DOCTOR" -> db.doctors.get(session.accountId());
                case "PATIENT" -> db.patients.get(session.accountId());
                case "HOSPITAL" -> db.hospitals.get(session.accountId());
                case "ADMIN" -> db.admins.get(session.accountId());
                default -> null;
            };
            if (acc == null || acc.isBlocked()) {
                sessions.destroy(session.token());
                write(res, 401, Json.obj("ok", false, "error",
                        "Your account has been blocked by an administrator."));
                return;
            }
        }
        req.setAttribute(Params.SESSION_ATTR, session);
        Log.debug("API " + req.getRequestURI().substring(5) + "  " + (session == null
                ? "(anonymous)" : "(" + session.role() + " " + session.accountId() + ")") + "  from " + ip);
        chain.doFilter(req, res);
    }

    private static String readCookie(HttpServletRequest req, String name) {
        String header = req.getHeader("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && name.equals(kv[0])) return kv[1];
        }
        return null;
    }

    private static void write(HttpServletResponse res, int status, Object payload) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json; charset=utf-8");
        res.getOutputStream().write(Json.write(payload).getBytes(StandardCharsets.UTF_8));
    }
}