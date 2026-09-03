package ehms.test;

import ehms.db.Database;
import ehms.security.SessionManager.Session;
import ehms.service.AppointmentService;
import ehms.service.AuditService;
import ehms.service.DoctorService;
import ehms.service.PatientService;
import ehms.service.PdfService;
import ehms.service.SlotService;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class PdfAuditTests extends EhmsTest {

    @TestFactory
    Stream<DynamicTest> all() { return junitTests(); }

    @Override protected void define() {
        Database db = Database.createDetached();
        DoctorService doctors = new DoctorService(db);
        PatientService patients = new PatientService(db);
        SlotService slots = new SlotService(db);
        AppointmentService appts = new AppointmentService(db);
        PdfService pdfs = new PdfService(db);
        AuditService audit = new AuditService(db);

        String doctorId = (String) doctors.register("Sneha K", "sneha@x.com", "pass1234",
                "98100", "Dermatologist", "MCI-7", 250).get("id");
        String patientId = (String) patients.register("Rahul", "rahul@x.com", "pass1234",
                "98100", 30, "Male", "B+", "Delhi").get("id");
        db.doctors.get(doctorId).setVerified(true);
        String slotId = (String) slots.publish(doctorId, LocalDate.now().plusDays(1).toString(),
                "10:00", 20, 1).get(0).get("id");
        String apptId = (String) appts.book(patientId, doctorId, slotId, "skin rash").get("id");

        Session patientSession = new Session("t", "PATIENT", patientId, "Rahul", 0, Long.MAX_VALUE);
        Session doctorSession = new Session("t2", "DOCTOR", doctorId, "Dr. Sneha", 0, Long.MAX_VALUE);

        test("PDF: prescription only exists after completion", () ->
            assertThrowsWithMessage(IllegalArgumentException.class,
                "only after the consultation is completed",
                () -> pdfs.prescription(patientSession, apptId)));

        test("PDF: completed consultation produces a real PDF", () -> {
            appts.consult(doctorId, apptId, "Eczema", "Moisturiser twice daily");
            byte[] pdf = pdfs.prescription(patientSession, apptId);
            assertEquals("%PDF-1.", new String(pdf, 0, 8, StandardCharsets.ISO_8859_1).substring(0, 7));
        });

        test("PDF: history is patient-only", () ->
            assertThrowsWithMessage(IllegalArgumentException.class, "patients only",
                () -> pdfs.history(doctorSession)));

        test("Audit: entries matched by id OR email (failed logins visible to the victim)", () -> {
            audit.record(patientSession, AuditService.LOGIN, "Signed in");
            audit.record("PATIENT", null, null, "rahul@x.com",
                    AuditService.LOGIN_FAILED, "Failed sign-in attempt");
            List<Map<String, Object>> mine = audit.mine(patientId, "rahul@x.com", 10);
            assertEquals(2, mine.size());
            assertTrue(audit.recent(10).size() >= 2);
        });
    }
}