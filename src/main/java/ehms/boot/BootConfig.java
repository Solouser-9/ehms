package ehms.boot;

import ehms.db.Database;
import ehms.db.FileStore;
import ehms.db.JdbcStore;
import ehms.db.Store;
import ehms.security.Captcha;
import ehms.security.LoginGuard;
import ehms.security.RateLimiter;
import ehms.security.SessionManager;
import ehms.service.AdminService;
import ehms.service.AppointmentService;
import ehms.service.AuditService;
import ehms.service.AuthService;
import ehms.service.BedRequestService;
import ehms.service.BedService;
import ehms.service.BillingService;
import ehms.service.ChatService;
import ehms.service.DoctorService;
import ehms.service.EquipmentService;
import ehms.service.HospitalService;
import ehms.service.MockGateway;
import ehms.service.PatientService;
import ehms.service.PaymentGateway;
import ehms.service.PaymentService;
import ehms.service.PdfService;
import ehms.service.PharmacyService;
import ehms.service.ReportService;
import ehms.service.SlotService;
import ehms.util.Log;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class BootConfig {

    @Bean
    Store store(EhmsProperties p) {
        if (!p.dbUrl().isBlank()) {
            return new JdbcStore(p.dbUrl());
        }
        return new FileStore("/tmp/ehms.dat");
    }

    @Bean
    Database database(Store store, EhmsProperties p) {
        // Simple: fresh database, no loading, no configure(), no singleton games
        Database db = Database.createDetached();
        db.setBackupKeep(p.backups());
        db.setBackupMinIntervalMs(p.backupIntervalSeconds() * 1000L);
        db.setMaxAuditInMemory(p.auditCap());
        Log.info("Database initialised (fresh, in-memory state with " + store.describe() + ")");
        return db;
    }

    @Bean SessionManager sessionManager() { return new SessionManager(); }
    @Bean LoginGuard loginGuard() { return new LoginGuard(); }
    @Bean RateLimiter rateLimiter() { return new RateLimiter(120, 60_000);
    }
    @Bean Captcha captcha(EhmsProperties p) { return new Captcha(p.captchaDifficulty()); }

    @Bean AuthService authService(Database db, SessionManager s, LoginGuard g) { return new AuthService(db, s, g); }
    @Bean AuditService auditService(Database db) { return new AuditService(db); }
    @Bean BedService bedService(Database db) { return new BedService(db); }
    @Bean BedRequestService bedRequestService(Database db, BedService beds) { return new BedRequestService(db, beds); }
    @Bean BillingService billingService(Database db, EhmsProperties p) { return new BillingService(db, Boolean.TRUE.equals(p.prorate())); }
    @Bean EquipmentService equipmentService(Database db) { return new EquipmentService(db); }
    @Bean DoctorService doctorService(Database db) { return new DoctorService(db); }
    @Bean PatientService patientService(Database db) { return new PatientService(db); }
    @Bean HospitalService hospitalService(Database db, BedService beds) { return new HospitalService(db, beds); }
    @Bean AppointmentService appointmentService(Database db) { return new AppointmentService(db); }
    @Bean SlotService slotService(Database db) { return new SlotService(db); }
    @Bean ChatService chatService(Database db) { return new ChatService(db); }
    @Bean ReportService reportService(Database db) { return new ReportService(db); }
    @Bean PdfService pdfService(Database db) { return new PdfService(db); }
    @Bean PharmacyService pharmacyService(Database db) { return new PharmacyService(db); }
    @Bean PaymentService paymentService(Database db) { return new PaymentService(db); }
    @Bean AdminService adminService(Database db, EhmsProperties p) { return new AdminService(db, p.adminKey()); }

    @Bean
    PaymentGateway paymentGateway(EhmsProperties p) {
        if (p.paystackKey() != null) {
            try {
                PaymentGateway gw = (PaymentGateway) Class.forName("ehms.pay.PaystackGateway")
                        .getDeclaredConstructor(String.class, String.class)
                        .newInstance(p.paystackKey(), p.stripeCurrency());
                Log.info("Payments: Paystack checkout enabled");
                return gw;
            } catch (Throwable t) {
                Log.warn("Paystack unavailable - using mock");
            }
        }
        if (p.stripeKey() != null) {
            try {
                PaymentGateway gw = (PaymentGateway) Class.forName("ehms.pay.StripeGateway")
                        .getDeclaredConstructor(String.class, String.class)
                        .newInstance(p.stripeKey(), p.stripeCurrency());
                Log.info("Payments: Stripe checkout enabled");
                return gw;
            } catch (Throwable t) {
                Log.warn("Stripe unavailable - using mock");
            }
        }
        return new MockGateway();
    }

    @Bean
    WebMvcConfigurer apiCors() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}