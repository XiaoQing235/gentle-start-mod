package dev.newcomerprotection;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NewcomerProtection implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("newcomer-protection");
    private static ProtectionConfig config;

    public static ProtectionConfig config() {
        return config;
    }

    public static boolean isProtected(ServerPlayer player) {
        return config.protects(player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)));
    }

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            config = ProtectionConfig.load(FabricLoader.getInstance().getConfigDir().resolve("newcomer-protection.json"));
            LOGGER.info("Newcomer Protection loaded: enabled={}, protectionSeconds={}",
                    config.enabled(), config.protectionSeconds());
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> refreshEffects(handler.player));
        ServerPlayerEvents.COPY_FROM.register(NewcomerProtection::copyProtectedDeath);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> refreshEffects(newPlayer));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.tickCount % 20 == 0) {
                    refreshEffects(player);
                }
            }
        });
    }

    private static void copyProtectedDeath(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
        if (alive || newPlayer.level().getGameRules().get(GameRules.KEEP_INVENTORY) || oldPlayer.isSpectator()) {
            return;
        }
        DeathProtection death = (DeathProtection) oldPlayer;
        if (death.newcomerProtection$keepInventory()) {
            newPlayer.getInventory().replaceWith(oldPlayer.getInventory());
        }
        if (death.newcomerProtection$keepExperience()) {
            newPlayer.experienceLevel = oldPlayer.experienceLevel;
            newPlayer.totalExperience = oldPlayer.totalExperience;
            newPlayer.experienceProgress = oldPlayer.experienceProgress;
        }
        if (death.newcomerProtection$keepInventory() || death.newcomerProtection$keepExperience()) {
            newPlayer.setScore(oldPlayer.getScore());
        }
    }

    public static void refreshEffects(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || !isProtected(player)) {
            return;
        }
        for (ProtectionConfig.Effect effect : config.effects()) {
            // Fifteen seconds also prevents vanilla night vision from flashing between refreshes.
            player.addEffect(new MobEffectInstance(effect.effect(), 300, effect.level() - 1,
                    false, effect.showParticles(), effect.showIcon()));
        }
    }
}
