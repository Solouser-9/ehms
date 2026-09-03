package ehms.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

public class Bed implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String hospitalId;
    private final int bedNo;
    private final String type;      // GENERAL / ICU / VENTILATOR
    private final String ward;
    private String patientId;       // null => bed is free
    private long admittedAt;        // millis, 0 when free
    private double admittedRate;    // price per day captured AT ADMISSION (0 for legacy stays)

    public Bed(String id, String hospitalId, int bedNo, String type) {
        this(id, hospitalId, bedNo, type, null);
    }

    public Bed(String id, String hospitalId, int bedNo, String type, String ward) {
        this.id = id;
        this.hospitalId = hospitalId;
        this.bedNo = bedNo;
        this.type = type;
        this.ward = (ward == null || ward.isBlank()) ? "General Ward" : ward.trim();
    }

    public String getId() { return id; }
    public String getHospitalId() { return hospitalId; }
    public int getBedNo() { return bedNo; }
    public String getType() { return type; }
    public String getWard() { return ward == null || ward.isBlank() ? "General Ward" : ward; }
    public String getPatientId() { return patientId; }
    public long getAdmittedAt() { return admittedAt; }
    public double getAdmittedRate() { return admittedRate; }

    public boolean isFree() { return patientId == null; }
    public String getStatus() { return isFree() ? "FREE" : "OCCUPIED"; }

    public void admit(String patientId, long admittedAt) { admit(patientId, admittedAt, 0); }

    public void admit(String patientId, long admittedAt, double admittedRate) {
        this.patientId = patientId;
        this.admittedAt = admittedAt;
        this.admittedRate = admittedRate;
    }

    public void discharge() {
        this.patientId = null;
        this.admittedAt = 0;
        this.admittedRate = 0;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("hospitalId", hospitalId);
        m.put("bedNo", bedNo);
        m.put("type", type);
        m.put("ward", getWard());
        m.put("status", getStatus());
        m.put("patientId", patientId);
        m.put("admittedAt", admittedAt == 0 ? null : admittedAt);
        return m;
    }
}