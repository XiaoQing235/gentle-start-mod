package dev.gentlestart;

import static dev.gentlestart.ProtectionGameTests.player;
import static dev.gentlestart.ProtectionGameTests.respawn;
import static dev.gentlestart.ProtectionGameTests.withConfig;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.storage.LevelResource;

public class ExpandedProtectionGameTests {
    private static ProtectionEngine engine() {
        return GentleStart.engine();
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    static ProtectionConfig settings(String rules) {
        JsonObject root = ProtectionConfig.defaults().document();
        for (Feature feature : Feature.values()) {
            root.getAsJsonObject("defaults").getAsJsonObject(feature.id()).addProperty("minutes", 0);
        }
        root.add("defaults", ProtectionConfig.merge(root.getAsJsonObject("defaults"), json(rules)));
        return ProtectionConfig.parse(root);
    }

    @GameTest
    public void periodicChecksRotateWithinPlayerBudget(GameTestHelper test) {
        withConfig(test, settings("{\"inventory\":{\"minutes\":-1,\"cancel\":{\"playMinutes\":1}}}"), () -> {
            var players = List.of(player(test, 1200), player(test, 1200), player(test, 1200));
            engine().checkNextPlayers(List.of());
            engine().checkNextPlayers(players);
            test.assertTrue(engine().states().get(players.get(0).getUUID()).cancellation(Feature.INVENTORY) != null,
                    "First tick checks the first player");
            test.assertTrue(engine().states().get(players.get(1).getUUID()).cancellation(Feature.INVENTORY) == null,
                    "Default budget does not scan the second player on the same tick");
            JsonObject document = engine().config().document();
            document.addProperty("playersPerTick", 2);
            engine().replaceConfig(ProtectionConfig.parse(document));
            engine().checkNextPlayers(players);
            for (ServerPlayer player : players) {
                test.assertTrue(engine().states().get(player.getUUID()).cancellation(Feature.INVENTORY) != null,
                        "Runtime budget change continues the rotation");
                engine().states().reset(player.getUUID(), Feature.INVENTORY);
            }
            engine().checkNextPlayers(List.of(players.get(2)));
            test.assertTrue(engine().states().get(players.get(2).getUUID()).cancellation(Feature.INVENTORY) != null,
                    "Shrinking the player list wraps safely and checks remaining players");
            engine().checkNextPlayers(List.of());
            engine().checkNextPlayers(players);
            test.assertTrue(engine().states().get(players.get(0).getUUID()).cancellation(Feature.INVENTORY) != null,
                    "After an empty server the rotation restarts");
        });
        test.succeed();
    }

    @GameTest
    public void newNumericOptionsValidateAndInherit(GameTestHelper test) {
        test.assertValueEqual(ProtectionConfig.defaults().playersPerTick(), 1, "Default periodic budget is one player");
        test.assertValueEqual(ProtectionConfig.defaults().defaultProfile().deathDrops().lifetimeSeconds(), 600, "Default drop lifetime is 600 seconds");
        UUID id = UUID.randomUUID();
        ProtectionConfig config = settings("{\"deathDrops\":{\"minutes\":120,\"lifetimeSeconds\":42}}");
        config = config.withPlayer(id, json("{\"deathDrops\":{\"minutes\":-1}}"));
        test.assertValueEqual(config.profile(id).deathDrops().lifetimeSeconds(), 42, "Partial player override inherits lifetime");
        for (String invalid : List.of("{\"playersPerTick\":0}", "{\"playersPerTick\":1.5}",
                "{\"defaults\":{\"deathDrops\":{\"lifetimeSeconds\":0}}}",
                "{\"defaults\":{\"deathDrops\":{\"lifetimeSeconds\":1.5}}}",
                "{\"defaults\":{\"deathDrops\":{\"lifetimeSeconds\":86401}}}")) {
            boolean rejected = false;
            try {
                ProtectionConfig.parse(json(invalid));
            } catch (IllegalArgumentException | IllegalStateException expected) {
                rejected = true;
            }
            test.assertTrue(rejected, "Invalid numeric options are rejected: " + invalid);
        }
        test.succeed();
    }

    @GameTest
    public void eventChecksDoNotPollUnrelatedConditions(GameTestHelper test) {
        withConfig(test, settings("{\"inventory\":{\"minutes\":-1,\"cancel\":{\"experienceLevel\":5}},"
                + "\"pvp\":{\"minutes\":-1,\"cancel\":{\"items\":[\"minecraft:diamond\"]}}}"), () -> {
            ServerPlayer player = player(test, 0);
            // Deliberately bypass the vanilla setter to prove unrelated events and ticks no longer poll levels.
            player.experienceLevel = 5;
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
            test.assertTrue(engine().states().get(player.getUUID()).cancellation(Feature.PVP) != null,
                    "Inventory mutation immediately records its own condition without active() or a tick");
            engine().checkNextPlayers(List.of(player));
            test.assertTrue(engine().active(player, Feature.INVENTORY), "Time checks and hot-path protection reads do not scan level state");
            player.setExperienceLevels(5);
            test.assertTrue(engine().states().get(player.getUUID()).cancellation(Feature.INVENTORY) != null,
                    "Vanilla level event immediately cancels level-dependent protection");
        });
        test.succeed();
    }

    @GameTest
    public void equipmentChangesImmediatelyObserveItemConditions(GameTestHelper test) {
        withConfig(test, settings("{\"inventory\":{\"minutes\":-1,\"cancel\":{\"items\":[\"minecraft:diamond_helmet\"]}}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            test.assertTrue(engine().states().get(player.getUUID()).cancellation(Feature.INVENTORY) != null,
                    "Equipment API triggers cancellation without a periodic scan or active() call");
        });
        test.succeed();
    }

    @GameTest
    public void configurationAndResetRecheckExistingConditionsImmediately(GameTestHelper test) {
        withConfig(test, settings("{}"), () -> {
            ServerPlayer player = player(test, 0);
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
            engine().replaceConfig(settings("{\"inventory\":{\"minutes\":-1,\"cancel\":{\"items\":[\"minecraft:diamond\"]}}}"));
            test.assertTrue(engine().states().get(player.getUUID()).cancellation(Feature.INVENTORY) != null,
                    "Installing a new rule sees items acquired before that rule existed");
            var console = test.getLevel().getServer().createCommandSourceStack();
            execute(test, "gentlestart admin player " + player.getUUID() + " reset inventory", console);
            test.assertTrue(engine().states().get(player.getUUID()).cancellation(Feature.INVENTORY) != null,
                    "Reset immediately reevaluates a currently held item");
            player.getInventory().clearContent();
            execute(test, "gentlestart admin player " + player.getUUID() + " reset inventory", console);
            test.assertTrue(engine().states().get(player.getUUID()).cancellation(Feature.INVENTORY) == null,
                    "After removing the item a reset clears historical cancellation");
            player.experienceLevel = 7;
            JsonObject updated = engine().config().document();
            updated.getAsJsonObject("defaults").getAsJsonObject("inventory").add("cancel", json("{\"experienceLevel\":7}"));
            ProtectionConfig.parse(updated).save(configFile());
            engine().reload();
            test.assertTrue(engine().states().get(player.getUUID()).cancellation(Feature.INVENTORY) != null,
                    "File reload rechecks online players without waiting for another event");
        });
        test.succeed();
    }

    @GameTest
    public void uuidRulesInheritAndEachFeatureHasItsOwnClock(GameTestHelper test) {
        withConfig(test, settings("{\"inventory\":{\"minutes\":1},\"experience\":{\"minutes\":2},\"score\":{\"minutes\":-3}}"), () -> {
            ServerPlayer first = player(test, 1200);
            ServerPlayer second = player(test, 1200);
            engine().replaceConfig(engine().config().withPlayer(first.getUUID(), json("{\"inventory\":{\"minutes\":-1}}")));
            test.assertTrue(engine().active(first, Feature.INVENTORY), "UUID override grants permanent inventory protection");
            test.assertFalse(engine().active(second, Feature.INVENTORY), "Another UUID still uses the default cutoff");
            test.assertTrue(engine().active(first, Feature.EXPERIENCE), "Unspecified protection inherits defaults");
            first.getStats().setValue(first, Stats.CUSTOM.get(Stats.PLAY_TIME), Integer.MAX_VALUE);
            test.assertTrue(engine().active(first, Feature.INVENTORY), "Negative durations remain valid after long play time");
            test.assertTrue(engine().active(first, Feature.SCORE), "Any negative duration means permanent");
            test.assertFalse(engine().active(first, Feature.EXPERIENCE), "Experience can expire independently");
            test.assertFalse(engine().active(first, Feature.PVP), "Zero disables a feature");
        });
        test.succeed();
    }

    @GameTest
    public void selectiveInventoryKeepsCategoriesTagsItemsAndSlots(GameTestHelper test) {
        withConfig(test, settings("{\"inventory\":{\"minutes\":-1,\"keep\":{\"all\":false,\"categories\":[\"hotbar\",\"offhand\"],"
                + "\"items\":[\"minecraft:diamond\"],\"tags\":[\"minecraft:logs\"],\"slots\":[20]}}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            player.getInventory().setItem(10, new ItemStack(Items.DIAMOND, 3));
            player.getInventory().setItem(11, new ItemStack(Items.OAK_LOG, 4));
            player.getInventory().setItem(12, new ItemStack(Items.DIRT, 5));
            player.getInventory().setItem(20, new ItemStack(Items.EMERALD, 6));
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
            helmet.enchant(test.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.VANISHING_CURSE), 1);
            player.setItemSlot(EquipmentSlot.HEAD, helmet);
            player.kill(test.getLevel());
            ServerPlayer next = respawn(player);
            for (int slot : new int[]{0, 10, 11, 20, 40}) {
                test.assertFalse(next.getInventory().getItem(slot).isEmpty(), "Selected slot retained: " + slot);
            }
            test.assertTrue(next.getInventory().getItem(12).isEmpty(), "Unselected dirt is not duplicated");
            test.assertTrue(next.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "Unselected vanishing item follows vanilla");
            test.assertValueEqual(next.getInventory().countItem(Items.DIAMOND), 3, "Exact selected stack count");
        });
        test.succeed();
    }

    @GameTest
    public void itemCategoriesRetainMatchingItemsInOrdinaryBackpackSlots(GameTestHelper test) {
        String[] categories = {"weapons", "tools", "wearable", "food", "blocks"};
        var items = List.of(Items.BOW, Items.IRON_PICKAXE, Items.DIAMOND_HELMET, Items.BREAD, Items.DIRT);
        for (int index = 0; index < categories.length; index++) {
            int category = index;
            withConfig(test, settings("{\"inventory\":{\"minutes\":-1,\"keep\":{\"all\":false,\"categories\":[\""
                    + categories[index] + "\"]}}}"), () -> {
                ServerPlayer player = player(test, 0);
                player.getInventory().setItem(15, new ItemStack(items.get(category)));
                player.getInventory().setItem(16, new ItemStack(Items.COAL, 7));
                player.kill(test.getLevel());
                ServerPlayer next = respawn(player);
                test.assertTrue(next.getInventory().getItem(15).is(items.get(category)),
                        "Item category retained a matching backpack item: " + categories[category]);
                test.assertTrue(next.getInventory().getItem(16).isEmpty(), "Item category does not retain unrelated coal");
            });
        }
        test.succeed();
    }

    @GameTest
    public void experienceRatioUsesSpendablePointsAndScoreIsIndependent(GameTestHelper test) {
        withConfig(test, settings("{\"experience\":{\"minutes\":-1,\"ratio\":0.5}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.experienceLevel = 10;
            player.experienceProgress = 0;
            player.totalExperience = 9999; // Enchanting may leave this different from spendable XP.
            player.setScore(777);
            player.kill(test.getLevel());
            ServerPlayer next = respawn(player);
            test.assertValueEqual(next.totalExperience, 80, "Half of the spendable 160 XP is retained");
            test.assertValueEqual(next.getScore(), 0, "XP restoration does not accidentally restore score");
        });
        withConfig(test, settings("{\"score\":{\"minutes\":-1}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.experienceLevel = 10;
            player.setScore(123);
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
            player.kill(test.getLevel());
            ServerPlayer next = respawn(player);
            test.assertValueEqual(next.getScore(), 123, "Score can be retained independently");
            test.assertValueEqual(next.experienceLevel, 0, "Independent score does not restore XP");
            test.assertTrue(next.getInventory().getItem(0).isEmpty(), "Independent score does not restore items");
        });
        test.succeed();
    }

    @GameTest
    public void directReviveRunsAfterTotemsAndConsumesFiniteCharges(GameTestHelper test) {
        withConfig(test, settings("{\"revive\":{\"minutes\":-1,\"uses\":1,"
                + "\"effects\":[{\"id\":\"minecraft:fire_resistance\",\"durationSeconds\":8}]}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertTrue(player.isAlive(), "Held totem protects first");
            test.assertValueEqual(engine().states().get(player.getUUID()).revivesUsed(), 0, "A totem does not consume a mod charge");
            test.assertTrue(player.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty(), "Vanilla totem is consumed");
            player.removeAllEffects();
            player.invulnerableTime = 0;
            var position = player.position();
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertTrue(player.isAlive(), "Mod prevents actual death");
            test.assertValueEqual(player.position(), position, "Direct revive stays at the same location");
            test.assertValueEqual(player.getHealth(), 10.0F, "Direct revive restores ten health");
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(player), 0L, "Revive does not grant immunity by itself");
            test.assertValueEqual(engine().states().get(player.getUUID()).revivesUsed(), 1, "Exactly one charge consumed");
            test.assertTrue(player.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE), "Revive effect applied");
            player.invulnerableTime = 0;
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertTrue(player.isDeadOrDying(), "Exhausted charges allow normal death");
            PlayerStates reloaded = new PlayerStates(test.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("data/gentle-start-mod-state.json"));
            test.assertValueEqual(reloaded.get(player.getUUID()).revivesUsed(), 1, "Charge usage persists to disk");
        });
        test.succeed();
    }

    @GameTest(maxTicks = 80)
    public void reviveInvulnerabilityExpires(GameTestHelper test) {
        ProtectionConfig config = settings("{\"revive\":{\"minutes\":-1,\"uses\":2},"
                + "\"respawnProtection\":{\"minutes\":-1,\"durationSeconds\":1}}");
        ServerPlayer[] saved = new ServerPlayer[1];
        withConfig(test, config, () -> {
            ServerPlayer player = player(test, 0);
            saved[0] = player;
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            player.invulnerableTime = 0;
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertValueEqual(engine().states().get(player.getUUID()).revivesUsed(), 1, "Damage during invulnerability consumes no extra charge");
        });
        test.runAfterDelay(22, () -> withConfig(test, config, () -> {
            ServerPlayer player = saved[0];
            player.invulnerableTime = 0;
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertValueEqual(engine().states().get(player.getUUID()).revivesUsed(), 2, "After expiry lethal damage uses the next charge");
            test.succeed();
        }));
    }

    @GameTest
    public void administrativeKillAndVoidBypassDamageProtection(GameTestHelper test) {
        withConfig(test, settings("{\"revive\":{\"minutes\":-1,\"uses\":-1},\"damageReduction\":{\"minutes\":-1,\"reduction\":1}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.kill(test.getLevel());
            test.assertTrue(player.isDeadOrDying(), "Administrative kill bypasses protections");
            test.assertValueEqual(engine().states().get(player.getUUID()).revivesUsed(), 0, "No charge consumed by bypass damage");
            ServerPlayer other = player(test, 0);
            other.hurtServer(test.getLevel(), other.damageSources().fellOutOfWorld(), 1000);
            test.assertTrue(other.isDeadOrDying(), "Vanilla void bypass is respected");
        });
        test.succeed();
    }

    @GameTest
    public void pvpBlocksIncomingAndOffensePermanentlyEndsOnlyPvp(GameTestHelper test) {
        withConfig(test, settings("{\"pvp\":{\"minutes\":-1,\"removeOnAttack\":true},\"inventory\":{\"minutes\":-1}}"), () -> {
            ServerPlayer first = player(test, 0);
            ServerPlayer second = player(test, 0);
            first.removeAllEffects();
            second.removeAllEffects();
            first.hurtServer(test.getLevel(), first.damageSources().playerAttack(second), 6);
            test.assertValueEqual(first.getHealth(), 20.0F, "Protected target rejects incoming player damage");
            test.assertFalse(engine().active(second, Feature.PVP), "Offensive attempt ends attacker's PvP protection");
            test.assertTrue(engine().active(second, Feature.INVENTORY), "Offense leaves other protections intact");
            first.hurtServer(test.getLevel(), first.damageSources().playerAttack(second), 6);
            test.assertValueEqual(first.getHealth(), 20.0F, "An unprotected attacker still cannot hurt a protected target");
            engine().states().cancel(first.getUUID(), Feature.PVP, "test");
            first.invulnerableTime = 0;
            first.hurtServer(test.getLevel(), first.damageSources().playerAttack(second), 6);
            test.assertTrue(first.getHealth() < 20, "Two unprotected players use vanilla damage");
        });
        test.succeed();
    }

    @GameTest
    public void pvpCoversProjectilesAndProtectedAttackersCannotExploitShield(GameTestHelper test) {
        withConfig(test, settings("{\"pvp\":{\"minutes\":-1,\"removeOnAttack\":false}}"), () -> {
            ServerPlayer attacker = player(test, 0);
            ServerPlayer target = player(test, 0);
            engine().replaceConfig(engine().config().withPlayer(target.getUUID(), json("{\"pvp\":{\"minutes\":0}}")));
            Arrow arrow = new Arrow(test.getLevel(), attacker, new ItemStack(Items.ARROW), null);
            target.hurtServer(test.getLevel(), target.damageSources().arrow(arrow, attacker), 6);
            test.assertValueEqual(target.getHealth(), 20.0F, "Protected attacker cannot deal projectile damage");
            test.assertTrue(engine().active(attacker, Feature.PVP), "Blocking offense does not remove protection when configured false");
        });
        test.succeed();
    }

    @GameTest
    public void damageReductionScalesIncomingDamage(GameTestHelper test) {
        withConfig(test, settings("{\"damageReduction\":{\"minutes\":-1,\"reduction\":0.5}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.removeAllEffects();
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 8);
            test.assertValueEqual(player.getHealth(), 16.0F, "Half of incoming damage is removed");
            engine().replaceConfig(engine().config().withPlayer(player.getUUID(), json("{\"damageReduction\":{\"reduction\":1}}")));
            player.invulnerableTime = 0;
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 8);
            test.assertValueEqual(player.getHealth(), 16.0F, "100 percent reduction fully rejects damage");
        });
        test.succeed();
    }

    @GameTest
    public void mobRangeAffectsDetectionAndExistingTargets(GameTestHelper test) {
        withConfig(test, settings("{\"mobRange\":{\"minutes\":-1,\"multiplier\":0.5}}"), () -> {
            ServerPlayer player = player(test, 0);
            Zombie mob = new Zombie(EntityTypes.ZOMBIE, test.getLevel());
            mob.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(16);
            mob.snapTo(player.position().add(12, 0, 0));
            var conditions = TargetingConditions.forCombat().range(16).ignoreLineOfSight();
            test.assertFalse(conditions.test(test.getLevel(), mob, player), "Protected player is outside reduced discovery range");
            mob.setTarget(player);
            test.assertTrue(mob.getTarget() == null, "An existing target is invalidated outside the radius");
            mob.snapTo(player.position().add(4, 0, 0));
            test.assertTrue(conditions.test(test.getLevel(), mob, player), "Nearby monsters can still discover the player");
            mob.setTarget(player);
            test.assertTrue(mob.getTarget() == player, "Nearby existing target remains valid");
            mob.snapTo(player.position().add(12, 0, 0));
            mob.setLastHurtByMob(player);
            test.assertTrue(conditions.test(test.getLevel(), mob, player), "Attacking a monster still permits its retaliation");
        });
        test.succeed();
    }

    @GameTest
    public void itemAndLevelCancellationStayCancelledAfterLosingProgress(GameTestHelper test) {
        withConfig(test, settings("{\"inventory\":{\"minutes\":-1,\"cancel\":{\"items\":[\"minecraft:diamond\"]}},"
                + "\"experience\":{\"minutes\":-1,\"cancel\":{\"experienceLevel\":10}}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.getInventory().clearContent();
            test.assertFalse(engine().active(player, Feature.INVENTORY), "Obtaining then discarding the item still ends protection");
            player.setExperienceLevels(10);
            player.setExperienceLevels(0);
            test.assertFalse(engine().active(player, Feature.EXPERIENCE), "Spending XP does not regain protection");
            PlayerStates reloaded = new PlayerStates(test.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("data/gentle-start-mod-state.json"));
            test.assertTrue(reloaded.get(player.getUUID()).cancellation(Feature.INVENTORY) != null, "Cancellation persists to disk");
        });
        test.succeed();
    }

    @GameTest
    public void advancementCancellationAndAllConditionsUsePersistentHistory(GameTestHelper test) {
        withConfig(test, settings("{\"inventory\":{\"minutes\":-1,\"cancel\":{\"advancements\":[\"minecraft:story/mine_stone\"]}},"
                + "\"pvp\":{\"minutes\":-1,\"cancel\":{\"requireAll\":true,\"items\":[\"minecraft:diamond\"],\"experienceLevel\":5}}}"), () -> {
            ServerPlayer player = player(test, 0);
            var advancement = test.getLevel().getServer().getAdvancements().get(net.minecraft.resources.Identifier.parse("minecraft:story/mine_stone"));
            for (String criterion : advancement.value().criteria().keySet()) {
                player.getAdvancements().award(advancement, criterion);
            }
            test.assertFalse(engine().active(player, Feature.INVENTORY), "Completing an advancement ends the configured feature");
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.getInventory().clearContent();
            test.assertTrue(engine().active(player, Feature.PVP), "Require-all waits for the other condition");
            player.setExperienceLevels(5);
            test.assertFalse(engine().active(player, Feature.PVP), "Historical acquisition combines with a later level milestone");
        });
        test.succeed();
    }

    @GameTest
    public void playtimeCancellationAppliesToPermanentFeatures(GameTestHelper test) {
        withConfig(test, settings("{\"inventory\":{\"minutes\":-1,\"cancel\":{\"playMinutes\":1}}}"), () -> {
            ServerPlayer player = player(test, 1199);
            test.assertTrue(engine().active(player, Feature.INVENTORY), "Before the cancellation threshold");
            player.getStats().setValue(player, Stats.CUSTOM.get(Stats.PLAY_TIME), 1200);
            test.assertFalse(engine().active(player, Feature.INVENTORY), "Automatic cancellation can end a permanent duration");
        });
        test.succeed();
    }

    @GameTest
    public void administratorCommandsPersistOfflineUuidRulesAndEnforcePermissions(GameTestHelper test) {
        withConfig(test, settings("{}"), () -> {
            UUID id = UUID.randomUUID();
            var console = test.getLevel().getServer().createCommandSourceStack();
            execute(test, "gentlestart admin player " + id + " set inventory -1", console);
            execute(test, "gentlestart admin player " + id + " rule experience {\"minutes\":30,\"ratio\":0.5}", console);
            test.assertValueEqual(engine().config().profile(id).inventory().window().minutes(), -1.0, "Offline UUID duration saved");
            test.assertValueEqual(ProtectionConfig.load(configFile()).profile(id).experience().ratio(), 0.5, "Edited rule is on disk");
            execute(test, "gentlestart admin player " + id + " cancel inventory", console);
            test.assertTrue(engine().states().get(id).cancellation(Feature.INVENTORY) != null, "Admin cancellation saved");
            execute(test, "gentlestart admin player " + id + " reset inventory", console);
            test.assertTrue(engine().states().get(id).cancellation(Feature.INVENTORY) == null, "Admin reset clears the chosen feature");
            execute(test, "gentlestart admin player " + id + " clear experience", console);
            test.assertValueEqual(engine().config().profile(id).experience().window().minutes(), 0.0, "Clearing a rule resumes default inheritance");
            ServerPlayer ordinary = player(test, 0);
            boolean denied = false;
            try {
                test.getLevel().getServer().getCommands().getDispatcher().execute("gentlestart admin enabled false", ordinary.createCommandSourceStack());
            } catch (CommandSyntaxException expected) {
                denied = true;
            }
            test.assertTrue(denied, "Ordinary players cannot invoke administrative commands");
            test.assertTrue(engine().config().enabled(), "Denied command leaves config intact");
            execute(test, "gentlestart status", ordinary.createCommandSourceStack());
        });
        test.succeed();
    }

    @GameTest
    public void failedReloadRetainsLiveConfigAndInvalidFile(GameTestHelper test) {
        withConfig(test, settings("{\"inventory\":{\"minutes\":15}}"), () -> {
            ProtectionConfig previous = engine().config();
            try {
                Files.writeString(configFile(), "{broken");
                boolean failed = false;
                try {
                    engine().reload();
                } catch (IllegalStateException expected) {
                    failed = true;
                }
                test.assertTrue(failed, "Invalid reload fails");
                test.assertTrue(engine().config() == previous, "Old configuration remains active");
                test.assertValueEqual(Files.readString(configFile()), "{broken", "Invalid file is not silently replaced");
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        });
        test.succeed();
    }

    @GameTest
    public void grantUsesCurrentPlaytimeAndRuleErrorsAreAtomic(GameTestHelper test) {
        withConfig(test, settings("{}"), () -> {
            ServerPlayer player = player(test, 12000);
            var console = test.getLevel().getServer().createCommandSourceStack();
            String prefix = "gentlestart admin player " + player.getUUID();
            execute(test, prefix + " grant inventory 5", console);
            test.assertValueEqual(engine().profile(player).inventory().window().minutes(), 15.0,
                    "Grant adds minutes to current playtime, rather than restarting the player's statistic");
            test.assertValueEqual(ProtectionEngine.playedTicks(player), 12000L, "Grant never rewrites vanilla play time");
            JsonObject before = engine().config().document();
            for (String invalid : List.of("{broken", "{\"minutes\":3,\"keep\":{\"tags\":[\"minecraft:unknown_tag\"]}}")) {
                boolean failed = false;
                try {
                    test.getLevel().getServer().getCommands().getDispatcher().execute(prefix + " rule inventory " + invalid, console);
                } catch (CommandSyntaxException expected) {
                    failed = true;
                }
                test.assertTrue(failed, "Malformed or unresolvable rule fails as a command error");
                test.assertValueEqual(engine().config().document(), before, "Failed rule leaves current config intact");
            }
            execute(test, "gentlestart admin lookup " + player.getGameProfile().name(), console);
            UUID copy = UUID.randomUUID();
            execute(test, "gentlestart admin copy " + player.getUUID() + " " + copy, console);
            test.assertValueEqual(engine().config().profile(copy), engine().profile(player), "Copy transfers the effective profile");
        });
        test.succeed();
    }

    @GameTest
    public void itemTagCancellationAndConfigurationChangesDoNotRewritePendingDeaths(GameTestHelper test) {
        withConfig(test, settings("{\"inventory\":{\"minutes\":-1},\"experience\":{\"minutes\":-1,\"ratio\":0.5},"
                + "\"pvp\":{\"minutes\":-1,\"cancel\":{\"itemTags\":[\"minecraft:logs\"]}}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
            player.experienceLevel = 10;
            player.totalExperience = 160;
            player.experienceProgress = 0;
            player.getInventory().add(new ItemStack(Items.OAK_LOG));
            test.assertFalse(engine().active(player, Feature.PVP), "An acquired matching tag triggers cancellation");
            player.kill(test.getLevel());
            engine().replaceConfig(settings("{}"));
            ServerPlayer next = respawn(player);
            test.assertValueEqual(next.getInventory().countItem(Items.DIAMOND), 2, "Pending death retains its inventory decision");
            test.assertValueEqual(next.totalExperience, 80, "Pending death retains its XP ratio after config changes");
        });
        test.succeed();
    }

    @GameTest
    public void partialProfilesAndUuidCaseKeepDefaultInheritance(GameTestHelper test) {
        UUID id = UUID.randomUUID();
        JsonObject root = json("{\"defaults\":{\"inventory\":{\"keep\":{\"all\":false,\"categories\":[\"armor\"]}}},\"players\":{}}");
        root.getAsJsonObject("players").add(id.toString().toUpperCase(java.util.Locale.ROOT), json("{\"experience\":{\"ratio\":0.25}}"));
        ProtectionConfig config = ProtectionConfig.parse(root);
        test.assertValueEqual(config.profile(id).inventory().window().minutes(), 120.0, "Partial default rule inherits the duration");
        test.assertValueEqual(config.profile(id).experience().window().minutes(), 120.0, "Partial UUID rule inherits its duration");
        test.assertTrue(config.document().getAsJsonObject("players").has(id.toString()), "UUID keys are canonicalized");
        config = config.withPlayer(id, json("{\"score\":{\"minutes\":-1}}"));
        test.assertValueEqual(config.configuredPlayers().size(), 1, "Editing a canonical UUID does not create a duplicate");
        test.succeed();
    }

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("gentle-start-mod.json");
    }

    private static void execute(GameTestHelper test, String command, CommandSourceStack source) {
        try {
            test.assertTrue(test.getLevel().getServer().getCommands().getDispatcher().execute(command, source) > 0,
                    "Command succeeds: " + command);
        } catch (CommandSyntaxException exception) {
            throw new AssertionError(exception);
        }
    }
}
