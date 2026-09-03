package ehms.service;

import ehms.db.Database;
import ehms.model.Bill;
import ehms.model.Charge;
import ehms.model.Dispense;
import ehms.model.Hospital;
import ehms.model.Patient;
import ehms.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bed prices per hospital and the CONSOLIDATED bills generated when patients
 * are discharged: one itemised invoice covering the bed stay, medicines
 * dispensed during the stay, and equipment usage charges. Consultation fees
 * are deliberately excluded - they belong to the doctor, a different payee.
 */
public class BillingService {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final Set<String> METHODS = Set.of("UPI", "CARD", "NETBANKING", "WALLET");

    private final Database db;
    private final boolean prorate;        // --prorate: charge rate x hours/24 (min a quarter day)

    public BillingService(Database db) { this(db, false); }

    public BillingService(Database db, boolean prorate) {
        this.db = db;
        this.prorate = prorate;
    }

    public Map<String, Object> prices(String hospitalId) {
        return Json.obj("prices", hospital(hospitalId).getBedPrices());
    }

    public Map<String, Object> setPrices(String hospitalId, Map<String, Double> prices) {
        Hospital h = hospital(hospitalId);
        Map<String, Double> clean = new LinkedHashMap<>();
        for (String type : BedService.BED_TYPES) clean.put(type, 0.0);
        if (prices != null) {
            for (Map.Entry<String, Double> e : prices.entrySet()) {
                String t = e.getKey() == null ? "" : e.getKey().trim().toUpperCase();
                if (!BedService.BED_TYPES.contains(t))
                    throw new IllegalArgumentException("Unknown bed type: " + e.getKey());
                double v = e.getValue() == null ? 0 : e.getValue();
                if (v < 0) throw new IllegalArgumentException("Prices cannot be negative.");
                clean.put(t, v);
            }
        }
        synchronized (db) {
            h.setBedPrices(clean);
            db.save();
        }
        return Json.obj("prices", h.getBedPrices());
    }

    /**
     * Creates the consolidated bill for a finished stay. {@code discharged} is the map
     * returned by BedService.discharge (bed id, number, type, ward, patient, times,
     * and the admission-time rate snapshot).
     */
    public Map<String, Object> generate(String hospitalId, Map<String, Object> discharged) {
        Hospital h = hospital(hospitalId);
        String patientId = String.valueOf(discharged.get("patientId"));
        Patient p = db.patients.get(patientId);
        long admittedAt = ((Number) discharged.get("admittedAt")).longValue();
        long dischargedAt = ((Number) discharged.get("dischargedAt")).longValue();
        String type = String.valueOf(discharged.get("type"));
        int bedNo = ((Number) discharged.get("bedNo")).intValue();
        String ward = String.valueOf(discharged.get("ward"));
        Object rateObj = discharged.get("ratePerDay");
        double rate = rateObj instanceof Number n ? n.doubleValue() : h.priceFor(type);
        String bedId = discharged.get("id") == null ? null : String.valueOf(discharged.get("id"));
        long stayMs = Math.max(0, dischargedAt - admittedAt);
        int days = days(admittedAt, dischargedAt);
        double bedAmount = prorate
                ? Math.max(Bill.round2(rate * stayMs / DAY_MS), Bill.round2(rate / 4))
                : Bill.round2(days * rate);

        synchronized (db) {
            Bill bill = new Bill(db.nextBillId(), h.getId(), patientId,
                    p == null ? "?" : p.getName(), bedNo, type, ward,
                    admittedAt, dischargedAt, days, rate, 0, System.currentTimeMillis());
            bill.addLine("BED", "Bed " + bedNo + " (" + type
                    + (ward == null || "null".equals(ward) || ward.isEmpty() ? "" : ", " + ward)
                    + ") - " + days + " day(s) x " + price(rate) + "/day"
                    + (prorate ? " (prorated)" : ""), days, rate, bedAmount);

            // Pharmacy: everything this hospital dispensed to the patient during the stay.
            for (Dispense d : db.dispenses.values()) {
                if (d.getHospitalId().equals(h.getId()) && d.getPatientId().equals(patientId)
                        && d.getDispensedAt() >= admittedAt && d.getDispensedAt() <= dischargedAt) {
                    double amount = Bill.round2(d.getQty() * d.getUnitPrice());
                    bill.addLine("PHARMACY", d.getMedicineName() + " (" + d.getQty() + " "
                            + d.getUnit() + ")", d.getQty(), d.getUnitPrice(), amount);
                }
            }

            // Equipment: pending usage charges recorded for this patient on this bed.
            List<String> consumed = new ArrayList<>();
            for (Charge c : db.charges.values()) {
                if (c.getHospitalId().equals(h.getId()) && patientId.equals(c.getPatientId())
                        && bedId != null && bedId.equals(c.getBedId())) {
                    bill.addLine("EQUIPMENT", c.getLabel() + " - " + (int) c.getQty()
                            + " day(s) x " + price(c.getUnitPrice()) + "/day",
                            c.getQty(), c.getUnitPrice(), c.getAmount());
                    consumed.add(c.getId());
                }
            }
            for (String id : consumed) db.charges.remove(id);

            db.bills.put(bill.getId(), bill);
            db.save();
            return view(bill);
        }
    }

    public List<Map<String, Object>> forHospital(String hospitalId) {
        hospital(hospitalId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Bill b : db.bills.values())
            if (b.getHospitalId().equals(hospitalId)) out.add(view(b));
        out.sort((x, y) -> Long.compare((Long) y.get("createdAt"), (Long) x.get("createdAt")));
        return out;
    }

    public List<Map<String, Object>> forPatient(String patientId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Bill b : db.bills.values())
            if (b.getPatientId().equals(patientId)) out.add(view(b));
        out.sort((x, y) -> Long.compare((Long) y.get("createdAt"), (Long) x.get("createdAt")));
        return out;
    }

    public Map<String, Object> pay(String patientId, String billId, String method) {
        String m = method == null ? "" : method.trim().toUpperCase();
        if (!METHODS.contains(m))
            throw new IllegalArgumentException("Payment method must be one of " + METHODS + ".");
        synchronized (db) {
            Bill b = of(patientId, billId);
            if (b.isPaid()) throw new IllegalArgumentException("This bill was already settled.");
            b.pay(m);
            db.save();
            return view(b);
        }
    }

    /** The hospital records that the bill was settled in cash. */
    public Map<String, Object> receive(String hospitalId, String billId) {
        synchronized (db) {
            Bill b = billId == null ? null : db.bills.get(billId.trim());
            if (b == null || !b.getHospitalId().equals(hospitalId))
                throw new IllegalArgumentException("Bill not found: " + billId);
            if (b.isPaid()) throw new IllegalArgumentException("This bill was already settled.");
            b.pay("CASH");
            db.save();
            return view(b);
        }
    }

    /** Read-only view for gateway checkout creation. */
    public Map<String, Object> lookup(String patientId, String billId) {
        return view(of(patientId, billId));
    }

    /** Marks a bill settled after server-side gateway verification. */
    public void gatewayPaid(String billId, String method) {
        synchronized (db) {
            Bill b = billId == null ? null : db.bills.get(billId.trim());
            if (b == null) throw new IllegalArgumentException("Bill not found: " + billId);
            if (!b.isPaid()) { b.pay(method); db.save(); }
        }
    }

    private Bill of(String patientId, String billId) {
        Bill b = billId == null ? null : db.bills.get(billId.trim());
        if (b == null || !b.getPatientId().equals(patientId))
            throw new IllegalArgumentException("Bill not found: " + billId);
        return b;
    }

    private Map<String, Object> view(Bill b) {
        Map<String, Object> m = b.toMap();
        Hospital h = db.hospitals.get(b.getHospitalId());
        m.put("hospitalName", h == null ? "?" : h.getName());
        return m;
    }

    private Hospital hospital(String id) {
        Hospital h = id == null ? null : db.hospitals.get(id.trim());
        if (h == null) throw new IllegalArgumentException("Hospital not found: " + id);
        return h;
    }

    /** Days stayed, rounded up, minimum one day. */
    private static int days(long from, long to) {
        long ms = Math.max(0, to - from);
        return (int) Math.max(1, (ms + DAY_MS - 1) / DAY_MS);
    }

    private static String price(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}