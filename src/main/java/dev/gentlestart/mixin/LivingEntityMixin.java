package dev.gentlestart.mixin;

import dev.gentlestart.GentleStart;
import dev.gentlestart.DeathProtection;
import dev.gentlestart.DeathDropProtection;
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
    private void gentleStart$protectDeathDrop(ItemStack stack, boolean randomly, boolean thrownFromHand,
                                                    CallbackInfoReturnable<ItemEntity> callback) {
        if ((Object) this instanceof DeathProtection death && death.gentleStart$protectDrops()
                && callback.getReturnValue() != null) {
            ((DeathDropProtection) callback.getReturnValue()).gentleStart$protect(death.gentleStart$dropLifetimeSeconds());
        }
    }

    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float gentleStart$scaleDamage(float damage, ServerLevel level, DamageSource source, float original) {
        return (Object) this instanceof ServerPlayer player
                ? GentleStart.engine().scaleDamage(player, source, damage) : damage;
    }
}
