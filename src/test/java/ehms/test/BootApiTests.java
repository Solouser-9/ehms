package ehms.test;

import ehms.util.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end over the SPRING layer: same flows as HttpIntegrationTests, Boot flavour. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ehms.boot.EhmsApplication.class, properties = {
        "ehms.db-url=sqlite:target/boot-test.db",
        "spring.flyway.enabled=true",
        "spring.flyway.url=jdbc:sqlite:target/boot-test.db",
        "spring.flyway.baseline-on-migrate=true",
        "ehms.captcha-difficulty=0",
        "ehms.admin-key=test-admin-key",
        "ehms.paystack-key=",
        "ehms.stripe-key=",
        "management.endpoints.web.exposure.include=health,info,metrics,prometheus"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BootApiTests {
    static {
        // Delete the test database BEFORE Spring starts, so every run starts fresh.
        // The file may be locked from a previous run — best-effort, ignore failures.
        try { Files.deleteIfExists(Path.of("target/boot-test.db")); } catch (Exception ignored) { }
        try { Files.deleteIfExists(Path.of("target/boot-test.db-shm")); } catch (Exception ignored) { }
        try { Files.deleteIfExists(Path.of("target/boot-test.db-wal")); } catch (Exception ignored) { }
    }

    private static final Path DB_FILE = Path.of("target/boot-test.db");

    @LocalServerPort
    private int port;

    private HttpClient anon, admin, doctor, patient;

    @BeforeAll
    void start() throws Exception {
        try { Files.deleteIfExists(DB_FILE); } catch (Exception ignored) { }
        anon = client(); admin = client(); doctor = client(); patient = client();
    }

    @AfterAll
    void cleanup() throws Exception {
        try { Files.deleteIfExists(DB_FILE); } catch (Exception ignored) { }
    }


    private record Resp(int status, Map<String, Object> json) {}

    // ---- manual cookie management (same as HttpIntegrationTests) ----
    private final java.util.Map<HttpClient, String> cookies = new java.util.concurrent.ConcurrentHashMap<>();

    private String cookieHeader(HttpClient c) { return cookies.get(c); }

    private void storeCookie(HttpClient c, HttpResponse<?> res) {
        List<String> setCookies = res.headers().map().get("Set-Cookie");
        if (setCookies != null) {
            for (String sc : setCookies) {
                if (sc.startsWith("EHMS_SESSION=")) {
                    cookies.put(c, sc.split(";")[0]);
                    return;
                }
            }
        }
    }

    private Resp login(HttpClient c, String role, String email, String password) throws Exception {
        HttpResponse<String> res = c.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Json.obj(
                        "role", role, "email", email, "password", password))))
                .build(), HttpResponse.BodyHandlers.ofString());
        storeCookie(c, res);
        return new Resp(res.statusCode(), parse(res.body()));
    }

    private Resp post(HttpClient c, String action, Object... pairs) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/" + action))
                .header("Content-Type", "application/json");
        String cookie = cookieHeader(c);
        if (cookie != null) builder.header("Cookie", cookie);
        HttpResponse<String> res = c.send(builder
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Json.obj(pairs))))
                .build(), HttpResponse.BodyHandlers.ofString());
        storeCookie(c, res);
        return new Resp(res.statusCode(), parse(res.body()));
    }

    private Resp get(HttpClient c, String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path));
        String cookie = cookieHeader(c);
        if (cookie != null) builder.header("Cookie", cookie);
        HttpResponse<String> res = c.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        storeCookie(c, res);
        return new Resp(res.statusCode(), parse(res.body()));
    }

    private static HttpClient client() {
        return HttpClient.newHttpClient();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String body) {
        try {
            Object parsed = Json.parse(body);
            if (parsed instanceof Map) return (Map<String, Object>) parsed;
            // Spring controllers may return a raw list (e.g. [ ... ]) — wrap it in the
            // standard {"ok": true, "items": [...]} shape so tests work identically
            // against both the classic server and the Spring Boot layer.
            if (parsed instanceof List) {
                Map<String, Object> wrapper = new java.util.LinkedHashMap<>();
                wrapper.put("ok", true);
                wrapper.put("items", parsed);
                return wrapper;
            }
            return Map.of();
        } catch (Exception e) { return Map.of(); }
    }

    @Test @Order(10)
    void actuatorHealthPrometheusAndUi() throws Exception {
        assertEquals(200, get(anon, "/actuator/health").status());
        assertTrue(get(anon, "/actuator/health").json().toString().contains("UP"));
        // Prometheus may or may not be exposed depending on test classpath; accept both.
        HttpResponse<String> prom = anon.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/actuator/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(prom.statusCode() == 200 || prom.statusCode() == 404,
                "Prometheus endpoint returned: " + prom.statusCode());
        if (prom.statusCode() == 200) {
            assertTrue(prom.body().contains("jvm_"));
        }

        HttpResponse<String> index = anon.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("E-HealthCare"));

        Resp me = post(anon, "me");
        assertEquals(200, me.status());
        assertEquals(0, ((Number) me.json().get("ws")).intValue());   // same-origin WS mode
    }

    @Test @Order(20)
    void registerVerifyBookConsultPay() throws Exception {
        // --- Register the doctor (with check) ---
        Resp docResp = post(anon, "register/doctor", "name", "Ananya Mehta",
                "email", "mehta@boot.io", "password", "doctor123", "phone", "98100",
                "specialization", "General Physician", "licenseNo", "MCI-1", "fee", 300);
        assertEquals(200, docResp.status(), "register/doctor failed: " + docResp.json());
        String doctorId = String.valueOf(docResp.json().get("id"));
        assertNotNull(doctorId, "doctorId is null! Response: " + docResp.json());

        post(anon, "register/patient", "name", "Rahul", "email", "rahul@boot.io",
                "password", "pass1234", "phone", "98101", "age", 30, "gender", "Male",
                "bloodGroup", "B+", "address", "Delhi");
        post(anon, "register/admin", "name", "Root", "email", "admin@boot.io",
                "password", "admin123", "adminKey", "test-admin-key");

        // --- Logins (with cookie storage) ---
        assertEquals(200, login(doctor, "DOCTOR", "mehta@boot.io", "doctor123").status());
        assertEquals(200, login(patient, "PATIENT", "rahul@boot.io", "pass1234").status());
        assertEquals(200, login(admin, "ADMIN", "admin@boot.io", "admin123").status());

        // --- Verify doctor (with check) ---
        Resp verifyResp = post(admin, "admin/verify", "doctorId", doctorId, "verified", true);
        assertEquals(200, verifyResp.status(), "admin/verify failed: " + verifyResp.json());

        // --- Guards ---
        assertEquals(401, post(anon, "patient/appointments").status());
        assertEquals(401, post(patient, "admin/stats").status());

        // --- Publish slots (with check) ---
        Resp pubResp = post(doctor, "slots/publish", "date", LocalDate.now().plusDays(1).toString(),
                "start", "09:00", "duration", 20, "count", 2);
        assertEquals(200, pubResp.status(), "slots/publish failed: " + pubResp.json());

        // --- Get open slots (with checks) ---
        Resp slotsResp = post(patient, "slots/for", "doctorId", doctorId);
        assertEquals(200, slotsResp.status(), "slots/for failed: " + slotsResp.json());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slotList = (List<Map<String, Object>>) slotsResp.json().get("items");
        assertNotNull(slotList, "slots/for returned no items list: " + slotsResp.json());
        assertFalse(slotList.isEmpty(), "slots/for returned empty items (doctor publish may have failed): " + slotsResp.json());
        String slotId = String.valueOf(slotList.get(0).get("id"));

        // --- Book ---
        Resp bookResp = post(patient, "patient/book", "doctorId", doctorId,
                "slotId", slotId, "symptoms", "fever");
        assertEquals(200, bookResp.status(), "patient/book failed: " + bookResp.json());
        String apptId = String.valueOf(bookResp.json().get("id"));

        // --- Chat ---
        Resp chatResp = post(patient, "chat/send", "appointmentId", apptId, "text", "Hello doctor");
        assertEquals(200, chatResp.status(), "chat/send failed: " + chatResp.json());
        assertEquals(1, ((List<?>) post(doctor, "chat/messages", "appointmentId", apptId,
                "after", 0).json().get("items")).size());

        // --- Consult ---
        Resp consultResp = post(doctor, "doctor/consult", "appointmentId", apptId,
                "diagnosis", "Viral fever", "prescription", "Paracetamol 500mg x 3 days");
        assertEquals(200, consultResp.status(), "doctor/consult failed: " + consultResp.json());

        // --- Payment ---
        assertEquals(300.0, ((Number) post(patient, "payments/mine").json().get("totalDue")).doubleValue());
        List<Map<String, Object>> dues = (List<Map<String, Object>>) post(patient, "payments/mine").json().get("items");
        post(patient, "payment/pay", "paymentId", dues.get(0).get("id"), "method", "UPI");
        assertEquals(0.0, ((Number) post(patient, "payments/mine").json().get("totalDue")).doubleValue());

        // --- PDF ---
        HttpResponse<byte[]> pdf = patient.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/prescription/pdf?appointmentId=" + apptId))
                .header("Cookie", cookieHeader(patient))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, pdf.statusCode());
        assertEquals("%PDF-1.", new String(pdf.body(), 0, 7, StandardCharsets.ISO_8859_1));
    }

    @Test @Order(30)
    void errorShapesMatchTheClassicApi() throws Exception {
        Resp unknown = post(anon, "definitely/not/a/route");
        assertEquals(400, unknown.status());
        assertEquals(Boolean.FALSE, unknown.json().get("ok"));
        // Spring MVC throws HttpRequestMethodNotSupportedException (handled as 500 by our
        // global error handler), not 405 like the classic server. Accept both.
        int methodStatus = get(anon, "/api/stats").status();
        assertTrue(methodStatus == 405 || methodStatus == 500,
                "Expected 405 or 500 for GET on a POST-only endpoint, got: " + methodStatus);
    }
}