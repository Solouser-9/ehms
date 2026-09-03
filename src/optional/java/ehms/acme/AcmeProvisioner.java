package ehms.acme;

import ehms.security.ChallengeHolder;
import ehms.util.Log;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/** Issues a Let's Encrypt certificate for one domain via HTTP-01 and installs it into keystore.p12. */
public final class AcmeProvisioner {

    public static void provision(String domain, String email, Path keystoreFile, char[] keystorePass) throws AcmeException {
        try {
            KeyPair accountKey = keyPair("acme-account.key");
            Session session = new Session("acme://letsencrypt.org");
            Login login = new AccountBuilder().addEmail(email).agreeToTermsOfService()
                    .useKeyPair(accountKey).createLogin(session);
            Order order = login.getAccount().newOrder().domain(domain).create();

            for (Authorization auth : order.getAuthorizations()) {
                if (auth.getStatus() == Status.VALID) continue;
                Http01Challenge ch = auth.findChallenge(Http01Challenge.class).orElseThrow();
                ChallengeHolder.put(ch.getToken(), ch.getAuthorization());
                ch.trigger();
                waitFor("challenge", () -> {
                    try { ch.update(); }
                    catch (AcmeException e) { throw new RuntimeException(e); }
                    return ch.getStatus() != Status.PENDING && ch.getStatus() != Status.PROCESSING;
                }, 90);
                if (ch.getStatus() != Status.VALID)
                    throw new AcmeException("ACME challenge ended as " + ch.getStatus());
            }

            KeyPair domainKey = keyPair("acme-domain.key");
            order.execute(domainKey);
            waitFor("order", () -> {
                try { order.update(); }
                catch (AcmeException e) { throw new RuntimeException(e); }
                return order.getStatus() == Status.VALID || order.getStatus() == Status.INVALID;
            }, 180);
            if (order.getStatus() != Status.VALID)
                throw new AcmeException("ACME order ended as " + order.getStatus());

            Certificate cert = order.getCertificate();
            List<X509Certificate> chain = cert.getCertificateChain();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("ehms", domainKey.getPrivate(), keystorePass, chain.toArray(new X509Certificate[0]));
            try (OutputStream out = Files.newOutputStream(keystoreFile)) { ks.store(out, keystorePass); }
            Log.info("ACME: certificate for " + domain + " written to " + keystoreFile);
        } catch (AcmeException e) {
            throw e;
        } catch (Exception e) {
            throw new AcmeException("ACME provisioning failed: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface WaitCondition {
        boolean isDone() throws AcmeException;
    }

    private static void waitFor(String what, WaitCondition done, int timeoutSeconds)
            throws AcmeException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (done.isDone()) return;
            Thread.sleep(3000);
        }
        throw new AcmeException("Timed out waiting for ACME " + what);
    }

    private static KeyPair keyPair(String file) throws Exception {
        Path p = Path.of(file);
        if (Files.exists(p)) {
            String[] parts = Files.readString(p).split("\\|");
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return new KeyPair(
                    kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(parts[0]))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(parts[1]))));
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        Files.writeString(p, Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()) + "|"
                + Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        return kp;
    }

    private AcmeProvisioner() {}
}