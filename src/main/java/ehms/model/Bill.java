package ehms.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The bill for a finished hospital stay: bed + pharmacy during the stay + equipment usage. */
public class Bill implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String DUE = "DUE";
    public static final String PAID = "PAID";

    /** One itemised charge line on the bill. */
    public static final class Line implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String kind;        // BED / PHARMACY / EQUIPMENT
        private final String label;
        private final double qty;
        private final double unitPrice;
        private final double amount;
        public Line(String kind, String label, double qty, double unitPrice, double amount) {
            this.kind = kind;
            this.label = label;
            this.qty = qty;
            this.unitPrice = unitPrice;
            this.amount = amount;
        }
        public String getKind() { return kind; }
        public String getLabel() { return label; }
        public double getQty() { return qty; }
        public double getUnitPrice() { return unitPrice; }
        public double getAmount() { return amount; }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("label", label);
            m.put("qty", qty);
            m.put("unitPrice", unitPrice);
            m.put("amount", amount);
            return m;
        }
    }

    private final String id;              // BL001
    private final String hospitalId;
    private final String patientId;
    private final String patientName;
    private final int bedNo;
    private final String bedType;
    private final String ward;
    private final long admittedAt;
    private final long dischargedAt;
    private final int days;
    private final double ratePerDay;
    private double amount;                // grand total = bed charge + all lines
    private final long createdAt;
    private String status = DUE;
    private String method = "";           // UPI / CARD / NETBANKING / WALLET / CASH / STRIPE / PAYSTACK
    private long paidAt;
    private List<Line> lines;             // lazy (old data files have none)

    public Bill(String id, String hospitalId, String patientId, String patientName, int bedNo,
                String bedType, String ward, long admittedAt, long dischargedAt, int days,
                double ratePerDay, double amount, long createdAt) {
        this.id = id;
        this.hospitalId = hospitalId;
        this.patientId = patientId;
        this.patientName = patientName;
        this.bedNo = bedNo;
        this.bedType = bedType;
        this.ward = ward;
        this.admittedAt = admittedAt;
        this.dischargedAt = dischargedAt;
        this.days = days;
        this.ratePerDay = ratePerDay;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    /** Full-state constructor used when restoring from a database. */
    public Bill(String id, String hospitalId, String patientId, String patientName, int bedNo,
                String bedType, String ward, long admittedAt, long dischargedAt, int days,
                double ratePerDay, double amount, long createdAt,
                String status, String method, long paidAt, List<Line> lines) {
        this(id, hospitalId, patientId, patientName, bedNo, bedType, ward, admittedAt,
                dischargedAt, days, ratePerDay, amount, createdAt);
        this.status = status;
        this.method = method == null ? "" : method;
        this.paidAt = paidAt;
        if (lines != null) this.lines = new ArrayList<>(lines);
    }

    public String getId() { return id; }
    public String getHospitalId() { return hospitalId; }
    public String getPatientId() { return patientId; }
    public String getPatientName() { return patientName; }
    public int getBedNo() { return bedNo; }
    public String getBedType() { return bedType; }
    public String getWard() { return ward; }
    public long getAdmittedAt() { return admittedAt; }
    public long getDischargedAt() { return dischargedAt; }
    public int getDays() { return days; }
    public double getRatePerDay() { return ratePerDay; }
    public double getAmount() { return amount; }
    public long getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    public String getMethod() { return method; }
    public long getPaidAt() { return paidAt; }

    public boolean isPaid() { return PAID.equals(status); }

    public List<Line> getLines() {
        if (lines == null) lines = new ArrayList<>();
        return lines;
    }

    /** Appends a charge line and adds its amount to the total. */
    public void addLine(String kind, String label, double qty, double unitPrice, double amount) {
        getLines().add(new Line(kind, label, qty, unitPrice, amount));
        this.amount = round2(this.amount + amount);
    }

    public void pay(String method) {
        this.status = PAID;
        this.method = method;
        this.paidAt = System.currentTimeMillis();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("hospitalId", hospitalId);
        m.put("patientId", patientId);
        m.put("patientName", patientName);
        m.put("bedNo", bedNo);
        m.put("bedType", bedType);
        m.put("ward", ward);
        m.put("admittedAt", admittedAt);
        m.put("dischargedAt", dischargedAt);
        m.put("days", days);
        m.put("ratePerDay", ratePerDay);
        m.put("bedAmount", bedAmount());
        m.put("amount", round2(amount));
        m.put("status", status);
        m.put("method", method);
        m.put("createdAt", createdAt);
        m.put("paidAt", paidAt);
        List<Map<String, Object>> lineMaps = new ArrayList<>();
        for (Line l : getLines()) lineMaps.add(l.toMap());
        m.put("lines", lineMaps);
        m.put("itemCount", lineMaps.size());
        return m;
    }

    private double bedAmount() {
        for (Line l : getLines()) if ("BED".equals(l.getKind())) return l.getAmount();
        return round2(days * ratePerDay);
    }

    public static double round2(double v) { return Math.round(v * 100) / 100.0; }
}