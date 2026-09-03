package ehms.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Application logging on the JDK's System.Logger API.
 *
 * Politeness rule: the single-line console handler is attached ONLY when
 * nobody else has configured the "ehms" JUL logger (or the root logger) yet.
 * Under Spring Boot (jul-to-slf4j/Logback) the host configuration wins.
 * -Dehms.log.level always wins; otherwise INFO is the standalone default.
 */
public final class Log {

    private static final System.Logger LOGGER = System.getLogger("ehms");

    static {
        Logger jul = Logger.getLogger("ehms");
        boolean hostConfigured = jul.getHandlers().length > 0
                || Logger.getLogger("").getHandlers().length > 0;
        String requested = System.getProperty("ehms.log.level");
        if (!hostConfigured) {
            ConsoleHandler handler = new ConsoleHandler() {
                { setOutputStream(System.out); }
            };
            handler.setLevel(Level.ALL);
            handler.setFormatter(new SimpleFormatter() {
                @Override
                public synchronized String format(LogRecord r) {
                    String ts = new SimpleDateFormat("HH:mm:ss").format(new Date(r.getMillis()));
                    String line = String.format("%s %-7s [ehms] %s%n",
                            ts, r.getLevel().getName(), r.getMessage());
                    if (r.getThrown() == null) return line;
                    StringWriter sw = new StringWriter();
                    r.getThrown().printStackTrace(new PrintWriter(sw));
                    return line + sw;
                }
            });
            jul.addHandler(handler);
            jul.setUseParentHandlers(false);
        }
        if (requested != null) jul.setLevel(parse(requested));
        else if (!hostConfigured && jul.getLevel() == null) jul.setLevel(Level.INFO);
    }

    private Log() {}

    public static void debug(String msg) { LOGGER.log(System.Logger.Level.DEBUG, msg); }
    public static void info(String msg)  { LOGGER.log(System.Logger.Level.INFO, msg); }
    public static void warn(String msg)  { LOGGER.log(System.Logger.Level.WARNING, msg); }
    public static void error(String msg) { LOGGER.log(System.Logger.Level.ERROR, msg); }
    public static void error(String msg, Throwable t) {
        LOGGER.log(System.Logger.Level.ERROR, msg, t);
    }

    private static Level parse(String name) {
        return switch (name.trim().toUpperCase()) {
            case "DEBUG", "FINE", "ALL" -> Level.FINE;
            case "WARNING", "WARN"      -> Level.WARNING;
            case "ERROR", "SEVERE"      -> Level.SEVERE;
            case "OFF"                  -> Level.OFF;
            default                     -> Level.INFO;
        };
    }
}