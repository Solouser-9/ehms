package ehms.test;

import ehms.db.Database;
import ehms.model.DailyOccupancy;
import ehms.service.BedRequestService;
import ehms.service.BedService;
import ehms.service.BillingService;
import ehms.service.EquipmentService;
import ehms.service.HospitalService;
import ehms.service.PatientService;
import ehms.service.PharmacyService;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class HospitalTests extends EhmsTest {

    @TestFactory
    Stream<DynamicTest> all() { return junitTests(); }

    @Override protected void define() {
        final Database db = Database.createDetached();
        final BedService beds = new BedService(db);
        final HospitalService hospitals = new HospitalService(db, beds);
        final PatientService patients = new PatientService(db);
        final BedRequestService requests = new BedRequestService(db, beds);
        final BillingService billing = new BillingService(db);
        final EquipmentService equipment = new EquipmentService(db);
        final PharmacyService pharmacy = new PharmacyService(db);

        final String hospitalId = (String) hospitals.register("City Care", "cc@x.com", "pass1234",
                "011-1", "MG Road", 0).get("id");
        final String patientId = (String) patients.register("Rahul", "rahul@x.com", "pass1234",
                "98100", 30, "Male", "B+", "Delhi").get("id");
        final String secondPatientId = (String) patients.register("Priya", "priya@x.com", "pass1234",
                "98100", 34, "Female", "O+", "Kolkata").get("id");

        test("Beds: invalid type rejected, ward defaults applied", () -> {
            assertThrowsWithMessage(IllegalArgumentException.class, "Bed type",
                    () -> beds.addBeds(hospitalId, "LUXURY", 1));
            beds.addBeds(hospitalId, "ICU", 1);
            assertEquals("General Ward", db.beds.values().iterator().next().getWard());
        });

        test("Beds: admit and double-admission prevention", () -> {
            String bedId = db.beds.values().iterator().next().getId();
            Map<String, Object> admitted = beds.admit(hospitalId, bedId, patientId);
            assertEquals("OCCUPIED", admitted.get("status"));
            beds.addBeds(hospitalId, "GENERAL", 1, "Ward A");
            String freeBed = findFreeBedOtherThan(db, hospitalId, bedId);
            final String fb = freeBed;
            assertThrowsWithMessage(IllegalArgumentException.class, "already admitted",
                    () -> beds.admit(hospitalId, fb, patientId));
        });

        test("Billing: 25h stay rounds to 2 days at the default ICU rate", () -> {
            long now = System.currentTimeMillis();
            Map<String, Object> stay = Map.of(
                    "patientId", patientId, "patientName", "Rahul",
                    "bedNo", 1, "type", "ICU", "ward", "General Ward",
                    "admittedAt", now - 25L * 3600_000, "dischargedAt", now);
            Map<String, Object> bill = billing.generate(hospitalId, stay);
            assertEquals(2, bill.get("days"));
            assertEquals(16000.0, bill.get("amount"));
        });

        test("Billing: custom prices used; 2h stay = minimum 1 day", () -> {
            billing.setPrices(hospitalId, Map.of("GENERAL", 1000.0, "ICU", 2500.0, "VENTILATOR", 5000.0));
            long now = System.currentTimeMillis();
            Map<String, Object> stay = Map.of(
                    "patientId", patientId, "patientName", "Rahul",
                    "bedNo", 2, "type", "GENERAL", "ward", "Ward A",
                    "admittedAt", now - 2L * 3600_000, "dischargedAt", now);
            Map<String, Object> bill = billing.generate(hospitalId, stay);
            assertEquals(1, bill.get("days"));
            assertEquals(1000.0, bill.get("amount"));
        });

        test("Billing: patient pays online; double settlement rejected", () -> {
            String billId = null;
            for (var b : db.bills.values()) if (!b.isPaid()) { billId = b.getId(); break; }
            final String bid = billId;
            billing.pay(patientId, bid, "UPI");
            assertTrue(db.bills.get(bid).isPaid());
            assertThrowsWithMessage(IllegalArgumentException.class, "already settled",
                    () -> billing.receive(hospitalId, bid));
        });

        test("Bed requests: already-admitted patient cannot request", () ->
                assertThrowsWithMessage(IllegalArgumentException.class, "already admitted to bed",
                        () -> requests.create(patientId, hospitalId, "ANY", "test")));

        test("Bed requests: duplicate pending request rejected", () -> {
            requests.create(secondPatientId, hospitalId, "GENERAL", "post-surgery care");
            assertThrowsWithMessage(IllegalArgumentException.class, "pending bed request",
                    () -> requests.create(secondPatientId, hospitalId, "ICU", "again"));
        });

        test("Bed requests: rejection with note is visible to the patient", () -> {
            String requestId = firstPendingRequestId(db, secondPatientId);
            requests.decide(hospitalId, requestId, false, "No beds available", null);
            Map<String, Object> r = requests.forPatient(secondPatientId).get(0);
            assertEquals("REJECTED", r.get("status"));
            assertEquals("No beds available", r.get("decisionNote"));
        });

        test("Bed requests: approval can assign a specific bed (type-checked)", () -> {
            Map<String, Object> req = requests.create(secondPatientId, hospitalId, "ANY", "advised admission");
            String requestId = (String) req.get("id");
            Map<String, Object> decided = requests.decide(hospitalId, requestId, true, "welcome", null);
            assertEquals("APPROVED", decided.get("status"));
            int bedNo = ((Number) decided.get("bedNo")).intValue();
            String approvedBedId = bedWithNumber(db, hospitalId, bedNo);
            assertTrue(secondPatientId.equals(db.beds.get(approvedBedId).getPatientId()));

            String p4 = (String) patients.register("Nita", "nita@x.com", "pass1234", "94", 30, "Female", "A+", "Pune").get("id");
            beds.addBeds(hospitalId, "ICU", 1, "ICU 2");
            String icuBedId = findIcuBed(db, hospitalId);
            Map<String, Object> req2 = requests.create(p4, hospitalId, "GENERAL", "test2");
            String req2Id = (String) req2.get("id");
            assertThrowsWithMessage(IllegalArgumentException.class, "requested bed type",
                    () -> requests.decide(hospitalId, req2Id, true, "", icuBedId));
        });

        test("Equipment: lifecycle and auto-release on discharge", () -> {
            beds.addBeds(hospitalId, "GENERAL", 1, "Ward A");
            String freeBed = bedWithNumber(db, hospitalId, 3);
            String eqId = (String) equipment.add(hospitalId, "OXYGEN CYLINDER", "O2-014").get("id");

            assertThrowsWithMessage(IllegalArgumentException.class, "occupied bed",
                    () -> equipment.assign(hospitalId, eqId, freeBed));

            String thirdPatient = (String) patients.register("Amit", "amit@x.com", "pass1234",
                    "98", 40, "Male", "A+", "Pune").get("id");
            beds.admit(hospitalId, freeBed, thirdPatient);
            equipment.assign(hospitalId, eqId, freeBed);
            assertEquals("IN_USE", db.equipment.get(eqId).getStatus());
            assertThrowsWithMessage(IllegalArgumentException.class, "Release the equipment",
                    () -> equipment.setStatus(hospitalId, eqId, "MAINTENANCE"));

            beds.discharge(hospitalId, freeBed);
            assertEquals(1, equipment.releaseForBed(hospitalId, freeBed));
            assertEquals("AVAILABLE", db.equipment.get(eqId).getStatus());
        });

        test("Pharmacy: stock, dispensing and reorder alerts", () -> {
            String medId = (String) pharmacy.add(hospitalId, "Paracetamol 500 mg", "tablets", 10, 5, 2.0).get("id");
            Map<String, Object> dispensed = pharmacy.dispense(hospitalId, patientId, medId, 3, "against A001");
            assertEquals(7, dispensed.get("remainingStock"));
            assertThrowsWithMessage(IllegalArgumentException.class, "Only 7",
                    () -> pharmacy.dispense(hospitalId, patientId, medId, 8, ""));
            pharmacy.dispense(hospitalId, patientId, medId, 2, "");
            boolean low = false;
            for (Map<String, Object> m : pharmacy.list(hospitalId))
                if (Boolean.TRUE.equals(m.get("lowStock"))) low = true;
            assertTrue(low);
            pharmacy.restock(hospitalId, medId, 10);
            assertEquals(15, db.medicines.get(medId).getStock());
            assertEquals(2, pharmacy.forPatient(patientId).size());
            assertEquals(2, pharmacy.history(hospitalId).size());
        });

        test("Occupancy: snapshots written on admission and preferred by dailyStats", () -> {
            String h5 = (String) hospitals.register("Riverside", "riv@x.com", "pass1234", "071", "River Rd", 0).get("id");
            String p5 = (String) patients.register("Omkar", "om@x.com", "pass1234", "93", 35, "Male", "AB+", "Nashik").get("id");
            beds.addBeds(h5, "GENERAL", 2, "W");
            String b5 = null;
            for (var b : db.beds.values()) if (b.getHospitalId().equals(h5)) b5 = b.getId();
            beds.admit(h5, b5, p5);

            String today = java.time.LocalDate.now().toString();
            boolean found = false;
            for (DailyOccupancy o : db.occupancy.values())
                if (o.getDay().equals(today) && o.getHospitalId().equals(h5)) { found = true; assertEquals(1, o.getOccupied()); }
            assertTrue(found);

            String yesterday = java.time.LocalDate.now().minusDays(1).toString();
            db.occupancy.put("y|" + h5, new DailyOccupancy("y|" + h5, yesterday, h5, 5, 9, System.currentTimeMillis()));
            List<Map<String, Object>> stats = db.dailyStats(3);
            assertEquals(5, stats.get(1).get("occupancy"));
            long live = db.beds.values().stream().filter(b -> !b.isFree()).count();
            assertEquals((int) live, stats.get(2).get("occupancy"));
        });
    }

    // ---- helper methods (no lambda capture issues here) ----

    private static String findFreeBedOtherThan(Database db, String hospitalId, String excludeBedId) {
        for (var b : db.beds.values()) {
            if (b.getHospitalId().equals(hospitalId) && b.isFree() && !b.getId().equals(excludeBedId)) {
                return b.getId();
            }
        }
        throw new IllegalStateException("no free bed found other than " + excludeBedId);
    }

    private static String findIcuBed(Database db, String hospitalId) {
        for (var b : db.beds.values()) {
            if (b.getHospitalId().equals(hospitalId) && "ICU".equals(b.getType()) && b.isFree()) {
                return b.getId();
            }
        }
        throw new IllegalStateException("no free ICU bed found");
    }

    private static String firstPendingRequestId(Database db, String patientId) {
        for (var r : db.bedRequests.values())
            if (r.getPatientId().equals(patientId) && "PENDING".equals(r.getStatus())) return r.getId();
        throw new IllegalStateException("no pending request");
    }

    private static String bedWithNumber(Database db, String hospitalId, int bedNo) {
        for (var b : db.beds.values())
            if (b.getHospitalId().equals(hospitalId) && b.getBedNo() == bedNo) return b.getId();
        throw new IllegalStateException("bed not found: " + bedNo);
    }
}