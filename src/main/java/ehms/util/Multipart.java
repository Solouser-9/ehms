package ehms.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal multipart/form-data parser for file uploads through the JDK's
 * built-in HTTP server (no external libraries).
 */
public final class Multipart {

    public static final String FILENAME = "__upload_filename";
    public static final String CONTENT_TYPE = "__upload_ctype";
    public static final String BYTES = "__upload_bytes";
    public static final long MAX_BYTES = 6 * 1024 * 1024;   // hard stop: ~6 MB request

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] HEADER_SEP = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] DASH_DASH = "--".getBytes(StandardCharsets.ISO_8859_1);

    private Multipart() {}

    public static Map<String, Object> parse(String contentType, byte[] body) {
        byte[] delim = ("--" + boundaryOf(contentType)).getBytes(StandardCharsets.ISO_8859_1);
        Map<String, Object> fields = new LinkedHashMap<>();

        int pos = indexOf(body, delim, 0);
        if (pos < 0) throw new IllegalArgumentException("Malformed upload (boundary not found).");

        while (pos >= 0) {
            int start = pos + delim.length;
            if (startsWith(body, start, DASH_DASH)) break;
            if (startsWith(body, start, CRLF)) start += 2;

            int next = indexOf(body, delim, start);
            if (next < 0) break;
            int dataEnd = next - 2;
            if (dataEnd > start) readPart(Arrays.copyOfRange(body, start, dataEnd), fields);
            pos = next;
        }
        return fields;
    }

    private static String boundaryOf(String contentType) {
        if (contentType == null) throw new IllegalArgumentException("Missing Content-Type.");
        for (String piece : contentType.split(";")) {
            String p = piece.trim();
            if (p.startsWith("boundary=")) {
                String b = p.substring(9).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) b = b.substring(1, b.length() - 1);
                if (!b.isEmpty()) return b;
            }
        }
        throw new IllegalArgumentException("Missing multipart boundary.");
    }

    private static void readPart(byte[] part, Map<String, Object> fields) {
        int sep = indexOf(part, HEADER_SEP, 0);
        if (sep < 0) return;
        String headers = new String(part, 0, sep, StandardCharsets.UTF_8);
        byte[] data = Arrays.copyOfRange(part, sep + 4, part.length);

        String name = headerAttr(headers, "name");
        if (name == null) return;
        String filename = headerAttr(headers, "filename");
        if (filename != null && !filename.isBlank()) {
            fields.put(FILENAME, filename);
            fields.put(CONTENT_TYPE, headerValue(headers, "Content-Type"));
            fields.put(BYTES, data);
        } else {
            fields.put(name, new String(data, StandardCharsets.UTF_8));
        }
    }

    private static String headerValue(String headers, String key) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(key)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static String headerAttr(String headers, String attr) {
        String disposition = headerValue(headers, "Content-Disposition");
        if (disposition == null) return null;
        for (String piece : disposition.split(";")) {
            String p = piece.trim();
            if (p.startsWith(attr + "=")) {
                String v = p.substring(attr.length() + 1).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
                return v;
            }
        }
        return null;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        if (needle.length == 0) return from;
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (offset < 0 || offset + prefix.length > data.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[offset + i] != prefix[i]) return false;
        return true;
    }
}