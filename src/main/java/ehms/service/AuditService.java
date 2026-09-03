package ehms.service;

import ehms.db.Database;
import ehms.model.AuditEntry;
import ehms.security.SessionManager.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Records and queries the audit trail. */
public class AuditService {

    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String LOGOUT = "LOGOUT";
    public static final String REGISTERED = "REGISTERED";
    public static final String APPOINTMENT_BOOKED = "APPOINTMENT_BOOKED";
    public static final String APPOINTMENT_CANCELLED = "APPOINTMENT_CANCELLED";
    public static final String PRESCRIPTION_ISSUED = "PRESCRIPTION_ISSUED";
    public static final String AVAILABILITY_CHANGED = "AVAILABILITY_CHANGED";
    public static final String BEDS_ADDED = "BEDS_ADDED";
    public static final String PATIENT_ADMITTED = "PATIENT_ADMITTED";
    public static final String PATIENT_DISCHARGED = "PATIENT_DISCHARGED";
    public static final String REPORT_UPLOADED = "REPORT_UPLOADED";
    public static final String REPORT_DELETED = "REPORT_DELETED";
    public static final String PAYMENT_MADE = "PAYMENT_MADE";
    public static final String PAYMENT_RECORDED = "PAYMENT_RECORDED";
    public static final String MEDICINE_ADDED = "MEDICINE_ADDED";
    public static final String MEDICINE_DISPENSED = "MEDICINE_DISPENSED";
    public static final String STOCK_RESTOCKED = "STOCK_RESTOCKED";
    public static final String DOCTOR_VERIFIED = "DOCTOR_VERIFIED";
    public static final String DOCTOR_UNVERIFIED = "DOCTOR_UNVERIFIED";
    public static final String ACCOUNT_BLOCKED = "ACCOUNT_BLOCKED";
    public static final String ACCOUNT_UNBLOCKED = "ACCOUNT_UNBLOCKED";
    public static final String BED_REQUESTED = "BED_REQUESTED";
    public static final String BED_REQUEST_APPROVED = "BED_REQUEST_APPROVED";
    public static final String BED_REQUEST_REJECTED = "BED_REQUEST_REJECTED";
    public static final String BED_REQUEST_CANCELLED = "BED_REQUEST_CANCELLED";
    public static final String BED_PRICES_UPDATED = "BED_PRICES_UPDATED";
    public static final String BILL_GENERATED = "BILL_GENERATED";
    public static final String BILL_PAID = "BILL_PAID";
    public static final String BILL_SETTLED = "BILL_SETTLED";
    public static final String EQUIPMENT_ADDED = "EQUIPMENT_ADDED";
    public static final String EQUIPMENT_ASSIGNED = "EQUIPMENT_ASSIGNED";
    public static final String EQUIPMENT_RELEASED = "EQUIPMENT_RELEASED";
    public static final String EQUIPMENT_STATUS_CHANGED = "EQUIPMENT_STATUS_CHANGED";
    public static final String EQUIPMENT_CHARGED = "EQUIPMENT_CHARGED";
    public static final String WARD_SAVED = "WARD_SAVED";
    public static final String EQUIPMENT_PRICES_UPDATED = "EQUIPMENT_PRICES_UPDATED";

    private final Database db;

    public AuditService(Database db) { this.db = db; }

    /** Records an action performed by a signed-in user. */
    public void record(Session actor, String action, String details) {
        record(actor == null ? null : actor.role(), actor == null ? null : actor.accountId(),
                actor == null ? null : actor.name(), null, action, details);
    }

    /** Records an action by an arbitrary actor (registrations, failed logins...). */
    public void record(String actorRole, String actorId, String actorName, String actorEmail,
                       String action, String details) {
        db.recordAudit(new AuditEntry(db.nextAuditId(), System.currentTimeMillis(),
                actorRole, actorId, actorName, actorEmail, action, details));
    }

    /** Everything, newest first (admin view). */
    public List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditEntry e : db.auditEntries()) {
            out.add(e.toMap());
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** Entries where the given account is the actor - by id, or by email so
     *  that users also see failed sign-in attempts against their account. */
    public List<Map<String, Object>> mine(String accountId, String email, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditEntry e : db.auditEntries()) {
            boolean match = accountId != null && accountId.equals(e.getActorId());
            if (!match && email != null && e.getActorEmail() != null
                    && e.getActorEmail().equalsIgnoreCase(email)) {
                match = true;
            }
            if (match) {
                out.add(e.toMap());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }
}