package ehms.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** End-of-day bed occupancy snapshot for one hospital (id = "<yyyy-MM-dd>|<hospitalId>"). */
public class DailyOccupancy implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String day;             // yyyy-MM-dd
    private final String hospitalId;
    private final int occupied;
    private final int total;
    private final long updatedAt;

    public DailyOccupancy(String id, String day, String hospitalId, int occupied, int total, long updatedAt) {
        this.id = id;
        this.day = day;
        this.hospitalId = hospitalId;
        this.occupied = occupied;
        this.total = total;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getDay() { return day; }
    public String getHospitalId() { return hospitalId; }
    public int getOccupied() { return occupied; }
    public int getTotal() { return total; }
    public long getUpdatedAt() { return updatedAt; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("day", day);
        m.put("hospitalId", hospitalId);
        m.put("occupied", occupied);
        m.put("total", total);
        m.put("updatedAt", updatedAt);
        return m;
    }
}