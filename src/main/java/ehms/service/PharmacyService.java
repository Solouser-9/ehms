package ehms.service;

import ehms.db.Database;
import ehms.model.Dispense;
import ehms.model.Hospital;
import ehms.model.Medicine;
import ehms.model.Patient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Hospital pharmacy: medicine catalogue, stock levels, restocking and dispensing. */
public class PharmacyService {

    private final Database db;

    public PharmacyService(Database db) { this.db = db; }

    public List<Map<String, Object>> list(String hospitalId) {
        hospital(hospitalId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Medicine m : db.medicines.values()) {
            if (!m.getHospitalId().equals(hospitalId)) continue;
            Map<String, Object> v = m.toMap();
            v.put("lowStock", m.getStock() <= m.getReorderLevel());
            out.add(v);
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return out;
    }

    public Map<String, Object> add(String hospitalId, String name, String unit,
                                   int stock, int reorderLevel, double price) {
        hospital(hospitalId);
        String n = Validation.require(name, "Medicine name");
        if (stock < 0 || stock > 1_000_000)
            throw new IllegalArgumentException("Stock must be between 0 and 1,000,000.");
        if (reorderLevel < 0) throw new IllegalArgumentException("Reorder level cannot be negative.");
        if (price < 0) throw new IllegalArgumentException("Price cannot be negative.");
        synchronized (db) {
            Medicine m = new Medicine(db.nextMedicineId(), hospitalId, n,
                    unit == null || unit.isBlank() ? "units" : unit.trim(), stock, reorderLevel, price);
            db.medicines.put(m.getId(), m);
            db.save();
            return m.toMap();
        }
    }

    public Map<String, Object> restock(String hospitalId, String medicineId, int qty) {
        hospital(hospitalId);
        if (qty < 1 || qty > 100_000) throw new IllegalArgumentException("Quantity must be between 1 and 100,000.");
        synchronized (db) {
            Medicine m = medicine(hospitalId, medicineId);
            m.setStock(m.getStock() + qty);
            db.save();
            return m.toMap();
        }
    }

    public Map<String, Object> dispense(String hospitalId, String patientId,
                                        String medicineId, int qty, String note) {
        hospital(hospitalId);
        Patient p = patient(patientId);
        if (qty < 1) throw new IllegalArgumentException("Quantity must be at least 1.");
        synchronized (db) {
            Medicine m = medicine(hospitalId, medicineId);
            if (qty > m.getStock())
                throw new IllegalArgumentException("Only " + m.getStock() + " " + m.getUnit()
                        + " of " + m.getName() + " in stock.");
            m.setStock(m.getStock() - qty);
            // The unit price is SNAPSHOTTED at dispense time so hospital bills
            // never change retroactively when prices are updated later.
            Dispense d = new Dispense(db.nextDispenseId(), hospitalId, m.getId(), m.getName(),
                    p.getId(), qty, m.getUnit(), System.currentTimeMillis(),
                    note == null ? "" : note.trim(), m.getPrice());
            db.dispenses.put(d.getId(), d);
            db.save();
            Map<String, Object> out = d.toMap();
            out.put("patientName", p.getName());
            out.put("remainingStock", m.getStock());
            return out;
        }
    }

    public List<Map<String, Object>> history(String hospitalId) {
        hospital(hospitalId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dispense d : db.dispenses.values()) {
            if (!d.getHospitalId().equals(hospitalId)) continue;
            Map<String, Object> m = d.toMap();
            Patient p = db.patients.get(d.getPatientId());
            m.put("patientName", p == null ? "?" : p.getName());
            out.add(m);
        }
        out.sort((a, b) -> Long.compare((Long) b.get("dispensedAt"), (Long) a.get("dispensedAt")));
        return out;
    }

    public List<Map<String, Object>> forPatient(String patientId) {
        patient(patientId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dispense d : db.dispenses.values()) {
            if (!d.getPatientId().equals(patientId)) continue;
            Map<String, Object> m = d.toMap();
            Hospital h = db.hospitals.get(d.getHospitalId());
            m.put("hospitalName", h == null ? "?" : h.getName());
            out.add(m);
        }
        out.sort((a, b) -> Long.compare((Long) b.get("dispensedAt"), (Long) a.get("dispensedAt")));
        return out;
    }

    private Hospital hospital(String id) {
        Hospital h = id == null ? null : db.hospitals.get(id.trim());
        if (h == null) throw new IllegalArgumentException("Hospital not found: " + id);
        return h;
    }

    private Medicine medicine(String hospitalId, String medicineId) {
        Medicine m = medicineId == null ? null : db.medicines.get(medicineId.trim());
        if (m == null || !m.getHospitalId().equals(hospitalId))
            throw new IllegalArgumentException("Medicine not found in your pharmacy: " + medicineId);
        return m;
    }

    private Patient patient(String id) {
        Patient p = id == null ? null : db.patients.get(id.trim());
        if (p == null) throw new IllegalArgumentException("Patient not found: " + id);
        return p;
    }
}