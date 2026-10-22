package dev.gentlestart.mixin;

import dev.gentlestart.DeathDropProtection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
abstract class ItemEntityMixin implements DeathDropProtection {
    @Unique
    private boolean gentleStart$protected;
    @Unique
    private int gentleStart$lifetimeTicks = 6000;
    @Shadow
    private int age;

    @Override
    public void gentleStart$protect(int lifetimeSeconds) {
        gentleStart$protected = true;
        gentleStart$lifetimeTicks = lifetimeSeconds * 20;
        age = 0;
    }

    @ModifyConstant(method = "tick", constant = @Constant(intValue = 6000))
    private int gentleStart$lifetime(int original) {
        return gentleStart$protected ? gentleStart$lifetimeTicks : original;
    }

    @Override
    public boolean gentleStart$isProtected() {
        return gentleStart$protected;
    }

    @Inject(method = "isMergable", at = @At("HEAD"), cancellable = true)
    private void gentleStart$disableMerge(CallbackInfoReturnable<Boolean> callback) {
        if (gentleStart$protected) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "fireImmune", at = @At("HEAD"), cancellable = true)
    private void gentleStart$fireProof(CallbackInfoReturnable<Boolean> callback) {
        if (gentleStart$protected) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void gentleStart$rejectOrdinaryDamage(ServerLevel level, DamageSource source, float amount,
                                                        CallbackInfoReturnable<Boolean> callback) {
        if (gentleStart$protected && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void gentleStart$save(ValueOutput output, CallbackInfo callback) {
        output.putBoolean("gentle-start-mod:death_drop", gentleStart$protected);
        if (gentleStart$protected) {
            output.putInt("gentle-start-mod:lifetime_ticks", gentleStart$lifetimeTicks);
            // Vanilla saves Age as a short; configured lifetimes can exceed that range.
            output.putInt("gentle-start-mod:age", age);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void gentleStart$load(ValueInput input, CallbackInfo callback) {
        gentleStart$protected = input.getBooleanOr("gentle-start-mod:death_drop", false);
        if (gentleStart$protected) {
            gentleStart$lifetimeTicks = input.getInt("gentle-start-mod:lifetime_ticks").orElseThrow();
            age = input.getInt("gentle-start-mod:age").orElseThrow();
        }
    }
}
