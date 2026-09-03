package ehms.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Holds ACME HTTP-01 challenge responses while a certificate is being issued. */
public final class ChallengeHolder {
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    public static void put(String token, String content) { TOKENS.put(token, content); }
    public static String get(String token) { return TOKENS.get(token); }
    private ChallengeHolder() {}
}