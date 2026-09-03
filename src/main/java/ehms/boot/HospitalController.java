package ehms.boot;

import ehms.security.SessionManager.Session;
import ehms.service.AuditService;
import ehms.service.BedRequestService;
import ehms.service.BedService;
import ehms.service.BillingService;
import ehms.service.EquipmentService;
import ehms.service.PharmacyService;
import ehms.util.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class HospitalController {

    private final BedService beds;
    private final BedRequestService bedRequests;
    private final BillingService billing;
    private final EquipmentService equipment;
    private final PharmacyService pharmacy;
    private final AuditService audit;

    public HospitalController(BedService beds, BedRequestService bedRequests, BillingService billing,
                              EquipmentService equipment, PharmacyService pharmacy, AuditService audit) {
        this.beds = beds; this.bedRequests = bedRequests; this.billing = billing;
        this.equipment = equipment; this.pharmacy = pharmacy; this.audit = audit;
    }

    @PostMapping("/api/hospital/beds")
    public Map<String, Object> overview(HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        return beds.overview(s.accountId());
    }

    @PostMapping("/api/hospital/beds/add")
    public Integer addBeds(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        String ward = Params.opt(b, "ward");
        int added = beds.addBeds(s.accountId(), Params.str(b, "type"), Params.intVal(b, "count"), ward);
        audit.record(s, AuditService.BEDS_ADDED, added + " " + Params.str(b, "type") + " bed(s) added"
                + (ward == null || ward.isBlank() ? "" : " to " + ward));
        return added;
    }

    @PostMapping("/api/hospital/admit")
    public Map<String, Object> admit(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        Map<String, Object> r = beds.admit(s.accountId(), Params.str(b, "bedId"), Params.str(b, "patientId"));
        audit.record(s, AuditService.PATIENT_ADMITTED, "Patient " + r.get("patientId")
                + " admitted to bed " + r.get("bedNo") + " (" + r.get("type") + ", " + r.get("ward") + ")");
        return r;
    }

    @PostMapping("/api/hospital/discharge")
    public Map<String, Object> discharge(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        Map<String, Object> r = beds.discharge(s.accountId(), Params.str(b, "bedId"));
        int released = equipment.releaseForBed(s.accountId(), Params.str(b, "bedId"));
        Map<String, Object> bill = billing.generate(s.accountId(), r);
        r.put("bill", bill);
        r.put("equipmentReleased", released);
        audit.record(s, AuditService.PATIENT_DISCHARGED, "Bed " + r.get("bedNo") + " freed (patient discharged)");
        audit.record(s, AuditService.BILL_GENERATED, "Bill " + bill.get("id") + " of " + bill.get("amount")
                + " generated (" + bill.get("itemCount") + " charge item(s), " + bill.get("days")
                + " day(s) at " + bill.get("ratePerDay") + "/day)"
                + (released > 0 ? "; " + released + " equipment item(s) released and billed" : ""));
        return r;
    }

    @PostMapping("/api/hospital/prices")
    public Map<String, Object> prices(HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        return billing.prices(s.accountId());
    }

    @PostMapping("/api/hospital/prices/set")
    public Map<String, Object> setPrices(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        Map<String, Object> r = billing.setPrices(s.accountId(), priceMap(b));
        @SuppressWarnings("unchecked")
        Map<String, Double> saved = (Map<String, Double>) r.get("prices");
        audit.record(s, AuditService.BED_PRICES_UPDATED, "Bed prices per day: GENERAL "
                + saved.get("GENERAL") + ", ICU " + saved.get("ICU")
                + ", VENTILATOR " + saved.get("VENTILATOR"));
        return r;
    }

    @PostMapping("/api/hospital/wards")
    public List<Map<String, Object>> wards(HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        return beds.wards(s.accountId());
    }

    @PostMapping("/api/hospital/ward/save")
    public Map<String, Object> saveWard(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        Map<String, Object> r = beds.saveWard(s.accountId(), Params.str(b, "name"),
                Params.opt(b, "floor"), Params.intVal(b, "capacity"));
        audit.record(s, AuditService.WARD_SAVED, "Ward '" + r.get("name") + "' saved (capacity "
                + r.get("capacity") + ", floor '" + r.get("floor") + "')");
        return r;
    }

    @PostMapping("/api/hospital/eqprices/set")
    public Map<String, Object> setEqPrices(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        Map<String, Object> r = equipment.setPrices(s.accountId(), priceMap(b));
        audit.record(s, AuditService.EQUIPMENT_PRICES_UPDATED,
                "Equipment prices per day updated (" + ((Map<?, ?>) r.get("prices")).size() + " kinds)");
        return r;
    }

    private static Map<String, Double> priceMap(Map<String, Object> b) {
        Map<String, Double> prices = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Params.mapVal(b, "prices").entrySet()) {
            Object v = e.getValue();
            double d;
            if (v instanceof Number n) d = n.doubleValue();
            else {
                try { d = Double.parseDouble(String.valueOf(v).trim()); }
                catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException("Price for '" + e.getKey() + "' must be a number.");
                }
            }
            prices.put(e.getKey(), d);
        }
        return prices;
    }

    // ----- equipment -----

    @PostMapping("/api/equipment/list")
    public List<Map<String, Object>> equipmentList(HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        return equipment.list(s.accountId());
    }

    @PostMapping("/api/equipment/add")
    public Map<String, Object> equipmentAdd(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        Map<String, Object> r = equipment.add(s.accountId(), Params.str(b, "kind"), Params.opt(b, "label"));
        audit.record(s, AuditService.EQUIPMENT_ADDED, r.get("kind") + " '" + r.get("label") + "' added");
        return r;
    }

    @PostMapping("/api/equipment/assign")
    public Map<String, Object> equipmentAssign(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        Map<String, Object> r = equipment.assign(s.accountId(), Params.str(b, "equipmentId"), Params.str(b, "bedId"));
        audit.record(s, AuditService.EQUIPMENT_ASSIGNED, r.get("label") + " attached to bed " + r.get("bedNo"));
        return r;
    }

    @PostMapping("/api/equipment/release")
    public Map<String, Object> equipmentRelease(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        Map<String, Object> r = equipment.release(s.accountId(), Params.str(b, "equipmentId"));
        Object charged = r.get("chargedAmount");
        audit.record(s, AuditService.EQUIPMENT_RELEASED, r.get("label") + " released back to stock"
                + (charged instanceof Number n && n.doubleValue() > 0
                   ? "; " + n.doubleValue() + " usage charge recorded for the patient's bill" : ""));
        return r;
    }

    @PostMapping("/api/equipment/status")
    public Map<String, Object> equipmentStatus(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        Map<String, Object> r = equipment.setStatus(s.accountId(), Params.str(b, "equipmentId"), Params.str(b, "status"));
        audit.record(s, AuditService.EQUIPMENT_STATUS_CHANGED, r.get("label") + " set to " + r.get("status"));
        return r;
    }

    // ----- pharmacy -----

    @PostMapping("/api/pharmacy/list")
    public List<Map<String, Object>> pharmacyList(HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        return pharmacy.list(s.accountId());
    }

    @PostMapping("/api/pharmacy/add")
    public Map<String, Object> pharmacyAdd(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        Map<String, Object> m = pharmacy.add(s.accountId(), Params.str(b, "name"), Params.opt(b, "unit"),
                Params.intVal(b, "stock"), Params.intVal(b, "reorderLevel"), Params.dbl(b, "price"));
        audit.record(s, AuditService.MEDICINE_ADDED,
                m.get("name") + " added to pharmacy (" + m.get("stock") + " " + m.get("unit") + ")");
        return m;
    }

    @PostMapping("/api/pharmacy/restock")
    public Map<String, Object> restock(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        Map<String, Object> m = pharmacy.restock(s.accountId(), Params.str(b, "medicineId"), Params.intVal(b, "qty"));
        audit.record(s, AuditService.STOCK_RESTOCKED, m.get("name") + " restocked to " + m.get("stock") + " " + m.get("unit"));
        return m;
    }

    @PostMapping("/api/pharmacy/dispense")
    public Map<String, Object> dispense(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        Map<String, Object> d = pharmacy.dispense(s.accountId(), Params.str(b, "patientId"),
                Params.str(b, "medicineId"), Params.intVal(b, "qty"), Params.opt(b, "note"));
        audit.record(s, AuditService.MEDICINE_DISPENSED,
                d.get("qty") + " x " + d.get("medicineName") + " dispensed to " + d.get("patientName"));
        return d;
    }

    @PostMapping("/api/pharmacy/history")
    public List<Map<String, Object>> history(HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        return pharmacy.history(s.accountId());
    }

    // ----- bed requests + bills -----

    @PostMapping("/api/bed/requests")
    public List<Map<String, Object>> requests(HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        return bedRequests.forHospital(s.accountId());
    }

    @PostMapping("/api/bed/request/decide")
    public Map<String, Object> decide(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        boolean approve = Params.bool(b, "approve");
        Map<String, Object> r = bedRequests.decide(s.accountId(), Params.str(b, "requestId"),
                approve, Params.opt(b, "note"), Params.opt(b, "bedId"));
        if (approve) {
            audit.record(s, AuditService.BED_REQUEST_APPROVED, "Bed request " + r.get("id") + " approved - "
                    + r.get("patientName") + " admitted to bed " + r.get("bedNo"));
        } else {
            audit.record(s, AuditService.BED_REQUEST_REJECTED, "Bed request " + r.get("id") + " rejected"
                    + (r.get("decisionNote") == null || String.valueOf(r.get("decisionNote")).isEmpty()
                       ? "" : " (" + r.get("decisionNote") + ")"));
        }
        return r;
    }

    @PostMapping("/api/hospital/bills")
    public List<Map<String, Object>> bills(HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        return billing.forHospital(s.accountId());
    }

    @PostMapping("/api/bill/received")
    public Map<String, Object> billReceived(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Session s = Params.requireRole(req, "HOSPITAL");
        Map<String, Object> r = billing.receive(s.accountId(), Params.str(b, "billId"));
        audit.record(s, AuditService.BILL_SETTLED, "Cash payment of " + r.get("amount")
                + " received from " + r.get("patientName") + " (bill " + r.get("id") + ")");
        return r;
    }
}