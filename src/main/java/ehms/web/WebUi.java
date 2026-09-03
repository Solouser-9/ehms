package ehms.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the single-page web UI from the classpath resource webui.html.
 * The HTML is far too large for a Java string constant (the JVM caps
 * constants at 64KB), so it ships as a resource file instead.
 */
public final class WebUi {

    private WebUi() {}

    private static volatile String cached;

    /** Returns the full UI HTML; loaded from webui.html on first call, then cached. */
    public static String index() {
        if (cached == null) {
            synchronized (WebUi.class) {
                if (cached == null) {
                    cached = load();
                }
            }
        }
        return cached;
    }

    private static String load() {
        try (InputStream in = WebUi.class.getClassLoader().getResourceAsStream("webui.html")) {
            if (in == null) {
                throw new IllegalStateException(
                        "webui.html not found on the classpath - check src/main/resources/webui.html exists");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load webui.html: " + e.getMessage(), e);
        }
    }
}