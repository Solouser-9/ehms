package ehms;

import com.sun.net.httpserver.HttpServer;
import ehms.db.Backups;
import ehms.db.Database;
import ehms.db.FileStore;
import ehms.db.JdbcStore;
import ehms.db.Store;
import ehms.model.Doctor;
import ehms.security.AcmeSupport;
import ehms.security.KeystoreTool;
import ehms.util.Log;
import ehms.web.WebServer;
import ehms.web.WebServerConfig;

import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    public static void main(String[] args) {
        // Arguments: [port] [--https] [--db <url>] [--backups N] [--admin-key <key>]
        //             [--backup-interval SECONDS] [--trust-proxy] [--strict-verification]
        //             [--prorate] [--safe-dump] [--audit-cap N] [--captcha N]
        //             [--stripe-key <key>] [--paystack-key <key>] [--stripe-currency <cur>]
        //             [--public-url <url>] [--acme-domain <domain>] [--acme-email <email>]
        int port = 8000;
        boolean https = false;
        String dbUrl = null;
        int backups = 10;
        String adminKey = "ehms-admin-key";
        long backupIntervalSeconds = 60;
        boolean trustProxy = false;
        boolean strictVerification = false;
        boolean prorate = false;
        boolean safeDump = false;
        int auditCap = Database.MAX_AUDIT_IN_MEMORY;
        int captchaDifficulty = 3;
        String stripeKey = null;
        String paystackKey = null;        // takes precedence over stripeKey when both are set
        String stripeCurrency = "ngn";    // default charge currency: Naira (kobo)
        String publicUrl = null;
        String acmeDomain = null;
        String acmeEmail = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--https".equalsIgnoreCase(arg)) {
                https = true;
            } else if ("--db".equalsIgnoreCase(arg) && i + 1 < args.length) {
                dbUrl = args[++i];
            } else if ("--backups".equalsIgnoreCase(arg) && i + 1 < args.length) {
                try { backups = Integer.parseInt(args[++i]); }
                catch (NumberFormatException e) { Log.warn("Invalid --backups value, using " + backups); }
            } else if ("--admin-key".equalsIgnoreCase(arg) && i + 1 < args.length) {
                adminKey = args[++i];
            } else if ("--backup-interval".equalsIgnoreCase(arg) && i + 1 < args.length) {
                try { backupIntervalSeconds = Long.parseLong(args[++i]); }
                catch (NumberFormatException e) { Log.warn("Invalid --backup-interval value, using " + backupIntervalSeconds); }
            } else if ("--audit-cap".equalsIgnoreCase(arg) && i + 1 < args.length) {
                try { auditCap = Integer.parseInt(args[++i]); }
                catch (NumberFormatException e) { Log.warn("Invalid --audit-cap value, using " + auditCap); }
            } else if ("--captcha".equalsIgnoreCase(arg) && i + 1 < args.length) {
                try { captchaDifficulty = Integer.parseInt(args[++i]); }
                catch (NumberFormatException e) { Log.warn("Invalid --captcha value, using " + captchaDifficulty); }
            } else if ("--trust-proxy".equalsIgnoreCase(arg)) {
                trustProxy = true;
            } else if ("--strict-verification".equalsIgnoreCase(arg)) {
                strictVerification = true;
            } else if ("--prorate".equalsIgnoreCase(arg)) {
                prorate = true;
            } else if ("--safe-dump".equalsIgnoreCase(arg)) {
                safeDump = true;
            } else if ("--stripe-key".equalsIgnoreCase(arg) && i + 1 < args.length) {
                stripeKey = args[++i];
            } else if ("--paystack-key".equalsIgnoreCase(arg) && i + 1 < args.length) {
                paystackKey = args[++i];
            } else if ("--stripe-currency".equalsIgnoreCase(arg) && i + 1 < args.length) {
                stripeCurrency = args[++i];
            } else if ("--public-url".equalsIgnoreCase(arg) && i + 1 < args.length) {
                publicUrl = args[++i];
            } else if ("--acme-domain".equalsIgnoreCase(arg) && i + 1 < args.length) {
                acmeDomain = args[++i];
            } else if ("--acme-email".equalsIgnoreCase(arg) && i + 1 < args.length) {
                acmeEmail = args[++i];
            } else {
                try { port = Integer.parseInt(arg); } catch (NumberFormatException ignored) { }
            }
        }

        Store store = dbUrl == null ? new FileStore() : new JdbcStore(dbUrl);
        if (safeDump && store instanceof JdbcStore js) js.setSafeDump(true);
        Database db;
        try {
            db = Database.configure(store);
        } catch (Exception e) {
            Log.error("Could not open the database (" + store.describe() + ")", e);
            return;
        }
        db.setBackupKeep(backups);
        db.setBackupMinIntervalMs(backupIntervalSeconds * 1000L);
        db.setMaxAuditInMemory(auditCap);

        if (strictVerification) {
            int changed = 0;
            for (Doctor d : db.doctors.values()) {
                if (d.getVerifiedFlag() == null) {
                    d.setVerified(false);
                    changed++;
                }
            }
            if (changed > 0) {
                db.save();
                Log.info("--strict-verification: " + changed
                        + " legacy doctor account(s) now require admin licence verification.");
            }
        }

        if (!(store instanceof FileStore)
                && db.doctors.isEmpty() && db.patients.isEmpty() && db.hospitals.isEmpty()) {
            Path legacy = Path.of(FileStore.DATA_FILE);
            if (Files.exists(legacy)) {
                Log.info("Empty database - importing existing data from " + FileStore.DATA_FILE + " ...");
                new FileStore().load(db);
                if (!db.doctors.isEmpty() || !db.patients.isEmpty() || !db.hospitals.isEmpty()) {
                    db.save();
                    Path renamed = legacy.resolveSibling(FileStore.DATA_FILE + ".imported-"
                            + Backups.label(System.currentTimeMillis()));
                    try {
                        Files.move(legacy, renamed);
                        Log.info("Migration complete. " + FileStore.DATA_FILE + " was renamed to "
                                + renamed.getFileName() + " (kept as a safety copy).");
                    } catch (Exception e) {
                        Log.warn("Migration complete, but " + FileStore.DATA_FILE
                                + " could not be renamed: " + e);
                    }
                }
            }
        }

        if (db.snapshotOccupancy()) db.save();

        if (acmeDomain != null && acmeEmail != null) {
            AcmeSupport.startAsync(acmeDomain, acmeEmail);
        }

        if (https && !KeystoreTool.exists()) {
            Log.info("Generating a self-signed TLS certificate (keystore.p12) with the JDK's keytool...");
            if (!KeystoreTool.generate()) {
                Log.warn("Automatic keytool generation failed. Create the keystore manually:");
                Log.warn("  keytool -genkeypair -alias ehms -keyalg RSA -keysize 2048 -storetype PKCS12 \\");
                Log.warn("          -keystore keystore.p12 -storepass <password> -validity 3650 \\");
                Log.warn("          -dname \"CN=localhost, OU=E-HealthCare, O=EHMS, C=IN\"");
                Log.warn("Falling back to plain HTTP for this run.");
                https = false;
            }
        }

        HttpServer server;
        try {
            server = WebServer.start(db, new WebServerConfig(port, https, adminKey, trustProxy,
                    prorate, captchaDifficulty, stripeKey, paystackKey, stripeCurrency, publicUrl));
        } catch (Exception e) {
            Log.error("Could not start server on port " + port, e);
            Log.warn("Hint: try another port, e.g.  java -cp out ehms.Main 8080");
            return;
        }

        banner(port, https, store, backups, backupIntervalSeconds, adminKey, trustProxy,
                strictVerification, prorate, safeDump, auditCap, captchaDifficulty,
                stripeKey, paystackKey);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("Shutting down: saving database and writing a final backup...");
            db.save();
            db.backupNow();
            store.close();
            server.stop(0);
        }));

        try { Thread.currentThread().join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void banner(int port, boolean https, Store store, int backups,
                               long backupIntervalSeconds, String adminKey,
                               boolean trustProxy, boolean strictVerification,
                               boolean prorate, boolean safeDump, int auditCap,
                               int captchaDifficulty, String stripeKey, String paystackKey) {
        System.out.println();
        System.out.println("=====================================================");
        System.out.println("   E-HEALTHCARE MANAGEMENT SYSTEM   (pure Java)");
        System.out.println("=====================================================");
        System.out.println(" Web UI    : http" + (https ? "s" : "") + "://localhost:" + port + "/");
        System.out.println(" Database  : " + store.describe());
        System.out.println(" Backups   : " + (backups > 0
                ? "backups/ (keep last " + backups + ", min " + backupIntervalSeconds + "s apart, uploads included)"
                : "disabled"));
        System.out.println(" Admin key : " + adminKey + "  (change with --admin-key)");
        if (trustProxy) System.out.println(" Proxy     : trusting X-Forwarded-For (ONLY behind a reverse proxy!)");
        if (strictVerification) System.out.println(" Strict    : legacy doctors require licence verification");
        if (prorate) System.out.println(" Billing   : hourly proration (min a quarter day)");
        if (safeDump) System.out.println(" Dumps     : SAFE mode (no DROP, INSERT IGNORE)");
        System.out.println(" Audit cap : " + (auditCap > 0 ? auditCap + " entries in memory" : "unlimited in memory"));
        System.out.println(" Captcha   : " + (captchaDifficulty > 0 ? "on (difficulty " + captchaDifficulty + ")" : "off"));
        if (paystackKey != null) System.out.println(" Payments  : Paystack checkout enabled");
        else if (stripeKey != null) System.out.println(" Payments  : Stripe checkout enabled");
        System.out.println(" Build     : mvn test | mvn package | mvn exec:java");
        System.out.println(" Logging   : -Dehms.log.level=DEBUG|INFO|WARNING|OFF");
        System.out.println(" Stop with : Ctrl+C");
        System.out.println("=====================================================");
    }
}