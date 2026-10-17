package dev.newcomerprotection.mixin;

import dev.newcomerprotection.DeathProtection;
import dev.newcomerprotection.NewcomerProtection;
import dev.newcomerprotection.ProtectionConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin implements DeathProtection {
    @Unique
    private boolean newcomerProtection$keepInventory;
    @Unique
    private boolean newcomerProtection$keepExperience;

    @Inject(method = "die", at = @At("HEAD"))
    private void newcomerProtection$captureDeath(DamageSource source, CallbackInfo callback) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        ProtectionConfig config = NewcomerProtection.config();
        boolean protectedDeath = NewcomerProtection.isProtected(player);
        newcomerProtection$keepInventory = protectedDeath && config.keepInventory();
        newcomerProtection$keepExperience = protectedDeath && config.keepExperience();
        if (newcomerProtection$keepInventory) {
            // Return carried/crafting items before the old player can be saved or removed.
            player.closeContainer();
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void newcomerProtection$saveDeath(ValueOutput output, CallbackInfo callback) {
        if (newcomerProtection$keepInventory) {
            output.putBoolean("newcomer-protection:keep_inventory", true);
        }
        if (newcomerProtection$keepExperience) {
            output.putBoolean("newcomer-protection:keep_experience", true);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void newcomerProtection$loadDeath(ValueInput input, CallbackInfo callback) {
        boolean dead = ((ServerPlayer) (Object) this).isDeadOrDying();
        newcomerProtection$keepInventory = dead && input.getBooleanOr("newcomer-protection:keep_inventory", false);
        newcomerProtection$keepExperience = dead && input.getBooleanOr("newcomer-protection:keep_experience", false);
    }

    @Override
    public boolean newcomerProtection$keepInventory() {
        return newcomerProtection$keepInventory;
    }

    @Override
    public boolean newcomerProtection$keepExperience() {
        return newcomerProtection$keepExperience;
    }
}
