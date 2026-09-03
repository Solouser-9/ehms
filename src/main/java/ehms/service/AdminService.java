package ehms.service;

import ehms.db.Database;
import ehms.model.Account;
import ehms.model.Admin;
import ehms.model.BedRequest;
import ehms.model.Bill;
import ehms.model.Doctor;
import ehms.model.Equipment;
import ehms.model.Medicine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Administrator functions: licence verification, account blocking, global reports. */
public class AdminService {

    private final Database db;
    private final String adminKey;

    public AdminService(Database db, String adminKey) {
        this.db = db;
        this.adminKey = (adminKey == null || adminKey.isBlank()) ? "ehms-admin-key" : adminKey;
    }

    public Map<String, Object> register(String name, String email, String password, String key) {
        Validation.require(name, "Name");
        String mail = Validation.requireEmail(email);
        Validation.requirePassword(password);
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                adminKey.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Invalid administrator key. The operator who deployed this "
                    + "system can give you the key (default: ehms-admin-key, change with --admin-key).");
        synchronized (db) {
            if (db.byEmail(db.admins, mail) != null)
                throw new IllegalArgumentException("An administrator is already registered with this email.");
            Admin a = new Admin(db.nextAdminId(), name.trim(), mail, password);
            db.admins.put(a.getId(), a);
            db.save();
            return a.toMap();
        }
    }

    /** Global statistics covering every subsystem: consultations, payments, beds, requests, bills, pharmacy, equipment. */
    public Map<String, Object> stats() {
        double collected = 0, bedRevenue = 0;
        long paidCount = 0, dueCount = 0, billsPaid = 0, billsDue = 0;
        for (var pay : db.payments.values()) {
            if (pay.isPaid()) { collected += pay.getAmount(); paidCount++; } else dueCount++;
        }
        for (Bill b : db.bills.values()) {
            if (b.isPaid()) { bedRevenue += b.getAmount(); billsPaid++; } else billsDue++;
        }
        int unverified = 0, blocked = 0;
        for (Doctor d : db.doctors.values()) {
            if (!d.isVerified()) unverified++;
            if (d.isBlocked()) blocked++;
        }
        for (var p : db.patients.values()) if (p.isBlocked()) blocked++;
        for (var h : db.hospitals.values()) if (h.isBlocked()) blocked++;
        for (var a : db.admins.values()) if (a.isBlocked()) blocked++;

        int pendingBedRequests = 0;
        for (BedRequest r : db.bedRequests.values())
            if (BedRequest.PENDING.equals(r.getStatus())) pendingBedRequests++;

        int lowStock = 0;
        for (Medicine m : db.medicines.values())
            if (m.getStock() <= m.getReorderLevel()) lowStock++;

        int eqAvailable = 0, eqInUse = 0, eqMaintenance = 0;
        for (Equipment e : db.equipment.values()) {
            switch (e.getStatus()) {
                case Equipment.IN_USE -> eqInUse++;
                case Equipment.MAINTENANCE -> eqMaintenance++;
                default -> eqAvailable++;
            }
        }

        Map<String, Object> s = db.stats();
        s.put("revenueCollected", Math.round(collected * 100) / 100.0);
        s.put("paymentsPaid", paidCount);
        s.put("paymentsDue", dueCount);
        s.put("pendingVerifications", unverified);
        s.put("blockedAccounts", blocked);
        s.put("pendingBedRequests", pendingBedRequests);
        s.put("bedRevenueCollected", Math.round(bedRevenue * 100) / 100.0);
        s.put("billsPaid", billsPaid);
        s.put("billsDue", billsDue);
        s.put("medicinesLowStock", lowStock);
        s.put("equipmentAvailable", eqAvailable);
        s.put("equipmentInUse", eqInUse);
        s.put("equipmentMaintenance", eqMaintenance);
        return s;
    }

    public List<Map<String, Object>> doctors() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Doctor d : db.doctors.values()) out.add(d.toMap());
        out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return out;
    }

    public Map<String, Object> setDoctorVerified(String doctorId, boolean verified) {
        synchronized (db) {
            Doctor d = doctor(doctorId);
            d.setVerified(verified);
            db.save();
            return d.toMap();
        }
    }

    public List<Map<String, Object>> accounts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Doctor d : db.doctors.values()) out.add(row("DOCTOR", d, d.getSpecialization()));
        for (var p : db.patients.values()) out.add(row("PATIENT", p, "age " + p.getAge()));
        for (var h : db.hospitals.values()) out.add(row("HOSPITAL", h, h.getAddress()));
        for (var a : db.admins.values()) out.add(row("ADMIN", a, "administrator"));
        out.sort((x, y) -> {
            int c = String.valueOf(x.get("role")).compareTo(String.valueOf(y.get("role")));
            return c != 0 ? c : String.valueOf(x.get("name")).compareToIgnoreCase(String.valueOf(y.get("name")));
        });
        return out;
    }

    /** Blocks or unblocks any account; blocked accounts cannot sign in and live sessions die. */
    public Map<String, Object> setBlocked(String actingAdminId, String role, String accountId, boolean blocked) {
        String r = role == null ? "" : role.trim().toUpperCase();
        synchronized (db) {
            Account acc = switch (r) {
                case "DOCTOR"   -> doctor(accountId);
                case "PATIENT"  -> patient(accountId);
                case "HOSPITAL" -> hospital(accountId);
                case "ADMIN"    -> admin(accountId);
                default -> throw new IllegalArgumentException("Invalid role: " + role);
            };
            if ("ADMIN".equals(r) && acc.getId().equals(actingAdminId) && blocked)
                throw new IllegalArgumentException("You cannot block your own administrator account.");
            acc.setBlocked(blocked);
            db.save();
            Map<String, Object> m = acc.toMap();
            m.put("role", r);
            m.put("blocked", acc.isBlocked());
            return m;
        }
    }

    private Doctor doctor(String id) {
        Doctor d = id == null ? null : db.doctors.get(id.trim());
        if (d == null) throw new IllegalArgumentException("Doctor not found: " + id);
        return d;
    }

    private Account patient(String id) {
        Account a = id == null ? null : db.patients.get(id.trim());
        if (a == null) throw new IllegalArgumentException("Patient not found: " + id);
        return a;
    }

    private Account hospital(String id) {
        Account a = id == null ? null : db.hospitals.get(id.trim());
        if (a == null) throw new IllegalArgumentException("Hospital not found: " + id);
        return a;
    }

    private Account admin(String id) {
        Account a = id == null ? null : db.admins.get(id.trim());
        if (a == null) throw new IllegalArgumentException("Administrator not found: " + id);
        return a;
    }

    private static Map<String, Object> row(String role, Account acc, String details) {
        Map<String, Object> m = acc.toMap();
        m.put("role", role);
        m.put("blocked", acc.isBlocked());
        m.put("details", details);
        return m;
    }
}