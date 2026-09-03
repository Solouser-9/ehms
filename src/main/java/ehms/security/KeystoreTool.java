package ehms.security;

import ehms.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Manages keystore.p12 (self-signed TLS for --https). The keystore is generated
 * on first run with the JDK's own keytool; its password is a random
 * per-installation value stored in keystore.pass (owner-readable only where
 * POSIX permissions exist). A keystore from an older version (no keystore.pass)
 * keeps its legacy password so existing setups are never locked out.
 */
public final class KeystoreTool {

    public static final String KEYSTORE_FILE = "keystore.p12";
    public static final String PASSWORD_FILE = "keystore.pass";
    public static final String LEGACY_PASSWORD = "ehms-change-me";

    private static char[] cachedPassword;

    private KeystoreTool() {}

    public static boolean exists() { return new File(KEYSTORE_FILE).isFile(); }

    public static synchronized char[] password() {
        if (cachedPassword != null) return cachedPassword.clone();

        Path passFile = Path.of(PASSWORD_FILE);
        try {
            if (Files.exists(passFile)) {
                String read = Files.readString(passFile, StandardCharsets.UTF_8).trim();
                if (!read.isEmpty()) {
                    cachedPassword = read.toCharArray();
                    return cachedPassword.clone();
                }
            }
        } catch (IOException e) {
            Log.warn("Could not read " + PASSWORD_FILE + " (" + e + "); continuing.");
        }

        if (exists()) {
            cachedPassword = LEGACY_PASSWORD.toCharArray();
            return cachedPassword.clone();
        }

        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        try {
            Files.writeString(passFile, generated, StandardCharsets.UTF_8);
            restrictToFileOwner(passFile);
            Log.info("Generated a random keystore password and stored it in " + PASSWORD_FILE
                    + " (readable by the file owner only).");
        } catch (IOException e) {
            Log.warn("Could not write " + PASSWORD_FILE + " (" + e + "); using an in-memory password for this run.");
        }
        cachedPassword = generated.toCharArray();
        return cachedPassword.clone();
    }

    private static void restrictToFileOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception notPosix) {
            // Windows ACLs are a different API; acceptable for now.
        }
    }

    public static boolean generate() {
        if (exists()) return true;

        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
        Path keytool = Paths.get(System.getProperty("java.home"), "bin", exe);
        if (!Files.isExecutable(keytool)) return false;

        char[] pass = password();
        ProcessBuilder pb = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias", "ehms",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-sigalg", "SHA256withRSA",
                "-storetype", "PKCS12",
                "-keystore", KEYSTORE_FILE,
                "-storepass", new String(pass),
                "-validity", "3650",
                "-dname", "CN=localhost, OU=E-HealthCare, O=EHMS, C=IN");
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0 && exists();
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}