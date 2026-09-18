package com.volhv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Json {
    private final String s;
    private int p;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        if (json.p != text.length()) throw new IllegalArgumentException();
        return value;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    private Object value() {
        char c = s.charAt(p);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("null", p)) { p += 4; return null; }
        if (s.startsWith("true", p)) { p += 4; return true; }
        if (s.startsWith("false", p)) { p += 5; return false; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        p++;
        if (s.charAt(p) == '}') { p++; return map; }
        while (true) {
            String key = string();
            expect(": ");
            map.put(key, value());
            if (s.charAt(p) == '}') { p++; return map; }
            expect(", ");
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        p++;
        if (s.charAt(p) == ']') { p++; return list; }
        while (true) {
            list.add(value());
            if (s.charAt(p) == ']') { p++; return list; }
            expect(", ");
        }
    }

    private String string() {
        expect("\"");
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(p++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(p++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16)); p += 4; break;
                default: sb.append(e);
            }
        }
    }

    private Long number() {
        int start = p;
        if (s.charAt(p) == '-') p++;
        while (p < s.length() && s.charAt(p) >= '0' && s.charAt(p) <= '9') p++;
        return Long.parseLong(s.substring(start, p));
    }

    private void expect(String token) {
        if (!s.startsWith(token, p)) throw new IllegalArgumentException();
        p += token.length();
    }

    private static void write(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            quote((String) value, sb);
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                quote((String) e.getKey(), sb);
                sb.append(": ");
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) value) {
                if (!first) sb.append(", ");
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else {
            sb.append(value);
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
