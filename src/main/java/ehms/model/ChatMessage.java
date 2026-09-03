package ehms.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** One message in the chat thread attached to a consultation. */
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;              // C001, C002, ...
    private final String appointmentId;
    private final String senderRole;      // PATIENT / DOCTOR
    private final String senderName;
    private final String text;
    private final long sentAt;
    private long readAt;                  // 0 = not yet read by the other party

    public ChatMessage(String id, String appointmentId, String senderRole,
                       String senderName, String text, long sentAt) {
        this(id, appointmentId, senderRole, senderName, text, sentAt, 0);
    }

    public ChatMessage(String id, String appointmentId, String senderRole, String senderName,
                       String text, long sentAt, long readAt) {
        this.id = id;
        this.appointmentId = appointmentId;
        this.senderRole = senderRole;
        this.senderName = senderName;
        this.text = text;
        this.sentAt = sentAt;
        this.readAt = readAt;
    }

    public String getId() { return id; }
    public String getAppointmentId() { return appointmentId; }
    public String getSenderRole() { return senderRole; }
    public String getSenderName() { return senderName; }
    public String getText() { return text; }
    public long getSentAt() { return sentAt; }
    public long getReadAt() { return readAt; }

    public void markRead(long ts) { if (readAt == 0) readAt = ts; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("appointmentId", appointmentId);
        m.put("senderRole", senderRole);
        m.put("senderName", senderName);
        m.put("text", text);
        m.put("sentAt", sentAt);
        m.put("readAt", readAt);
        return m;
    }
}