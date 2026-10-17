package dev.gentlestart;

import static dev.gentlestart.ExpandedProtectionGameTests.settings;
import static dev.gentlestart.ProtectionGameTests.player;
import static dev.gentlestart.ProtectionGameTests.respawn;
import static dev.gentlestart.ProtectionGameTests.withConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
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
        withConfig(test, settings("{\"revive\":{\"minutes\":-1},\"respawnProtection\":{\"minutes\":-1,\"durationSeconds\":5}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertValueEqual(player.getHealth(), 10.0F, "Revive restores half of vanilla maximum health");
            engine().states().cancel(player.getUUID(), Feature.REVIVE, "test");
            test.assertTrue(engine().respawnInvulnerabilityTicks(player) > 0, "Cancelling revive leaves independent immunity");
            engine().states().reset(player.getUUID(), Feature.REVIVE);
            engine().states().setRevivesUsed(player.getUUID(), 2);
            test.assertTrue(engine().respawnInvulnerabilityTicks(player) > 0, "Changing revive charges leaves immunity intact");
            engine().states().reset(player.getUUID(), Feature.RESPAWN_PROTECTION);
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(player), 0L, "Only immunity reset clears its timer");
            test.assertValueEqual(engine().states().get(player.getUUID()).revivesUsed(), 2, "Immunity reset preserves revive charges");
            engine().protectRespawn(player);
            engine().states().cancel(player.getUUID(), Feature.RESPAWN_PROTECTION, "test");
            test.assertValueEqual(engine().respawnInvulnerabilityTicks(player), 0L, "Immunity cancellation clears its timer");
            test.assertTrue(engine().active(player, Feature.REVIVE), "Immunity cancellation leaves revive eligible");
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

    @GameTest
    public void versionTwoFileImportsSplitImmunityWithoutRewritingIt(GameTestHelper test) throws Exception {
        Path file = Files.createTempFile("gentlestart-version-two-", ".json");
        UUID id = UUID.randomUUID();
        String text = "{\"defaults\":{\"revive\":{\"minutes\":120,\"uses\":2,\"invulnerabilitySeconds\":8}},"
                + "\"players\":{\"" + id + "\":{\"revive\":{\"minutes\":-1}}}}";
        try {
            Files.writeString(file, text);
            ProtectionConfig config = ProtectionConfig.load(file);
            test.assertValueEqual(config.defaultProfile().respawnProtection().window().minutes(), 120.0, "Old default immunity window imported");
            test.assertValueEqual(config.profile(id).respawnProtection().window().minutes(), -1.0, "Old UUID window imported independently");
            test.assertValueEqual(config.profile(id).respawnProtection().durationSeconds(), 8, "Old inherited immunity duration imported");
            test.assertFalse(config.document().getAsJsonObject("defaults").getAsJsonObject("revive").has("invulnerabilitySeconds"),
                    "New revive schema excludes immunity duration");
            test.assertValueEqual(Files.readString(file), text, "Loading leaves the old file intact");
        } finally {
            Files.deleteIfExists(file);
        }
        test.succeed();
    }
}
