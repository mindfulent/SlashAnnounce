package dev.blockacademy.slashannounce.intercept;

import dev.blockacademy.slashannounce.common.Emitter;
import dev.blockacademy.slashannounce.common.SlashAnnounceConfig;
import dev.blockacademy.slashannounce.compat.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code death} — a player died.
 *
 * <p>Hooked at HEAD of {@code ServerPlayer#die(DamageSource)} (players only — mob
 * deaths would be spam). Emits the vanilla death message verbatim plus structured
 * data to latch onto: the machine-readable cause ({@code DamageSource#getMsgId},
 * e.g. {@code "fall"}), the killer (if any), and the location — so the bridge
 * never has to string-match the dozens of fragile vanilla death-message variants.
 * All referenced symbols are stable 1.20.1→26.1.2 (no {@code Compat} needed beyond
 * the shared dimension-name helper).
 */
public final class DeathIntercept implements Intercept {

    private static volatile boolean active = false;
    private static volatile boolean includeMessage = true;

    @Override
    public String type() {
        return "death";
    }

    @Override
    public void register(MinecraftServer server, SlashAnnounceConfig.Section cfg) {
        includeMessage = cfg.getBoolean("includeMessage", true);
        active = true;
    }

    /** Called from {@code DeathMixin} at HEAD of {@code ServerPlayer#die}. Never throws. */
    public static void onDie(ServerPlayer player, DamageSource source) {
        try {
            if (!active) return;

            BlockPos pos = player.blockPosition();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("player", player.getScoreboardName());
            if (includeMessage) {
                // Combat tracker is already populated with the killing blow at this
                // point, so the message matches what vanilla broadcasts.
                payload.put("message", player.getCombatTracker().getDeathMessage().getString());
            }
            payload.put("cause", source.getMsgId());
            Entity killer = source.getEntity();
            if (killer != null) {
                payload.put("killer", killer.getScoreboardName());
            }
            payload.put("x", pos.getX());
            payload.put("y", pos.getY());
            payload.put("z", pos.getZ());
            payload.put("dimension", Compat.dimensionName(player.level()));
            Emitter.emit("death", payload);
        } catch (Throwable t) {
            // Swallow — never disrupt the tick.
        }
    }
}
