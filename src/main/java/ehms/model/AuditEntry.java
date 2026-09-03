package ehms.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** One entry of the audit trail: who did what, when. */
public class AuditEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;          // L001, L002, ...
    private final long ts;            // epoch millis
    private final String actorRole;   // PATIENT / DOCTOR / HOSPITAL / ADMIN (null for failed logins)
    private final String actorId;     // account id (null for failed logins)
    private final String actorName;
    private final String actorEmail;  // lets users find attempts made against their own account
    private final String action;      // LOGIN, LOGIN_FAILED, REGISTERED, ...
    private final String details;

    public AuditEntry(String id, long ts, String actorRole, String actorId,
                      String actorName, String actorEmail, String action, String details) {
        this.id = id;
        this.ts = ts;
        this.actorRole = actorRole;
        this.actorId = actorId;
        this.actorName = actorName;
        this.actorEmail = actorEmail;
        this.action = action;
        this.details = details;
    }

    public String getId() { return id; }
    public long getTs() { return ts; }
    public String getActorRole() { return actorRole; }
    public String getActorId() { return actorId; }
    public String getActorName() { return actorName; }
    public String getActorEmail() { return actorEmail; }
    public String getAction() { return action; }
    public String getDetails() { return details; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("ts", ts);
        m.put("actorRole", actorRole);
        m.put("actorId", actorId);
        m.put("actorName", actorName);
        m.put("actorEmail", actorEmail);
        m.put("action", action);
        m.put("details", details);
        return m;
    }
}