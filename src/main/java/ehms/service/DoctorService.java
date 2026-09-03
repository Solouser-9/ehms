package ehms.service;

import ehms.db.Database;
import ehms.model.Doctor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DoctorService {

    private final Database db;

    public DoctorService(Database db) { this.db = db; }

    public Map<String, Object> register(String name, String email, String password, String phone,
                                        String specialization, String licenseNo, double fee) {
        Validation.require(name, "Name");
        String mail = Validation.requireEmail(email);
        Validation.requirePassword(password);
        Validation.require(phone, "Phone");
        Validation.require(specialization, "Specialisation");
        Validation.require(licenseNo, "Medical licence number");
        if (fee < 0) throw new IllegalArgumentException("Consultation fee cannot be negative.");

        synchronized (db) {
            if (db.byEmail(db.doctors, mail) != null)
                throw new IllegalArgumentException("A doctor is already registered with this email.");
            Doctor d = new Doctor(db.nextDoctorId(), name.trim(), mail, password, phone.trim(),
                    specialization.trim(), licenseNo.trim(), fee);
            db.doctors.put(d.getId(), d);
            db.save();
            return d.toMap();
        }
    }

    public List<Map<String, Object>> list(String specialization) {
        List<Map<String, Object>> out = new ArrayList<>();
        String filter = specialization == null ? null : specialization.trim().toLowerCase();
        for (Doctor d : db.doctors.values()) {
            if (filter != null && !filter.isEmpty()
                    && !d.getSpecialization().toLowerCase().contains(filter)) continue;
            out.add(d.toMap());
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return out;
    }

    public void setAvailability(String doctorId, boolean available) {
        Doctor d = requireDoctor(doctorId);
        synchronized (db) {
            d.setAvailable(available);
            db.save();
        }
    }

    public Doctor requireDoctor(String id) {
        Doctor d = id == null ? null : db.doctors.get(id.trim());
        if (d == null) throw new IllegalArgumentException("Doctor not found: " + id);
        return d;
    }
}