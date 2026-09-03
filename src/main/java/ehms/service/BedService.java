package ehms.service;

import ehms.db.Database;
import ehms.model.Bed;
import ehms.model.Hospital;
import ehms.model.Patient;
import ehms.util.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BedService {

    public static final List<String> BED_TYPES = List.of("GENERAL", "ICU", "VENTILATOR");
    public static final String DEFAULT_WARD = "General Ward";

    private final Database db;

    public BedService(Database db) { this.db = db; }

    /** Backward-compatible overload (used by HospitalService registration). */
    public int addBeds(String hospitalId, String type, int count) {
        return addBeds(hospitalId, type, count, null);
    }

    public int addBeds(String hospitalId, String type, int count, String ward) {
        Hospital h = hospital(hospitalId);
        String t = type == null ? "" : type.trim().toUpperCase();
        if (!BED_TYPES.contains(t)) throw new IllegalArgumentException("Bed type must be one of " + BED_TYPES + ".");
        if (count < 1 || count > 500) throw new IllegalArgumentException("Number of beds must be between 1 and 500.");
        String w = (ward == null || ward.trim().isEmpty()) ? DEFAULT_WARD : ward.trim();
        if (w.length() > 60) throw new IllegalArgumentException("Ward name is limited to 60 characters.");

        Hospital.Ward known = h.getWard(w);
        if (known != null && known.getCapacity() > 0) {
            long current = 0;
            for (Bed b : db.beds.values())
                if (b.getHospitalId().equals(h.getId()) && b.getWard().equals(w)) current++;
            if (current + count > known.getCapacity())
                throw new IllegalArgumentException("Ward '" + w + "' is full: capacity " + known.getCapacity()
                        + ", currently " + current + " bed(s). Raise the ward capacity or choose another ward.");
        }

        synchronized (db) {
            if (known == null) h.upsertWard(w, "", 0);   // auto-register unknown wards (unlimited)
            for (int i = 0; i < count; i++) {
                Bed b = new Bed(db.nextBedId(), h.getId(), h.nextBedNo(), t, w);
                db.beds.put(b.getId(), b);
            }
            db.save();
        }
        return count;
    }

    // ---------------- ward registry ----------------

    public List<Map<String, Object>> wards(String hospitalId) {
        Hospital h = hospital(hospitalId);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Bed b : db.beds.values())
            if (b.getHospitalId().equals(h.getId())) counts.merge(b.getWard(), 1L, Long::sum);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Hospital.Ward w : h.getWards())
            out.add(Json.obj("name", w.getName(), "floor", w.getFloor(), "capacity", w.getCapacity(),
                    "currentBeds", counts.getOrDefault(w.getName(), 0L)));
        return out;
    }

    public Map<String, Object> saveWard(String hospitalId, String name, String floor, int capacity) {
        Hospital h = hospital(hospitalId);
        String n = Validation.require(name, "Ward name");
        if (n.length() > 60) throw new IllegalArgumentException("Ward name is limited to 60 characters.");
        if (capacity < 0 || capacity > 5000)
            throw new IllegalArgumentException("Capacity must be between 0 and 5000 (0 = unlimited).");
        synchronized (db) {
            h.upsertWard(n, floor == null ? "" : floor.trim(), capacity);
            db.save();
        }
        return Json.obj("name", n, "floor", floor == null ? "" : floor.trim(), "capacity", capacity);
    }

    // ---------------- beds ----------------

    public Map<String, Object> overview(String hospitalId) {
        Hospital h = hospital(hospitalId);
        List<Map<String, Object>> bedList = new ArrayList<>();
        int total = 0, free = 0;
        Map<String, int[]> byType = new LinkedHashMap<>();

        for (Bed b : db.beds.values()) {
            if (!b.getHospitalId().equals(h.getId())) continue;
            total++;
            if (b.isFree()) free++;
            byType.computeIfAbsent(b.getType(), k -> new int[2])[0]++;
            if (b.isFree()) byType.get(b.getType())[1]++;

            Map<String, Object> m = b.toMap();
            Patient p = b.getPatientId() == null ? null : db.patients.get(b.getPatientId());
            m.put("patientName", p == null ? null : p.getName());
            bedList.add(m);
        }
        bedList.sort(Comparator.comparingInt(m -> (int) m.get("bedNo")));

        Map<String, Object> byTypeOut = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : byType.entrySet())
            byTypeOut.put(e.getKey(), Json.obj("total", e.getValue()[0], "free", e.getValue()[1]));

        return Json.obj(
                "hospital", h.toMap(),
                "beds", bedList,
                "summary", Json.obj("total", total, "free", free, "occupied", total - free, "byType", byTypeOut));
    }

    public List<Map<String, Object>> availability() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Hospital h : db.hospitals.values()) {
            int total = 0, free = 0;
            Map<String, int[]> byType = new LinkedHashMap<>();
            for (Bed b : db.beds.values()) {
                if (!b.getHospitalId().equals(h.getId())) continue;
                total++;
                if (b.isFree()) free++;
                byType.computeIfAbsent(b.getType(), k -> new int[2])[0]++;
                if (b.isFree()) byType.get(b.getType())[1]++;
            }
            Map<String, Object> freeByType = new LinkedHashMap<>();
            for (Map.Entry<String, int[]> e : byType.entrySet()) freeByType.put(e.getKey(), e.getValue()[1]);
            out.add(Json.obj("hospitalId", h.getId(), "hospitalName", h.getName(),
                    "address", h.getAddress(), "phone", h.getPhone(),
                    "totalBeds", total, "freeBeds", free, "freeByType", freeByType,
                    "prices", h.getBedPrices()));
        }
        out.sort((a, b) -> String.valueOf(a.get("hospitalName"))
                .compareToIgnoreCase(String.valueOf(b.get("hospitalName"))));
        return out;
    }

    public Map<String, Object> admit(String hospitalId, String bedId, String patientId) {
        Hospital h = hospital(hospitalId);
        Bed b = bed(bedId);
        if (!b.getHospitalId().equals(hospitalId.trim()))
            throw new IllegalArgumentException("That bed does not belong to your hospital.");
        if (!b.isFree()) throw new IllegalArgumentException("Bed " + b.getBedNo() + " is already occupied.");
        Patient p = patient(patientId);
        for (Bed other : db.beds.values())
            if (p.getId().equals(other.getPatientId()))
                throw new IllegalArgumentException(p.getName() + " is already admitted to another bed. Discharge first.");
        synchronized (db) {
            b.admit(p.getId(), System.currentTimeMillis(), h.priceFor(b.getType()));   // rate snapshot
            db.snapshotOccupancy();
            db.save();
        }
        return b.toMap();
    }

    /**
     * Discharges the patient and returns the full stay information, including the
     * admission-time rate snapshot, so the caller can bill and release equipment.
     */
    public Map<String, Object> discharge(String hospitalId, String bedId) {
        Hospital h = hospital(hospitalId);
        Bed b = bed(bedId);
        if (!b.getHospitalId().equals(hospitalId.trim()))
            throw new IllegalArgumentException("That bed does not belong to your hospital.");
        if (b.isFree()) throw new IllegalArgumentException("That bed is already free.");
        synchronized (db) {
            String patientId = b.getPatientId();
            long admittedAt = b.getAdmittedAt();
            double rate = b.getAdmittedRate() > 0 ? b.getAdmittedRate() : h.priceFor(b.getType());
            long dischargedAt = System.currentTimeMillis();
            Patient p = db.patients.get(patientId);
            b.discharge();
            db.snapshotOccupancy();
            db.save();
            Map<String, Object> m = b.toMap();
            m.put("patientId", patientId);
            m.put("patientName", p == null ? "?" : p.getName());
            m.put("admittedAt", admittedAt);
            m.put("dischargedAt", dischargedAt);
            m.put("ratePerDay", rate);
            return m;
        }
    }

    private Hospital hospital(String id) {
        Hospital h = id == null ? null : db.hospitals.get(id.trim());
        if (h == null) throw new IllegalArgumentException("Hospital not found: " + id);
        return h;
    }

    private Bed bed(String id) {
        Bed b = id == null ? null : db.beds.get(id.trim());
        if (b == null) throw new IllegalArgumentException("Bed not found: " + id);
        return b;
    }

    private Patient patient(String id) {
        Patient p = id == null ? null : db.patients.get(id.trim());
        if (p == null) throw new IllegalArgumentException("Patient not found: " + id);
        return p;
    }
}