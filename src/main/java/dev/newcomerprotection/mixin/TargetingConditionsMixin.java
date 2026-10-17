package dev.newcomerprotection.mixin;

import dev.newcomerprotection.NewcomerProtection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TargetingConditions.class)
abstract class TargetingConditionsMixin {
    @Shadow
    private boolean isCombat;
    @Shadow
    private double range;

    @Inject(method = "test", at = @At("HEAD"), cancellable = true)
    private void newcomerProtection$reduceDetection(ServerLevel level, LivingEntity targeter, LivingEntity target,
                                                    CallbackInfoReturnable<Boolean> callback) {
        if (isCombat && range > 0 && targeter != null && target instanceof ServerPlayer player
                && !NewcomerProtection.engine().withinMobRange(targeter, player, range)) {
            callback.setReturnValue(false);
        }
    }
}
