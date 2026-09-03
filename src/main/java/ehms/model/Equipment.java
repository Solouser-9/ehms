package ehms.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** A piece of hospital equipment (oxygen cylinder, ventilator, monitor, ...). */
public class Equipment implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String AVAILABLE = "AVAILABLE";
    public static final String IN_USE = "IN_USE";
    public static final String MAINTENANCE = "MAINTENANCE";

    private final String id;              // EQ001
    private final String hospitalId;
    private final String kind;
    private final String label;
    private final long createdAt;
    private String status = AVAILABLE;
    private String bedId;
    private String patientId;             // captured at attach time so charges survive discharge
    private long assignedAt;              // 0 = unknown (legacy attachments are released uncharged)

    public Equipment(String id, String hospitalId, String kind, String label, long createdAt) {
        this(id, hospitalId, kind, label, createdAt, AVAILABLE, null, null, 0);
    }

    /** Full-state constructor used when restoring from a database. */
    public Equipment(String id, String hospitalId, String kind, String label,
                     long createdAt, String status, String bedId, String patientId, long assignedAt) {
        this.id = id;
        this.hospitalId = hospitalId;
        this.kind = kind;
        this.label = label;
        this.createdAt = createdAt;
        this.status = status;
        this.bedId = bedId;
        this.patientId = patientId;
        this.assignedAt = assignedAt;
    }

    public String getId() { return id; }
    public String getHospitalId() { return hospitalId; }
    public String getKind() { return kind; }
    public String getLabel() { return label; }
    public long getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    public String getBedId() { return bedId; }
    public String getPatientId() { return patientId; }
    public long getAssignedAt() { return assignedAt; }

    public void assign(String bedId, String patientId) {
        this.status = IN_USE;
        this.bedId = bedId;
        this.patientId = patientId;
        this.assignedAt = System.currentTimeMillis();
    }

    public void release() {
        this.status = AVAILABLE;
        this.bedId = null;
        this.patientId = null;
        this.assignedAt = 0;
    }

    /** AVAILABLE <-> MAINTENANCE (only allowed while not attached to a bed). */
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("hospitalId", hospitalId);
        m.put("kind", kind);
        m.put("label", label);
        m.put("status", status);
        m.put("bedId", bedId);
        m.put("createdAt", createdAt);
        return m;
    }
}