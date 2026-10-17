package dev.newcomerprotection.mixin;

import dev.newcomerprotection.NewcomerProtection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float newcomerProtection$scaleDamage(float damage, ServerLevel level, DamageSource source, float original) {
        return (Object) this instanceof ServerPlayer player
                ? NewcomerProtection.engine().scaleDamage(player, source, damage) : damage;
    }
}
