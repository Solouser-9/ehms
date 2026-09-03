package ehms.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import ehms.db.Database;
import ehms.model.Account;
import ehms.security.Captcha;
import ehms.security.ChallengeHolder;
import ehms.security.KeystoreTool;
import ehms.security.LoginGuard;
import ehms.security.RateLimiter;
import ehms.security.SessionManager;
import ehms.security.SessionManager.Session;
import ehms.service.AdminService;
import ehms.service.AppointmentService;
import ehms.service.AuditService;
import ehms.service.AuthService;
import ehms.service.BedRequestService;
import ehms.service.BedService;
import ehms.service.BillingService;
import ehms.service.ChatService;
import ehms.service.DoctorService;
import ehms.service.EquipmentService;
import ehms.service.HospitalService;
import ehms.service.MockGateway;
import ehms.service.PatientService;
import ehms.service.PaymentGateway;
import ehms.service.PaymentService;
import ehms.service.PdfService;
import ehms.service.PharmacyService;
import ehms.service.ReportService;
import ehms.service.SlotService;
import ehms.util.Json;
import ehms.util.Log;
import ehms.util.Multipart;
import ehms.ws.WebSocketProvider;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The classic hand-built web layer: the single-page UI + PWA assets at "/",
 * the JSON API at "/api/...", ACME HTTP-01 challenge answering, and the
 * scheduled janitor + live stats push. The optional Jetty WebSocket server is
 * loaded reflectively; when absent the UI silently falls back to polling.
 * Gateway priority: Paystack > Stripe > mock. Default charge currency: NGN.
 */
public final class WebServer {

    private WebServer() {}

    private record Binary(String contentType, byte[] bytes, String fileName) {}

    private record Redirect(String location) {}

    @FunctionalInterface
    private interface Route {
        Object handle(HttpExchange ex, Session session, Map<String, Object> body) throws Exception;
    }

    private static final class AuthException extends RuntimeException {
        AuthException(String message) { super(message); }
    }

    /** Convenience entry point (CAPTCHA on, mock payments). */
    public static HttpServer start(int port, boolean https, String adminKey,
                                   boolean trustProxy, boolean prorate) throws IOException {
        return start(Database.getInstance(), new WebServerConfig(port, https, adminKey,
                trustProxy, prorate, 3, null, null, "ngn", null));
    }

    /** Full-control variant: any database (tests use detached ones), every flag. */
    public static HttpServer start(Database db, WebServerConfig cfg) throws IOException {
        final int port = cfg.port();
        final boolean https = cfg.https();

        SessionManager sessions = new SessionManager();
        LoginGuard loginGuard = new LoginGuard();
        RateLimiter rateLimiter = new RateLimiter(120, 60_000);
        Captcha captcha = new Captcha(cfg.captchaDifficulty());

        AuthService auth = new AuthService(db, sessions, loginGuard);
        AuditService audit = new AuditService(db);
        BedService beds = new BedService(db);
        BedRequestService bedRequests = new BedRequestService(db, beds);
        BillingService billing = new BillingService(db, cfg.prorate());
        EquipmentService equipment = new EquipmentService(db);
        DoctorService doctors = new DoctorService(db);
        PatientService patients = new PatientService(db);
        HospitalService hospitals = new HospitalService(db, beds);
        AppointmentService appointments = new AppointmentService(db);
        SlotService slots = new SlotService(db);
        ChatService chat = new ChatService(db);
        ReportService reports = new ReportService(db);
        PdfService pdfs = new PdfService(db);
        PharmacyService pharmacy = new PharmacyService(db);
        PaymentService payments = new PaymentService(db);
        AdminService admin = new AdminService(db, cfg.adminKey());

        PaymentGateway gateway = new MockGateway();
        if (cfg.paystackKey() != null) {
            if (cfg.stripeKey() != null)
                Log.warn("Both Paystack and Stripe keys are set - using Paystack (remove one to avoid ambiguity).");
            try {
                gateway = (PaymentGateway) Class.forName("ehms.pay.PaystackGateway")
                        .getDeclaredConstructor(String.class, String.class)
                        .newInstance(cfg.paystackKey(), cfg.chargeCurrency());
                Log.info("Payments: Paystack checkout enabled (currency " + cfg.chargeCurrency() + ")");
            } catch (Throwable t) {
                Log.warn("Paystack gateway unavailable (" + t + ") - using the built-in mock");
            }
        } else if (cfg.stripeKey() != null) {
            try {
                gateway = (PaymentGateway) Class.forName("ehms.pay.StripeGateway")
                        .getDeclaredConstructor(String.class, String.class)
                        .newInstance(cfg.stripeKey(), cfg.chargeCurrency());
                Log.info("Payments: Stripe checkout enabled (currency " + cfg.chargeCurrency() + ")");
            } catch (Throwable t) {
                Log.warn("Stripe gateway unavailable (" + t + ") - using the built-in mock");
            }
        }
        final PaymentGateway gw = gateway;
        final String baseUrl = cfg.publicUrl() != null ? cfg.publicUrl()
                : (https ? "https" : "http") + "://localhost:" + port;

        final int[] wsPort = { -1 };
        final WebSocketProvider[] wsHolder = { null };

        Map<String, Route> routes = new LinkedHashMap<>();

        // ----- auth -----
        routes.put("login", (ex, session, b) -> {
            String role = str(b, "role");
            String email = str(b, "email");
            try {
                if (captcha.enabled() && !captcha.verify(opt(b, "captchaSalt"), longOpt(b, "captchaAnswer", -1)))
                    throw new IllegalArgumentException("Please complete the human verification and try again.");
                Session created = auth.login(role, email, str(b, "password"), clientIp(ex, cfg.trustProxy()));
                ex.getResponseHeaders().add("Set-Cookie", SessionManager.cookieHeader(created.token(), https));
                audit.record(created, AuditService.LOGIN, "Signed in");
                return accountPayload(db, created);
            } catch (LoginGuard.LockedException locked) {
                audit.record(role, null, null, email, AuditService.ACCOUNT_LOCKED,
                        "Sign-in blocked: too many failed attempts");
                throw locked;
            } catch (IllegalArgumentException failure) {
                audit.record(role, null, null, email, AuditService.LOGIN_FAILED, "Failed sign-in attempt");
                throw failure;
            }
        });
        routes.put("logout", (ex, session, b) -> {
            if (session != null) {
                sessions.destroy(session.token());
                audit.record(session, AuditService.LOGOUT, "Signed out");
            }
            ex.getResponseHeaders().add("Set-Cookie", SessionManager.clearCookieHeader(https));
            return Json.obj("loggedOut", true);
        });
        routes.put("me", (ex, session, b) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("user", session == null ? null : accountPayload(db, session));
            out.put("ws", wsPort[0]);
            return out;
        });
        routes.put("captcha", (ex, s, b) -> captcha.challenge());

        // ----- registration -----
        routes.put("register/doctor", (ex, s, b) -> {
            Map<String, Object> d = doctors.register(
                    str(b, "name"), str(b, "email"), str(b, "password"), str(b, "phone"),
                    str(b, "specialization"), str(b, "licenseNo"), dbl(b, "fee"));
            audit.record("DOCTOR", (String) d.get("id"), (String) d.get("name"), (String) d.get("email"),
                    AuditService.REGISTERED, "Doctor account registered (" + d.get("specialization") + ")");
            return d;
        });
        routes.put("register/patient", (ex, s, b) -> {
            Map<String, Object> p = patients.register(
                    str(b, "name"), str(b, "email"), str(b, "password"), str(b, "phone"),
                    intVal(b, "age"), opt(b, "gender"), opt(b, "bloodGroup"), opt(b, "address"));
            audit.record("PATIENT", (String) p.get("id"), (String) p.get("name"), (String) p.get("email"),
                    AuditService.REGISTERED, "Patient account registered");
            return p;
        });
        routes.put("register/hospital", (ex, s, b) -> {
            Map<String, Object> h = hospitals.register(
                    str(b, "name"), str(b, "email"), str(b, "password"), str(b, "phone"),
                    str(b, "address"), intOpt(b, "initialBeds", 0));
            audit.record("HOSPITAL", (String) h.get("id"), (String) h.get("name"), (String) h.get("email"),
                    AuditService.REGISTERED, "Hospital account registered");
            return h;
        });
        routes.put("register/admin", (ex, s, b) -> {
            Map<String, Object> a = admin.register(str(b, "name"), str(b, "email"),
                    str(b, "password"), opt(b, "adminKey"));
            audit.record("ADMIN", (String) a.get("id"), (String) a.get("name"), (String) a.get("email"),
                    AuditService.REGISTERED, "Administrator account registered");
            return a;
        });

        // ----- public info -----
        routes.put("stats", (ex, s, b) -> db.stats());
        routes.put("stats/daily", (ex, s, b) -> db.dailyStats((int) longOpt(b, "days", 14)));
        routes.put("doctors", (ex, s, b) -> {
            List<Map<String, Object>> list = doctors.list(opt(b, "specialization"));
            list.removeIf(m -> !Boolean.TRUE.equals(m.get("verified")) || Boolean.TRUE.equals(m.get("blocked")));
            return list;
        });
        routes.put("patients", requireAnyRole((ex, s, b) -> patients.list(), "HOSPITAL", "DOCTOR"));
        routes.put("bed/availability", (ex, s, b) -> beds.availability());

        // ----- slots + booking (patient) -----
        routes.put("slots/for", requireRole("PATIENT", (ex, s, b) -> slots.openFor(str(b, "doctorId"))));
        routes.put("patient/book", requireRole("PATIENT", (ex, s, b) -> {
            Map<String, Object> r = appointments.book(s.accountId(), str(b, "doctorId"),
                    str(b, "slotId"), str(b, "symptoms"));
            audit.record(s, AuditService.APPOINTMENT_BOOKED,
                    "Consultation booked with Dr. " + r.get("doctorName"));
            return r;
        }));
        routes.put("patient/cancel", requireRole("PATIENT", (ex, s, b) -> {
            Map<String, Object> r = appointments.cancel(s.accountId(), str(b, "appointmentId"));
            audit.record(s, AuditService.APPOINTMENT_CANCELLED,
                    "Consultation " + r.get("id") + " with Dr. " + r.get("doctorName") + " cancelled; slot released");
            return r;
        }));
        routes.put("patient/appointments", requireRole("PATIENT", (ex, s, b) ->
                appointments.forPatient(s.accountId(), opt(b, "status"))));

        // ----- slots (doctor) -----
        routes.put("slots/publish", requireRole("DOCTOR", (ex, s, b) ->
                slots.publish(s.accountId(), str(b, "date"), str(b, "start"),
                        intVal(b, "duration"), intVal(b, "count"))));
        routes.put("slots/mine", requireRole("DOCTOR", (ex, s, b) -> slots.forDoctor(s.accountId())));
        routes.put("slots/delete", requireRole("DOCTOR", (ex, s, b) ->
                slots.delete(s.accountId(), str(b, "slotId"))));

        // ----- doctor actions -----
        routes.put("doctor/appointments", requireRole("DOCTOR", (ex, s, b) ->
                appointments.forDoctor(s.accountId(), opt(b, "status"))));
        routes.put("doctor/consult", requireRole("DOCTOR", (ex, s, b) -> {
            Map<String, Object> r = appointments.consult(s.accountId(), str(b, "appointmentId"),
                    str(b, "diagnosis"), str(b, "prescription"));
            payments.createDue(db.appointments.get(String.valueOf(r.get("id"))));
            audit.record(s, AuditService.PRESCRIPTION_ISSUED,
                    "Prescription issued to " + r.get("patientName") + " (" + r.get("id") + ")");
            return r;
        }));
        routes.put("doctor/availability", requireRole("DOCTOR", (ex, s, b) -> {
            boolean available = bool(b, "available");
            doctors.setAvailability(s.accountId(), available);
            audit.record(s, AuditService.AVAILABILITY_CHANGED,
                    "Now " + (available ? "available" : "unavailable") + " for new consultation requests");
            return Json.obj("available", available);
        }));

        // ----- chat -----
        routes.put("chat/messages", requireAnyRole((ex, s, b) ->
                chat.messages(s, str(b, "appointmentId"), longOpt(b, "after", 0)), "PATIENT", "DOCTOR"));
        routes.put("chat/send", requireAnyRole((ex, s, b) -> {
            Map<String, Object> r = chat.send(s, str(b, "appointmentId"), str(b, "text"));
            if (wsHolder[0] != null)
                wsHolder[0].push(str(b, "appointmentId"), Json.write(Json.obj("type", "msg", "message", r)));
            return r;
        }, "PATIENT", "DOCTOR"));
        routes.put("chat/unread", requireAnyRole((ex, s, b) -> chat.unread(s), "PATIENT", "DOCTOR"));

        // ----- reports -----
        routes.put("report/upload", requireRole("PATIENT", (ex, s, b) -> {
            Map<String, Object> r = reports.upload(s.accountId(), opt(b, "title"),
                    (String) b.get(Multipart.FILENAME), (String) b.get(Multipart.CONTENT_TYPE),
                    (byte[]) b.get(Multipart.BYTES));
            audit.record(s, AuditService.REPORT_UPLOADED, "Report uploaded: " + r.get("title"));
            return r;
        }));
        routes.put("reports/mine", requireRole("PATIENT", (ex, s, b) -> reports.mine(s.accountId())));
        routes.put("reports/patient", requireRole("DOCTOR", (ex, s, b) ->
                reports.forPatient(s.accountId(), str(b, "patientId"))));
        routes.put("report/delete", requireRole("PATIENT", (ex, s, b) -> {
            Map<String, Object> r = reports.delete(s.accountId(), str(b, "id"));
            audit.record(s, AuditService.REPORT_DELETED, "Report deleted: " + r.get("title"));
            return r;
        }));

        // ----- binary GET endpoints -----
        routes.put("file", (ex, s, b) -> {
            if (s == null) throw new AuthException("Please sign in to view reports.");
            ReportService.Loaded l = reports.load(s, str(b, "id"));
            String ctype = l.attachment().getContentType() == null
                    ? "application/octet-stream" : l.attachment().getContentType();
            return new Binary(ctype, l.data(), l.attachment().getFileName());
        });
        routes.put("prescription/pdf", (ex, s, b) -> {
            if (s == null) throw new AuthException("Please sign in.");
            String id = str(b, "appointmentId");
            return new Binary("application/pdf", pdfs.prescription(s, id), "prescription-" + id + ".pdf");
        });
        routes.put("history/pdf", (ex, s, b) -> {
            if (s == null) throw new AuthException("Please sign in.");
            return new Binary("application/pdf", pdfs.history(s), "my-prescriptions.pdf");
        });
        routes.put("bill/pdf", (ex, s, b) -> {
            if (s == null) throw new AuthException("Please sign in.");
            String id = str(b, "billId");
            String cur = opt(b, "cur");
            return new Binary("application/pdf", pdfs.bill(s, id, cur == null ? "\u20A6" : cur),
                    "bill-" + id + ".pdf");
        });

        // ----- payments (consultation fees) -----
        routes.put("payments/mine", requireRole("PATIENT", (ex, s, b) -> payments.mine(s.accountId())));
        routes.put("payment/pay", requireRole("PATIENT", (ex, s, b) -> {
            String paymentId = str(b, "paymentId");
            if (!"mock".equals(gw.name())) {
                Map<String, Object> info = payments.lookup(s.accountId(), paymentId);
                String url = gw.checkout("payment", paymentId, ((Number) info.get("amount")).doubleValue(),
                        cfg.chargeCurrency(), "Consultation - " + info.get("doctorName"),
                        baseUrl, accountEmail(db, s));
                return Json.obj("checkout", url);
            }
            Map<String, Object> r = payments.pay(s.accountId(), paymentId, str(b, "method"));
            audit.record(s, AuditService.PAYMENT_MADE,
                    "Paid " + r.get("amount") + " to Dr. " + r.get("doctorName") + " via " + r.get("method"));
            return r;
        }));
        routes.put("payments/doctor", requireRole("DOCTOR", (ex, s, b) -> payments.forDoctor(s.accountId())));
        routes.put("payment/received", requireRole("DOCTOR", (ex, s, b) -> {
            Map<String, Object> r = payments.receive(s.accountId(), str(b, "paymentId"));
            audit.record(s, AuditService.PAYMENT_RECORDED,
                    "Cash fee of " + r.get("amount") + " received from " + r.get("patientName"));
            return r;
        }));
        routes.put("payment/stripe/return", (ex, s, b) -> {
            String kind = str(b, "type");
            String id = str(b, "id");
            String ref = str(b, "session_id");
            boolean paid = false;
            try { paid = gw.verify(kind, id, ref); }
            catch (Exception e) { Log.warn("Stripe verification failed: " + e); }
            if (paid) {
                try {
                    if ("payment".equals(kind)) payments.gatewayPaid(id, "STRIPE");
                    else if ("bill".equals(kind)) billing.gatewayPaid(id, "STRIPE");
                    if (s != null) audit.record(s, AuditService.PAYMENT_MADE,
                            "Paid " + kind + " " + id + " online via Stripe");
                } catch (IllegalArgumentException notFound) {
                    Log.warn("Stripe callback for unknown " + kind + ": " + id);
                }
                return new Redirect("/?paid=1");
            }
            return new Redirect("/?paid=0");
        });
        routes.put("payment/paystack/return", (ex, s, b) -> {
            String kind = str(b, "type");
            String id = str(b, "id");
            String ref = opt(b, "reference");
            if (ref == null) ref = opt(b, "trxref");   // Paystack sends both
            boolean paid = false;
            try { paid = gw.verify(kind, id, ref); }
            catch (Exception e) { Log.warn("Paystack verification failed: " + e); }
            if (paid) {
                try {
                    if ("payment".equals(kind)) payments.gatewayPaid(id, "PAYSTACK");
                    else if ("bill".equals(kind)) billing.gatewayPaid(id, "PAYSTACK");
                    if (s != null) audit.record(s, AuditService.PAYMENT_MADE,
                            "Paid " + kind + " " + id + " online via Paystack");
                } catch (IllegalArgumentException notFound) {
                    Log.warn("Paystack callback for unknown " + kind + ": " + id);
                }
                return new Redirect("/?paid=1");
            }
            return new Redirect("/?paid=0");
        });

        // ----- pharmacy -----
        routes.put("pharmacy/list", requireRole("HOSPITAL", (ex, s, b) -> pharmacy.list(s.accountId())));
        routes.put("pharmacy/add", requireRole("HOSPITAL", (ex, s, b) -> {
            Map<String, Object> m = pharmacy.add(s.accountId(), str(b, "name"), opt(b, "unit"),
                    intVal(b, "stock"), intVal(b, "reorderLevel"), dbl(b, "price"));
            audit.record(s, AuditService.MEDICINE_ADDED,
                    m.get("name") + " added to pharmacy (" + m.get("stock") + " " + m.get("unit") + ")");
            return m;
        }));
        routes.put("pharmacy/restock", requireRole("HOSPITAL", (ex, s, b) -> {
            Map<String, Object> m = pharmacy.restock(s.accountId(), str(b, "medicineId"), intVal(b, "qty"));
            audit.record(s, AuditService.STOCK_RESTOCKED,
                    m.get("name") + " restocked to " + m.get("stock") + " " + m.get("unit"));
            return m;
        }));
        routes.put("pharmacy/dispense", requireRole("HOSPITAL", (ex, s, b) -> {
            Map<String, Object> d = pharmacy.dispense(s.accountId(), str(b, "patientId"),
                    str(b, "medicineId"), intVal(b, "qty"), opt(b, "note"));
            audit.record(s, AuditService.MEDICINE_DISPENSED,
                    d.get("qty") + " x " + d.get("medicineName") + " dispensed to " + d.get("patientName"));
            return d;
        }));
        routes.put("pharmacy/history", requireRole("HOSPITAL", (ex, s, b) -> pharmacy.history(s.accountId())));
        routes.put("pharmacy/my", requireRole("PATIENT", (ex, s, b) -> pharmacy.forPatient(s.accountId())));

        // ----- hospital beds -----
        routes.put("hospital/beds", requireRole("HOSPITAL", (ex, s, b) -> beds.overview(s.accountId())));
        routes.put("hospital/bills", requireRole("HOSPITAL", (ex, s, b) -> billing.forHospital(s.accountId())));
        routes.put("hospital/beds/add", requireRole("HOSPITAL", (ex, s, b) -> {
            String ward = opt(b, "ward");
            int added = beds.addBeds(s.accountId(), str(b, "type"), intVal(b, "count"), ward);
            audit.record(s, AuditService.BEDS_ADDED, added + " " + str(b, "type") + " bed(s) added"
                    + (ward == null || ward.isBlank() ? "" : " to " + ward));
            return added;
        }));
        routes.put("hospital/admit", requireRole("HOSPITAL", (ex, s, b) -> {
            Map<String, Object> r = beds.admit(s.accountId(), str(b, "bedId"), str(b, "patientId"));
            audit.record(s, AuditService.PATIENT_ADMITTED,
                    "Patient " + r.get("patientId") + " admitted to bed " + r.get("bedNo")
                            + " (" + r.get("type") + ", " + r.get("ward") + ")");
            return r;
        }));
        routes.put("hospital/discharge", requireRole("HOSPITAL", (ex, s, b) -> {
            Map<String, Object> r = beds.discharge(s.accountId(), str(b, "bedId"));
            int released = equipment.releaseForBed(s.accountId(), str(b, "bedId"));
            Map<String, Object> bill = billing.generate(s.accountId(), r);
            r.put("bill", bill);
            r.put("equipmentReleased", released);
            audit.record(s, AuditService.PATIENT_DISCHARGED,
                    "Bed " + r.get("bedNo") + " freed (patient discharged)");
            audit.record(s, AuditService.BILL_GENERATED, "Bill " + bill.get("id") + " of "
                    + bill.get("amount") + " generated (" + bill.get("itemCount") + " charge item(s), "
                    + bill.get("days") + " day(s) at " + bill.get("ratePerDay") + "/day)"
                    + (released > 0 ? "; " + released + " equipment item(s) released and billed" : ""));
            return r;
        }));

        // ----- bed prices, wards, equipment prices -----
        routes.put("hospital/prices", requireRole("HOSPITAL", (ex, s, b) -> billing.prices(s.accountId())));
        routes.put("hospital/prices/set", requireRole("HOSPITAL", (ex, s, b) -> {
            Map<String, Double> prices = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : mapVal(b, "prices").entrySet()) {
                Object v = e.getValue();
                double d;
                if (v instanceof Number n) d = n.doubleValue();
                else {
                    try { d = Double.parseDouble(String.valueOf(v).trim()); }
                    catch (NumberFormatException nfe) {
                        throw new IllegalArgumentException("Price for '" + e.getKey() + "' must be a number.");
                    }
                }
                prices.put(e.getKey(), d);
            }
            Map<String, Object> r = billing.setPrices(s.accountId(), prices);
            @SuppressWarnings("unchecked")
            Map<String, Double> saved = (Map<String, Double>) r.get("prices");
            audit.record(s, AuditService.BED_PRICES_UPDATED, "Bed prices per day: GENERAL "
                    + saved.get("GENERAL") + ", ICU " + saved.get("ICU")
                    + ", VENTILATOR " + saved.get("VENTILATOR"));
            return r;
        }));
        routes.put("hospital/wards", requireRole("HOSPITAL", (ex, s, b) -> beds.wards(s.accountId())));
        routes.put("hospital/ward/save", requireRole("HOSPITAL", (ex, s, b) -> {
            Map<String, Object> r = beds.saveWard(s.accountId(), str(b, "name"),
                    opt(b, "floor"), intVal(b, "capacity"));
            audit.record(s, AuditService.WARD_SAVED, "Ward '" + r.get("name") + "' saved (capacity "
                    + r.get("capacity") + ", floor '" + r.get("floor") + "')");
            return r;
        }));
        routes.put("hospital/eqprices/set", requireRole("HOSPITAL", (ex, s, b) -> {
            Map<String, Double> prices = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : mapVal(b, "prices").entrySet()) {
                Object v = e.getValue();
                double d;
                if (v instanceof Number n) d = n.doubleValue();
                else {
                    try { d = Double.parseDouble(String.valueOf(v).trim()); }
                    catch (NumberFormatException nfe) {
                        throw new IllegalArgumentException("Price for '" + e.getKey() + "' must be a number.");
                    }
                }
                prices.put(e.getKey(), d);
            }
            Map<String, Object> r = equipment.setPrices(s.accountId(), prices);
            audit.record(s, AuditService.EQUIPMENT_PRICES_UPDATED,
                    "Equipment prices per day updated (" + ((Map<?, ?>) r.get("prices")).size() + " kinds)");
            return r;
        }));

        // ----- bed requests -----
        routes.put("bed/request", requireRole("PATIENT", (ex, s, b) -> {
            Map<String, Object> r = bedRequests.create(s.accountId(), str(b, "hospitalId"),
                    opt(b, "bedType"), opt(b, "reason"));
            audit.record(s, AuditService.BED_REQUESTED,
                    "Bed request (" + r.get("bedType") + ") sent to " + r.get("hospitalName"));
            return r;
        }));
        routes.put("bed/requests/mine", requireRole("PATIENT", (ex, s, b) -> bedRequests.forPatient(s.accountId())));
        routes.put("bed/request/cancel", requireRole("PATIENT", (ex, s, b) -> {
            Map<String, Object> r = bedRequests.cancel(s.accountId(), str(b, "requestId"));
            audit.record(s, AuditService.BED_REQUEST_CANCELLED, "Bed request " + r.get("id") + " cancelled");
            return r;
        }));
        routes.put("bed/requests", requireRole("HOSPITAL", (ex, s, b) -> bedRequests.forHospital(s.accountId())));
        routes.put("bed/request/decide", requireRole("HOSPITAL", (ex, s, b) -> {
            boolean approve = bool(b, "approve");
            Map<String, Object> r = bedRequests.decide(s.accountId(), str(b, "requestId"),
                    approve, opt(b, "note"), opt(b, "bedId"));
            if (approve) {
                audit.record(s, AuditService.BED_REQUEST_APPROVED, "Bed request " + r.get("id") + " approved - "
                        + r.get("patientName") + " admitted to bed " + r.get("bedNo"));
            } else {
                audit.record(s, AuditService.BED_REQUEST_REJECTED, "Bed request " + r.get("id") + " rejected"
                        + (r.get("decisionNote") == null || String.valueOf(r.get("decisionNote")).isEmpty()
                        ? "" : " (" + r.get("decisionNote") + ")"));
            }
            return r;
        }));

        // ----- hospital bills -----
        routes.put("bills/mine", requireRole("PATIENT", (ex, s, b) -> billing.forPatient(s.accountId())));
        routes.put("bill/pay", requireRole("PATIENT", (ex, s, b) -> {
            String billId = str(b, "billId");
            if (!"mock".equals(gw.name())) {
                Map<String, Object> info = billing.lookup(s.accountId(), billId);
                String url = gw.checkout("bill", billId, ((Number) info.get("amount")).doubleValue(),
                        cfg.chargeCurrency(), "Hospital bill " + billId + " - " + info.get("hospitalName"),
                        baseUrl, accountEmail(db, s));
                return Json.obj("checkout", url);
            }
            Map<String, Object> r = billing.pay(s.accountId(), billId, str(b, "method"));
            audit.record(s, AuditService.BILL_PAID, "Paid hospital bill " + r.get("id") + " of "
                    + r.get("amount") + " (" + r.get("hospitalName") + ") via " + r.get("method"));
            return r;
        }));
        routes.put("bill/received", requireRole("HOSPITAL", (ex, s, b) -> {
            Map<String, Object> r = billing.receive(s.accountId(), str(b, "billId"));
            audit.record(s, AuditService.BILL_SETTLED, "Cash payment of " + r.get("amount")
                    + " received from " + r.get("patientName") + " (bill " + r.get("id") + ")");
            return r;
        }));

        // ----- equipment -----
        routes.put("equipment/list", requireRole("HOSPITAL", (ex, s, b) -> equipment.list(s.accountId())));
        routes.put("equipment/add", requireRole("HOSPITAL", (ex, s, b) -> {
            Map<String, Object> r = equipment.add(s.accountId(), str(b, "kind"), opt(b, "label"));
            audit.record(s, AuditService.EQUIPMENT_ADDED, r.get("kind") + " '" + r.get("label") + "' added");
            return r;
        }));
        routes.put("equipment/assign", requireRole("HOSPITAL", (ex, s, b) -> {
            Map<String, Object> r = equipment.assign(s.accountId(), str(b, "equipmentId"), str(b, "bedId"));
            audit.record(s, AuditService.EQUIPMENT_ASSIGNED,
                    r.get("label") + " attached to bed " + r.get("bedNo"));
            return r;
        }));
        routes.put("equipment/release", requireRole("HOSPITAL", (ex, s, b) -> {
            Map<String, Object> r = equipment.release(s.accountId(), str(b, "equipmentId"));
            Object charged = r.get("chargedAmount");
            audit.record(s, AuditService.EQUIPMENT_RELEASED, r.get("label") + " released back to stock"
                    + (charged instanceof Number n && n.doubleValue() > 0
                    ? "; " + n.doubleValue() + " usage charge recorded for the patient's bill" : ""));
            return r;
        }));
        routes.put("equipment/status", requireRole("HOSPITAL", (ex, s, b) -> {
            Map<String, Object> r = equipment.setStatus(s.accountId(), str(b, "equipmentId"), str(b, "status"));
            audit.record(s, AuditService.EQUIPMENT_STATUS_CHANGED,
                    r.get("label") + " set to " + r.get("status"));
            return r;
        }));

        // ----- admin -----
        routes.put("admin/stats", requireRole("ADMIN", (ex, s, b) -> admin.stats()));
        routes.put("admin/doctors", requireRole("ADMIN", (ex, s, b) -> admin.doctors()));
        routes.put("admin/verify", requireRole("ADMIN", (ex, s, b) -> {
            boolean verified = bool(b, "verified");
            Map<String, Object> d = admin.setDoctorVerified(str(b, "doctorId"), verified);
            audit.record(s, verified ? AuditService.DOCTOR_VERIFIED : AuditService.DOCTOR_UNVERIFIED,
                    "Doctor " + d.get("name") + " (" + d.get("id") + ")");
            return d;
        }));
        routes.put("admin/accounts", requireRole("ADMIN", (ex, s, b) -> admin.accounts()));
        routes.put("admin/block", requireRole("ADMIN", (ex, s, b) -> {
            boolean blocked = bool(b, "blocked");
            Map<String, Object> acc = admin.setBlocked(s.accountId(), str(b, "role"), str(b, "id"), blocked);
            audit.record(s, blocked ? AuditService.ACCOUNT_BLOCKED : AuditService.ACCOUNT_UNBLOCKED,
                    acc.get("role") + " " + acc.get("name") + " (" + acc.get("id") + ")");
            return acc;
        }));
        routes.put("admin/audit", requireRole("ADMIN", (ex, s, b) -> audit.recent((int) longOpt(b, "limit", 50))));

        // ----- audit trail -----
        routes.put("audit/mine", requireAnyRole((ex, s, b) ->
                audit.mine(s.accountId(), accountEmail(db, s), 25), "PATIENT", "DOCTOR", "HOSPITAL", "ADMIN"));

        HttpServer server = https ? createHttpsServer(port) : HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", WebServer::serveIndex);
        server.createContext("/api", ex -> serveApi(ex, routes, sessions, rateLimiter, db, cfg.trustProxy()));
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        // Optional WebSocket server (Jetty) on port+2; the UI falls back to polling without it.
        try {
            WebSocketProvider p = (WebSocketProvider) Class.forName("ehms.ws.WsServer")
                    .getDeclaredConstructor().newInstance();
            wsPort[0] = p.start(db, sessions, port == 0 ? 0 : port + 2, https);
            wsHolder[0] = p;
        } catch (ClassNotFoundException absent) {
            Log.info("WebSockets: unavailable (Jetty not on the classpath) - chat and stats use polling");
        } catch (Throwable t) {
            Log.warn("WebSocket server could not start (falling back to polling): " + t);
        }

        if (https) startHttpRedirect(port);

        ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ehms-janitor");
            t.setDaemon(true);
            return t;
        });
        janitor.scheduleAtFixedRate(() -> {
            try {
                sessions.purgeExpired();
                loginGuard.purgeStale();
                rateLimiter.purgeStale();
                if (db.snapshotOccupancy()) db.save();
            } catch (Exception ignored) { }
        }, 5, 5, TimeUnit.MINUTES);

        AtomicReference<String> lastStats = new AtomicReference<>();
        janitor.scheduleAtFixedRate(() -> {
            try {
                String json = Json.write(Json.obj("type", "stats", "stats", db.stats(),
                        "daily", db.dailyStats(14)));
                String prev = lastStats.getAndSet(json);
                if (prev != null && !prev.equals(json) && wsHolder[0] != null) wsHolder[0].push("stats", json);
            } catch (Exception ignored) { }
        }, 10, 10, TimeUnit.SECONDS);

        return server;
    }

    // ------------------------------------------------- HTTPS

    private static HttpServer createHttpsServer(int port) throws IOException {
        try {
            char[] pass = KeystoreTool.password();
            KeyStore ks = KeyStore.getInstance(new File(KeystoreTool.KEYSTORE_FILE), pass);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pass);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), null, null);

            HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(ssl));
            return httpsServer;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not initialise HTTPS: " + e.getMessage(), e);
        }
    }

    private static void startHttpRedirect(int httpsPort) {
        try {
            HttpServer redirector = HttpServer.create(new InetSocketAddress(httpsPort + 1), 0);
            redirector.createContext("/", ex -> {
                try {
                    String host = ex.getRequestHeaders().getFirst("Host");
                    String location = (host == null ? "localhost" : host.replaceAll(":\\d+$", ""))
                            + ":" + httpsPort + "/";
                    ex.getResponseHeaders().add("Location", "https://" + location);
                    byte[] body = "Redirecting to the secure HTTPS site...".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(301, body.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(body); }
                } finally {
                    ex.close();
                }
            });
            redirector.setExecutor(Executors.newFixedThreadPool(2));
            redirector.start();
            Log.info("HTTP redirector on http://localhost:" + (httpsPort + 1) + "/ -> HTTPS");
        } catch (Exception e) {
            Log.warn("Could not start HTTP->HTTPS redirector: " + e.getMessage());
        }
    }

    // ------------------------------------------------- static UI + PWA + ACME

    private static void serveIndex(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                respond(ex, 405, "text/plain; charset=utf-8", "Method not allowed.".getBytes(StandardCharsets.UTF_8));
                return;
            }
            switch (ex.getRequestURI().getPath()) {
                case "/", "/index.html" ->
                        respond(ex, 200, "text/html; charset=utf-8", WebUi.index().getBytes(StandardCharsets.UTF_8));
                case "/manifest.webmanifest" ->
                        respond(ex, 200, "application/manifest+json; charset=utf-8",
                                PwaAssets.manifest().getBytes(StandardCharsets.UTF_8));
                case "/sw.js" ->
                        respond(ex, 200, "application/javascript; charset=utf-8",
                                PwaAssets.serviceWorker().getBytes(StandardCharsets.UTF_8));
                case "/icon-192.png" -> serveIcon(ex, 192);
                case "/icon-512.png" -> serveIcon(ex, 512);
                default -> serveStaticOrChallenge(ex);
            }
        } finally {
            ex.close();
        }
    }

    private static void serveIcon(HttpExchange ex, int size) throws IOException {
        byte[] png = PwaAssets.icon(size);
        if (png == null) respond(ex, 500, "text/plain; charset=utf-8",
                "Icon unavailable.".getBytes(StandardCharsets.UTF_8));
        else respond(ex, 200, "image/png", png, "public, max-age=86400");
    }

    private static void serveStaticOrChallenge(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.startsWith("/.well-known/acme-challenge/")) {
            String content = ChallengeHolder.get(path.substring(path.lastIndexOf('/') + 1));
            respond(ex, content == null ? 404 : 200, "text/plain; charset=utf-8",
                    (content == null ? "Not found." : content).getBytes(StandardCharsets.UTF_8));
        } else {
            respond(ex, 404, "text/plain; charset=utf-8", "Not found.".getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------- JSON API

    private static void serveApi(HttpExchange ex, Map<String, Route> routes,
                                 SessionManager sessions, RateLimiter rateLimiter,
                                 Database db, boolean trustProxy) {
        try {
            String method = ex.getRequestMethod();
            if ("OPTIONS".equals(method)) {
                cors(ex);
                ex.sendResponseHeaders(204, -1);
                return;
            }

            String path = ex.getRequestURI().getPath();
            String action = path.startsWith("/api/") ? path.substring("/api/".length()) : "";

            boolean binaryGet = "GET".equals(method) && ("file".equals(action)
                    || "prescription/pdf".equals(action) || "history/pdf".equals(action)
                    || "bill/pdf".equals(action) || "payment/stripe/return".equals(action)
                    || "payment/paystack/return".equals(action));
            if ("GET".equals(method) && !binaryGet) {
                respondJson(ex, 405, Json.obj("ok", false, "error", "Please use POST for API calls."));
                return;
            }

            String ip = clientIp(ex, trustProxy);
            if (!rateLimiter.tryAcquire(ip)) {
                respondJson(ex, 429, Json.obj("ok", false, "error",
                        "Too many requests from your address. Please wait a minute and try again."));
                return;
            }

            Session session = sessions.get(readCookie(ex, SessionManager.COOKIE_NAME));
            if (session != null) {
                Account acc = account(db, session);
                if (acc == null || acc.isBlocked()) {
                    sessions.destroy(session.token());
                    respondJson(ex, 401, Json.obj("ok", false, "error",
                            "Your account has been blocked by an administrator."));
                    return;
                }
            }

            Route route = routes.get(action);
            if (route == null) throw new IllegalArgumentException("Unknown endpoint: " + path);

            Log.debug("API " + action + "  " + (session == null
                    ? "(anonymous)" : "(" + session.role() + " " + session.accountId() + ")") + "  from " + ip);

            Map<String, Object> body = binaryGet ? queryParams(ex) : readBody(ex);
            Object result = route.handle(ex, session, body);

            if (result instanceof Redirect red) {
                cors(ex);
                ex.getResponseHeaders().add("Location", red.location());
                ex.sendResponseHeaders(302, -1);
                return;
            }
            if (result instanceof Binary bin) {
                ex.getResponseHeaders().add("Content-Disposition",
                        "inline; filename=\"" + sanitizeFileName(bin.fileName()) + "\"");
                respond(ex, 200, bin.contentType(), bin.bytes());
                return;
            }
            respond(ex, 200, "application/json; charset=utf-8",
                    Json.write(normalize(result)).getBytes(StandardCharsets.UTF_8));
        } catch (LoginGuard.LockedException e) {
            respondJson(ex, 429, Json.obj("ok", false, "error", e.getMessage()));
        } catch (AuthException e) {
            respondJson(ex, 401, Json.obj("ok", false, "error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            respondJson(ex, 400, Json.obj("ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            Log.error("Unhandled error while serving an API request", e);
            respondJson(ex, 500, Json.obj("ok", false, "error", "Internal server error: " + e));
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------- role guards

    private static Route requireRole(String role, Route inner) {
        return (ex, session, body) -> {
            if (session == null)
                throw new AuthException("Your session has expired. Please sign in again.");
            if (!role.equals(session.role()))
                throw new AuthException("This action is only available to signed-in " + role.toLowerCase() + "s.");
            return inner.handle(ex, session, body);
        };
    }

    private static Route requireAnyRole(Route inner, String... roles) {
        return (ex, session, body) -> {
            if (session == null)
                throw new AuthException("Please sign in to use this feature.");
            for (String role : roles) {
                if (role.equals(session.role())) return inner.handle(ex, session, body);
            }
            throw new AuthException("This action is not available for your account type.");
        };
    }

    // ------------------------------------------------- helpers

    private static Map<String, Object> accountPayload(Database db, Session s) {
        Account acc = account(db, s);
        Map<String, Object> m = acc != null ? acc.toMap() : new LinkedHashMap<>();
        m.put("role", s.role());
        return m;
    }

    private static String accountEmail(Database db, Session s) {
        Account acc = account(db, s);
        return acc == null ? null : acc.getEmail();
    }

    private static Account account(Database db, Session s) {
        return switch (s.role()) {
            case "DOCTOR"   -> db.doctors.get(s.accountId());
            case "PATIENT"  -> db.patients.get(s.accountId());
            case "HOSPITAL" -> db.hospitals.get(s.accountId());
            case "ADMIN"    -> db.admins.get(s.accountId());
            default -> null;
        };
    }

    private static String clientIp(HttpExchange ex, boolean trustProxy) {
        if (trustProxy) {
            String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",")[0].trim();
                if (!first.isEmpty()) return first;
            }
        }
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        return "0:0:0:0:0:0:0:1".equals(ip) ? "127.0.0.1" : ip;
    }

    private static String readCookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && name.equals(kv[0])) return kv[1];
            }
        }
        return null;
    }

    private static Object normalize(Object result) {
        if (result instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) m);
            copy.putIfAbsent("ok", true);
            return copy;
        }
        if (result instanceof List<?> l) return Json.obj("ok", true, "items", l);
        return Json.obj("ok", true, "value", result);
    }

    private static Map<String, Object> readBody(HttpExchange ex) throws IOException {
        String ctype = ex.getRequestHeaders().getFirst("Content-Type");
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (ctype != null && ctype.toLowerCase().startsWith("multipart/form-data")) {
            if (raw.length > Multipart.MAX_BYTES)
                throw new IllegalArgumentException("Upload too large - files are limited to 5 MB.");
            return Multipart.parse(ctype, raw);
        }
        if (raw.length == 0) return new LinkedHashMap<>();
        Object parsed = Json.parse(new String(raw, StandardCharsets.UTF_8));
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) parsed;
            return m;
        }
        throw new IllegalArgumentException("Request body must be a JSON object.");
    }

    private static Map<String, Object> queryParams(HttpExchange ex) {
        Map<String, Object> m = new LinkedHashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.isEmpty()) return m;
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) m.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                else m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) { }
        }
        return m;
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "download.bin";
        return name.replaceAll("[^A-Za-z0-9._ ()-]", "_");
    }

    private static void cors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    private static void respond(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        respond(ex, code, contentType, body, null);
    }

    private static void respond(HttpExchange ex, int code, String contentType, byte[] body,
                                String cacheControl) throws IOException {
        cors(ex);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", cacheControl == null ? "no-store" : cacheControl);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static void respondJson(HttpExchange ex, int code, Object payload) {
        try {
            respond(ex, code, "application/json; charset=utf-8",
                    Json.write(payload).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapVal(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v == null) return new LinkedHashMap<>();
        if (v instanceof Map) return (Map<String, Object>) v;
        throw new IllegalArgumentException("Field '" + key + "' must be a JSON object.");
    }

    private static String str(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v == null) throw new IllegalArgumentException("Missing field: " + key);
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Field '" + key + "' must not be empty.");
        return s;
    }

    private static String opt(Map<String, Object> b, String key) {
        Object v = b.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static int intVal(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null && !String.valueOf(v).isBlank()) {
            try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException ignored) { }
        }
        throw new IllegalArgumentException("Field '" + key + "' must be a whole number.");
    }

    private static int intOpt(Map<String, Object> b, String key, int defaultValue) {
        Object v = b.get(key);
        if (v == null || String.valueOf(v).isBlank()) return defaultValue;
        return intVal(b, key);
    }

    private static long longOpt(Map<String, Object> b, String key, long defaultValue) {
        Object v = b.get(key);
        if (v == null || String.valueOf(v).isBlank()) return defaultValue;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static double dbl(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v != null && !String.valueOf(v).isBlank()) {
            try { return Double.parseDouble(String.valueOf(v).trim()); } catch (NumberFormatException ignored) { }
        }
        throw new IllegalArgumentException("Field '" + key + "' must be a number.");
    }

    private static boolean bool(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v instanceof Boolean bo) return bo;
        if (v != null && !String.valueOf(v).isBlank()) return Boolean.parseBoolean(String.valueOf(v));
        throw new IllegalArgumentException("Field '" + key + "' must be true or false.");
    }
}