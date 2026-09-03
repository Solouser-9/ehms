package ehms.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** A patient's request to be admitted to a hospital bed; the hospital approves or rejects it. */
public class BedRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    private final String id;              // BR001
    private final String patientId;
    private final String hospitalId;
    private final String bedType;         // preferred type or ANY
    private final String reason;
    private final long createdAt;
    private String status = PENDING;
    private String bedId;                 // set when approved
    private int bedNo;                    // informational
    private long decidedAt;
    private String decisionNote = "";

    public BedRequest(String id, String patientId, String hospitalId, String bedType,
                      String reason, long createdAt) {
        this.id = id;
        this.patientId = patientId;
        this.hospitalId = hospitalId;
        this.bedType = bedType;
        this.reason = reason == null ? "" : reason;
        this.createdAt = createdAt;
    }

    /** Full-state constructor used when restoring from a database. */
    public BedRequest(String id, String patientId, String hospitalId, String bedType, String reason,
                      long createdAt, String status, String bedId, int bedNo,
                      long decidedAt, String decisionNote) {
        this(id, patientId, hospitalId, bedType, reason, createdAt);
        this.status = status;
        this.bedId = bedId;
        this.bedNo = bedNo;
        this.decidedAt = decidedAt;
        this.decisionNote = decisionNote == null ? "" : decisionNote;
    }

    public String getId() { return id; }
    public String getPatientId() { return patientId; }
    public String getHospitalId() { return hospitalId; }
    public String getBedType() { return bedType; }
    public String getReason() { return reason; }
    public long getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    public String getBedId() { return bedId; }
    public int getBedNo() { return bedNo; }
    public long getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }

    public void approve(String bedId, int bedNo, String note) {
        this.status = APPROVED;
        this.bedId = bedId;
        this.bedNo = bedNo;
        this.decidedAt = System.currentTimeMillis();
        this.decisionNote = note == null ? "" : note;
    }

    public void reject(String note) {
        this.status = REJECTED;
        this.decidedAt = System.currentTimeMillis();
        this.decisionNote = note == null ? "" : note;
    }

    public void cancel() {
        this.status = CANCELLED;
        this.decidedAt = System.currentTimeMillis();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("patientId", patientId);
        m.put("hospitalId", hospitalId);
        m.put("bedType", bedType);
        m.put("reason", reason);
        m.put("status", status);
        m.put("bedId", bedId);
        m.put("bedNo", bedNo);
        m.put("createdAt", createdAt);
        m.put("decidedAt", decidedAt == 0 ? null : decidedAt);
        m.put("decisionNote", decisionNote);
        return m;
    }
}