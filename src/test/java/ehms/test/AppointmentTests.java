package ehms.test;

import ehms.db.Database;
import ehms.model.Slot;
import ehms.security.SessionManager.Session;
import ehms.service.AppointmentService;
import ehms.service.ChatService;
import ehms.service.DoctorService;
import ehms.service.PatientService;
import ehms.service.PaymentService;
import ehms.service.ReportService;
import ehms.service.SlotService;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class AppointmentTests extends EhmsTest {

    @TestFactory
    Stream<DynamicTest> all() { return junitTests(); }

    private String slotId;
    private String appointmentId;

    @Override protected void define() {
        Database db = Database.createDetached();
        DoctorService doctors = new DoctorService(db);
        PatientService patients = new PatientService(db);
        SlotService slots = new SlotService(db);
        AppointmentService appts = new AppointmentService(db);
        ChatService chat = new ChatService(db);
        PaymentService payments = new PaymentService(db);
        ReportService reports = new ReportService(db);

        String doctorId = (String) doctors.register("Vikram Rao", "vikram@x.com", "pass1234",
                "98100", "Cardiologist", "MCI-9", 300).get("id");
        String patientId = (String) patients.register("Rahul", "rahul@x.com", "pass1234",
                "98100", 30, "Male", "B+", "Delhi").get("id");
        String otherPatientId = (String) patients.register("Priya", "priya@x.com", "pass1234",
                "98100", 34, "Female", "O+", "Kolkata").get("id");
        String tomorrow = LocalDate.now().plusDays(1).toString();

        test("Slots: unverified doctor cannot publish", () ->
            assertThrowsWithMessage(IllegalArgumentException.class, "verification is pending",
                () -> slots.publish(doctorId, tomorrow, "09:00", 20, 2)));

        test("Slots: publish 2 slots after verification", () -> {
            db.doctors.get(doctorId).setVerified(true);
            List<Map<String, Object>> created = slots.publish(doctorId, tomorrow, "09:00", 20, 2);
            assertEquals(2, created.size());
            slotId = (String) created.get(0).get("id");
            assertEquals(2, slots.openFor(doctorId).size());
        });

        test("Booking: a slot is required", () ->
            assertThrowsWithMessage(IllegalArgumentException.class, "open time slots",
                () -> appts.book(patientId, doctorId, null, "fever")));

        test("Booking: success stores the slot time", () -> {
            Map<String, Object> a = appts.book(patientId, doctorId, slotId, "fever and headache");
            appointmentId = (String) a.get("id");
            assertEquals("PENDING", a.get("status"));
            assertEquals(db.slots.get(slotId).getStartAt(), a.get("scheduledAt"));
        });

        test("Booking: booked slot cannot be booked twice", () ->
            assertThrowsWithMessage(IllegalArgumentException.class, "no longer available",
                () -> appts.book(otherPatientId, doctorId, slotId, "sore throat")));

        test("Booking: unavailable doctor rejected", () -> {
            doctors.setAvailability(doctorId, false);
            String freeSlot = secondOpenSlot(db, doctorId);
            assertThrowsWithMessage(IllegalArgumentException.class, "not accepting",
                () -> appts.book(otherPatientId, doctorId, freeSlot, "sore throat"));
            doctors.setAvailability(doctorId, true);
        });

        test("Cancel: pending consultation cancelled, slot released", () -> {
            Map<String, Object> c = appts.cancel(patientId, appointmentId);
            assertEquals("CANCELLED", c.get("status"));
            assertTrue(db.slots.get(slotId).isOpen());
        });

        test("Consult: cancelled consultation cannot be completed", () ->
            assertThrowsWithMessage(IllegalArgumentException.class, "cancelled by the patient",
                () -> appts.consult(doctorId, appointmentId, "dx", "rx")));

        test("Full flow: re-book released slot, chat, consult, pay", () -> {
            Map<String, Object> a = appts.book(otherPatientId, doctorId, slotId, "sore throat");
            String id = (String) a.get("id");
            Session patient = new Session("t1", "PATIENT", otherPatientId, "Priya", 0, Long.MAX_VALUE);
            Session doctor = new Session("t2", "DOCTOR", doctorId, "Dr. Rao", 0, Long.MAX_VALUE);
            Session stranger = new Session("t3", "PATIENT", patientId, "Rahul", 0, Long.MAX_VALUE);

            chat.send(patient, id, "Hello doctor, since morning my throat hurts.");
            assertTrue(((Number) chat.unread(doctor).get("total")).longValue() >= 1);
            Map<String, Object> thread = chat.messages(doctor, id, 0);
            assertEquals(1, ((List<?>) thread.get("items")).size());
            assertEquals(0L, ((Number) chat.unread(doctor).get("total")).longValue());   // marked read
            assertThrowsWithMessage(IllegalArgumentException.class, "different patient",
                () -> chat.messages(stranger, id, 0));

            assertThrowsWithMessage(IllegalArgumentException.class, "Diagnosis",
                () -> appts.consult(doctorId, id, "   ", "paracetamol"));
            Map<String, Object> done = appts.consult(doctorId, id, "Viral pharyngitis",
                    "Paracetamol 500mg x 3 days");
            assertEquals("COMPLETED", done.get("status"));

            payments.createDue(db.appointments.get(id));
            assertEquals(300.0, payments.mine(otherPatientId).get("totalDue"));
            String payId = db.payments.values().iterator().next().getId();
            assertThrowsWithMessage(IllegalArgumentException.class, "Payment method",
                () -> payments.pay(otherPatientId, payId, "CASHLESS"));
            payments.pay(otherPatientId, payId, "UPI");
            assertEquals(0.0, payments.mine(otherPatientId).get("totalDue"));
            assertEquals(300.0, payments.forDoctor(doctorId).get("earned"));
        });

        test("Reports: extension and empty-file validation", () -> {
            assertThrowsWithMessage(IllegalArgumentException.class, "Allowed file types",
                () -> reports.upload(otherPatientId, "x", "virus.exe", null, new byte[3]));
            assertThrowsWithMessage(IllegalArgumentException.class, "choose a file",
                () -> reports.upload(otherPatientId, "x", "a.png", null, new byte[0]));
        });
    }

    private static String secondOpenSlot(Database db, String doctorId) {
        for (Slot s : db.slots.values())
            if (s.getDoctorId().equals(doctorId) && s.isOpen()) return s.getId();
        throw new IllegalStateException("no open slot found");
    }
}