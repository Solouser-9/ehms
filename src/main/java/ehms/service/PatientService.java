package ehms.service;

import ehms.db.Database;
import ehms.model.Patient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PatientService {

    private final Database db;

    public PatientService(Database db) { this.db = db; }

    public Map<String, Object> register(String name, String email, String password, String phone,
                                        int age, String gender, String bloodGroup, String address) {
        Validation.require(name, "Name");
        String mail = Validation.requireEmail(email);
        Validation.requirePassword(password);
        Validation.require(phone, "Phone");
        if (age < 1 || age > 120) throw new IllegalArgumentException("Age must be between 1 and 120.");

        synchronized (db) {
            if (db.byEmail(db.patients, mail) != null)
                throw new IllegalArgumentException("A patient is already registered with this email.");
            Patient p = new Patient(db.nextPatientId(), name.trim(), mail, password, phone.trim(),
                    age, Validation.optional(gender), Validation.optional(bloodGroup), Validation.optional(address));
            db.patients.put(p.getId(), p);
            db.save();
            return p.toMap();
        }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Patient p : db.patients.values()) out.add(p.toMap());
        out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return out;
    }

    public Patient requirePatient(String id) {
        Patient p = id == null ? null : db.patients.get(id.trim());
        if (p == null) throw new IllegalArgumentException("Patient not found: " + id);
        return p;
    }
}