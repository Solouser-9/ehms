package ehms.service;

import ehms.db.Database;
import ehms.model.Bed;
import ehms.model.Bill;
import ehms.model.Charge;
import ehms.model.Equipment;
import ehms.model.Hospital;
import ehms.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hospital equipment: add items, attach to occupied beds, release, maintenance.
 * Release creates a usage charge (kind x days x price per day) that is pulled
 * into the patient's bill at discharge.
 */
public class EquipmentService {

    public static final List<String> KINDS = List.of("OXYGEN CYLINDER", "VENTILATOR", "OXYGEN CONCENTRATOR",
            "PATIENT MONITOR", "NEBULIZER", "DEFIBRILLATOR", "INFUSION PUMP", "CPAP MACHINE");

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final Database db;

    public EquipmentService(Database db) { this.db = db; }

    public Map<String, Object> add(String hospitalId, String kind, String label) {
        hospital(hospitalId);
        String k = Validation.require(kind, "Equipment kind");
        String l = label == null || label.isBlank() ? k.trim() : label.trim();
        if (l.length() > 100) throw new IllegalArgumentException("Label is limited to 100 characters.");
        synchronized (db) {
            Equipment e = new Equipment(db.nextEquipmentId(), hospitalId, k.trim(), l, System.currentTimeMillis());
            db.equipment.put(e.getId(), e);
            db.save();
            return view(e);
        }
    }

    public List<Map<String, Object>> list(String hospitalId) {
        hospital(hospitalId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Equipment e : db.equipment.values())
            if (e.getHospitalId().equals(hospitalId)) out.add(view(e));
        out.sort((a, b) -> String.valueOf(a.get("label")).compareToIgnoreCase(String.valueOf(b.get("label"))));
        return out;
    }

    public Map<String, Object> assign(String hospitalId, String equipmentId, String bedId) {
        hospital(hospitalId);
        synchronized (db) {
            Equipment e = equipment(hospitalId, equipmentId);
            Bed b = bed(hospitalId, bedId);
            if (!Equipment.AVAILABLE.equals(e.getStatus()))
                throw new IllegalArgumentException("Only AVAILABLE equipment can be attached to a bed.");
            if (b.isFree())
                throw new IllegalArgumentException(
                        "Equipment can only be attached to an occupied bed (a bed with a patient in it).");
            e.assign(b.getId(), b.getPatientId());
            db.save();
            return view(e);
        }
    }

    public Map<String, Object> release(String hospitalId, String equipmentId) {
        hospital(hospitalId);
        synchronized (db) {
            Equipment e = equipment(hospitalId, equipmentId);
            if (!Equipment.IN_USE.equals(e.getStatus()))
                throw new IllegalArgumentException("This equipment is not attached to any bed.");
            Map<String, Object> m = releaseOne(hospitalId, e);
            db.save();
            return m;
        }
    }

    /** AVAILABLE <-> MAINTENANCE; equipment in use must be released first. */
    public Map<String, Object> setStatus(String hospitalId, String equipmentId, String status) {
        hospital(hospitalId);
        String st = status == null ? "" : status.trim().toUpperCase();
        if (!Equipment.AVAILABLE.equals(st) && !Equipment.MAINTENANCE.equals(st))
            throw new IllegalArgumentException("Status must be AVAILABLE or MAINTENANCE.");
        synchronized (db) {
            Equipment e = equipment(hospitalId, equipmentId);
            if (Equipment.IN_USE.equals(e.getStatus()))
                throw new IllegalArgumentException("Release the equipment from its bed before changing its status.");
            e.setStatus(st);
            db.save();
            return view(e);
        }
    }

    /** Releases everything attached to a bed - called automatically when the patient is discharged. */
    public int releaseForBed(String hospitalId, String bedId) {
        int released = 0;
        synchronized (db) {
            for (Equipment e : db.equipment.values()) {
                if (e.getHospitalId().equals(hospitalId) && bedId != null && bedId.equals(e.getBedId())) {
                    releaseOne(hospitalId, e);
                    released++;
                }
            }
            if (released > 0) db.save();
        }
        return released;
    }

    // ---------------- equipment prices ----------------

    public Map<String, Object> prices(String hospitalId) {
        return Json.obj("prices", hospital(hospitalId).getEquipmentPrices());
    }

    public Map<String, Object> setPrices(String hospitalId, Map<String, Double> prices) {
        Hospital h = hospital(hospitalId);
        Map<String, Double> clean = new LinkedHashMap<>();
        for (String k : KINDS) clean.put(k, 0.0);
        if (prices != null) {
            for (Map.Entry<String, Double> e : prices.entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim().toUpperCase();
                if (!KINDS.contains(k))
                    throw new IllegalArgumentException("Unknown equipment kind: " + e.getKey());
                double v = e.getValue() == null ? 0 : e.getValue();
                if (v < 0) throw new IllegalArgumentException("Prices cannot be negative.");
                clean.put(k, v);
            }
        }
        synchronized (db) {
            h.setEquipmentPrices(clean);
            db.save();
        }
        return Json.obj("prices", h.getEquipmentPrices());
    }

    // ---------------- internals ----------------

    /** Releases one item and records the usage charge (kind x days x price/day). */
    private Map<String, Object> releaseOne(String hospitalId, Equipment e) {
        String patientId = e.getPatientId();
        String bedId = e.getBedId();
        long from = e.getAssignedAt();
        e.release();
        Map<String, Object> m = view(e);
        if (patientId == null || from <= 0) return m;   // legacy attachment or unknown start: no charge
        Hospital h = db.hospitals.get(hospitalId);
        double price = h == null ? 0 : h.priceForEquipment(e.getKind());
        if (price <= 0) return m;                       // this kind is priced at zero
        int days = Math.max(1, (int) Math.ceil((System.currentTimeMillis() - from) / DAY_MS));
        double amount = Bill.round2(days * price);
        Charge charge = new Charge(db.nextChargeId(), hospitalId, patientId, bedId, "EQUIPMENT",
                e.getKind() + " '" + e.getLabel() + "'", days, price, amount, System.currentTimeMillis());
        db.charges.put(charge.getId(), charge);
        m.put("chargedAmount", amount);
        m.put("chargeDays", days);
        return m;
    }

    private Map<String, Object> view(Equipment e) {
        Map<String, Object> m = e.toMap();
        if (e.getBedId() != null) {
            Bed b = db.beds.get(e.getBedId());
            m.put("bedNo", b == null ? null : b.getBedNo());
        }
        return m;
    }

    private Hospital hospital(String id) {
        Hospital h = id == null ? null : db.hospitals.get(id.trim());
        if (h == null) throw new IllegalArgumentException("Hospital not found: " + id);
        return h;
    }

    private Equipment equipment(String hospitalId, String equipmentId) {
        Equipment e = equipmentId == null ? null : db.equipment.get(equipmentId.trim());
        if (e == null || !e.getHospitalId().equals(hospitalId))
            throw new IllegalArgumentException("Equipment not found in your hospital: " + equipmentId);
        return e;
    }

    private Bed bed(String hospitalId, String bedId) {
        Bed b = bedId == null ? null : db.beds.get(bedId.trim());
        if (b == null || !b.getHospitalId().equals(hospitalId))
            throw new IllegalArgumentException("Bed not found in your hospital: " + bedId);
        return b;
    }
}