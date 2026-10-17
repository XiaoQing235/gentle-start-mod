package dev.newcomerprotection.mixin;

import dev.newcomerprotection.DeathDropProtection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
abstract class ItemEntityMixin implements DeathDropProtection {
    @Unique
    private boolean newcomerProtection$protected;

    @Override
    public void newcomerProtection$protect() {
        newcomerProtection$protected = true;
        ((ItemEntity) (Object) this).setExtendedLifetime();
    }

    @Override
    public boolean newcomerProtection$isProtected() {
        return newcomerProtection$protected;
    }

    @Inject(method = "isMergable", at = @At("HEAD"), cancellable = true)
    private void newcomerProtection$disableMerge(CallbackInfoReturnable<Boolean> callback) {
        if (newcomerProtection$protected) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "fireImmune", at = @At("HEAD"), cancellable = true)
    private void newcomerProtection$fireProof(CallbackInfoReturnable<Boolean> callback) {
        if (newcomerProtection$protected) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void newcomerProtection$rejectOrdinaryDamage(ServerLevel level, DamageSource source, float amount,
                                                        CallbackInfoReturnable<Boolean> callback) {
        if (newcomerProtection$protected && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void newcomerProtection$save(ValueOutput output, CallbackInfo callback) {
        output.putBoolean("newcomer-protection:death_drop", newcomerProtection$protected);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void newcomerProtection$load(ValueInput input, CallbackInfo callback) {
        // Restore only the marker. The saved vanilla Age must keep counting from its previous value.
        newcomerProtection$protected = input.getBooleanOr("newcomer-protection:death_drop", false);
    }
}
