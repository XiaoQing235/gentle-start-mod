package dev.gentlestart;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GentleStart implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("gentle-start-mod");
    private static ProtectionEngine engine;

    public static ProtectionEngine engine() {
        return engine;
    }

    public static void checkConditions(Player player, ProtectionEngine.ConditionChange change) {
        // Inventory and level changes also occur while a player is being constructed or loaded.
        if (engine != null && player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) {
            engine.checkConditions(serverPlayer, change);
        }
    }

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            engine = new ProtectionEngine(server,
                    FabricLoader.getInstance().getConfigDir().resolve("gentle-start-mod.json"),
                    server.getWorldPath(LevelResource.ROOT).resolve("data/gentle-start-mod-state.json"));
            LOGGER.info("Gentle Start loaded with {} UUID overrides", engine.config().configuredPlayers().size());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (!engine.states().save()) LOGGER.error("Gentle Start is stopping with unsaved player state; check storage access before restarting");
        });
        ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> {
            if (flush) engine.states().save();
            else engine.states().saveAsync();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> engine = null);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                engine.checkOnlineConditions();
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> ProtectionCommands.register(dispatcher));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof CorpseHolder holder && holder.gentleStart$corpse() != null)
                        && engine.allowDamage(entity, source, amount));
        // An unmodified client can send use-item after interacting with a mannequin.
        UseItemCallback.EVENT.register((player, level, hand) -> player.containerMenu instanceof CorpseMenu
                ? InteractionResult.FAIL : InteractionResult.PASS);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            engine.checkConditions(handler.player);
            engine.refreshEffects(handler.player);
        });
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            if (!alive && !newPlayer.level().getGameRules().get(GameRules.KEEP_INVENTORY) && !oldPlayer.isSpectator()) {
                ((DeathProtection) oldPlayer).gentleStart$death().restore(oldPlayer, newPlayer);
            }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            engine.checkConditions(newPlayer);
            engine.refreshEffects(newPlayer);
            if (!alive) {
                engine.protectRespawn(newPlayer);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 100 == 0) engine.states().saveAsync();
            engine.checkNextPlayers(server.getPlayerList().getPlayers());
        });
    }
}
