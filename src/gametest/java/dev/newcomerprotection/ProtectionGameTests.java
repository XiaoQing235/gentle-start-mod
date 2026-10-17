package dev.newcomerprotection;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.stats.Stats;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

public class ProtectionGameTests {
    @GameTest
    public void protectedDeathKeepsFullInventoryAndExperience(GameTestHelper test) {
        withConfig(test, ProtectionConfig.DEFAULT, () -> {
            ServerPlayer player = player(test, 0);
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
            player.getInventory().setSelectedSlot(2);
            ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
            helmet.enchant(test.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.VANISHING_CURSE), 1);
            player.setItemSlot(EquipmentSlot.HEAD, helmet);
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            giveExperience(player);
            int drops = drops(player);
            int orbs = orbs(player);
            player.kill(test.getLevel());
            test.assertValueEqual(drops(player), drops, "Protected death must not drop items");
            test.assertValueEqual(orbs(player), orbs, "Protected death must not spawn XP");
            ServerPlayer respawned = respawn(player);
            test.assertValueEqual(respawned.getInventory().getItem(0).getCount(), 7, "Backpack survives");
            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) {
                test.assertFalse(respawned.getItemBySlot(slot).isEmpty(), "Equipment survives: " + slot);
            }
            test.assertValueEqual(respawned.getInventory().getSelectedSlot(), 2, "Selected slot survives");
            assertExperience(test, respawned);
            test.assertFalse(((DeathProtection) respawned).newcomerProtection$keepInventory(),
                    "A new life must not inherit the previous death decision");
            test.assertFalse(test.getLevel().getGameRules().get(GameRules.KEEP_INVENTORY),
                    "Personal protection must not change the global rule");
        });
        test.succeed();
    }

    @GameTest
    public void thresholdAndVeteransUseVanillaDrops(GameTestHelper test) {
        withConfig(test, ProtectionConfig.DEFAULT, () -> {
            for (int ticks : new int[]{144000, 144001}) {
                ServerPlayer player = player(test, ticks);
                player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
                giveExperience(player);
                int dropped = drops(player);
                int experience = orbs(player);
                player.kill(test.getLevel());
                test.assertTrue(drops(player) > dropped, "Unprotected players drop their items");
                test.assertTrue(orbs(player) > experience, "Unprotected players drop XP");
                ServerPlayer respawned = respawn(player);
                test.assertTrue(respawned.getInventory().getItem(0).isEmpty(), "No inventory duplication");
                test.assertValueEqual(respawned.experienceLevel, 0, "Vanilla XP reset");
            }
            test.assertTrue(ProtectionConfig.DEFAULT.protects(143999), "Strict threshold includes the preceding tick");
        });
        test.succeed();
    }

    @GameTest
    public void deathDecisionSurvivesThresholdAndPlayerSave(GameTestHelper test) {
        withConfig(test, ProtectionConfig.DEFAULT, () -> {
            ServerPlayer player = player(test, 143999);
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 9));
            giveExperience(player);
            player.kill(test.getLevel());
            player.getStats().setValue(player, Stats.CUSTOM.get(Stats.PLAY_TIME), 144100);
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                    test.getLevel().registryAccess());
            player.saveWithoutId(output);
            test.getLevel().getServer().getPlayerList().remove(player);
            ServerPlayer reconnected = new ServerPlayer(test.getLevel().getServer(), test.getLevel(),
                    player.getGameProfile(), player.clientInformation());
            reconnected.connection = player.connection;
            test.assertFalse(((DeathProtection) reconnected).newcomerProtection$keepInventory(),
                    "A fresh player starts without the previous in-memory decision");
            reconnected.load(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(),
                    output.buildResult()));
            test.assertFalse(NewcomerProtection.isProtected(reconnected), "Time has crossed the threshold");
            ServerPlayer respawned = respawn(reconnected);
            test.assertValueEqual(respawned.getInventory().getItem(0).getCount(), 9, "Saved death keeps items");
            assertExperience(test, respawned);
        });
        test.succeed();
    }

    @GameTest
    public void inventoryAndExperienceOptionsAreIndependent(GameTestHelper test) {
        for (boolean keepItems : new boolean[]{true, false}) {
            withConfig(test, new ProtectionConfig(true, 7200, keepItems, !keepItems, List.of()), () -> {
                ServerPlayer player = player(test, 0);
                player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
                giveExperience(player);
                int dropped = drops(player);
                int experience = orbs(player);
                player.kill(test.getLevel());
                test.assertValueEqual(drops(player) == dropped, keepItems, "Inventory drop option");
                test.assertValueEqual(orbs(player) == experience, !keepItems, "XP drop option");
                ServerPlayer respawned = respawn(player);
                test.assertValueEqual(!respawned.getInventory().getItem(0).isEmpty(), keepItems, "Inventory copy option");
                test.assertValueEqual(respawned.experienceLevel, keepItems ? 0 : 17, "XP copy option");
            });
        }
        test.succeed();
    }

    @GameTest
    public void disabledAndZeroDurationDoNotProtect(GameTestHelper test) {
        for (ProtectionConfig config : List.of(new ProtectionConfig(false, 7200, true, true, List.of()),
                new ProtectionConfig(true, 0, true, true, List.of()))) {
            withConfig(test, config, () -> {
                ServerPlayer player = player(test, 0);
                player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
                player.kill(test.getLevel());
                test.assertTrue(respawn(player).getInventory().getItem(0).isEmpty(), "Disabled protection uses vanilla death");
            });
        }
        test.succeed();
    }

    @GameTest
    public void globalKeepInventoryStillWorks(GameTestHelper test) {
        withConfig(test, new ProtectionConfig(false, 0, false, false, List.of()), () -> {
            test.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, true, test.getLevel().getServer());
            ServerPlayer player = player(test, 144000);
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
            giveExperience(player);
            player.kill(test.getLevel());
            ServerPlayer respawned = respawn(player);
            test.assertTrue(respawned.getInventory().getItem(0).is(Items.DIAMOND), "Global rule keeps items");
            assertExperience(test, respawned);
        });
        test.succeed();
    }

    @GameTest
    public void effectsRefreshWithoutRemovingExternalEffects(GameTestHelper test) {
        withConfig(test, ProtectionConfig.DEFAULT, () -> {
            ServerPlayer player = player(test, 0);
            player.removeAllEffects();
            NewcomerProtection.refreshEffects(player);
            test.assertTrue(player.hasEffect(MobEffects.NIGHT_VISION), "Configured effect applied");
            test.assertValueEqual(player.getEffect(MobEffects.RESISTANCE).getAmplifier(), 0, "Level 1 maps to amplifier 0");
            player.removeAllEffects(); // Milk clears the effect until the next refresh.
            NewcomerProtection.refreshEffects(player);
            test.assertTrue(player.hasEffect(MobEffects.NIGHT_VISION), "Effects return after milk");
            player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 2400, 2));
            NewcomerProtection.refreshEffects(player);
            test.assertValueEqual(player.getEffect(MobEffects.RESISTANCE).getAmplifier(), 2, "Stronger potion stays active");
            player.getStats().setValue(player, Stats.CUSTOM.get(Stats.PLAY_TIME), 144000);
            player.removeEffect(MobEffects.NIGHT_VISION);
            NewcomerProtection.refreshEffects(player);
            test.assertFalse(player.hasEffect(MobEffects.NIGHT_VISION), "No refresh at threshold");
            test.assertValueEqual(player.getEffect(MobEffects.RESISTANCE).getDuration(), 2400, "External effect remains");
        });
        test.succeed();
    }

    @GameTest
    public void invalidConfigurationFailsClearly(GameTestHelper test) {
        for (String json : List.of("null", "[]", "{\"protectionSeconds\":-1}",
                "{\"protectionSeconds\":9223372036854775807}", "{\"enabled\":\"yes\"}",
                "{\"effects\":[{\"id\":\"minecraft:unknown\"}]}",
                "{\"effects\":[{\"id\":\"minecraft:instant_health\"}]}",
                "{\"effects\":[{\"id\":\"minecraft:speed\",\"level\":0}]}",
                "{\"effects\":[{\"id\":\"minecraft:speed\",\"level\":257}]}")) {
            test.assertTrue(ProtectionConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).error().isPresent(),
                    "Invalid configuration rejected: " + json);
        }
        ProtectionConfig config = ProtectionConfig.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"protectionSeconds\":10,\"effects\":[]}")).getOrThrow();
        test.assertTrue(config.protects(199), "Custom duration is used");
        test.assertFalse(config.protects(200), "Custom duration ends exactly at threshold");
        test.succeed();
    }

    @GameTest
    public void defaultConfigIsEditableAndInvalidFilesArePreserved(GameTestHelper test) throws Exception {
        Path directory = Files.createTempDirectory("newcomer-config-test-");
        Path file = directory.resolve("newcomer-protection.json");
        try {
            test.assertValueEqual(ProtectionConfig.load(file), ProtectionConfig.DEFAULT, "Missing config uses defaults");
            String json = Files.readString(file);
            test.assertTrue(json.contains("protectionSeconds") && json.contains("minecraft:night_vision")
                    && json.contains("showParticles"), "Generated config includes editable defaults");
            test.assertValueEqual(ProtectionConfig.load(file), ProtectionConfig.DEFAULT, "Generated config round trip");
            Files.writeString(file, "{invalid");
            boolean rejected = false;
            try {
                ProtectionConfig.load(file);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            test.assertTrue(rejected, "Malformed JSON fails instead of silently resetting");
            test.assertValueEqual(Files.readString(file), "{invalid", "Invalid config remains available for repair");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
        test.succeed();
    }

    @GameTest
    public void openChestDeathReturnsCursorWithoutCopyingChest(GameTestHelper test) {
        withConfig(test, ProtectionConfig.DEFAULT, () -> {
            ServerPlayer player = player(test, 0);
            SimpleContainer chest = new SimpleContainer(27);
            chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 11));
            player.containerMenu = ChestMenu.threeRows(1, player.getInventory(), chest);
            player.containerMenu.setCarried(new ItemStack(Items.DIAMOND, 5));
            int dropped = drops(player);
            player.kill(test.getLevel());
            ServerPlayer respawned = respawn(player);
            test.assertValueEqual(respawned.getInventory().countItem(Items.DIAMOND), 5, "Cursor stack returns exactly once");
            test.assertValueEqual(respawned.getInventory().countItem(Items.GOLD_INGOT), 0, "Chest contents are not copied");
            test.assertValueEqual(chest.getItem(0).getCount(), 11, "Chest contents remain intact");
            test.assertValueEqual(drops(respawned), dropped, "Cursor is retained rather than dropped");
        });
        test.succeed();
    }

    @GameTest
    public void deathReturnsCraftingInputsWithoutDuplicatingResult(GameTestHelper test) {
        withConfig(test, ProtectionConfig.DEFAULT, () -> {
            ServerPlayer player = player(test, 0);
            player.inventoryMenu.getSlot(1).set(new ItemStack(Items.OAK_LOG, 3));
            player.inventoryMenu.getSlot(0).set(new ItemStack(Items.OAK_PLANKS, 4));
            player.kill(test.getLevel());
            ServerPlayer respawned = respawn(player);
            test.assertValueEqual(respawned.getInventory().countItem(Items.OAK_LOG), 3, "Crafting inputs return once");
            test.assertValueEqual(respawned.getInventory().countItem(Items.OAK_PLANKS), 0, "Untaken result is not duplicated");
        });
        test.succeed();
    }

    private static ServerPlayer player(GameTestHelper test, int ticks) {
        UUID id = UUID.randomUUID();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(id,
                "np-" + id.toString().substring(0, 8)), false);
        ServerPlayer player = new ServerPlayer(test.getLevel().getServer(), test.getLevel(),
                cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        test.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        new ServerboundPlayerLoadedPacket().handle(player.connection);
        player.setGameMode(GameType.SURVIVAL);
        player.snapTo(test.absoluteVec(new net.minecraft.world.phys.Vec3(1, 2, 1)));
        player.getStats().setValue(player, Stats.CUSTOM.get(Stats.PLAY_TIME), ticks);
        return player;
    }

    private static void giveExperience(ServerPlayer player) {
        player.experienceLevel = 17;
        player.totalExperience = 400;
        player.experienceProgress = 0.5F;
    }

    private static void assertExperience(GameTestHelper test, ServerPlayer player) {
        test.assertValueEqual(player.experienceLevel, 17, "XP level survives");
        test.assertValueEqual(player.totalExperience, 400, "XP total survives");
        test.assertValueEqual(player.experienceProgress, 0.5F, "XP progress survives");
    }

    private static ServerPlayer respawn(ServerPlayer player) {
        return player.level().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
    }

    private static int drops(ServerPlayer player) {
        return player.level().getEntitiesOfClass(ItemEntity.class, new AABB(player.blockPosition()).inflate(8)).size();
    }

    private static int orbs(ServerPlayer player) {
        return player.level().getEntitiesOfClass(ExperienceOrb.class, new AABB(player.blockPosition()).inflate(8)).size();
    }

    private static void withConfig(GameTestHelper test, ProtectionConfig config, Runnable assertions) {
        ProtectionConfig previous = NewcomerProtection.config();
        boolean keepInventory = test.getLevel().getGameRules().get(GameRules.KEEP_INVENTORY);
        try {
            Field field = NewcomerProtection.class.getDeclaredField("config");
            field.setAccessible(true);
            field.set(null, config);
            test.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, false, test.getLevel().getServer());
            assertions.run();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        } finally {
            try {
                Field field = NewcomerProtection.class.getDeclaredField("config");
                field.setAccessible(true);
                field.set(null, previous);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
            test.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, keepInventory, test.getLevel().getServer());
        }
    }
}
