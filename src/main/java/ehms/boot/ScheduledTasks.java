package ehms.boot;

import ehms.db.Database;
import ehms.security.LoginGuard;
import ehms.security.RateLimiter;
import ehms.security.SessionManager;
import ehms.util.Json;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class ScheduledTasks {

    private final Database db;
    private final SessionManager sessions;
    private final LoginGuard loginGuard;
    private final RateLimiter rateLimiter;
    private final EhmsWebSocketHandler ws;
    private final AtomicReference<String> lastStats = new AtomicReference<>();

    public ScheduledTasks(Database db, SessionManager sessions, LoginGuard loginGuard,
                          RateLimiter rateLimiter, EhmsWebSocketHandler ws) {
        this.db = db; this.sessions = sessions; this.loginGuard = loginGuard;
        this.rateLimiter = rateLimiter; this.ws = ws;
    }

    /** The classic janitor: expiry purges + occupancy snapshot roll-over. */
    @Scheduled(fixedDelay = 300_000)
    public void janitor() {
        try {
            sessions.purgeExpired();
            loginGuard.purgeStale();
            rateLimiter.purgeStale();
            if (db.snapshotOccupancy()) db.save();
        } catch (Exception ignored) { }
    }

    /** Push live stats to subscribed dashboards only when something changed. */
    @Scheduled(fixedDelay = 10_000)
    public void statsPush() {
        try {
            String json = Json.write(Json.obj("type", "stats", "stats", db.stats(), "daily", db.dailyStats(14)));
            String prev = lastStats.getAndSet(json);
            if (prev != null && !prev.equals(json)) ws.push("stats", json);
        } catch (Exception ignored) { }
    }
}