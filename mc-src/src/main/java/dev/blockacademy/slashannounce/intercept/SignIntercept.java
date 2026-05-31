package dev.blockacademy.slashannounce.intercept;

import dev.blockacademy.slashannounce.common.Emitter;
import dev.blockacademy.slashannounce.common.SlashAnnounceConfig;
import dev.blockacademy.slashannounce.compat.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code sign} — a sign was placed, first write only (DESIGN §6.2).
 *
 * <p>Placement is detected via the <b>editor-UUID gate</b>, not emptiness: vanilla
 * sets {@code allowedPlayerEditor} to the placer on placement and clears it after
 * the first successful write. So only the placement write has a non-null
 * {@code getPlayerWhoMayEdit()} equal to the editor; re-edits are naturally
 * excluded. We inject at HEAD (before the gate is cleared) and emit only when the
 * gate is about to pass.
 */
public final class SignIntercept implements Intercept {

    private static volatile boolean active = false;
    private static volatile boolean includeBackSide = false;
    private static volatile int maxLineLength = 120;

    @Override
    public String type() {
        return "sign";
    }

    @Override
    public void register(MinecraftServer server, SlashAnnounceConfig.Section cfg) {
        includeBackSide = cfg.getBoolean("includeBackSide", false);
        maxLineLength = Math.max(1, cfg.getInt("maxLineLength", 120));
        active = true;
    }

    /** Called from {@code SignMixin} at HEAD of {@code updateSignText}. Never throws. */
    public static void onUpdateSignText(SignBlockEntity sign, Player player, boolean isFrontText,
                                        List<FilteredText> messages) {
        try {
            if (!active) return;
            if (sign.isWaxed()) return;

            UUID editor = sign.getPlayerWhoMayEdit();
            if (editor == null || !editor.equals(player.getUUID())) return; // re-edit, not placement
            if (!isFrontText && !includeBackSide) return;

            Level level = sign.getLevel();
            if (level == null) return;
            BlockPos pos = sign.getBlockPos();

            List<String> lines = new ArrayList<>(4);
            for (FilteredText ft : messages) {
                String raw = ft.raw();
                if (raw == null) raw = "";
                if (raw.length() > maxLineLength) raw = raw.substring(0, maxLineLength);
                lines.add(raw);
            }
            while (lines.size() < 4) lines.add("");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("player", player.getScoreboardName());
            payload.put("x", pos.getX());
            payload.put("y", pos.getY());
            payload.put("z", pos.getZ());
            payload.put("dimension", Compat.dimensionName(level));
            payload.put("side", isFrontText ? "front" : "back");
            payload.put("lines", lines);
            Emitter.emit("sign", payload);
        } catch (Throwable t) {
            // Swallow — never disrupt the tick.
        }
    }
}
