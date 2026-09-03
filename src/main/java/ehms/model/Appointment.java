package ehms.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

public class Appointment implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String PENDING = "PENDING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    private final String id;
    private final String patientId;
    private final String doctorId;
    private final String symptoms;
    private final long createdAt;
    private final String slotId;
    private final long scheduledAt;
    private final double fee;             // doctor's fee at BOOKING time (0 for legacy rows)
    private String status = PENDING;
    private String diagnosis = "";
    private String prescription = "";
    private long completedAt;

    public Appointment(String id, String patientId, String doctorId, String symptoms, long createdAt) {
        this(id, patientId, doctorId, symptoms, createdAt, null, 0, 0);
    }

    public Appointment(String id, String patientId, String doctorId, String symptoms,
                       long createdAt, String slotId, long scheduledAt) {
        this(id, patientId, doctorId, symptoms, createdAt, slotId, scheduledAt, 0);
    }

    public Appointment(String id, String patientId, String doctorId, String symptoms,
                       long createdAt, String slotId, long scheduledAt, double fee) {
        this.id = id;
        this.patientId = patientId;
        this.doctorId = doctorId;
        this.symptoms = symptoms;
        this.createdAt = createdAt;
        this.slotId = slotId;
        this.scheduledAt = scheduledAt;
        this.fee = fee;
    }

    /** Full-state constructor used when restoring from a database. */
    public Appointment(String id, String patientId, String doctorId, String symptoms, long createdAt,
                       String slotId, long scheduledAt, double fee, String status,
                       String diagnosis, String prescription, long completedAt) {
        this(id, patientId, doctorId, symptoms, createdAt, slotId, scheduledAt, fee);
        this.status = status;
        this.diagnosis = diagnosis == null ? "" : diagnosis;
        this.prescription = prescription == null ? "" : prescription;
        this.completedAt = completedAt;
    }

    public String getId() { return id; }
    public String getPatientId() { return patientId; }
    public String getDoctorId() { return doctorId; }
    public String getSymptoms() { return symptoms; }
    public long getCreatedAt() { return createdAt; }
    public String getSlotId() { return slotId; }
    public long getScheduledAt() { return scheduledAt; }
    public double getFee() { return fee; }
    public String getStatus() { return status; }
    public String getDiagnosis() { return diagnosis; }
    public String getPrescription() { return prescription; }
    public long getCompletedAt() { return completedAt; }

    public boolean isCompleted() { return COMPLETED.equals(status); }
    public void cancel() { this.status = CANCELLED; }

    public void complete(String diagnosis, String prescription) {
        this.diagnosis = diagnosis;
        this.prescription = prescription;
        this.completedAt = System.currentTimeMillis();
        this.status = COMPLETED;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("patientId", patientId);
        m.put("doctorId", doctorId);
        m.put("symptoms", symptoms);
        m.put("status", status);
        m.put("diagnosis", diagnosis);
        m.put("prescription", prescription);
        m.put("createdAt", createdAt);
        m.put("completedAt", completedAt == 0 ? null : completedAt);
        m.put("slotId", slotId);
        m.put("scheduledAt", scheduledAt);
        m.put("fee", fee);
        return m;
    }
}