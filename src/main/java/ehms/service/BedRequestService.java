package ehms.service;

import ehms.db.Database;
import ehms.model.Bed;
import ehms.model.BedRequest;
import ehms.model.Hospital;
import ehms.model.Patient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Patients request a bed; the hospital approves (auto-admit, optionally into a chosen bed) or rejects. */
public class BedRequestService {

    private final Database db;
    private final BedService bedService;

    public BedRequestService(Database db, BedService bedService) {
        this.db = db;
        this.bedService = bedService;
    }

    public Map<String, Object> create(String patientId, String hospitalId, String bedType, String reason) {
        Patient p = patient(patientId);
        Hospital h = hospital(hospitalId);
        String type = bedType == null || bedType.isBlank() ? "ANY" : bedType.trim().toUpperCase();
        if (!"ANY".equals(type) && !BedService.BED_TYPES.contains(type))
            throw new IllegalArgumentException("Bed type must be one of " + BedService.BED_TYPES + " or ANY.");
        for (Bed b : db.beds.values()) {
            if (p.getId().equals(b.getPatientId()))
                throw new IllegalArgumentException("You are already admitted to bed " + b.getBedNo()
                        + ". A discharge must happen before you can request another bed.");
        }
        synchronized (db) {
            for (BedRequest existing : db.bedRequests.values()) {
                if (existing.getPatientId().equals(p.getId()) && BedRequest.PENDING.equals(existing.getStatus()))
                    throw new IllegalArgumentException("You already have a pending bed request. "
                            + "Cancel it or wait for a hospital to respond first.");
            }
            BedRequest r = new BedRequest(db.nextBedRequestId(), p.getId(), h.getId(), type,
                    reason == null ? "" : reason.trim(), System.currentTimeMillis());
            db.bedRequests.put(r.getId(), r);
            db.save();
            return view(r);
        }
    }

    public Map<String, Object> cancel(String patientId, String requestId) {
        synchronized (db) {
            BedRequest r = request(requestId);
            if (!r.getPatientId().equals(patientId))
                throw new IllegalArgumentException("This bed request belongs to a different patient.");
            if (!BedRequest.PENDING.equals(r.getStatus()))
                throw new IllegalArgumentException("Only pending requests can be cancelled.");
            r.cancel();
            db.save();
            return view(r);
        }
    }

    public List<Map<String, Object>> forPatient(String patientId) {
        patient(patientId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (BedRequest r : db.bedRequests.values())
            if (r.getPatientId().equals(patientId)) out.add(view(r));
        out.sort(Comparator.comparingLong((Map<String, Object> m) ->
                ((Number) m.get("createdAt")).longValue()).reversed());
        return out;
    }

    /** Pending requests first, then newest - the hospital's work queue. */
    public List<Map<String, Object>> forHospital(String hospitalId) {
        hospital(hospitalId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (BedRequest r : db.bedRequests.values())
            if (r.getHospitalId().equals(hospitalId)) out.add(view(r));
        out.sort((a, b) -> {
            boolean pa = BedRequest.PENDING.equals(a.get("status"));
            boolean pb = BedRequest.PENDING.equals(b.get("status"));
            if (pa != pb) return pa ? -1 : 1;
            return Long.compare(((Number) b.get("createdAt")).longValue(),
                                ((Number) a.get("createdAt")).longValue());
        });
        return out;
    }

    /** Approve (optionally into a specific bed) or reject with a note. */
    public Map<String, Object> decide(String hospitalId, String requestId,
                                      boolean approve, String note, String bedId) {
        hospital(hospitalId);
        synchronized (db) {
            BedRequest r = request(requestId);
            if (!r.getHospitalId().equals(hospitalId))
                throw new IllegalArgumentException("This bed request was sent to a different hospital.");
            if (!BedRequest.PENDING.equals(r.getStatus()))
                throw new IllegalArgumentException("This bed request was already handled.");
            if (approve) {
                Bed bed;
                if (bedId != null && !bedId.isBlank()) {
                    bed = db.beds.get(bedId.trim());
                    if (bed == null || !bed.getHospitalId().equals(hospitalId))
                        throw new IllegalArgumentException("Bed not found in your hospital: " + bedId);
                    if (!bed.isFree())
                        throw new IllegalArgumentException("Bed " + bed.getBedNo() + " is already occupied.");
                    if (!"ANY".equals(r.getBedType()) && !bed.getType().equals(r.getBedType()))
                        throw new IllegalArgumentException("The selected bed does not match the requested bed type ("
                                + r.getBedType() + ").");
                } else {
                    bed = findFreeBed(hospitalId, r.getBedType());   // lowest-numbered match, as before
                }
                if (bed == null)
                    throw new IllegalArgumentException("No free " + r.getBedType().toLowerCase()
                            + " bed is available right now. Add beds or reject the request.");
                Map<String, Object> admitted = bedService.admit(hospitalId, bed.getId(), r.getPatientId());
                r.approve(bed.getId(), bed.getBedNo(), note);
                db.save();
                Map<String, Object> m = view(r);
                m.put("admitted", admitted);
                return m;
            }
            r.reject(note);
            db.save();
            return view(r);
        }
    }

    /** Lowest-numbered free bed matching the preferred type (any type when "ANY"). */
    private Bed findFreeBed(String hospitalId, String preferredType) {
        Bed best = null;
        for (Bed b : db.beds.values()) {
            if (!b.getHospitalId().equals(hospitalId) || !b.isFree()) continue;
            if (preferredType != null && !"ANY".equals(preferredType)
                    && !b.getType().equals(preferredType)) continue;
            if (best == null || b.getBedNo() < best.getBedNo()) best = b;
        }
        return best;
    }

    private Map<String, Object> view(BedRequest r) {
        Map<String, Object> m = r.toMap();
        Hospital h = db.hospitals.get(r.getHospitalId());
        Patient p = db.patients.get(r.getPatientId());
        m.put("hospitalName", h == null ? "?" : h.getName());
        m.put("patientName", p == null ? "?" : p.getName());
        m.put("patientAge", p == null ? null : p.getAge());
        m.put("patientPhone", p == null ? null : p.getPhone());
        return m;
    }

    private BedRequest request(String id) {
        BedRequest r = id == null ? null : db.bedRequests.get(id.trim());
        if (r == null) throw new IllegalArgumentException("Bed request not found: " + id);
        return r;
    }

    private Patient patient(String id) {
        Patient p = id == null ? null : db.patients.get(id.trim());
        if (p == null) throw new IllegalArgumentException("Patient not found: " + id);
        return p;
    }

    private Hospital hospital(String id) {
        Hospital h = id == null ? null : db.hospitals.get(id.trim());
        if (h == null) throw new IllegalArgumentException("Hospital not found: " + id);
        return h;
    }
}