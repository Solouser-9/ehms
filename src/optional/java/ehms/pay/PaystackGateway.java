package ehms.pay;

import ehms.service.PaymentGateway;
import ehms.util.Json;
import ehms.util.Log;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Paystack Checkout (redirect flow) over the pure JDK HttpClient - no SDK needed.
 *
 * Flow: initialize transaction (server-side, secret key) -> redirect the browser
 * to Paystack's authorization_url -> Paystack redirects back to our callback with
 * ?reference=... -> we VERIFY server-side before marking anything paid.
 *
 * Reference scheme: "ehms-{kind}-{id}-{hexTime}". Paystack rejects duplicate
 * references, so a timestamp suffix makes every retry a fresh transaction;
 * verify() accepts the prefix so retries still settle the right invoice.
 */
public final class PaystackGateway implements PaymentGateway {

    private static final String INIT_URL = "https://api.paystack.co/transaction/initialize";
    private static final String VERIFY_BASE = "https://api.paystack.co/transaction/verify/";

    private final String secretKey;
    private final String currency;
    private final HttpClient http;

    public PaystackGateway(String secretKey, String currency) {
        this.secretKey = secretKey;
        this.currency = currency == null || currency.isBlank() ? "ngn" : currency.toLowerCase();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override public String name() { return "paystack"; }

    @Override
    public String checkout(String kind, String id, double amount, String currency,
                           String description, String baseUrl, String email) throws Exception {
        if (email == null || email.isBlank())
            throw new IllegalArgumentException(
                    "Paystack needs your email address to start the payment. Please set one on your profile.");
        Map<String, Object> body = Json.obj(
                "email", email,
                "amount", Math.round(amount * 100),          // subunits: kobo for NGN, cents for USD/GHS...
                "currency", this.currency.toUpperCase(),
                "reference", reference(kind, id),
                "callback_url", baseUrl + "/api/payment/paystack/return?type=" + kind + "&id=" + id,
                "metadata", Json.obj("kind", kind, "id", id, "description", description));

        Map<String, Object> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(INIT_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build());

        Object data = resp.get("data");
        if (!Boolean.TRUE.equals(resp.get("status")) || !(data instanceof Map<?, ?> d))
            throw new IllegalArgumentException("Payment could not be started (Paystack): "
                    + resp.getOrDefault("message", Json.write(resp)));
        String url = String.valueOf(d.get("authorization_url"));
        if (url.isBlank() || "null".equals(url))
            throw new IllegalArgumentException("Payment could not be started (Paystack returned no URL).");
        return url;
    }

    @Override
    public boolean verify(String kind, String id, String externalRef) throws Exception {
        if (!referenceMatches(kind, id, externalRef)) {
            Log.warn("Paystack: reference does not match this invoice (expected ehms-" + kind + "-"
                    + id + "-..., got " + externalRef + ")");
            return false;
        }
        Map<String, Object> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(VERIFY_BASE + externalRef))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secretKey)
                .GET()
                .build());
        Object data = resp.get("data");
        if (!Boolean.TRUE.equals(resp.get("status")) || !(data instanceof Map<?, ?> d)) return false;
        return "success".equals(String.valueOf(d.get("status")))
                && externalRef.equals(String.valueOf(d.get("reference")));
    }

    private Map<String, Object> send(HttpRequest req) throws Exception {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        Object parsed;
        try {
            parsed = Json.parse(res.body());
        } catch (Exception e) {
            throw new IllegalStateException("Paystack HTTP " + res.statusCode() + ": " + res.body());
        }
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) parsed;
            return m;
        }
        throw new IllegalStateException("Paystack HTTP " + res.statusCode() + ": unexpected response");
    }

    private static String reference(String kind, String id) {
        return "ehms-" + kind + "-" + id + "-" + Long.toHexString(System.currentTimeMillis());
    }

    /** Accepts the exact legacy form or any timestamp-suffixed retry of it. */
    private static boolean referenceMatches(String kind, String id, String ref) {
        String prefix = "ehms-" + kind + "-" + id + "-";
        return ref != null && (ref.equals("ehms-" + kind + "-" + id) || ref.startsWith(prefix));
    }
}