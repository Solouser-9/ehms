package ehms.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** The consultation fee recorded against a completed appointment. */
public class Payment implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String DUE = "DUE";
    public static final String PAID = "PAID";

    private final String id;              // PY001
    private final String appointmentId;
    private final String patientId;
    private final String patientName;
    private final String doctorId;
    private final String doctorName;
    private final double amount;
    private final long createdAt;
    private String status = DUE;
    private String method = "";           // UPI / CARD / NETBANKING / WALLET / CASH / STRIPE
    private long paidAt;

    public Payment(String id, String appointmentId, String patientId, String patientName,
                   String doctorId, String doctorName, double amount, long createdAt) {
        this.id = id;
        this.appointmentId = appointmentId;
        this.patientId = patientId;
        this.patientName = patientName;
        this.doctorId = doctorId;
        this.doctorName = doctorName;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public Payment(String id, String appointmentId, String patientId, String patientName,
                   String doctorId, String doctorName, double amount, long createdAt,
                   String status, String method, long paidAt) {
        this(id, appointmentId, patientId, patientName, doctorId, doctorName, amount, createdAt);
        this.status = status;
        this.method = method == null ? "" : method;
        this.paidAt = paidAt;
    }

    public String getId() { return id; }
    public String getAppointmentId() { return appointmentId; }
    public String getPatientId() { return patientId; }
    public String getPatientName() { return patientName; }
    public String getDoctorId() { return doctorId; }
    public String getDoctorName() { return doctorName; }
    public double getAmount() { return amount; }
    public long getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    public String getMethod() { return method; }
    public long getPaidAt() { return paidAt; }

    public boolean isPaid() { return PAID.equals(status); }

    public void pay(String method) {
        this.status = PAID;
        this.method = method;
        this.paidAt = System.currentTimeMillis();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("appointmentId", appointmentId);
        m.put("patientId", patientId);
        m.put("patientName", patientName);
        m.put("doctorId", doctorId);
        m.put("doctorName", doctorName);
        m.put("amount", amount);
        m.put("status", status);
        m.put("method", method);
        m.put("createdAt", createdAt);
        m.put("paidAt", paidAt);
        return m;
    }
}