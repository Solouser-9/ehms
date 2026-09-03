package ehms.security;

import com.sun.net.httpserver.HttpServer;
import ehms.util.Log;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Starts the port-80 ACME challenge responder and runs the (optional) acme4j provisioner. */
public final class AcmeSupport {

    public static void startAsync(String domain, String email) {
        try {
            HttpServer ch80 = HttpServer.create(new InetSocketAddress(80), 0);
            ch80.createContext("/", ex -> {
                try {
                    String path = ex.getRequestURI().getPath();
                    String token = path.substring(path.lastIndexOf('/') + 1);
                    String content = ChallengeHolder.get(token);
                    byte[] body = (content == null ? "Not found." : content).getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(content == null ? 404 : 200, body.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(body); }
                } finally {
                    ex.close();
                }
            });
            ch80.start();
            Log.info("ACME: HTTP-01 challenge responder listening on port 80");
        } catch (Exception e) {
            Log.warn("ACME: could not bind port 80 (" + e + "). HTTP-01 will fail unless a reverse " +
                     "proxy forwards /.well-known/acme-challenge/ to this app.");
        }

        Runnable job = () -> {
            try {
                Class.forName("ehms.acme.AcmeProvisioner")
                        .getMethod("provision", String.class, String.class, Path.class, char[].class)
                        .invoke(null, domain, email, Path.of(KeystoreTool.KEYSTORE_FILE), KeystoreTool.password());
                Log.info("ACME: certificate installed - restart the app to serve it over HTTPS");
            } catch (Throwable t) {
                Log.warn("ACME: provisioning failed: " + (t.getCause() != null ? t.getCause() : t));
            }
        };
        Thread first = new Thread(job, "ehms-acme");
        first.setDaemon(true);
        first.start();
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ehms-acme-renew");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(job, 30, 30, TimeUnit.DAYS);
    }

    private AcmeSupport() {}
}