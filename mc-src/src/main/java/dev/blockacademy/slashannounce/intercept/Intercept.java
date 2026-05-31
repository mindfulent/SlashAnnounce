package dev.blockacademy.slashannounce.intercept;

import dev.blockacademy.slashannounce.common.SlashAnnounceConfig;
import net.minecraft.server.MinecraftServer;

/**
 * A single, self-contained source of console announcements (DESIGN §5.1).
 *
 * <p>Each intercept owns its mixin(s); the mixin calls a static handler on the
 * intercept which turns the raw game event into a payload and emits. {@link
 * #register} is called once on server start for each <i>enabled</i> intercept —
 * it captures the config snapshot the mixin reads, which is also the gate: an
 * unregistered (disabled) intercept's handler early-returns at near-zero cost.
 */
public interface Intercept {

    /** Stable wire type, e.g. {@code "sleep"}, {@code "sign"}. Must match {@code ^[a-z][a-z0-9_]*$}. */
    String type();

    /** Wire up config/state. Called once on SERVER_STARTED when this intercept is enabled. */
    void register(MinecraftServer server, SlashAnnounceConfig.Section cfg);
}
