package ehms.model;

import ehms.security.PasswordHasher;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Hospital implements Account {

    private static final long serialVersionUID = 1L;

    private static final Map<String, Double> DEFAULT_BED_PRICES = Map.of(
            "GENERAL", 2000.0, "ICU", 8000.0, "VENTILATOR", 15000.0);
    private static final Map<String, Double> DEFAULT_EQUIPMENT_PRICES = Map.of(
            "OXYGEN CYLINDER", 500.0, "VENTILATOR", 3000.0, "OXYGEN CONCENTRATOR", 800.0,
            "PATIENT MONITOR", 1000.0, "NEBULIZER", 300.0, "DEFIBRILLATOR", 1500.0,
            "INFUSION PUMP", 700.0, "CPAP MACHINE", 1200.0);

    /** A registered ward: name, optional floor label and bed capacity (0 = unlimited). */
    public static final class Ward implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final String floor;
        private final int capacity;
        public Ward(String name, String floor, int capacity) {
            this.name = name;
            this.floor = floor == null ? "" : floor;
            this.capacity = capacity;
        }
        public String getName() { return name; }
        public String getFloor() { return floor; }
        public int getCapacity() { return capacity; }
    }

    private final String id;
    private String name;
    private String email;
    private String password;              // PBKDF2 hash (field name kept for old ehms.dat files)
    private String phone;
    private String address;
    private int bedCounter;
    private Boolean blocked = null;
    private LinkedHashMap<String, Double> bedPrices;
    private LinkedHashMap<String, Double> equipmentPrices;
    private LinkedHashMap<String, Ward> wards;

    public Hospital(String id, String name, String email, String password, String phone, String address) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.password = PasswordHasher.isHashed(password) ? password : PasswordHasher.hash(password);
        this.phone = phone;
        this.address = address;
    }

    @Override public String getId() { return id; }
    @Override public String getName() { return name; }
    @Override public String getEmail() { return email; }
    @Override public String getPasswordHash() { return password; }
    @Override public void setPasswordHash(String h) { this.password = h; }

    @Override public boolean isBlocked() { return Boolean.TRUE.equals(blocked); }
    @Override public void setBlocked(boolean b) { this.blocked = b; }
    @Override public Boolean getBlockedFlag() { return blocked; }
    @Override public void setBlockedFlag(Boolean b) { this.blocked = b; }

    public String getPhone() { return phone; }
    public String getAddress() { return address; }

    public int nextBedNo() { return ++bedCounter; }
    public int getBedCounter() { return bedCounter; }
    public void setBedCounter(int bedCounter) { this.bedCounter = bedCounter; }

    // ---------------- bed prices ----------------

    public double priceFor(String bedType) {
        Double v = bedPricesMap().get(bedType);
        return v == null ? DEFAULT_BED_PRICES.getOrDefault(bedType, 0.0) : v;
    }

    public void setBedPrices(Map<String, Double> prices) {
        LinkedHashMap<String, Double> clean = new LinkedHashMap<>(DEFAULT_BED_PRICES);
        if (prices != null) clean.putAll(prices);
        this.bedPrices = clean;
    }

    public Map<String, Double> getBedPrices() { return new LinkedHashMap<>(bedPricesMap()); }

    // ---------------- equipment prices ----------------

    public double priceForEquipment(String kind) {
        Double v = equipmentPricesMap().get(kind);
        return v == null ? DEFAULT_EQUIPMENT_PRICES.getOrDefault(kind, 0.0) : v;
    }

    public void setEquipmentPrices(Map<String, Double> prices) {
        LinkedHashMap<String, Double> clean = new LinkedHashMap<>(DEFAULT_EQUIPMENT_PRICES);
        if (prices != null) clean.putAll(prices);
        this.equipmentPrices = clean;
    }

    public Map<String, Double> getEquipmentPrices() { return new LinkedHashMap<>(equipmentPricesMap()); }

    // ---------------- ward registry ----------------

    public Ward getWard(String name) { return name == null ? null : wardMap().get(name); }

    public void upsertWard(String name, String floor, int capacity) {
        wardMap().put(name, new Ward(name, floor, capacity));
    }

    public List<Ward> getWards() {
        List<Ward> l = new ArrayList<>(wardMap().values());
        l.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return l;
    }

    // ---------------- lazy maps (old data files lack these fields) ----------------

    private LinkedHashMap<String, Double> bedPricesMap() {
        if (bedPrices == null) bedPrices = new LinkedHashMap<>(DEFAULT_BED_PRICES);
        return bedPrices;
    }

    private LinkedHashMap<String, Double> equipmentPricesMap() {
        if (equipmentPrices == null) equipmentPrices = new LinkedHashMap<>(DEFAULT_EQUIPMENT_PRICES);
        return equipmentPrices;
    }

    private LinkedHashMap<String, Ward> wardMap() {
        if (wards == null) wards = new LinkedHashMap<>();
        return wards;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("email", email);
        m.put("phone", phone);
        m.put("address", address);
        m.put("bedPrices", getBedPrices());
        m.put("equipmentPrices", getEquipmentPrices());
        Map<String, Object> w = new LinkedHashMap<>();
        for (Ward ward : getWards())
            w.put(ward.getName(), Map.of("floor", ward.getFloor(), "capacity", ward.getCapacity()));
        m.put("wards", w);
        return m;
    }
}