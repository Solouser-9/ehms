package ehms.boot;

import ehms.security.SessionManager.Session;
import ehms.service.AdminService;
import ehms.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class AdminController {

    private final AdminService admin;
    private final AuditService audit;

    public AdminController(AdminService admin, AuditService audit) {
        this.admin = admin;
        this.audit = audit;
    }

    @PostMapping("/api/admin/stats")
    public Map<String, Object> stats(HttpServletRequest req) {
        Params.requireRole(req, "ADMIN");
        return admin.stats();
    }

    @PostMapping("/api/admin/doctors")
    public List<Map<String, Object>> doctors(HttpServletRequest req) {
        Params.requireRole(req, "ADMIN");
        return admin.doctors();
    }

    @PostMapping("/api/admin/verify")
    public Map<String, Object> verify(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "ADMIN");
        boolean verified = Params.bool(b, "verified");
        Map<String, Object> d = admin.setDoctorVerified(Params.str(b, "doctorId"), verified);
        audit.record(s, verified ? AuditService.DOCTOR_VERIFIED : AuditService.DOCTOR_UNVERIFIED,
                "Doctor " + d.get("name") + " (" + d.get("id") + ")");
        return d;
    }

    @PostMapping("/api/admin/accounts")
    public List<Map<String, Object>> accounts(HttpServletRequest req) {
        Params.requireRole(req, "ADMIN");
        return admin.accounts();
    }

    @PostMapping("/api/admin/block")
    public Map<String, Object> block(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "ADMIN");
        boolean blocked = Params.bool(b, "blocked");
        Map<String, Object> acc = admin.setBlocked(s.accountId(), Params.str(b, "role"),
                Params.str(b, "id"), blocked);
        audit.record(s, blocked ? AuditService.ACCOUNT_BLOCKED : AuditService.ACCOUNT_UNBLOCKED,
                acc.get("role") + " " + acc.get("name") + " (" + acc.get("id") + ")");
        return acc;
    }

    @PostMapping("/api/admin/audit")
    public List<Map<String, Object>> auditTrail(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Params.requireRole(req, "ADMIN");
        return audit.recent((int) Params.longOpt(b, "limit", 50));
    }
}