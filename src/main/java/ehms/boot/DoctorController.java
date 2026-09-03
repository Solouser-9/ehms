package ehms.boot;

import ehms.db.Database;
import ehms.security.SessionManager.Session;
import ehms.service.AppointmentService;
import ehms.service.AuditService;
import ehms.service.DoctorService;
import ehms.service.PaymentService;
import ehms.service.ReportService;
import ehms.service.SlotService;
import ehms.util.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class DoctorController {

    private final Database db;
    private final SlotService slots;
    private final AppointmentService appointments;
    private final DoctorService doctors;
    private final PaymentService payments;
    private final ReportService reports;
    private final AuditService audit;

    public DoctorController(Database db, SlotService slots, AppointmentService appointments,
                            DoctorService doctors, PaymentService payments, ReportService reports,
                            AuditService audit) {
        this.db = db; this.slots = slots; this.appointments = appointments;
        this.doctors = doctors; this.payments = payments; this.reports = reports; this.audit = audit;
    }

    @PostMapping("/api/slots/publish")
    public List<Map<String, Object>> publish(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "DOCTOR");
        return slots.publish(s.accountId(), Params.str(b, "date"), Params.str(b, "start"),
                Params.intVal(b, "duration"), Params.intVal(b, "count"));
    }

    @PostMapping("/api/slots/mine")
    public List<Map<String, Object>> mySlots(HttpServletRequest req) {
        Session s = Params.requireRole(req, "DOCTOR");
        return slots.forDoctor(s.accountId());
    }

    @PostMapping("/api/slots/delete")
    public Map<String, Object> deleteSlot(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "DOCTOR");
        return slots.delete(s.accountId(), Params.str(b, "slotId"));
    }

    @PostMapping("/api/doctor/appointments")
    public List<Map<String, Object>> myAppointments(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "DOCTOR");
        return appointments.forDoctor(s.accountId(), Params.opt(b, "status"));
    }

    @PostMapping("/api/doctor/consult")
    public Map<String, Object> consult(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "DOCTOR");
        Map<String, Object> r = appointments.consult(s.accountId(), Params.str(b, "appointmentId"),
                Params.str(b, "diagnosis"), Params.str(b, "prescription"));
        payments.createDue(db.appointments.get(String.valueOf(r.get("id"))));
        audit.record(s, AuditService.PRESCRIPTION_ISSUED,
                "Prescription issued to " + r.get("patientName") + " (" + r.get("id") + ")");
        return r;
    }

    @PostMapping("/api/doctor/availability")
    public Map<String, Object> availability(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "DOCTOR");
        boolean available = Params.bool(b, "available");
        doctors.setAvailability(s.accountId(), available);
        audit.record(s, AuditService.AVAILABILITY_CHANGED,
                "Now " + (available ? "available" : "unavailable") + " for new consultation requests");
        return Json.obj("available", available);
    }

    @PostMapping("/api/payments/doctor")
    public Map<String, Object> myEarnings(HttpServletRequest req) {
        Session s = Params.requireRole(req, "DOCTOR");
        return payments.forDoctor(s.accountId());
    }

    @PostMapping("/api/payment/received")
    public Map<String, Object> received(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "DOCTOR");
        Map<String, Object> r = payments.receive(s.accountId(), Params.str(b, "paymentId"));
        audit.record(s, AuditService.PAYMENT_RECORDED,
                "Cash fee of " + r.get("amount") + " received from " + r.get("patientName"));
        return r;
    }

    @PostMapping("/api/reports/patient")
    public List<Map<String, Object>> patientReports(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "DOCTOR");
        return reports.forPatient(s.accountId(), Params.str(b, "patientId"));
    }
}