package ehms.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class EhmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(EhmsApplication.class, args);
    }

    @Bean
    ApplicationRunner banner(EhmsProperties p, org.springframework.core.env.Environment env) {
        return args -> {
            String store = p.dbUrl().isBlank() ? "file ehms.dat" : p.dbUrl();
            System.out.println();
            System.out.println("=====================================================");
            System.out.println("   E-HEALTHCARE MANAGEMENT SYSTEM   (Spring Boot)");
            System.out.println("=====================================================");
            System.out.println(" Web UI    : http://localhost:" + env.getProperty("local.server.port", "8000") + "/");
            System.out.println(" Store     : " + store + "   Backups: " + (p.backups() > 0 ? p.backups() : "off"));
            System.out.println(" Admin key : " + p.adminKey() + "   Captcha: " + (p.captchaDifficulty() > 0 ? "on" : "off"));
            System.out.println(" Actuator  : /actuator/health  /actuator/prometheus");
            System.out.println(" Stop with : Ctrl+C (graceful)");
            System.out.println("=====================================================");
        };
    }
}