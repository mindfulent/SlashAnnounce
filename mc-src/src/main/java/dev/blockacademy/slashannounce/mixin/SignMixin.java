package dev.blockacademy.slashannounce.mixin;

import dev.blockacademy.slashannounce.intercept.SignIntercept;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * HEAD-injects {@code SignBlockEntity#updateSignText(Player, boolean, List)} — the
 * worker behind the sign-update packet. The handler applies the editor-UUID gate
 * (placement only), is config-gated, and never throws.
 */
@Mixin(SignBlockEntity.class)
public class SignMixin {

    @Inject(method = "updateSignText", at = @At("HEAD"))
    private void slashannounce$onUpdateSignText(Player player, boolean isFrontText,
                                                List<FilteredText> messages, CallbackInfo ci) {
        SignIntercept.onUpdateSignText((SignBlockEntity) (Object) this, player, isFrontText, messages);
    }
}
