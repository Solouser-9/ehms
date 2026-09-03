package ehms.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** One dispensing event: medicine handed to a patient from a hospital's stock. */
public class Dispense implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;              // DP001
    private final String hospitalId;
    private final String medicineId;
    private final String medicineName;
    private final String patientId;
    private final int qty;
    private final String unit;
    private final long dispensedAt;
    private final String note;
    private final double unitPrice;       // price snapshot at dispense time (0 for legacy rows)

    public Dispense(String id, String hospitalId, String medicineId, String medicineName,
                    String patientId, int qty, String unit, long dispensedAt, String note) {
        this(id, hospitalId, medicineId, medicineName, patientId, qty, unit, dispensedAt, note, 0);
    }

    public Dispense(String id, String hospitalId, String medicineId, String medicineName,
                    String patientId, int qty, String unit, long dispensedAt, String note,
                    double unitPrice) {
        this.id = id;
        this.hospitalId = hospitalId;
        this.medicineId = medicineId;
        this.medicineName = medicineName;
        this.patientId = patientId;
        this.qty = qty;
        this.unit = unit;
        this.dispensedAt = dispensedAt;
        this.note = note;
        this.unitPrice = unitPrice;
    }

    public String getId() { return id; }
    public String getHospitalId() { return hospitalId; }
    public String getMedicineId() { return medicineId; }
    public String getMedicineName() { return medicineName; }
    public String getPatientId() { return patientId; }
    public int getQty() { return qty; }
    public String getUnit() { return unit; }
    public long getDispensedAt() { return dispensedAt; }
    public String getNote() { return note; }
    public double getUnitPrice() { return unitPrice; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("hospitalId", hospitalId);
        m.put("medicineId", medicineId);
        m.put("medicineName", medicineName);
        m.put("patientId", patientId);
        m.put("qty", qty);
        m.put("unit", unit);
        m.put("dispensedAt", dispensedAt);
        m.put("note", note);
        m.put("unitPrice", unitPrice);
        return m;
    }
}