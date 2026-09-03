package ehms.test;

import ehms.db.Database;
import ehms.model.Doctor;
import ehms.security.PasswordHasher;
import ehms.service.AdminService;
import ehms.service.BedService;
import ehms.service.DoctorService;
import ehms.service.HospitalService;
import ehms.service.PatientService;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

public class RegistrationTests extends EhmsTest {

    @TestFactory
    Stream<DynamicTest> all() { return junitTests(); }

    @Override protected void define() {
        Database db = Database.createDetached();
        DoctorService doctors = new DoctorService(db);
        PatientService patients = new PatientService(db);
        HospitalService hospitals = new HospitalService(db, new BedService(db));
        AdminService admin = new AdminService(db, "test-key");

        test("Doctor registration: id, verification pending, hashed password", () -> {
            var d = doctors.register("Ananya Mehta", "doc@x.com", "pass1234", "98100",
                    "General Physician", "MCI-10021", 300);
            assertEquals("D001", d.get("id"));
            assertEquals(false, d.get("verified"));
            Doctor doc = db.doctors.get("D001");
            assertTrue(PasswordHasher.isHashed(doc.getPasswordHash()));
        });
        test("Doctor registration: duplicate email rejected", () ->
            assertThrowsWithMessage(IllegalArgumentException.class, "already registered",
                () -> doctors.register("Someone Else", "doc@x.com", "pass1234", "98100",
                        "Paediatrics", "MCI-2", 400)));

        test("Patient registration: age bounds enforced", () ->
            assertThrowsWithMessage(IllegalArgumentException.class, "Age",
                () -> patients.register("Kid", "kid@x.com", "pass1234", "98100", 0, "Male", "", "")));

        test("Admin registration: wrong key rejected, right key accepted", () -> {
            assertThrowsWithMessage(IllegalArgumentException.class, "administrator key",
                () -> admin.register("Root", "admin@x.com", "pass1234", "wrong"));
            assertEquals("AD001", admin.register("Root", "admin@x.com", "pass1234", "test-key").get("id"));
        });

        test("Admin: verify doctor, block doctor, cannot block self", () -> {
            admin.setDoctorVerified("D001", true);
            assertTrue(db.doctors.get("D001").isVerified());
            admin.setBlocked("AD001", "DOCTOR", "D001", true);
            assertTrue(db.doctors.get("D001").isBlocked());
            assertThrowsWithMessage(IllegalArgumentException.class, "own administrator",
                () -> admin.setBlocked("AD001", "ADMIN", "AD001", true));
        });

        test("Hospital registration: initial beds created", () -> {
            hospitals.register("City Care", "cc@x.com", "pass1234", "011-123", "MG Road", 3);
            long beds = db.beds.values().stream()
                    .filter(b -> b.getHospitalId().equals("H001")).count();
            assertEquals(3L, beds);
        });
    }
}