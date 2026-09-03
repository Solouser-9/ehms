package ehms.boot;

import ehms.db.Database;
import ehms.security.SessionManager.Session;
import ehms.service.AppointmentService;
import ehms.service.AuditService;
import ehms.service.BedRequestService;
import ehms.service.BillingService;
import ehms.service.ChatService;
import ehms.service.PaymentGateway;
import ehms.service.PaymentService;
import ehms.service.PharmacyService;
import ehms.service.ReportService;
import ehms.service.SlotService;
import ehms.util.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
public class PatientController {

    private final Database db;
    private final AppointmentService appointments;
    private final SlotService slots;
    private final ChatService chat;
    private final ReportService reports;
    private final PharmacyService pharmacy;
    private final PaymentService payments;
    private final BillingService billing;
    private final BedRequestService bedRequests;
    private final AuditService audit;
    private final PaymentGateway gateway;
    private final EhmsWebSocketHandler ws;
    private final EhmsProperties props;

    public PatientController(Database db, AppointmentService appointments, SlotService slots,
                             ChatService chat, ReportService reports, PharmacyService pharmacy,
                             PaymentService payments, BillingService billing, BedRequestService bedRequests,
                             AuditService audit, PaymentGateway gateway, EhmsWebSocketHandler ws,
                             EhmsProperties props) {
        this.db = db; this.appointments = appointments; this.slots = slots; this.chat = chat;
        this.reports = reports; this.pharmacy = pharmacy; this.payments = payments;
        this.billing = billing; this.bedRequests = bedRequests; this.audit = audit;
        this.gateway = gateway; this.ws = ws; this.props = props;
    }

    @PostMapping("/api/slots/for")
    public List<Map<String, Object>> slotsFor(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Params.requireRole(req, "PATIENT");
        return slots.openFor(Params.str(b, "doctorId"));
    }

    @PostMapping("/api/patient/book")
    public Map<String, Object> book(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "PATIENT");
        Map<String, Object> r = appointments.book(s.accountId(), Params.str(b, "doctorId"),
                Params.str(b, "slotId"), Params.str(b, "symptoms"));
        audit.record(s, AuditService.APPOINTMENT_BOOKED, "Consultation booked with Dr. " + r.get("doctorName"));
        return r;
    }

    @PostMapping("/api/patient/cancel")
    public Map<String, Object> cancel(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "PATIENT");
        Map<String, Object> r = appointments.cancel(s.accountId(), Params.str(b, "appointmentId"));
        audit.record(s, AuditService.APPOINTMENT_CANCELLED,
                "Consultation " + r.get("id") + " with Dr. " + r.get("doctorName") + " cancelled; slot released");
        return r;
    }

    @PostMapping("/api/patient/appointments")
    public List<Map<String, Object>> myAppointments(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "PATIENT");
        return appointments.forPatient(s.accountId(), Params.opt(b, "status"));
    }

    @PostMapping("/api/chat/messages")
    public Map<String, Object> chatMessages(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireAnyRole(req, "PATIENT", "DOCTOR");
        return chat.messages(s, Params.str(b, "appointmentId"), Params.longOpt(b, "after", 0));
    }

    @PostMapping("/api/chat/send")
    public Map<String, Object> chatSend(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireAnyRole(req, "PATIENT", "DOCTOR");
        Map<String, Object> r = chat.send(s, Params.str(b, "appointmentId"), Params.str(b, "text"));
        ws.push(Params.str(b, "appointmentId"), Json.write(Json.obj("type", "msg", "message", r)));
        return r;
    }

    @PostMapping("/api/chat/unread")
    public Map<String, Object> chatUnread(HttpServletRequest req) {
        Session s = Params.requireAnyRole(req, "PATIENT", "DOCTOR");
        return chat.unread(s);
    }

    @PostMapping(value = "/api/report/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam(value = "title", required = false) String title,
                                      @RequestParam("file") MultipartFile file,
                                      HttpServletRequest req) throws IOException {
        Session s = Params.requireRole(req, "PATIENT");
        Map<String, Object> r = reports.upload(s.accountId(), title,
                file.getOriginalFilename(), file.getContentType(), file.getBytes());
        audit.record(s, AuditService.REPORT_UPLOADED, "Report uploaded: " + r.get("title"));
        return r;
    }

    @PostMapping("/api/reports/mine")
    public List<Map<String, Object>> myReports(HttpServletRequest req) {
        Session s = Params.requireRole(req, "PATIENT");
        return reports.mine(s.accountId());
    }

    @PostMapping("/api/report/delete")
    public Map<String, Object> deleteReport(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "PATIENT");
        Map<String, Object> r = reports.delete(s.accountId(), Params.str(b, "id"));
        audit.record(s, AuditService.REPORT_DELETED, "Report deleted: " + r.get("title"));
        return r;
    }

    @PostMapping("/api/pharmacy/my")
    public List<Map<String, Object>> myMedicines(HttpServletRequest req) {
        Session s = Params.requireRole(req, "PATIENT");
        return pharmacy.forPatient(s.accountId());
    }

    @PostMapping("/api/payments/mine")
    public Map<String, Object> myPayments(HttpServletRequest req) {
        Session s = Params.requireRole(req, "PATIENT");
        return payments.mine(s.accountId());
    }

    @PostMapping("/api/payment/pay")
    public Map<String, Object> pay(@RequestBody Map<String, Object> b, HttpServletRequest req) throws Exception {
        Session s = Params.requireRole(req, "PATIENT");
        String paymentId = Params.str(b, "paymentId");
        if (!"mock".equals(gateway.name())) {
            Map<String, Object> info = payments.lookup(s.accountId(), paymentId);
            String baseUrl = props.publicUrl() != null ? props.publicUrl()
                    : req.getScheme() + "://" + req.getServerName() + ":" + req.getServerPort();
            String url = gateway.checkout("payment", paymentId, ((Number) info.get("amount")).doubleValue(),
                    props.stripeCurrency(), "Consultation - " + info.get("doctorName"), baseUrl,
                    Params.accountEmail(db, s));
            return Json.obj("checkout", url);
        }
        Map<String, Object> r = payments.pay(s.accountId(), paymentId, Params.str(b, "method"));
        audit.record(s, AuditService.PAYMENT_MADE,
                "Paid " + r.get("amount") + " to Dr. " + r.get("doctorName") + " via " + r.get("method"));
        return r;
    }

    @PostMapping("/api/bed/request")
    public Map<String, Object> requestBed(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "PATIENT");
        Map<String, Object> r = bedRequests.create(s.accountId(), Params.str(b, "hospitalId"),
                Params.opt(b, "bedType"), Params.opt(b, "reason"));
        audit.record(s, AuditService.BED_REQUESTED,
                "Bed request (" + r.get("bedType") + ") sent to " + r.get("hospitalName"));
        return r;
    }

    @PostMapping("/api/bed/requests/mine")
    public List<Map<String, Object>> myBedRequests(HttpServletRequest req) {
        Session s = Params.requireRole(req, "PATIENT");
        return bedRequests.forPatient(s.accountId());
    }

    @PostMapping("/api/bed/request/cancel")
    public Map<String, Object> cancelBedRequest(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "PATIENT");
        Map<String, Object> r = bedRequests.cancel(s.accountId(), Params.str(b, "requestId"));
        audit.record(s, AuditService.BED_REQUEST_CANCELLED, "Bed request " + r.get("id") + " cancelled");
        return r;
    }

    @PostMapping("/api/bills/mine")
    public List<Map<String, Object>> myBills(HttpServletRequest req) {
        Session s = Params.requireRole(req, "PATIENT");
        return billing.forPatient(s.accountId());
    }

    @PostMapping("/api/bill/pay")
    public Map<String, Object> payBill(@RequestBody Map<String, Object> b, HttpServletRequest req) throws Exception {
        Session s = Params.requireRole(req, "PATIENT");
        String billId = Params.str(b, "billId");
        if (!"mock".equals(gateway.name())) {
            Map<String, Object> info = billing.lookup(s.accountId(), billId);
            String baseUrl = props.publicUrl() != null ? props.publicUrl()
                    : req.getScheme() + "://" + req.getServerName() + ":" + req.getServerPort();
            String url = gateway.checkout("bill", billId, ((Number) info.get("amount")).doubleValue(),
                    props.stripeCurrency(), "Hospital bill " + billId + " - " + info.get("hospitalName"),
                    baseUrl, Params.accountEmail(db, s));
            return Json.obj("checkout", url);
        }
        Map<String, Object> r = billing.pay(s.accountId(), billId, Params.str(b, "method"));
        audit.record(s, AuditService.BILL_PAID, "Paid hospital bill " + r.get("id") + " of "
                + r.get("amount") + " (" + r.get("hospitalName") + ") via " + r.get("method"));
        return r;
    }
}