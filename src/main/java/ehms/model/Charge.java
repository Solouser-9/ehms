package ehms.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** A pending hospital charge (e.g. equipment usage) waiting to be pulled into a bill at discharge. */
public class Charge implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;              // CH001
    private final String hospitalId;
    private final String patientId;
    private final String bedId;           // bed the charge originated from
    private final String kind;            // EQUIPMENT / PHARMACY / OTHER
    private final String label;
    private final double qty;
    private final double unitPrice;
    private final double amount;
    private final long createdAt;

    public Charge(String id, String hospitalId, String patientId, String bedId, String kind,
                  String label, double qty, double unitPrice, double amount, long createdAt) {
        this.id = id;
        this.hospitalId = hospitalId;
        this.patientId = patientId;
        this.bedId = bedId;
        this.kind = kind;
        this.label = label;
        this.qty = qty;
        this.unitPrice = unitPrice;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getHospitalId() { return hospitalId; }
    public String getPatientId() { return patientId; }
    public String getBedId() { return bedId; }
    public String getKind() { return kind; }
    public String getLabel() { return label; }
    public double getQty() { return qty; }
    public double getUnitPrice() { return unitPrice; }
    public double getAmount() { return amount; }
    public long getCreatedAt() { return createdAt; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("hospitalId", hospitalId);
        m.put("patientId", patientId);
        m.put("bedId", bedId);
        m.put("kind", kind);
        m.put("label", label);
        m.put("qty", qty);
        m.put("unitPrice", unitPrice);
        m.put("amount", amount);
        m.put("createdAt", createdAt);
        return m;
    }
}