package ehms.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** A bookable consultation time slot published by a doctor. */
public class Slot implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String OPEN = "OPEN";
    public static final String BOOKED = "BOOKED";

    private final String id;              // S001
    private final String doctorId;
    private final long startAt;           // epoch millis
    private final int durationMinutes;
    private String status = OPEN;
    private String appointmentId;         // set once booked

    public Slot(String id, String doctorId, long startAt, int durationMinutes) {
        this.id = id;
        this.doctorId = doctorId;
        this.startAt = startAt;
        this.durationMinutes = durationMinutes;
    }

    public Slot(String id, String doctorId, long startAt, int durationMinutes,
                String status, String appointmentId) {
        this(id, doctorId, startAt, durationMinutes);
        this.status = status;
        this.appointmentId = appointmentId;
    }

    public String getId() { return id; }
    public String getDoctorId() { return doctorId; }
    public long getStartAt() { return startAt; }
    public int getDurationMinutes() { return durationMinutes; }
    public String getStatus() { return status; }
    public String getAppointmentId() { return appointmentId; }

    public boolean isOpen() { return OPEN.equals(status); }

    public void book(String appointmentId) {
        this.status = BOOKED;
        this.appointmentId = appointmentId;
    }

    public void release() {
        this.status = OPEN;
        this.appointmentId = null;
    }

    public boolean bookable(long now) {
        return isOpen() && startAt + durationMinutes * 60_000L > now;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("doctorId", doctorId);
        m.put("startAt", startAt);
        m.put("durationMinutes", durationMinutes);
        m.put("status", status);
        m.put("appointmentId", appointmentId);
        return m;
    }
}