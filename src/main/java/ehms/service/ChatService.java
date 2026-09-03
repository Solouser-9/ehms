package ehms.service;

import ehms.db.Database;
import ehms.model.Appointment;
import ehms.model.ChatMessage;
import ehms.model.Doctor;
import ehms.model.Patient;
import ehms.security.SessionManager.Session;
import ehms.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Doctor-patient chat attached to a consultation; browsers poll or use the WebSocket push. */
public class ChatService {

    private static final int MAX_TEXT = 2000;

    private final Database db;

    public ChatService(Database db) { this.db = db; }

    public Map<String, Object> send(Session s, String appointmentId, String text) {
        if (text == null || text.trim().isEmpty())
            throw new IllegalArgumentException("Message text is required.");
        if (text.trim().length() > MAX_TEXT)
            throw new IllegalArgumentException("Messages are limited to " + MAX_TEXT + " characters.");
        synchronized (db) {
            Appointment a = requireAccess(s, appointmentId);
            if (Appointment.CANCELLED.equals(a.getStatus()))
                throw new IllegalArgumentException("This consultation was cancelled and its chat is closed.");
            ChatMessage m = new ChatMessage(db.nextMessageId(), a.getId(), s.role(), s.name(),
                    text.trim(), System.currentTimeMillis());
            db.messages.put(m.getId(), m);
            db.save();
            return m.toMap();
        }
    }

    /** Messages after the given sequence number; also marks the other party's messages as read. */
    public Map<String, Object> messages(Session s, String appointmentId, long after) {
        Appointment a = requireAccess(s, appointmentId);
        List<Map<String, Object>> items = new ArrayList<>();
        List<ChatMessage> thread = new ArrayList<>();
        long maxSeq = after;

        for (ChatMessage m : db.messages.values()) {
            if (!m.getAppointmentId().equals(a.getId())) continue;
            thread.add(m);
            long seq = seq(m.getId());
            if (seq > maxSeq) maxSeq = seq;
            if (seq > after) items.add(m.toMap());
        }
        items.sort((x, y) -> Long.compare((Long) x.get("sentAt"), (Long) y.get("sentAt")));
        markRead(s, thread);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", a.getId());
        info.put("status", a.getStatus());
        info.put("patientName", name(db.patients.get(a.getPatientId())));
        info.put("doctorName", name(db.doctors.get(a.getDoctorId())));
        info.put("symptoms", a.getSymptoms());
        return Json.obj("items", items, "cursor", maxSeq, "consultation", info);
    }

    /** Unread message counts for the signed-in patient or doctor. */
    public Map<String, Object> unread(Session s) {
        Map<String, Long> per = new LinkedHashMap<>();
        for (Appointment a : myAppointments(s)) {
            long n = 0;
            for (ChatMessage m : db.messages.values()) {
                if (m.getAppointmentId().equals(a.getId())
                        && !m.getSenderRole().equals(s.role()) && m.getReadAt() == 0) n++;
            }
            if (n > 0) per.put(a.getId(), n);
        }
        long total = per.values().stream().mapToLong(Long::longValue).sum();
        return Json.obj("total", total, "per", per);
    }

    private void markRead(Session s, List<ChatMessage> thread) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (ChatMessage m : thread) {
            if (!m.getSenderRole().equals(s.role()) && m.getReadAt() == 0) {
                m.markRead(now);
                changed = true;
            }
        }
        if (changed) {
            synchronized (db) { db.save(); }
        }
    }

    private List<Appointment> myAppointments(Session s) {
        List<Appointment> out = new ArrayList<>();
        for (Appointment a : db.appointments.values()) {
            if ("PATIENT".equals(s.role()) && a.getPatientId().equals(s.accountId())) out.add(a);
            if ("DOCTOR".equals(s.role()) && a.getDoctorId().equals(s.accountId())) out.add(a);
        }
        return out;
    }

    private Appointment requireAccess(Session s, String appointmentId) {
        Appointment a = appointmentId == null ? null : db.appointments.get(appointmentId.trim());
        if (a == null) throw new IllegalArgumentException("Consultation not found: " + appointmentId);
        if (!"PATIENT".equals(s.role()) && !"DOCTOR".equals(s.role()))
            throw new IllegalArgumentException("Chat is available to patients and doctors only.");
        if ("PATIENT".equals(s.role()) && !a.getPatientId().equals(s.accountId()))
            throw new IllegalArgumentException("This consultation belongs to a different patient.");
        if ("DOCTOR".equals(s.role()) && !a.getDoctorId().equals(s.accountId()))
            throw new IllegalArgumentException("This consultation belongs to a different doctor.");
        return a;
    }

    private static String name(Object o) {
        if (o instanceof Patient p) return p.getName();
        if (o instanceof Doctor d) return "Dr. " + d.getName();
        return "?";
    }

    private static long seq(String id) {
        try { return Long.parseLong(id.substring(1)); } catch (Exception e) { return 0; }
    }
}