package ehms.test;

import ehms.db.Database;
import ehms.model.Patient;
import ehms.security.LoginGuard;
import ehms.security.PasswordHasher;
import ehms.security.RateLimiter;
import ehms.security.SessionManager;
import ehms.security.SessionManager.Session;
import ehms.service.AuthService;
import ehms.service.PatientService;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

public class SecurityTests extends EhmsTest {

    @TestFactory
    Stream<DynamicTest> all() { return junitTests(); }

    @Override protected void define() {

        test("SessionManager: create / get / destroy", () -> {
            SessionManager sm = new SessionManager();
            Session s = sm.create("PATIENT", "P001", "Rahul");
            assertEquals("P001", sm.get(s.token()).accountId());
            sm.destroy(s.token());
            assertTrue(sm.get(s.token()) == null);
        });
        test("SessionManager: cookie flags", () -> {
            assertTrue(SessionManager.cookieHeader("tok", true).contains("HttpOnly"));
            assertTrue(SessionManager.cookieHeader("tok", true).contains("Secure"));
            assertTrue(SessionManager.cookieHeader("tok", false).contains("SameSite=Strict"));
            assertTrue(SessionManager.clearCookieHeader(false).contains("Max-Age=0"));
        });

        test("LoginGuard: account locks after 5 failures", () -> {
            LoginGuard g = new LoginGuard();
            for (int i = 0; i < 5; i++) g.recordFailure("PATIENT", "victim@x.com", "10.0.0.9");
            assertThrowsWithMessage(LoginGuard.LockedException.class, "Too many failed login attempts",
                    () -> g.checkAllowed("PATIENT", "victim@x.com", "10.0.0.9"));
        });
        test("LoginGuard: other accounts unaffected; success clears the lock", () -> {
            LoginGuard g = new LoginGuard();
            for (int i = 0; i < 5; i++) g.recordFailure("PATIENT", "victim@x.com", "10.0.0.9");
            g.checkAllowed("PATIENT", "someone-else@x.com", "10.0.0.9");   // must not throw
            g.recordSuccess("PATIENT", "victim@x.com", "10.0.0.9");
            g.checkAllowed("PATIENT", "victim@x.com", "10.0.0.9");         // must not throw
        });

        test("RateLimiter: limit reached then blocked", () -> {
            RateLimiter rl = new RateLimiter(3, 60_000);
            assertTrue(rl.tryAcquire("ip1"));
            assertTrue(rl.tryAcquire("ip1"));
            assertTrue(rl.tryAcquire("ip1"));
            assertFalse(rl.tryAcquire("ip1"));
            assertTrue(rl.tryAcquire("ip2"));
        });

        Database db = Database.createDetached();
        AuthService auth = new AuthService(db, new SessionManager(), new LoginGuard());
        new PatientService(db).register("Rahul", "rahul@x.com", "pass1234",
                "98100", 30, "Male", "B+", "Delhi");

        test("AuthService: successful login", () -> {
            Session s = auth.login("PATIENT", "rahul@x.com", "pass1234", "127.0.0.1");
            assertEquals("P001", s.accountId());
            assertEquals("PATIENT", s.role());
        });
        test("AuthService: wrong password and unknown email give the same message", () -> {
            IllegalArgumentException wrong = null, unknown = null;
            try { auth.login("PATIENT", "rahul@x.com", "nope", "ip"); }
            catch (IllegalArgumentException e) { wrong = e; }
            try { auth.login("PATIENT", "ghost@x.com", "nope", "ip"); }
            catch (IllegalArgumentException e) { unknown = e; }
            assertTrue(wrong != null && unknown != null);
            assertEquals(wrong.getMessage(), unknown.getMessage());        // anti-enumeration
        });
        test("AuthService: legacy plaintext password is upgraded on login", () -> {
            Patient p = db.patients.get("P001");
            p.setPasswordHash("oldpass");
            auth.login("PATIENT", "rahul@x.com", "oldpass", "ip");
            assertTrue(PasswordHasher.isHashed(p.getPasswordHash()));
            assertTrue(PasswordHasher.verify("oldpass", p.getPasswordHash()));
        });
        test("AuthService: blocked account cannot sign in", () -> {
            db.patients.get("P001").setBlocked(true);
            assertThrowsWithMessage(IllegalArgumentException.class, "blocked",
                    () -> auth.login("PATIENT", "rahul@x.com", "oldpass", "ip"));
        });
    }
}