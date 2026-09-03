package ehms.service;

import ehms.db.Database;
import ehms.model.Appointment;
import ehms.model.Doctor;
import ehms.model.Patient;
import ehms.model.Slot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Doctors publish bookable time slots; patients must pick one to book a consultation. */
public class SlotService {

    private final Database db;

    public SlotService(Database db) { this.db = db; }

    public List<Map<String, Object>> publish(String doctorId, String date, String start,
                                             int durationMinutes, int count) {
        Doctor d = doctor(doctorId);
        if (!d.isVerified())
            throw new IllegalArgumentException(
                    "Your licence verification is pending - an administrator must verify it before you can publish time slots.");

        LocalDateTime first;
        try {
            first = LocalDateTime.of(LocalDate.parse(require(date, "Date")), LocalTime.parse(require(start, "Start time")));
        } catch (Exception e) {
            throw new IllegalArgumentException("Date must be YYYY-MM-DD and start time must be HH:MM.");
        }
        if (durationMinutes < 5 || durationMinutes > 180)
            throw new IllegalArgumentException("Slot duration must be between 5 and 180 minutes.");
        if (count < 1 || count > 20)
            throw new IllegalArgumentException("You can publish between 1 and 20 slots at a time.");

        long firstMillis = first.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (firstMillis <= System.currentTimeMillis())
            throw new IllegalArgumentException("Slots must lie in the future. Check the date and start time.");

        synchronized (db) {
            List<Map<String, Object>> created = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                long startAt = firstMillis + i * durationMinutes * 60_000L;
                Slot s = new Slot(db.nextSlotId(), doctorId, startAt, durationMinutes);
                db.slots.put(s.getId(), s);
                created.add(s.toMap());
            }
            db.save();
            return created;
        }
    }

    /** All of the doctor's slots from today onwards (management view). */
    public List<Map<String, Object>> forDoctor(String doctorId) {
        doctor(doctorId);
        long todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<Slot> list = new ArrayList<>();
        for (Slot s : db.slots.values())
            if (s.getDoctorId().equals(doctorId) && s.getStartAt() >= todayStart) list.add(s);
        list.sort((a, b) -> Long.compare(a.getStartAt(), b.getStartAt()));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Slot s : list) {
            Map<String, Object> m = s.toMap();
            if (s.getAppointmentId() != null) {
                Appointment a = db.appointments.get(s.getAppointmentId());
                if (a != null) {
                    m.put("patientName", displayName(a.getPatientId()));
                    m.put("appointmentStatus", a.getStatus());
                }
            }
            out.add(m);
        }
        return out;
    }

    /** Open, still-bookable slots of a doctor (patient view). */
    public List<Map<String, Object>> openFor(String doctorId) {
        doctor(doctorId);
        long now = System.currentTimeMillis();
        List<Slot> list = new ArrayList<>();
        for (Slot s : db.slots.values())
            if (s.getDoctorId().equals(doctorId) && s.bookable(now)) list.add(s);
        list.sort((a, b) -> Long.compare(a.getStartAt(), b.getStartAt()));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Slot s : list) out.add(s.toMap());
        return out;
    }

    public Map<String, Object> delete(String doctorId, String slotId) {
        synchronized (db) {
            Slot s = slot(slotId);
            if (!s.getDoctorId().equals(doctorId))
                throw new IllegalArgumentException("That slot belongs to a different doctor.");
            if (!s.isOpen())
                throw new IllegalArgumentException("Booked slots cannot be deleted.");
            db.slots.remove(s.getId());
            db.save();
            return s.toMap();
        }
    }

    private String displayName(String patientId) {
        Patient p = db.patients.get(patientId);
        return p == null ? "?" : p.getName();
    }

    private Doctor doctor(String id) {
        Doctor d = id == null ? null : db.doctors.get(id.trim());
        if (d == null) throw new IllegalArgumentException("Doctor not found: " + id);
        return d;
    }

    private Slot slot(String id) {
        Slot s = id == null ? null : db.slots.get(id.trim());
        if (s == null) throw new IllegalArgumentException("Slot not found: " + id);
        return s;
    }

    private static String require(String v, String field) {
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return v.trim();
    }
}