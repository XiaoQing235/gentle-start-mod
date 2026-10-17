package dev.newcomerprotection.mixin;

import dev.newcomerprotection.DeathProtection;
import dev.newcomerprotection.DeathSnapshot;
import dev.newcomerprotection.Feature;
import dev.newcomerprotection.NewcomerProtection;
import dev.newcomerprotection.ProtectionEngine;
import java.util.stream.IntStream;
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
    private DeathSnapshot newcomerProtection$death = DeathSnapshot.NONE;

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void newcomerProtection$captureDeath(DamageSource source, CallbackInfo callback) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        ProtectionEngine engine = NewcomerProtection.engine();
        // Vanilla has already tried a held totem before calling ServerPlayer.die.
        if (engine.revive(player, source)) {
            callback.cancel();
            return;
        }
        newcomerProtection$death = DeathSnapshot.capture(player, engine.active(player, Feature.INVENTORY),
                engine.active(player, Feature.EXPERIENCE) ? engine.profile(player).experience().ratio() : 0,
                engine.active(player, Feature.SCORE));
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void newcomerProtection$saveDeath(ValueOutput output, CallbackInfo callback) {
        if (!newcomerProtection$death.equals(DeathSnapshot.NONE)) {
            output.store("newcomer-protection:death", DeathSnapshot.CODEC, newcomerProtection$death);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void newcomerProtection$loadDeath(ValueInput input, CallbackInfo callback) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        newcomerProtection$death = player.isDeadOrDying()
                ? input.read("newcomer-protection:death", DeathSnapshot.CODEC).orElseGet(() -> {
                    // Preserve pending deaths saved by version 0.1.
                    boolean items = input.getBooleanOr("newcomer-protection:keep_inventory", false);
                    boolean experience = input.getBooleanOr("newcomer-protection:keep_experience", false);
                    return new DeathSnapshot(items ? IntStream.range(0, player.getInventory().getContainerSize()).boxed().toList()
                            : java.util.List.of(), experience ? 1 : 0, items || experience,
                            player.experienceLevel, player.totalExperience, player.experienceProgress, player.getScore());
                }) : DeathSnapshot.NONE;
    }

    @Inject(method = {"setExperiencePoints", "setExperienceLevels"}, at = @At("RETURN"))
    private void newcomerProtection$observeSetLevel(int amount, CallbackInfo callback) {
        NewcomerProtection.checkConditions((ServerPlayer) (Object) this);
    }

    @Override
    public DeathSnapshot newcomerProtection$death() {
        return newcomerProtection$death;
    }
}
