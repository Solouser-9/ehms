package ehms.service;

import ehms.db.Database;
import ehms.model.Appointment;
import ehms.model.Doctor;
import ehms.model.Patient;
import ehms.model.Payment;
import ehms.util.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Consultation-fee payments recorded against completed appointments. */
public class PaymentService {

    private static final Set<String> METHODS = Set.of("UPI", "CARD", "NETBANKING", "WALLET");

    private final Database db;

    public PaymentService(Database db) { this.db = db; }

    /** Called right after a consultation completes: records the due fee (appointment snapshot wins). */
    public void createDue(Appointment a) {
        if (a == null || !a.isCompleted()) return;
        for (Payment existing : db.payments.values())
            if (existing.getAppointmentId().equals(a.getId())) return;      // already recorded
        Doctor d = db.doctors.get(a.getDoctorId());
        Patient p = db.patients.get(a.getPatientId());
        // The appointment's fee snapshot wins; only legacy appointments (fee == 0) fall back.
        double fee = a.getFee() > 0 ? a.getFee() : (d != null ? d.getFee() : 0);
        if (fee <= 0) return;
        synchronized (db) {
            Payment pay = new Payment(db.nextPaymentId(), a.getId(), a.getPatientId(),
                    p == null ? "?" : p.getName(), a.getDoctorId(), d == null ? "?" : d.getName(),
                    fee, System.currentTimeMillis());
            db.payments.put(pay.getId(), pay);
            db.save();
        }
    }

    public Map<String, Object> mine(String patientId) {
        List<Map<String, Object>> items = new ArrayList<>();
        double due = 0, paid = 0;
        for (Payment pay : db.payments.values()) {
            if (!pay.getPatientId().equals(patientId)) continue;
            items.add(pay.toMap());
            if (pay.isPaid()) paid += pay.getAmount(); else due += pay.getAmount();
        }
        items.sort(DESC);
        return Json.obj("items", items, "totalDue", round(due), "totalPaid", round(paid));
    }

    public Map<String, Object> forDoctor(String doctorId) {
        List<Map<String, Object>> items = new ArrayList<>();
        double earned = 0, outstanding = 0;
        for (Payment pay : db.payments.values()) {
            if (!pay.getDoctorId().equals(doctorId)) continue;
            items.add(pay.toMap());
            if (pay.isPaid()) earned += pay.getAmount(); else outstanding += pay.getAmount();
        }
        items.sort(DESC);
        return Json.obj("items", items, "earned", round(earned), "outstanding", round(outstanding));
    }

    /** Read-only view for gateway checkout creation. */
    public Map<String, Object> lookup(String patientId, String paymentId) {
        return of(patientId, paymentId).toMap();
    }

    public Map<String, Object> pay(String patientId, String paymentId, String method) {
        String m = method == null ? "" : method.trim().toUpperCase();
        if (!METHODS.contains(m))
            throw new IllegalArgumentException("Payment method must be one of " + METHODS + ".");
        synchronized (db) {
            Payment pay = of(patientId, paymentId);
            if (pay.isPaid()) throw new IllegalArgumentException("This payment was already settled.");
            pay.pay(m);
            db.save();
            return pay.toMap();
        }
    }

    /** Marks a payment settled after server-side gateway verification. */
    public void gatewayPaid(String paymentId, String method) {
        synchronized (db) {
            Payment pay = paymentId == null ? null : db.payments.get(paymentId.trim());
            if (pay == null) throw new IllegalArgumentException("Payment not found: " + paymentId);
            if (!pay.isPaid()) { pay.pay(method); db.save(); }
        }
    }

    /** The doctor records that the fee was handed over in cash. */
    public Map<String, Object> receive(String doctorId, String paymentId) {
        synchronized (db) {
            Payment pay = paymentId == null ? null : db.payments.get(paymentId.trim());
            if (pay == null || !pay.getDoctorId().equals(doctorId))
                throw new IllegalArgumentException("Payment not found: " + paymentId);
            if (pay.isPaid()) throw new IllegalArgumentException("This payment was already settled.");
            pay.pay("CASH");
            db.save();
            return pay.toMap();
        }
    }

    private Payment of(String patientId, String paymentId) {
        Payment pay = paymentId == null ? null : db.payments.get(paymentId.trim());
        if (pay == null || !pay.getPatientId().equals(patientId))
            throw new IllegalArgumentException("Payment not found: " + paymentId);
        return pay;
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }

    private static final Comparator<Map<String, Object>> DESC =
            (a, b) -> Long.compare((Long) b.get("createdAt"), (Long) a.get("createdAt"));
}