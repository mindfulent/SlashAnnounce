package dev.blockacademy.slashannounce.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The single point where an intercept payload becomes a console line.
 *
 * <p>Format (DESIGN §4): {@code [SlashAnnounce/v1] <type> <compact-json>} on one
 * physical line, ANSI/control-char free, JSON compact, bounded in size. All
 * formatting/escaping/capping lives here — one place — so intercepts only build
 * a flat {@code Map} and call {@link #emit}.
 *
 * <p>Cheap (string-format + one {@code LOG.info}), main-thread-safe, and it
 * <b>never throws</b>: an announce bug must not disrupt the server tick.
 */
public final class Emitter {
    private static final Logger LOG = LoggerFactory.getLogger("SlashAnnounce");
    private static final String TAG = "[SlashAnnounce/v1]";
    private static final Pattern TYPE_RE = Pattern.compile("[a-z][a-z0-9_]*");

    /** Payload JSON cap; oversize collapses to a truncated marker (DESIGN §4). */
    private static final int MAX_JSON = 1500;

    private Emitter() {}

    public static void emit(String type, Map<String, Object> payload) {
        try {
            if (type == null || !TYPE_RE.matcher(type).matches()) return;
            String json = encode(payload);
            if (json.length() > MAX_JSON) {
                json = "{\"truncated\":true}";
            }
            // SLF4J parameterized form keeps it a single log event / single line.
            LOG.info("{} {} {}", TAG, type, json);
        } catch (Throwable t) {
            // Swallow — never let an announce disrupt the server.
            LOG.warn("[SlashAnnounce] emit failed for type '{}'", type, t);
        }
    }

    // --- minimal, dependency-free JSON writer for flat payloads ---
    // Supports String, Boolean, Number, null, and List<?> of those (used for the
    // `players` / `lines` arrays). Everything is escaped to stay single-line.

    static String encode(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder(96);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, e.getKey());
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                sb.append(Long.toString((long) d)); // 64.0 -> 64
            } else {
                sb.append(v.toString());
            }
        } else if (v instanceof Number) {
            sb.append(v.toString());
        } else if (v instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            // Unknown type: coerce to string defensively rather than emit invalid JSON.
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
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
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c)); // strip other control chars
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
