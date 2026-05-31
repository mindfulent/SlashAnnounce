package dev.blockacademy.slashannounce.intercept;

import dev.blockacademy.slashannounce.common.Emitter;
import dev.blockacademy.slashannounce.common.SlashAnnounceConfig;
import dev.blockacademy.slashannounce.compat.Compat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code sleep} — the server skipped a night (DESIGN §6.1).
 *
 * <p>Hooked at HEAD of {@code ServerLevel#wakeUpAllPlayers}, whose single call
 * site is the "enough players sleeping" branch in the level tick — so the inject
 * fires exactly when the night is being skipped. Manual single-player wake takes
 * a different path and is intentionally not caught.
 */
public final class SleepIntercept implements Intercept {

    // Config snapshot — set on register(); the mixin gate reads `active`.
    private static volatile boolean active = false;
    private static volatile boolean overworldOnly = true;
    private static volatile boolean includePlayerNames = true;
    private static volatile long debounceMs = 10_000L;
    private static volatile long lastEmit = 0L;

    @Override
    public String type() {
        return "sleep";
    }

    @Override
    public void register(MinecraftServer server, SlashAnnounceConfig.Section cfg) {
        overworldOnly = cfg.getBoolean("overworldOnly", true);
        includePlayerNames = cfg.getBoolean("includePlayerNames", true);
        debounceMs = Math.max(0L, cfg.getInt("debounceSeconds", 10)) * 1000L;
        active = true;
    }

    /** Called from {@code SleepMixin} at HEAD of {@code wakeUpAllPlayers}. Never throws. */
    public static void onWakeUpAllPlayers(ServerLevel level) {
        try {
            if (!active) return;
            if (overworldOnly && level.dimension() != Level.OVERWORLD) return;

            long now = System.currentTimeMillis();
            if (debounceMs > 0 && now - lastEmit < debounceMs) return;
            lastEmit = now;

            // At HEAD no one has been woken yet, so sleeping players still read as sleeping.
            List<String> sleepers = new ArrayList<>();
            int count = 0;
            for (ServerPlayer p : level.players()) {
                if (p.isSleeping()) {
                    count++;
                    sleepers.add(p.getScoreboardName());
                }
            }

            // wakeUpAllPlayers also runs with the daylight-cycle rule off (players
            // wake, time doesn't advance). Read via the per-band TimeRule seam — the
            // rule moved/renamed in 26.1.x — so the renderer can phrase correctly.
            boolean timeAdvanced = Compat.daylightCycle(level);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sleepers", count);
            if (includePlayerNames) payload.put("players", sleepers);
            payload.put("dimension", Compat.dimensionName(level));
            payload.put("timeAdvanced", timeAdvanced);
            Emitter.emit("sleep", payload);
        } catch (Throwable t) {
            // Swallow — never disrupt the tick.
        }
    }
}
