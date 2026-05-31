package dev.blockacademy.slashannounce;

import dev.blockacademy.slashannounce.common.SlashAnnounceConfig;
import dev.blockacademy.slashannounce.intercept.Intercept;
import dev.blockacademy.slashannounce.intercept.SignIntercept;
import dev.blockacademy.slashannounce.intercept.SleepIntercept;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Entry point: loads config, then on server start registers each enabled
 * intercept (DESIGN §5.2). Mixins always load; the registration is the gate —
 * an intercept whose section is disabled is never registered, so its handler
 * early-returns. Toggling an intercept is a config edit + restart, never a rebuild.
 */
public class SlashAnnounce implements ModInitializer {
    public static final String MOD_ID = "slashannounce";
    public static final Logger LOG = LoggerFactory.getLogger("SlashAnnounce");

    private final List<Intercept> intercepts = List.of(
            new SleepIntercept(),
            new SignIntercept()
    );

    @Override
    public void onInitialize() {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
        SlashAnnounceConfig config = SlashAnnounceConfig.loadOrCreate(configFile);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!config.enabled()) {
                LOG.info("SlashAnnounce disabled via config (enabled=false); no intercepts registered.");
                return;
            }
            int active = 0;
            for (Intercept intercept : intercepts) {
                SlashAnnounceConfig.Section section = config.section(intercept.type());
                if (!section.enabled()) continue;
                try {
                    intercept.register(server, section);
                    active++;
                } catch (Throwable t) {
                    LOG.warn("Failed to register intercept '{}'", intercept.type(), t);
                }
            }
            LOG.info("SlashAnnounce ready — {} intercept(s) active.", active);
        });
    }
}
