package dev.gentlestart;

import static dev.gentlestart.ExpandedProtectionGameTests.settings;
import static dev.gentlestart.ProtectionGameTests.player;
import static dev.gentlestart.ProtectionGameTests.respawn;
import static dev.gentlestart.ProtectionGameTests.withConfig;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class RecoveryProtectionGameTests {
    private static ProtectionEngine engine() {
        return GentleStart.engine();
    }

    @GameTest
    public void lethalInterceptionLeavesFullInventoryAndOpenContainerUntouched(GameTestHelper test) {
        withConfig(test, settings("{\"revive\":{\"minutes\":-1,\"uses\":1,\"invulnerabilitySeconds\":3}}"), () -> {
            ServerPlayer player = player(test, 0);
            for (int slot = 0; slot < 36; slot++) {
                player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            var chest = new net.minecraft.world.SimpleContainer(27);
            chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 11));
            var menu = net.minecraft.world.inventory.ChestMenu.threeRows(1, player.getInventory(), chest);
            player.containerMenu = menu;
            menu.setCarried(new ItemStack(Items.DIAMOND, 5));
            int drops = ProtectionGameTests.drops(player);
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertTrue(player.isAlive() && player.containerMenu == menu, "Lethal interception does not close or replace the menu");
            test.assertValueEqual(menu.getCarried().getCount(), 5, "Full inventory cursor remains on the cursor");
            test.assertValueEqual(chest.getItem(0).getCount(), 11, "Chest contents are untouched");
            test.assertValueEqual(player.getInventory().countItem(Items.COBBLESTONE), 36 * 64, "Inventory remains intact");
            test.assertValueEqual(ProtectionGameTests.drops(player), drops, "No overflow or death drops are created");
            test.assertValueEqual(((DeathProtection) player).gentleStart$death(), DeathSnapshot.NONE, "die() was not entered");
            test.assertValueEqual(engine().reviveInvulnerabilityTicks(player), 60L, "Independent revive timer is granted");
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(player), 0L, "No respawn timer is granted");
            player.invulnerableTime = 0;
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertValueEqual(engine().states().get(player.getUUID()).revivesUsed(), 1, "Consecutive damage consumes no extra charge");
            test.assertValueEqual(player.getHealth(), 10.0F, "Consecutive damage during immunity leaves health intact");
        });
        test.succeed();
    }

    @GameTest
    public void reviveChecksFinalDamageAfterReductionAndAbsorption(GameTestHelper test) {
        withConfig(test, settings("{\"revive\":{\"minutes\":-1,\"invulnerabilitySeconds\":0},"
                + "\"damageReduction\":{\"minutes\":-1,\"reduction\":0.5}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.setHealth(10);
            player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_ABSORPTION).setBaseValue(10);
            player.setAbsorptionAmount(10);
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 30);
            test.assertValueEqual(player.getHealth(), 5.0F, "Reduction then absorption make nominally lethal damage survivable");
            test.assertValueEqual(engine().states().get(player.getUUID()).revivesUsed(), 0, "Survivable damage consumes no charge");
            player.invulnerableTime = 0;
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 10);
            test.assertValueEqual(engine().states().get(player.getUUID()).revivesUsed(), 1, "Exactly lethal final damage consumes one charge");
            test.assertValueEqual(player.getHealth(), 10.0F, "Exactly lethal damage restores configured health");
            test.assertValueEqual(engine().reviveInvulnerabilityTicks(player), 0L, "Zero seconds grants no custom immunity");
        });
        test.succeed();
    }

    @GameTest
    public void reviveTimerPersistsAndOldStateFilesRemainReadable(GameTestHelper test) throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("gentlestart-revive-state-");
        java.nio.file.Path file = root.resolve("state.json");
        try {
            java.nio.file.Files.writeString(file, "{}");
            PlayerStates states = new PlayerStates(file);
            var id = java.util.UUID.randomUUID();
            states.revive(id, 120);
            states.protectRespawn(id, 240);
            test.assertTrue(states.save(), "Explicit save flushes pending state");
            var loaded = new PlayerStates(file).get(id);
            test.assertValueEqual(loaded.revivesUsed(), 1, "Charge usage persists");
            test.assertValueEqual(loaded.reviveInvulnerableUntil(), 120L, "Revive deadline persists independently");
            test.assertValueEqual(loaded.invulnerableUntil(), 240L, "Respawn deadline is unchanged");
            var playerFile = StatePersistenceGameTests.playerFile(file, id);
            var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(playerFile)).getAsJsonObject();
            json.remove("reviveInvulnerableUntil");
            java.nio.file.Files.writeString(playerFile, json.toString());
            loaded = new PlayerStates(file).get(id);
            test.assertValueEqual(loaded.reviveInvulnerableUntil(), 0L, "Old state grants no new revive immunity");
            test.assertValueEqual(loaded.invulnerableUntil(), 240L, "Old respawn deadline is preserved");
        } finally {
            StatePersistenceGameTests.delete(root);
        }
        test.succeed();
    }

    @GameTest
    public void reviveImmunityValidatesInheritsAndCancelsByItsOwnCondition(GameTestHelper test) {
        for (String invalid : new String[]{"-1", "1.5", "86401", "null", "\"5\""}) {
            boolean rejected = false;
            try {
                settings("{\"revive\":{\"invulnerabilitySeconds\":" + invalid + "}}");
            } catch (IllegalArgumentException | IllegalStateException expected) {
                rejected = true;
            }
            test.assertTrue(rejected, "Invalid immunity duration is rejected: " + invalid);
        }
        withConfig(test, settings("{\"revive\":{\"minutes\":-1,\"invulnerabilitySeconds\":7,"
                + "\"cancel\":{\"experienceLevel\":2}}}"), () -> {
            ServerPlayer player = player(test, 0);
            var override = com.google.gson.JsonParser.parseString("{\"preventDeath\":{\"health\":6}}").getAsJsonObject();
            engine().replaceConfig(engine().config().withPlayer(player.getUUID(), override));
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertValueEqual(engine().reviveInvulnerabilityTicks(player), 140L, "Partial UUID override inherits immunity duration");
            player.giveExperienceLevels(2);
            test.assertValueEqual(engine().reviveInvulnerabilityTicks(player), 0L, "Revive condition cancels its own immunity");
            player.invulnerableTime = 0;
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertTrue(player.isDeadOrDying(), "Cancelled revive allows ordinary death");
        });
        test.succeed();
    }

    @GameTest
    public void realDeathEndsReviveImmunityAndRespawnUsesOnlyItsOwnDuration(GameTestHelper test) {
        withConfig(test, settings("{\"revive\":{\"minutes\":-1,\"invulnerabilitySeconds\":9},"
                + "\"respawnProtection\":{\"minutes\":-1,\"durationSeconds\":2}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertValueEqual(engine().reviveInvulnerabilityTicks(player), 180L, "Revive starts its own duration");
            for (DamageSource source : new DamageSource[]{player.damageSources().generic(), player.damageSources().fall(),
                    player.damageSources().inFire(), player.damageSources().lava(), player.damageSources().starve(),
                    player.damageSources().magic()}) {
                player.invulnerableTime = 0;
                player.hurtServer(test.getLevel(), source, 1000);
                test.assertValueEqual(player.getHealth(), 10.0F, "Revive immunity rejects ordinary damage: " + source);
            }
            player.hurtServer(test.getLevel(), player.damageSources().fellOutOfWorld(), 1000);
            test.assertTrue(player.isDeadOrDying(), "Void bypasses revive immunity");
            test.assertValueEqual(engine().states().get(player.getUUID()).revivesUsed(), 1, "Bypass consumes no additional charge");
            test.assertValueEqual(engine().reviveInvulnerabilityTicks(player), 0L, "Actual death clears revive immunity");
            ServerPlayer next = respawn(player);
            test.assertValueEqual(engine().reviveInvulnerabilityTicks(next), 0L, "Old revive immunity cannot leak across respawn");
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(next), 40L, "Respawn uses its own shorter duration");
        });
        test.succeed();
    }

    @GameTest
    public void configuredReviveHealthKeepsTheSameLivingPlayerWithoutDeath(GameTestHelper test) {
        withConfig(test, settings("{\"revive\":{\"minutes\":-1,\"uses\":1,\"health\":6.5}}"), () -> {
            for (float health : new float[]{6.5F, 40.0F, 0.5F}) {
                ServerPlayer player = player(test, 0);
                if (health != 6.5F) {
                    var override = com.google.gson.JsonParser.parseString("{\"revive\":{\"health\":" + health + "}}").getAsJsonObject();
                    engine().replaceConfig(engine().config().withPlayer(player.getUUID(), override));
                }
                player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                player.giveExperiencePoints(55);
                int experience = player.totalExperience;
                int deaths = player.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));
                int dropped = ProtectionGameTests.drops(player);
                int orbs = ProtectionGameTests.orbs(player);
                int entityId = player.getId();
                var position = player.position();
                player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
                test.assertValueEqual(player.getHealth(), Math.min(health, player.getMaxHealth()),
                        "Configured health inherits, overrides by UUID, and clamps to maximum health");
                test.assertTrue(player.isAlive() && !player.isRemoved(), "The original player stays alive");
                test.assertTrue(test.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) == player,
                        "Revive does not replace the player through respawn");
                test.assertValueEqual(player.getId(), entityId, "Entity identity is unchanged");
                test.assertValueEqual(player.position(), position, "Revive does not move the player");
                test.assertValueEqual(player.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)), deaths, "Death statistics do not increase");
                test.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 3, "Inventory stays with the living player");
                test.assertValueEqual(player.totalExperience, experience, "Experience is not lost to death");
                test.assertValueEqual(ProtectionGameTests.drops(player), dropped, "No death item drops occur");
                test.assertValueEqual(ProtectionGameTests.orbs(player), orbs, "No death XP drops occur");
                test.assertValueEqual(((DeathProtection) player).gentleStart$death(), DeathSnapshot.NONE,
                        "Death processing never captures a retention snapshot");
            }
        });
        test.succeed();
    }

    @GameTest
    public void normalDeathRespawnGrantsImmunityWithoutRevive(GameTestHelper test) {
        withConfig(test, settings("{\"respawnProtection\":{\"minutes\":-1,\"durationSeconds\":5}}"), () -> {
            ServerPlayer previous = player(test, 0);
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(previous), 0L, "Joining does not grant respawn immunity");
            previous.kill(test.getLevel());
            ServerPlayer next = respawn(previous);
            test.assertValueEqual(engine().states().get(next.getUUID()).revivesUsed(), 0, "Ordinary respawn does not consume revive charges");
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(next), 100L, "Actual respawn grants its own window");
            for (DamageSource source : new DamageSource[]{next.damageSources().generic(), next.damageSources().fall(),
                    next.damageSources().inFire(), next.damageSources().lava(), next.damageSources().starve(),
                    next.damageSources().magic(), next.damageSources().playerAttack(player(test, 0))}) {
                test.assertFalse(engine().allowDamage(next, source, 2), "Immunity rejects ordinary nonlethal damage: " + source);
                next.invulnerableTime = 0;
                next.hurtServer(test.getLevel(), source, 2);
                test.assertValueEqual(next.getHealth(), 20.0F, "Ordinary nonlethal damage leaves health intact");
            }
            next.kill(test.getLevel());
            test.assertTrue(next.isDeadOrDying(), "Administrative kill bypasses respawn immunity");
            ServerPlayer again = respawn(next);
            again.hurtServer(test.getLevel(), again.damageSources().fellOutOfWorld(), 1000);
            test.assertTrue(again.isDeadOrDying(), "Void bypass remains effective during the window");
        });
        test.succeed();
    }

    @GameTest
    public void immunityAndReviveCanBeCancelledAndResetIndependently(GameTestHelper test) {
        withConfig(test, settings("{\"revive\":{\"minutes\":-1,\"invulnerabilitySeconds\":2},"
                + "\"respawnProtection\":{\"minutes\":-1,\"durationSeconds\":5}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertValueEqual(engine().reviveInvulnerabilityTicks(player), 40L, "Revive grants its own configured window");
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(player), 0L, "Revive does not grant respawn protection");
            engine().protectRespawn(player);
            engine().states().cancel(player.getUUID(), Feature.REVIVE, "test");
            test.assertValueEqual(engine().reviveInvulnerabilityTicks(player), 0L, "Cancelling revive clears only its timer");
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(player), 100L, "Respawn timer is independent");
            engine().states().reset(player.getUUID(), Feature.REVIVE);
            engine().states().setRevivesUsed(player.getUUID(), 2);
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(player), 100L, "Revive reset preserves respawn protection");
            engine().states().reset(player.getUUID(), Feature.RESPAWN_PROTECTION);
            player.invulnerableTime = 0;
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertValueEqual(engine().states().get(player.getUUID()).revivesUsed(), 3, "Resetting respawn preserves revive charges");
            test.assertValueEqual(engine().reviveInvulnerabilityTicks(player), 40L, "Last available charge still grants immunity");
            engine().states().cancel(player.getUUID(), Feature.RESPAWN_PROTECTION, "test");
            test.assertValueEqual(engine().reviveInvulnerabilityTicks(player), 40L, "Respawn cancellation preserves revive protection");
            engine().states().setRevivesUsed(player.getUUID(), 1);
            test.assertValueEqual(engine().reviveInvulnerabilityTicks(player), 40L, "Editing charges does not reset an active timer");
            engine().states().reset(player.getUUID(), Feature.REVIVE);
            test.assertValueEqual(engine().reviveInvulnerabilityTicks(player), 0L, "Revive reset clears its own timer");
            test.assertValueEqual(engine().states().get(player.getUUID()).revivesUsed(), 0, "Revive reset clears charges");
        });
        test.succeed();
    }

    @GameTest
    public void immunityHasIndependentUuidWindowAndAutomaticCancellation(GameTestHelper test) {
        withConfig(test, settings("{\"respawnProtection\":{\"minutes\":1,\"durationSeconds\":0}}"), () -> {
            ServerPlayer player = player(test, 0);
            engine().protectRespawn(player);
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(player), 0L, "Zero duration grants no immunity");
            var rule = com.google.gson.JsonParser.parseString("{\"respawnProtection\":{\"minutes\":-1,\"durationSeconds\":3,"
                    + "\"cancel\":{\"experienceLevel\":2}}}").getAsJsonObject();
            engine().replaceConfig(engine().config().withPlayer(player.getUUID(), rule));
            player.getStats().setValue(player, Stats.CUSTOM.get(Stats.PLAY_TIME), 1200);
            engine().protectRespawn(player);
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(player), 60L, "UUID immunity window overrides expired defaults");
            player.giveExperienceLevels(2);
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(player), 0L, "Automatic cancellation ends immunity");
            player.giveExperienceLevels(-2);
            engine().protectRespawn(player);
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(player), 0L, "Lost levels do not restore cancelled immunity");
        });
        test.succeed();
    }

}
