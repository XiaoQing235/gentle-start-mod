package dev.newcomerprotection.mixin;

import dev.newcomerprotection.DeathProtection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
abstract class PlayerMixin {
    @Inject(method = "dropEquipment", at = @At("HEAD"), cancellable = true)
    private void newcomerProtection$keepInventory(ServerLevel level, CallbackInfo callback) {
        if ((Object) this instanceof DeathProtection death && death.newcomerProtection$keepInventory()) {
            callback.cancel();
        }
    }

    @Inject(method = "getBaseExperienceReward", at = @At("HEAD"), cancellable = true)
    private void newcomerProtection$keepExperience(ServerLevel level, CallbackInfoReturnable<Integer> callback) {
        if ((Object) this instanceof DeathProtection death && death.newcomerProtection$keepExperience()) {
            callback.setReturnValue(0);
        }
    }
}
