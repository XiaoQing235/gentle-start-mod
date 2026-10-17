package dev.gentlestart.mixin;

import dev.gentlestart.GentleStart;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
abstract class MobMixin {
    @Inject(method = "asValidTarget", at = @At("RETURN"), cancellable = true)
    private void gentleStart$reduceActiveTarget(LivingEntity target, CallbackInfoReturnable<LivingEntity> callback) {
        Mob mob = (Mob) (Object) this;
        if (callback.getReturnValue() instanceof ServerPlayer player
                && !GentleStart.engine().withinMobRange(mob, player, mob.getAttributeValue(Attributes.FOLLOW_RANGE))) {
            callback.setReturnValue(null);
        }
    }
}
