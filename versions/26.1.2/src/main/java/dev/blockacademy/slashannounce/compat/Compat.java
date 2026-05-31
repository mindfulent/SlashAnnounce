package dev.blockacademy.slashannounce.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * The one per-band seam for Band I (MC 26.1.x). "Tiny Takeover" reworked the calls
 * that {@code mc-compat-classic} makes:
 * <ul>
 *   <li>gamerules moved to {@code world.level.gamerules}; rules are typed
 *       {@code GameRule<T>} read via {@code GameRules#get}; daylight is
 *       {@code ADVANCE_TIME}</li>
 *   <li>{@code ResourceKey#location()} was renamed to {@code identifier()}</li>
 * </ul>
 * The rest of {@code mc-src} compiles here unchanged.
 */
public final class Compat {
    private Compat() {}

    public static boolean daylightCycle(ServerLevel level) {
        return level.getGameRules().get(GameRules.ADVANCE_TIME);
    }

    public static String dimensionName(Level level) {
        var key = level.dimension();
        if (key == Level.OVERWORLD) return "overworld";
        if (key == Level.NETHER) return "nether";
        if (key == Level.END) return "end";
        return key.identifier().toString();
    }
}
