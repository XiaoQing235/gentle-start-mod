package dev.gentlestart.mixin;

import dev.gentlestart.DeathProtection;
import dev.gentlestart.DeathSnapshot;
import dev.gentlestart.Feature;
import dev.gentlestart.GentleStart;
import dev.gentlestart.ProtectionEngine;
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
    private DeathSnapshot gentleStart$death = DeathSnapshot.NONE;
    @Unique
    private boolean gentleStart$protectDrops;
    @Unique
    private int gentleStart$dropLifetimeSeconds = 600;

    @Inject(method = "die", at = @At("HEAD"))
    private void gentleStart$captureDeath(DamageSource source, CallbackInfo callback) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        ProtectionEngine engine = GentleStart.engine();
        engine.states().endReviveProtection(player.getUUID());
        gentleStart$protectDrops = false;
        // Keep this decision through the old player's later removal and container cleanup.
        gentleStart$protectDrops = engine.active(player, Feature.DEATH_DROPS);
        gentleStart$dropLifetimeSeconds = engine.profile(player).deathDrops().lifetimeSeconds();
        gentleStart$death = DeathSnapshot.capture(player, engine.active(player, Feature.INVENTORY),
                engine.active(player, Feature.EXPERIENCE) ? engine.profile(player).experience().ratio() : 0,
                engine.active(player, Feature.SCORE));
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void gentleStart$saveDeath(ValueOutput output, CallbackInfo callback) {
        if (!gentleStart$death.equals(DeathSnapshot.NONE)) {
            output.store("gentle-start-mod:death", DeathSnapshot.CODEC, gentleStart$death);
        }
        output.putBoolean("gentle-start-mod:protect_death_drops", gentleStart$protectDrops);
        output.putInt("gentle-start-mod:drop_lifetime_seconds", gentleStart$dropLifetimeSeconds);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void gentleStart$loadDeath(ValueInput input, CallbackInfo callback) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        gentleStart$protectDrops = player.isDeadOrDying()
                && input.getBooleanOr("gentle-start-mod:protect_death_drops", false);
        gentleStart$dropLifetimeSeconds = input.getIntOr("gentle-start-mod:drop_lifetime_seconds", 600);
        gentleStart$death = player.isDeadOrDying()
                ? input.read("gentle-start-mod:death", DeathSnapshot.CODEC).orElse(DeathSnapshot.NONE)
                : DeathSnapshot.NONE;
    }

    @Inject(method = {"setExperiencePoints", "setExperienceLevels"}, at = @At("RETURN"))
    private void gentleStart$observeSetLevel(int amount, CallbackInfo callback) {
        GentleStart.checkConditions((ServerPlayer) (Object) this, ProtectionEngine.ConditionChange.LEVEL);
    }

    @Override
    public DeathSnapshot gentleStart$death() {
        return gentleStart$death;
    }

    @Override
    public boolean gentleStart$protectDrops() {
        return gentleStart$protectDrops;
    }

    @Override
    public int gentleStart$dropLifetimeSeconds() {
        return gentleStart$dropLifetimeSeconds;
    }
}
