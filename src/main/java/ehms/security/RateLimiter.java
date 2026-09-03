package ehms.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Simple sliding-window rate limiter per client IP: 120 requests / minute. */
public final class RateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequests, long windowMillis) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= windowMillis) q.pollFirst();
            if (q.size() >= maxRequests) return false;
            q.addLast(now);
            return true;
        }
    }

    public void purgeStale() {
        long now = System.currentTimeMillis();
        hits.values().removeIf(q -> {
            synchronized (q) { return q.isEmpty() || now - q.peekLast() >= windowMillis; }
        });
    }
}