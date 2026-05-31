package dev.blockacademy.slashannounce.mixin;

import dev.blockacademy.slashannounce.intercept.SleepIntercept;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HEAD-injects {@code ServerLevel#wakeUpAllPlayers} — the single "night is being
 * skipped" call site. The handler is config-gated and never throws.
 */
@Mixin(ServerLevel.class)
public class SleepMixin {

    @Inject(method = "wakeUpAllPlayers", at = @At("HEAD"))
    private void slashannounce$onWakeUpAllPlayers(CallbackInfo ci) {
        SleepIntercept.onWakeUpAllPlayers((ServerLevel) (Object) this);
    }
}
