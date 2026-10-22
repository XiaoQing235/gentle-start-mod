package dev.gentlestart;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    public void configurationFilesRejectUnsupportedFields(GameTestHelper test) throws Exception {
        Path file = Files.createTempFile("gentlestart-invalid-config-", ".json");
        try {
            var documents = new ArrayList<>(List.of("{\"protectionSeconds\":600}", "{\"playersPerTick\":1}",
                    "{\"defaults\":{\"protectionEffects\":{\"list\":[{\"id\":\"minecraft:speed\",\"durationSeconds\":15}]}}}",
                    "{\"defaults\":{\"preventDeath\":{\"unknownProtectionOption\":8}}}"));
            for (String oldName : List.of("inventory", "experience", "score", "revive", "effects", "pvp",
                    "mobRange", "respawnProtection", "deathDrops", "corpse")) {
                documents.add("{\"defaults\":{\"" + oldName + "\":{}}}");
            }
            for (String document : documents) {
                Files.writeString(file, document);
                boolean rejected = false;
                try {
                    ProtectionConfig.load(file);
                } catch (IllegalStateException expected) {
                    rejected = true;
                }
                test.assertTrue(rejected, "Unsupported fields must fail at the file boundary");
                test.assertValueEqual(Files.readString(file), document, "Rejected files remain unchanged");
            }
        } finally {
            Files.deleteIfExists(file);
        }
        test.succeed();
    }

    @GameTest
    public void protectedDeathKeepsFullInventoryAndExperience(GameTestHelper test) {
        withConfig(test, ProtectionConfig.defaults(), () -> {
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
            test.assertFalse(((DeathProtection) respawned).gentleStart$keepInventory(),
                    "A new life must not inherit the previous death decision");
            test.assertFalse(test.getLevel().getGameRules().get(GameRules.KEEP_INVENTORY),
                    "Personal protection must not change the global rule");
        });
        test.succeed();
    }

    @GameTest
    public void thresholdAndVeteransUseVanillaDrops(GameTestHelper test) {
        withConfig(test, ProtectionConfig.defaults(), () -> {
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
            test.assertTrue(ProtectionRules.Profile.DEFAULT.inventory().window().includes(143999), "Strict threshold includes the preceding tick");
        });
        test.succeed();
    }

    @GameTest
    public void deathDecisionSurvivesThresholdAndPlayerSave(GameTestHelper test) {
        withConfig(test, ProtectionConfig.defaults(), () -> {
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
            test.assertFalse(((DeathProtection) reconnected).gentleStart$keepInventory(),
                    "A fresh player starts without the previous in-memory decision");
            reconnected.load(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(),
                    output.buildResult()));
            test.assertFalse(GentleStart.engine().active(reconnected, Feature.INVENTORY), "Time has crossed the threshold");
            ServerPlayer respawned = respawn(reconnected);
            test.assertValueEqual(respawned.getInventory().getItem(0).getCount(), 9, "Saved death keeps items");
            assertExperience(test, respawned);
        });
        test.succeed();
    }

    @GameTest
    public void inventoryAndExperienceOptionsAreIndependent(GameTestHelper test) {
        for (boolean keepItems : new boolean[]{true, false}) {
            withConfig(test, configuration("{\"keepInventory\":{\"minutes\":" + (keepItems ? 120 : 0) + "},\"keepExperience\":{\"minutes\":" + (keepItems ? 0 : 120) + "},\"protectionEffects\":{\"minutes\":0}}"), () -> {
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
        for (ProtectionConfig config : List.of(ProtectionConfig.parse(JsonParser.parseString("{\"enabled\":false}").getAsJsonObject()),
                configuration("{\"keepInventory\":{\"minutes\":0},\"keepExperience\":{\"minutes\":0},\"keepScore\":{\"minutes\":0},\"protectionEffects\":{\"minutes\":0}}"))) {
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
        withConfig(test, ProtectionConfig.parse(JsonParser.parseString("{\"enabled\":false}").getAsJsonObject()), () -> {
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
        withConfig(test, ProtectionConfig.defaults(), () -> {
            ServerPlayer player = player(test, 0);
            player.removeAllEffects();
            GentleStart.engine().refreshEffects(player);
            test.assertTrue(player.hasEffect(MobEffects.NIGHT_VISION), "Configured effect applied");
            test.assertValueEqual(player.getEffect(MobEffects.RESISTANCE).getAmplifier(), 0, "Level 1 maps to amplifier 0");
            player.removeAllEffects(); // Milk clears the effect until the next refresh.
            GentleStart.engine().refreshEffects(player);
            test.assertTrue(player.hasEffect(MobEffects.NIGHT_VISION), "Effects return after milk");
            player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 2400, 2));
            GentleStart.engine().refreshEffects(player);
            test.assertValueEqual(player.getEffect(MobEffects.RESISTANCE).getAmplifier(), 2, "Stronger potion stays active");
            player.getStats().setValue(player, Stats.CUSTOM.get(Stats.PLAY_TIME), 144000);
            player.removeEffect(MobEffects.NIGHT_VISION);
            GentleStart.engine().refreshEffects(player);
            test.assertFalse(player.hasEffect(MobEffects.NIGHT_VISION), "No refresh at threshold");
            test.assertValueEqual(player.getEffect(MobEffects.RESISTANCE).getDuration(), 2400, "External effect remains");
        });
        test.succeed();
    }

    @GameTest
    public void invalidConfigurationFailsClearly(GameTestHelper test) {
        for (String json : List.of("null", "[]", "{\"enabled\":\"yes\"}",
                "{\"players\":{\"invalid-uuid\":{}}}", "{\"defaults\":{\"unknown\":{}}}",
                "{\"defaults\":{\"keepInventory\":{\"minuets\":5}}}",
                "{\"defaults\":{\"keepInventory\":{\"keep\":{\"slots\":[43]}}}}",
                "{\"defaults\":{\"keepExperience\":{\"ratio\":1.1}}}",
                "{\"defaults\":{\"preventDeath\":{\"uses\":-2}}}",
                "{\"defaults\":{\"preventDeath\":{\"uses\":1.5}}}",
                "{\"defaults\":{\"preventDeath\":{\"health\":0}}}",
                "{\"defaults\":{\"preventDeath\":{\"health\":-1}}}",
                "{\"defaults\":{\"preventDeath\":{\"health\":1e400}}}",
                "{\"defaults\":{\"preventDeath\":{\"health\":1e-100}}}",
                "{\"defaults\":{\"preventDeath\":{\"health\":true}}}",
                "{\"defaults\":{\"keepInventory\":{\"keep\":{\"slots\":[0.5]}}}}",
                "{\"defaults\":{\"protectionEffects\":{\"list\":[{\"id\":\"minecraft:speed\",\"durationSecond\":10}]}}}",
                "{\"defaults\":{\"protectionEffects\":{\"list\":[{\"id\":\"minecraft:unknown\"}]}}}",
                "{\"defaults\":{\"protectionEffects\":{\"list\":[{\"id\":\"minecraft:instant_health\"}]}}}",
                "{\"defaults\":{\"protectionEffects\":{\"list\":[{\"id\":\"minecraft:speed\",\"level\":0}]}}}")) {
            boolean rejected = false;
            try {
                ProtectionConfig.parse(JsonParser.parseString(json).getAsJsonObject());
            } catch (RuntimeException expected) {
                rejected = true;
            }
            test.assertTrue(rejected, "Invalid configuration rejected: " + json);
        }
        var window = configuration("{\"keepInventory\":{\"minutes\":1}}").defaultProfile().inventory().window();
        test.assertTrue(window.includes(1199), "Minutes use 1200 ticks");
        test.assertFalse(window.includes(1200), "Protection ends exactly at threshold");
        test.succeed();
    }
    @GameTest
    public void defaultConfigIsEditableAndInvalidFilesArePreserved(GameTestHelper test) throws Exception {
        Path directory = Files.createTempDirectory("gentlestart-config-test-");
        Path file = directory.resolve("gentle-start-mod.json");
        try {
            test.assertValueEqual(ProtectionConfig.load(file).document(), ProtectionConfig.defaults().document(), "Missing config uses defaults");
            String json = Files.readString(file);
            test.assertTrue(json.contains("minutes") && json.contains("minecraft:night_vision")
                    && json.contains("showParticles"), "Generated config includes editable defaults");
            test.assertValueEqual(ProtectionConfig.load(file).document(), ProtectionConfig.defaults().document(), "Generated config round trip");
            test.assertTrue(json.contains("//") && json.contains("keepInventory")
                    && json.contains("timeConditionChecksPerTick") && !json.contains("\"inventory\""),
                    "Generated file has comments and descriptive names");
            Files.writeString(file, "// custom comment\n{\"timeConditionChecksPerTick\":3,\"defaults\":{\"keepInventory\":{\"minutes\":45}},"
                    + "\"players\":{\"00000000-0000-0000-0000-000000000001\":{\"protectDeathDrops\":{\"lifetimeSeconds\":900}}}}");
            ProtectionConfig named = ProtectionConfig.load(file);
            test.assertValueEqual(named.timeConditionChecksPerTick(), 3, "Descriptive tick budget is read");
            test.assertValueEqual(named.defaultProfile().inventory().window().minutes(), 45.0, "Descriptive feature is read");
            test.assertValueEqual(named.profile(UUID.fromString("00000000-0000-0000-0000-000000000001")).deathDrops().lifetimeSeconds(),
                    900, "Descriptive player override is read");
            named.save(file);
            test.assertValueEqual(ProtectionConfig.load(file).document(), named.document(), "Commented save preserves overrides");
            test.assertFalse(named.document().getAsJsonObject("defaults").getAsJsonObject("protectionEffects")
                    .getAsJsonArray("list").get(0).getAsJsonObject().has("durationSeconds"),
                    "Sustained effects do not serialize an unused duration");
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
        withConfig(test, ProtectionConfig.defaults(), () -> {
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
        withConfig(test, ProtectionConfig.defaults(), () -> {
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

    static ProtectionConfig configuration(String defaults) {
        return ProtectionConfig.parse(JsonParser.parseString("{\"defaults\":" + defaults + "}").getAsJsonObject());
    }
    static ServerPlayer player(GameTestHelper test, int ticks) {
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

    static ServerPlayer respawn(ServerPlayer player) {
        player.connection.player = player;
        new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN).handle(player.connection);
        new ServerboundPlayerLoadedPacket().handle(player.connection);
        return player.connection.player;
    }

    static int drops(ServerPlayer player) {
        return player.level().getEntitiesOfClass(ItemEntity.class, new AABB(player.blockPosition()).inflate(8)).size();
    }

    static int orbs(ServerPlayer player) {
        return player.level().getEntitiesOfClass(ExperienceOrb.class, new AABB(player.blockPosition()).inflate(8)).size();
    }

    static void withConfig(GameTestHelper test, ProtectionConfig config, Runnable assertions) {
        ProtectionConfig previous = GentleStart.engine().config();
        boolean keepInventory = test.getLevel().getGameRules().get(GameRules.KEEP_INVENTORY);
        try {
            GentleStart.engine().replaceConfig(config);
            test.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, false, test.getLevel().getServer());
            assertions.run();
        } finally {
            GentleStart.engine().replaceConfig(previous);
            test.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, keepInventory, test.getLevel().getServer());
        }
    }
}
