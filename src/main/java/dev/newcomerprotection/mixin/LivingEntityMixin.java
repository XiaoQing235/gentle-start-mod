package dev.newcomerprotection.mixin;

import dev.newcomerprotection.NewcomerProtection;
import dev.newcomerprotection.DeathProtection;
import dev.newcomerprotection.DeathDropProtection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
    @Inject(method = "createItemStackToDrop", at = @At("RETURN"))
    private void newcomerProtection$protectDeathDrop(ItemStack stack, boolean randomly, boolean thrownFromHand,
                                                    CallbackInfoReturnable<ItemEntity> callback) {
        if ((Object) this instanceof DeathProtection death && death.newcomerProtection$protectDrops()
                && callback.getReturnValue() != null) {
            ((DeathDropProtection) callback.getReturnValue()).newcomerProtection$protect();
        }
    }

    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float newcomerProtection$scaleDamage(float damage, ServerLevel level, DamageSource source, float original) {
        return (Object) this instanceof ServerPlayer player
                ? NewcomerProtection.engine().scaleDamage(player, source, damage) : damage;
    }
}
