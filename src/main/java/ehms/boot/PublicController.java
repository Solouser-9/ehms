package ehms.boot;

import ehms.db.Database;
import ehms.security.Captcha;
import ehms.security.LoginGuard;
import ehms.security.SessionManager;
import ehms.security.SessionManager.Session;
import ehms.service.AdminService;
import ehms.service.AppointmentService;
import ehms.service.AuditService;
import ehms.service.AuthService;
import ehms.service.BedService;
import ehms.service.DoctorService;
import ehms.service.HospitalService;
import ehms.service.PatientService;
import ehms.util.Json;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class PublicController {

    private final Database db;
    private final AuthService auth;
    private final AuditService audit;
    private final Captcha captcha;
    private final DoctorService doctors;
    private final PatientService patients;
    private final HospitalService hospitals;
    private final BedService beds;
    private final AppointmentService appointments;
    private final SessionManager sessions;
    private final AdminService admin;
    private final boolean trustProxy;

    public PublicController(Database db, AuthService auth, AuditService audit, Captcha captcha,
                            DoctorService doctors, PatientService patients, HospitalService hospitals,
                            BedService beds, AppointmentService appointments,
                            SessionManager sessions, AdminService admin, EhmsProperties props) {
        this.db = db; this.auth = auth; this.audit = audit; this.captcha = captcha;
        this.doctors = doctors; this.patients = patients; this.hospitals = hospitals;
        this.beds = beds; this.appointments = appointments;
        this.sessions = sessions; this.admin = admin;
        this.trustProxy = Boolean.TRUE.equals(props.trustProxy());
    }

    @PostMapping("/api/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> b,
                                     HttpServletRequest req, HttpServletResponse res) {
        String role = Params.str(b, "role");
        String email = Params.str(b, "email");
        try {
            if (captcha.enabled() && !captcha.verify(Params.opt(b, "captchaSalt"), Params.longOpt(b, "captchaAnswer", -1)))
                throw new IllegalArgumentException("Please complete the human verification and try again.");
            Session created = auth.login(role, email, Params.str(b, "password"), Params.clientIp(req, trustProxy));
            res.addHeader("Set-Cookie", SessionManager.cookieHeader(created.token(), req.isSecure()));
            audit.record(created, AuditService.LOGIN, "Signed in");
            return Params.accountPayload(db, created);
        } catch (LoginGuard.LockedException locked) {
            audit.record(role, null, null, email, AuditService.ACCOUNT_LOCKED,
                    "Sign-in blocked: too many failed attempts");
            throw locked;
        } catch (IllegalArgumentException failure) {
            audit.record(role, null, null, email, AuditService.LOGIN_FAILED, "Failed sign-in attempt");
            throw failure;
        }
    }

    @PostMapping("/api/logout")
    public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) {
        Session s = Params.session(req);
        if (s != null) {
            sessions.destroy(s.token());
            audit.record(s, AuditService.LOGOUT, "Signed out");
        }
        res.addHeader("Set-Cookie", SessionManager.clearCookieHeader(req.isSecure()));
        return Json.obj("loggedOut", true);
    }

    @PostMapping("/api/me")
    public Map<String, Object> me(HttpServletRequest req) {
        Session s = Params.session(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", s == null ? null : Params.accountPayload(db, s));
        out.put("ws", 0);            // 0 = WebSocket on the SAME origin (Spring WebSocket at /ws)
        return out;
    }

    @PostMapping("/api/captcha")
    public Map<String, Object> captcha() { return captcha.challenge(); }

    @PostMapping("/api/stats")
    public Map<String, Object> stats() { return db.stats(); }

    @PostMapping("/api/stats/daily")
    public List<Map<String, Object>> daily(@RequestBody Map<String, Object> b) {
        return db.dailyStats((int) Params.longOpt(b, "days", 14));
    }

    @PostMapping("/api/doctors")
    public List<Map<String, Object>> doctors(@RequestBody Map<String, Object> b) {
        List<Map<String, Object>> list = doctors.list(Params.opt(b, "specialization"));
        list.removeIf(m -> !Boolean.TRUE.equals(m.get("verified")) || Boolean.TRUE.equals(m.get("blocked")));
        return list;
    }

    @PostMapping("/api/patients")
    public List<Map<String, Object>> patients(HttpServletRequest req) {
        Params.requireAnyRole(req, "HOSPITAL", "DOCTOR");
        return patients.list();
    }

    @PostMapping("/api/bed/availability")
    public List<Map<String, Object>> availability() { return beds.availability(); }

    @PostMapping("/api/register/doctor")
    public Map<String, Object> registerDoctor(@RequestBody Map<String, Object> b) {
        Map<String, Object> d = doctors.register(Params.str(b, "name"), Params.str(b, "email"),
                Params.str(b, "password"), Params.str(b, "phone"), Params.str(b, "specialization"),
                Params.str(b, "licenseNo"), Params.dbl(b, "fee"));
        audit.record("DOCTOR", (String) d.get("id"), (String) d.get("name"), (String) d.get("email"),
                AuditService.REGISTERED, "Doctor account registered (" + d.get("specialization") + ")");
        return d;
    }

    @PostMapping("/api/register/patient")
    public Map<String, Object> registerPatient(@RequestBody Map<String, Object> b) {
        Map<String, Object> p = patients.register(Params.str(b, "name"), Params.str(b, "email"),
                Params.str(b, "password"), Params.str(b, "phone"), Params.intVal(b, "age"),
                Params.opt(b, "gender"), Params.opt(b, "bloodGroup"), Params.opt(b, "address"));
        audit.record("PATIENT", (String) p.get("id"), (String) p.get("name"), (String) p.get("email"),
                AuditService.REGISTERED, "Patient account registered");
        return p;
    }

    @PostMapping("/api/register/hospital")
    public Map<String, Object> registerHospital(@RequestBody Map<String, Object> b) {
        Map<String, Object> h = hospitals.register(Params.str(b, "name"), Params.str(b, "email"),
                Params.str(b, "password"), Params.str(b, "phone"), Params.str(b, "address"),
                Params.intOpt(b, "initialBeds", 0));
        audit.record("HOSPITAL", (String) h.get("id"), (String) h.get("name"), (String) h.get("email"),
                AuditService.REGISTERED, "Hospital account registered");
        return h;
    }

    @PostMapping("/api/register/admin")
    public Map<String, Object> registerAdmin(@RequestBody Map<String, Object> b) {
        Map<String, Object> a = admin.register(Params.str(b, "name"), Params.str(b, "email"),
                Params.str(b, "password"), Params.opt(b, "adminKey"));
        audit.record("ADMIN", (String) a.get("id"), (String) a.get("name"), (String) a.get("email"),
                AuditService.REGISTERED, "Administrator account registered");
        return a;
    }

    @PostMapping("/api/audit/mine")
    public List<Map<String, Object>> auditMine(HttpServletRequest req) {
        Session s = Params.requireAnyRole(req, "PATIENT", "DOCTOR", "HOSPITAL", "ADMIN");
        return audit.mine(s.accountId(), Params.accountEmail(db, s), 25);
    }
}