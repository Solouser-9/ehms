package ehms.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny JSON parser + writer so the project needs zero external libraries.
 * Parses into Map<String,Object>, List<Object>, String, Long, Double, Boolean or null.
 */
public final class Json {

    private Json() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    /** Convenience builder: Json.obj("id", "D001", "name", "Mehta") */
    public static Map<String, Object> obj(Object... keyValuePairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            m.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return m;
    }

    private static void writeValue(Object v, StringBuilder sb) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { writeString(s, sb); return; }
        if (v instanceof Boolean || v instanceof Number) { sb.append(v); return; }
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                writeValue(e.getValue(), sb);
            }
            sb.append('}');
            return;
        }
        if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                writeValue(o, sb);
            }
            sb.append(']');
            return;
        }
        if (v instanceof Object[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                writeValue(arr[i], sb);
            }
            sb.append(']');
            return;
        }
        writeString(String.valueOf(v), sb);
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    public static Object parse(String json) {
        Parser p = new Parser(json == null ? "" : json);
        p.skipWhitespace();
        Object value = p.value();
        p.skipWhitespace();
        if (!p.eof()) throw p.error("Unexpected trailing characters");
        return value;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        boolean eof() { return i >= s.length(); }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Invalid JSON at position " + i + ": " + message);
        }

        void skipWhitespace() {
            while (!eof() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private char peek() {
            if (eof()) throw error("Unexpected end of input");
            return s.charAt(i);
        }

        private char read() {
            char c = peek();
            i++;
            return c;
        }

        Object value() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true");  return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null");  return null;
                default:  return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            read();
            skipWhitespace();
            if (peek() == '}') { read(); return m; }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                if (read() != ':') throw error("Expected ':' after object key");
                m.put(key, value());
                skipWhitespace();
                char c = read();
                if (c == '}') return m;
                if (c != ',') throw error("Expected ',' or '}' in object");
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            read();
            skipWhitespace();
            if (peek() == ']') { read(); return list; }
            while (true) {
                list.add(value());
                skipWhitespace();
                char c = read();
                if (c == ']') return list;
                if (c != ',') throw error("Expected ',' or ']' in array");
            }
        }

        private String string() {
            read();
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = read();
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = read();
                    switch (e) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw error("Incomplete unicode escape");
                            String hex = s.substring(i, i + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException nfe) {
                                throw error("Invalid unicode escape");
                            }
                            i += 4;
                            break;
                        default:
                            throw error("Invalid escape character");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object number() {
            int start = i;
            while (!eof()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
                else break;
            }
            if (i == start) throw error("Unexpected character '" + peek() + "'");
            String text = s.substring(start, i);
            try {
                if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
                    return Long.parseLong(text);
                }
                return Double.parseDouble(text);
            } catch (NumberFormatException nfe) {
                throw error("Invalid number '" + text + "'");
            }
        }

        private void expect(String word) {
            if (!s.startsWith(word, i)) throw error("Expected '" + word + "'");
            i += word.length();
        }
    }
}