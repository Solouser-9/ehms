package ehms.service;

import ehms.db.Database;
import ehms.model.Hospital;

import java.util.Map;

public class HospitalService {

    private final Database db;
    private final BedService bedService;

    public HospitalService(Database db, BedService bedService) {
        this.db = db;
        this.bedService = bedService;
    }

    public Map<String, Object> register(String name, String email, String password, String phone,
                                        String address, int initialBeds) {
        Validation.require(name, "Hospital name");
        String mail = Validation.requireEmail(email);
        Validation.requirePassword(password);
        Validation.require(phone, "Phone");
        Validation.require(address, "Address");
        if (initialBeds < 0 || initialBeds > 500)
            throw new IllegalArgumentException("Initial beds must be between 0 and 500.");

        synchronized (db) {
            if (db.byEmail(db.hospitals, mail) != null)
                throw new IllegalArgumentException("A hospital is already registered with this email.");
            Hospital h = new Hospital(db.nextHospitalId(), name.trim(), mail, password,
                    phone.trim(), address.trim());
            db.hospitals.put(h.getId(), h);
            if (initialBeds > 0) bedService.addBeds(h.getId(), "GENERAL", initialBeds); // saves too
            else db.save();
            return h.toMap();
        }
    }
}