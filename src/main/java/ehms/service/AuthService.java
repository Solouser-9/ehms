package ehms.service;

import ehms.db.Database;
import ehms.model.Account;
import ehms.security.LoginGuard;
import ehms.security.PasswordHasher;
import ehms.security.SessionManager;
import ehms.security.SessionManager.Session;

public class AuthService {

    private final Database db;
    private final SessionManager sessions;
    private final LoginGuard guard;

    /** Burned when the email does not exist so response timing reveals nothing. */
    private static final String DUMMY_HASH = PasswordHasher.hash("ehms-timing-equaliser");

    public AuthService(Database db, SessionManager sessions, LoginGuard guard) {
        this.db = db;
        this.sessions = sessions;
        this.guard = guard;
    }

    /** Verifies credentials, enforces the lockout, upgrades legacy passwords and creates a session. */
    public Session login(String role, String email, String password, String clientIp) {
        String r = (role == null ? "" : role.trim()).toUpperCase();
        if (email == null || email.isBlank() || password == null || password.isBlank())
            throw new IllegalArgumentException("Email and password are required.");

        guard.checkAllowed(r, email, clientIp);

        Account acc = switch (r) {
            case "DOCTOR"   -> db.byEmail(db.doctors, email);
            case "PATIENT"  -> db.byEmail(db.patients, email);
            case "HOSPITAL" -> db.byEmail(db.hospitals, email);
            case "ADMIN"    -> db.byEmail(db.admins, email);
            default -> throw new IllegalArgumentException(
                    "Invalid role. Choose patient, doctor, hospital or administrator.");
        };

        if (acc == null) {
            PasswordHasher.verify(password, DUMMY_HASH);           // equalise timing
            guard.recordFailure(r, email, clientIp);
            throw new IllegalArgumentException("Incorrect email or password.");
        }
        if (acc.isBlocked())
            throw new IllegalArgumentException("Your account has been blocked by an administrator.");
        if (!PasswordHasher.verify(password, acc.getPasswordHash())) {
            guard.recordFailure(r, email, clientIp);
            throw new IllegalArgumentException("Incorrect email or password.");
        }

        guard.recordSuccess(r, email, clientIp);

        // Accounts saved by the pre-security version still store a plaintext
        // password: transparently upgrade it to a PBKDF2 hash right now.
        if (!PasswordHasher.isHashed(acc.getPasswordHash())) {
            acc.setPasswordHash(PasswordHasher.hash(password));
            db.save();
        }
        return sessions.create(r, acc.getId(), acc.getName());
    }
}