package ehms.web;

/** Everything the web layer needs, in one immutable record. Paystack wins if both keys are set. */
public record WebServerConfig(
        int port,
        boolean https,
        String adminKey,
        boolean trustProxy,
        boolean prorate,
        int captchaDifficulty,     // 0 = off; default 3 when launched via Main
        String stripeKey,          // null = not used
        String paystackKey,        // null = not used; takes precedence over stripeKey
        String chargeCurrency,     // gateway-generic charge currency: "ngn" (default), "inr", ...
        String publicUrl) {        // null = http(s)://localhost:<port>

    public static WebServerConfig defaults(int port) {
        return new WebServerConfig(port, false, "ehms-admin-key", false, false,
                0, null, null, "ngn", null);
    }
}