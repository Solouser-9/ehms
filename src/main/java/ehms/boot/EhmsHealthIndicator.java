package ehms.boot;

import ehms.db.Database;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Actuator health with EHMS domain details (works for the file store AND SQL stores). */
@Component
public class EhmsHealthIndicator implements HealthIndicator {

    private final Database db;

    public EhmsHealthIndicator(Database db) { this.db = db; }

    @Override
    public Health health() {
        try {
            var s = db.stats();
            return Health.up()
                    .withDetail("store", db.storeDescription())
                    .withDetail("doctors", s.get("doctors"))
                    .withDetail("patients", s.get("patients"))
                    .withDetail("hospitals", s.get("hospitals"))
                    .withDetail("freeBeds", s.get("freeBeds"))
                    .withDetail("totalBeds", s.get("totalBeds"))
                    .withDetail("pendingConsultations", s.get("pendingConsultations"))
                    .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}