package ehms.test;

import com.sun.net.httpserver.HttpServer;
import ehms.db.Database;
import ehms.util.Json;
import ehms.web.WebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests over real HTTP: a detached in-memory database, the real
 * WebServer on an ephemeral port (0), and the JDK HttpClient with real cookie
 * handling. Covers routing, session-cookie auth, role guards, anti-enumeration,
 * lockout, CAPTCHA, multipart uploads, binary downloads and the static/PWA assets.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpIntegrationTests {

    private static final String ADMIN_KEY = "test-admin-key";

    private Database db;
    private HttpServer server;
    private int port;

    private HttpClient anon, admin, doctor, patient, patient2, hospital;

    private String doctorId, hospitalId, appointmentId;

    @BeforeAll
    void startServer() throws Exception {
        db = Database.createDetached();
        server = WebServer.start(db, new ehms.web.WebServerConfig(0, false, ADMIN_KEY, false, false,
                3, null, null, "inr", null));
        port = server.getAddress().getPort();
        anon = client();      // one cookie jar per persona
        admin = client();
        doctor = client();
        patient = client();
        patient2 = client();
        hospital = client();
    }

    @AfterAll
    void stopServer() { server.stop(0); }

    // ---------------- helpers ----------------

    private record Resp(int status, Map<String, Object> json) {}

    // ---- manual cookie management (JDK CookieManager rejects SameSite=Strict) ----
    private final java.util.Map<HttpClient, String> cookies = new java.util.concurrent.ConcurrentHashMap<>();

    private static HttpClient client() {
        return HttpClient.newHttpClient();
    }

    private String cookieHeader(HttpClient c) {
        return cookies.get(c);
    }

    private void storeCookie(HttpClient c, HttpResponse<?> res) {
        List<String> setCookies = res.headers().map().get("Set-Cookie");
        if (setCookies != null) {
            for (String sc : setCookies) {
                if (sc.startsWith("EHMS_SESSION=")) {
                    cookies.put(c, sc.split(";")[0]);  // "EHMS_SESSION=token"
                    return;
                }
            }
        }
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

    private Resp post(HttpClient c, String action, java.util.Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/" + action))
                .header("Content-Type", "application/json");
        String cookie = cookieHeader(c);
        if (cookie != null) builder.header("Cookie", cookie);
        HttpResponse<String> res = c.send(builder
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
        storeCookie(c, res);
        return new Resp(res.statusCode(), parse(res.body()));
    }

    /** Login WITH a solved proof-of-work CAPTCHA. */
    private Resp login(HttpClient c, String role, String email, String password) throws Exception {
        Resp ch = post(c, "captcha");
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("role", role); body.put("email", email); body.put("password", password);
        if (Boolean.TRUE.equals(ch.json().get("enabled"))) {
            String salt = (String) ch.json().get("salt");
            int diff = ((Number) ch.json().get("difficulty")).intValue();
            long n = 0;
            while (n < 5_000_000 && !hexSha256(salt + ":" + n).startsWith("0".repeat(diff))) n++;
            body.put("captchaSalt", salt);
            body.put("captchaAnswer", n);
        }
        return post(c, "login", body);
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

    private static String hexSha256(String s) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String body) {
        try {
            Object parsed = Json.parse(body);
            return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void assertOk(Resp r, int expectedStatus, String action) {
        assertEquals(expectedStatus, r.status(), action + " -> " + r.json());
        assertEquals(Boolean.TRUE, r.json().get("ok"), action + " -> " + r.json());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Resp r) {
        return (List<Map<String, Object>>) r.json().getOrDefault("items", List.of());
    }

    // ---------------- tests ----------------

    @Test @Order(10)
    void registrationLoginAndAdminVerification() throws Exception {
        Resp d = post(anon, "register/doctor", "name", "Ananya Mehta", "email", "mehta@test.io",
                "password", "doctor123", "phone", "9810011111", "specialization", "General Physician",
                "licenseNo", "MCI-10021", "fee", 300);
        assertOk(d, 200, "register/doctor");
        doctorId = (String) d.json().get("id");

        assertOk(post(anon, "register/patient", "name", "Rahul Verma", "email", "rahul@test.io",
                "password", "pass1234", "phone", "9810022222", "age", 30, "gender", "Male",
                "bloodGroup", "B+", "address", "New Delhi"), 200, "register/patient");

        assertOk(post(anon, "register/patient", "name", "Priya Sharma", "email", "priya@test.io",
                "password", "pass1234", "phone", "9810022333", "age", 34, "gender", "Female",
                "bloodGroup", "O+", "address", "Kolkata"), 200, "register/patient (2)");

        Resp h = post(anon, "register/hospital", "name", "City Care", "email", "cc@test.io",
                "password", "hospital123", "phone", "011-123", "address", "MG Road", "initialBeds", 2);
        assertOk(h, 200, "register/hospital");
        hospitalId = (String) h.json().get("id");

        assertOk(post(anon, "register/admin", "name", "Root", "email", "admin@test.io",
                "password", "admin123", "adminKey", ADMIN_KEY), 200, "register/admin");

        Resp dl = login(doctor, "DOCTOR", "mehta@test.io", "doctor123");
        assertOk(dl, 200, "doctor login");
        assertEquals("DOCTOR", dl.json().get("role"));
        assertOk(login(patient, "PATIENT", "rahul@test.io", "pass1234"), 200, "patient login");
        assertOk(login(patient2, "PATIENT", "priya@test.io", "pass1234"), 200, "patient (2) login");
        assertOk(login(hospital, "HOSPITAL", "cc@test.io", "hospital123"), 200, "hospital login");
        assertOk(login(admin, "ADMIN", "admin@test.io", "admin123"), 200, "admin login");

        Resp v = post(admin, "admin/verify", "doctorId", doctorId, "verified", true);
        assertOk(v, 200, "admin/verify");
        assertEquals(Boolean.TRUE, v.json().get("verified"));
    }

    @Test @Order(15)
    void captchaProtectsLogin() throws Exception {
        Resp forged = post(anon, "login", java.util.Map.of("role", "PATIENT",
                "email", "rahul@test.io", "password", "pass1234",
                "captchaSalt", "forged-salt", "captchaAnswer", 0));
        assertEquals(400, forged.status());
        assertTrue(String.valueOf(forged.json().get("error")).contains("human verification"));
    }

    @Test @Order(20)
    void authGuardsAndHttpSemantics() throws Exception {
        assertEquals(401, post(anon, "patient/appointments").status());        // no session cookie
        assertEquals(401, post(patient, "admin/stats").status());             // valid session, wrong role
        assertEquals(405, get(anon, "/api/stats").status());                   // JSON endpoints are POST-only
        Resp unknown = post(anon, "definitely/not/a/route");
        assertEquals(400, unknown.status());
        assertEquals(Boolean.FALSE, unknown.json().get("ok"));

        // Anti-enumeration: wrong password and unknown email are indistinguishable.
        Resp wrong = post(anon, "login", java.util.Map.of("role", "PATIENT",
                "email", "rahul@test.io", "password", "wrong",
                "captchaSalt", "bogus", "captchaAnswer", 1));
        Resp ghost = post(anon, "login", java.util.Map.of("role", "PATIENT",
                "email", "ghost@test.io", "password", "wrong",
                "captchaSalt", "bogus2", "captchaAnswer", 1));
        assertEquals(400, wrong.status());
        assertEquals(400, ghost.status());
    }

    @Test @Order(30)
    void slotsAndBookingFlow() throws Exception {
        String tomorrow = LocalDate.now().plusDays(1).toString();
        Resp pub = post(doctor, "slots/publish", "date", tomorrow, "start", "09:00",
                "duration", 20, "count", 3);
        assertOk(pub, 200, "slots/publish");
        assertEquals(3, items(pub).size());

        Resp open = post(patient, "slots/for", "doctorId", doctorId);
        assertOk(open, 200, "slots/for");
        String slotId = (String) items(open).get(0).get("id");

        Resp book = post(patient, "patient/book", "doctorId", doctorId,
                "slotId", slotId, "symptoms", "fever and headache since 2 days");
        assertOk(book, 200, "patient/book");
        appointmentId = (String) book.json().get("id");
        assertEquals("PENDING", book.json().get("status"));
        assertEquals(items(open).get(0).get("startAt"), book.json().get("scheduledAt"));

        // The booked slot cannot be double-booked by another patient.
        assertEquals(400, post(patient2, "patient/book", "doctorId", doctorId,
                "slotId", slotId, "symptoms", "sore throat").status());

        // Identity comes from the session cookie, never from the request body.
        Resp mine = post(patient, "patient/appointments", "patientId", "P999");
        assertOk(mine, 200, "patient/appointments");
        List<Map<String, Object>> list = items(mine);
        assertEquals(1, list.size());
        assertEquals(appointmentId, list.get(0).get("id"));
        assertEquals("Rahul Verma", list.get(0).get("patientName"));
    }

    @Test @Order(40)
    void chatConsultPaymentAndPdfFlow() throws Exception {
        assertOk(post(patient, "chat/send", "appointmentId", appointmentId,
                "text", "Hello doctor, the fever is not going down."), 200, "chat/send");

        Resp thread = post(doctor, "chat/messages", "appointmentId", appointmentId, "after", 0);
        assertOk(thread, 200, "chat/messages");
        assertEquals(1, items(thread).size());
        assertEquals("Hello doctor, the fever is not going down.", items(thread).get(0).get("text"));
        assertEquals(0L, ((Number) post(doctor, "chat/unread").json().get("total")).longValue());
        assertEquals(400, post(patient2, "chat/messages", "appointmentId", appointmentId,
                "after", 0).status());

        Resp consult = post(doctor, "doctor/consult", "appointmentId", appointmentId,
                "diagnosis", "Viral fever", "prescription", "Paracetamol 500mg thrice a day for 3 days");
        assertOk(consult, 200, "doctor/consult");
        assertEquals("COMPLETED", consult.json().get("status"));

        Resp dues = post(patient, "payments/mine");
        assertOk(dues, 200, "payments/mine");
        assertEquals(300.0, ((Number) dues.json().get("totalDue")).doubleValue());
        String paymentId = (String) items(dues).get(0).get("id");
        assertOk(post(patient, "payment/pay", "paymentId", paymentId, "method", "UPI"),
                200, "payment/pay");
        assertEquals(0.0, ((Number) post(patient, "payments/mine").json().get("totalDue")).doubleValue());

        HttpResponse<byte[]> pdf = patient.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/prescription/pdf?appointmentId=" + appointmentId))
                .header("Cookie", cookieHeader(patient))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, pdf.statusCode());
        assertEquals("application/pdf", pdf.headers().firstValue("Content-Type").orElse(""));
        assertEquals("%PDF-1.", new String(pdf.body(), 0, 7, StandardCharsets.ISO_8859_1));
        assertEquals(401, anon.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/prescription/pdf?appointmentId=" + appointmentId))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    }

    @Test @Order(50)
    void bedRequestApprovalAndBillingFlow() throws Exception {
        assertOk(post(patient, "bed/request", "hospitalId", hospitalId, "bedType", "ANY",
                "reason", "advised admission"), 200, "bed/request");

        Resp queue = post(hospital, "bed/requests");
        assertOk(queue, 200, "bed/requests");
        String requestId = (String) items(queue).get(0).get("id");

        Resp overview = post(hospital, "hospital/beds");
        assertOk(overview, 200, "hospital/beds");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> beds = (List<Map<String, Object>>) overview.json().get("beds");
        String bedId = (String) beds.get(0).get("id");

        Resp decide = post(hospital, "bed/request/decide", "requestId", requestId,
                "approve", true, "bedId", bedId);
        assertOk(decide, 200, "bed/request/decide");
        assertEquals("APPROVED", decide.json().get("status"));

        Resp discharge = post(hospital, "hospital/discharge", "bedId", bedId);
        assertOk(discharge, 200, "hospital/discharge");
        @SuppressWarnings("unchecked")
        Map<String, Object> bill = (Map<String, Object>) discharge.json().get("bill");
        assertEquals(2000.0, ((Number) bill.get("amount")).doubleValue());   // 1 day x default GENERAL rate
        assertEquals(1, ((List<?>) bill.get("lines")).size());

        Resp mine = post(patient, "bills/mine");
        String billId = (String) items(mine).get(0).get("id");
        assertEquals("DUE", items(mine).get(0).get("status"));
        assertOk(post(patient, "bill/pay", "billId", billId, "method", "CARD"), 200, "bill/pay");

        Resp hospitalBills = post(hospital, "hospital/bills");
        assertOk(hospitalBills, 200, "hospital/bills");
        assertEquals("PAID", items(hospitalBills).get(0).get("status"));
    }

    @Test @Order(60)
    void multipartReportUploadAndAccessControl() throws Exception {
        String boundary = "XxYyZzBoundary123";
        byte[] fileBytes = { 1, 2, 3, 4, 5 };
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\nBlood test\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"report.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        String tail = "\r\n--" + boundary + "--";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        body.writeBytes(fileBytes);
        body.writeBytes(tail.getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> up = patient.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/report/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", cookieHeader(patient))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(), HttpResponse.BodyHandlers.ofString());
        Resp uploaded = new Resp(up.statusCode(), parse(up.body()));
        assertOk(uploaded, 200, "report/upload");
        String reportId = (String) uploaded.json().get("id");
        assertEquals(1, items(post(patient, "reports/mine")).size());

        HttpResponse<byte[]> file = patient.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/file?id=" + reportId))
                .header("Cookie", cookieHeader(patient))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, file.statusCode());
        assertArrayEquals(fileBytes, file.body());
        assertEquals(200, doctor.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/file?id=" + reportId))
                .header("Cookie", cookieHeader(doctor))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        assertEquals(400, patient2.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/file?id=" + reportId))
                .header("Cookie", cookieHeader(patient2))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    }

    @Test @Order(70)
    void loginLockoutBlocksEvenTheCorrectPassword() throws Exception {
        assertOk(post(anon, "register/patient", "name", "Victim", "email", "victim@test.io",
                "password", "right-pass", "phone", "98", "age", 40, "gender", "Male",
                "bloodGroup", "A+", "address", "-"), 200, "register victim");
        for (int i = 0; i < 5; i++) {
            assertEquals(400, login(anon, "PATIENT", "victim@test.io", "wrong").status());
        }
        Resp blocked = login(anon, "PATIENT", "victim@test.io", "right-pass");
        assertEquals(429, blocked.status());
        assertTrue(String.valueOf(blocked.json().get("error")).contains("Too many failed login attempts"));
    }

    @Test @Order(80)
    void staticUiAndPwaAssets() throws Exception {
        HttpResponse<String> index = anon.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("E-HealthCare"));
        assertTrue(index.headers().firstValue("Content-Type").orElse("").contains("text/html"));

        HttpResponse<String> manifest = anon.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/manifest.webmanifest")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, manifest.statusCode());
        assertTrue(manifest.body().contains("icons"));

        HttpResponse<String> sw = anon.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/sw.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, sw.statusCode());
        assertTrue(sw.body().contains("ehms-shell"));

        HttpResponse<byte[]> icon = anon.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/icon-192.png")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, icon.statusCode());
        assertEquals("image/png", icon.headers().firstValue("Content-Type").orElse(""));
        assertEquals(0x89, icon.body()[0] & 0xFF);   // PNG magic bytes
        assertEquals('P', icon.body()[1]);

        assertEquals(404, anon.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/definitely-not-here")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}