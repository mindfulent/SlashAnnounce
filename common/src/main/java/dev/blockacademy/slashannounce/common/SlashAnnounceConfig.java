package dev.blockacademy.slashannounce.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses {@code config/slashannounce.json} (DESIGN §5.3).
 *
 * <p>A global {@code enabled} master kill-switch plus per-intercept sections.
 * Missing sections default to enabled; unknown keys are ignored; a bad/unreadable
 * file falls back to all-enabled defaults rather than stopping the mod. Contains
 * no Minecraft references so it lives in the pure-Java {@code common} module.
 */
public final class SlashAnnounceConfig {

    private static final String DEFAULT_JSON = """
            {
              "enabled": true,
              "intercepts": {
                "sleep": { "enabled": true, "debounceSeconds": 10, "includePlayerNames": true, "overworldOnly": true },
                "sign":  { "enabled": true, "includeBackSide": false, "maxLineLength": 120 }
              }
            }
            """;

    private final boolean enabled;
    private final Map<String, Section> sections;

    private SlashAnnounceConfig(boolean enabled, Map<String, Section> sections) {
        this.enabled = enabled;
        this.sections = sections;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Never null. A missing section defaults to enabled with no options. */
    public Section section(String type) {
        Section s = sections.get(type);
        return s != null ? s : new Section(true, Map.of());
    }

    /** Reads the file, writing the default template on first run. Never throws. */
    public static SlashAnnounceConfig loadOrCreate(Path file) {
        try {
            if (!Files.exists(file)) {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, DEFAULT_JSON, StandardCharsets.UTF_8);
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return parse(JsonParser.parseString(text).getAsJsonObject());
        } catch (Exception e) {
            return parse(JsonParser.parseString(DEFAULT_JSON).getAsJsonObject());
        }
    }

    private static SlashAnnounceConfig parse(JsonObject root) {
        boolean enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
        Map<String, Section> sections = new HashMap<>();
        if (root.has("intercepts") && root.get("intercepts").isJsonObject()) {
            JsonObject intercepts = root.getAsJsonObject("intercepts");
            for (Map.Entry<String, JsonElement> e : intercepts.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject sec = e.getValue().getAsJsonObject();
                boolean secEnabled = !sec.has("enabled") || sec.get("enabled").getAsBoolean();
                Map<String, Object> opts = new HashMap<>();
                for (Map.Entry<String, JsonElement> o : sec.entrySet()) {
                    if (o.getKey().equals("enabled")) continue;
                    if (!o.getValue().isJsonPrimitive()) continue;
                    JsonPrimitive p = o.getValue().getAsJsonPrimitive();
                    if (p.isBoolean()) {
                        opts.put(o.getKey(), p.getAsBoolean());
                    } else if (p.isNumber()) {
                        opts.put(o.getKey(), p.getAsNumber());
                    } else {
                        opts.put(o.getKey(), p.getAsString());
                    }
                }
                sections.put(e.getKey(), new Section(secEnabled, opts));
            }
        }
        return new SlashAnnounceConfig(enabled, sections);
    }

    /** A single intercept's config: its enable flag plus arbitrary scalar options. */
    public static final class Section {
        private final boolean enabled;
        private final Map<String, Object> opts;

        Section(boolean enabled, Map<String, Object> opts) {
            this.enabled = enabled;
            this.opts = opts;
        }

        public boolean enabled() {
            return enabled;
        }

        public boolean getBoolean(String key, boolean def) {
            Object v = opts.get(key);
            return v instanceof Boolean b ? b : def;
        }

        public int getInt(String key, int def) {
            Object v = opts.get(key);
            return v instanceof Number n ? n.intValue() : def;
        }

        public String getString(String key, String def) {
            Object v = opts.get(key);
            return v instanceof String s ? s : def;
        }
    }
}
