package dev.blockacademy.slashannounce.mixin;

import dev.blockacademy.slashannounce.intercept.DeathIntercept;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HEAD-injects {@code ServerPlayer#die(DamageSource)} — player deaths only. The
 * handler is config-gated and never throws.
 */
@Mixin(ServerPlayer.class)
public class DeathMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void slashannounce$onDie(DamageSource source, CallbackInfo ci) {
        DeathIntercept.onDie((ServerPlayer) (Object) this, source);
    }
}
