package dev.blockacademy.slashannounce.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;

/**
 * The one per-band seam (classic bands: MC 1.20.1 – 1.21.11).
 *
 * <p>Everything in {@code mc-src} is shared verbatim across all bands. The few
 * calls that 26.1.x ("Tiny Takeover") reworked live here instead, so Band I can
 * supply its own copy without forking any real logic:
 * <ul>
 *   <li>daylight gamerule — {@code GameRules.RULE_DAYLIGHT} in {@code world.level}</li>
 *   <li>dimension id — {@code ResourceKey#location()}</li>
 * </ul>
 */
public final class Compat {
    private Compat() {}

    public static boolean daylightCycle(ServerLevel level) {
        return level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
    }

    public static String dimensionName(Level level) {
        var key = level.dimension();
        if (key == Level.OVERWORLD) return "overworld";
        if (key == Level.NETHER) return "nether";
        if (key == Level.END) return "end";
        return key.location().toString();
    }
}
