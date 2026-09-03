package ehms.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** One medicine in a hospital's pharmacy inventory. */
public class Medicine implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;              // MD001
    private final String hospitalId;
    private String name;
    private String unit;                  // tablets, bottles, ...
    private int stock;
    private int reorderLevel;
    private double price;

    public Medicine(String id, String hospitalId, String name, String unit,
                    int stock, int reorderLevel, double price) {
        this.id = id;
        this.hospitalId = hospitalId;
        this.name = name;
        this.unit = unit;
        this.stock = stock;
        this.reorderLevel = reorderLevel;
        this.price = price;
    }

    public String getId() { return id; }
    public String getHospitalId() { return hospitalId; }
    public String getName() { return name; }
    public String getUnit() { return unit; }
    public int getStock() { return stock; }
    public int getReorderLevel() { return reorderLevel; }
    public double getPrice() { return price; }

    public void setStock(int stock) { this.stock = stock; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("hospitalId", hospitalId);
        m.put("name", name);
        m.put("unit", unit);
        m.put("stock", stock);
        m.put("reorderLevel", reorderLevel);
        m.put("price", price);
        return m;
    }
}