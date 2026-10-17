package dev.gentlestart;

import static dev.gentlestart.ExpandedProtectionGameTests.settings;
import static dev.gentlestart.ProtectionGameTests.player;
import static dev.gentlestart.ProtectionGameTests.withConfig;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

public class EffectsGameTests {
    @GameTest
    public void regenerationUsesRemainingWindowWithoutResettingItsCadence(GameTestHelper test) {
        withConfig(test, settings("{\"effects\":{\"minutes\":1,\"list\":[{\"id\":\"minecraft:regeneration\",\"durationSeconds\":1}]}}"), () -> {
            var engine = GentleStart.engine();
            var player = player(test, 0);
            player.removeAllEffects();
            player.setHealth(10);
            engine.refreshEffects(player);
            var effect = player.getEffect(MobEffects.REGENERATION);
            test.assertValueEqual(effect.getDuration(), 1200, "Legacy duration is ignored for sustained protection");
            for (int tick = 1; tick <= 200; tick++) {
                effect.tickServer(test.getLevel(), player, () -> {});
                player.getStats().setValue(player, Stats.CUSTOM.get(Stats.PLAY_TIME), tick);
                engine.checkNextPlayers(List.of(player));
                test.assertValueEqual(effect.getDuration(), 1200 - tick, "Polling does not rewind the countdown");
            }
            test.assertValueEqual(player.getHealth(), 14.0F, "Regeneration I heals at vanilla 50-tick intervals");
            player.getStats().setValue(player, Stats.CUSTOM.get(Stats.PLAY_TIME), 1200);
            player.removeAllEffects();
            engine.refreshEffects(player);
            test.assertFalse(player.hasEffect(MobEffects.REGENERATION), "Expired protection does not return");
        });
        test.succeed();
    }

    @GameTest
    public void effectsShareThePlayerPollingBudgetAndReturnAfterMilk(GameTestHelper test) {
        withConfig(test, settings("{\"effects\":{\"minutes\":1,\"list\":[{\"id\":\"minecraft:night_vision\"}]}}"), () -> {
            var engine = GentleStart.engine();
            var first = player(test, 100);
            var second = player(test, 200);
            first.removeAllEffects();
            second.removeAllEffects();
            engine.checkNextPlayers(List.of());
            engine.checkNextPlayers(List.of(first, second));
            test.assertValueEqual(first.getEffect(MobEffects.NIGHT_VISION).getDuration(), 1100, "First player's remaining time is used");
            test.assertFalse(second.hasEffect(MobEffects.NIGHT_VISION), "No full online-player effect sweep");
            engine.checkNextPlayers(List.of(first, second));
            test.assertValueEqual(second.getEffect(MobEffects.NIGHT_VISION).getDuration(), 1000, "Next tick checks the next player");
            first.removeAllEffects();
            engine.checkNextPlayers(List.of(first, second));
            test.assertTrue(first.hasEffect(MobEffects.NIGHT_VISION), "Milk-cleared effects return on the next assigned check");
        });
        test.succeed();
    }

    @GameTest
    public void infiniteEffectsAndExternalPotionsKeepTheirVanillaState(GameTestHelper test) {
        withConfig(test, settings("{\"effects\":{\"minutes\":-1,\"list\":[{\"id\":\"minecraft:resistance\"}]}}"), () -> {
            var engine = GentleStart.engine();
            var player = player(test, 0);
            player.removeAllEffects();
            engine.refreshEffects(player);
            var permanent = player.getEffect(MobEffects.RESISTANCE);
            test.assertTrue(permanent.isInfiniteDuration(), "Permanent feature uses vanilla infinite duration");
            engine.refreshEffects(player);
            test.assertTrue(player.getEffect(MobEffects.RESISTANCE) == permanent, "Unchanged state does not replace the instance");
            player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 2400, 2));
            engine.refreshEffects(player);
            test.assertValueEqual(player.getEffect(MobEffects.RESISTANCE).getAmplifier(), 2, "Stronger external potion stays active");
            test.assertValueEqual(player.getEffect(MobEffects.RESISTANCE).getDuration(), 2400, "External duration is unchanged");
            engine.states().cancel(player.getUUID(), Feature.EFFECTS, "test");
            engine.refreshEffects(player);
            test.assertTrue(player.hasEffect(MobEffects.RESISTANCE), "Cancellation retains already granted effects by policy");
            player.removeAllEffects();
            engine.refreshEffects(player);
            test.assertFalse(player.hasEffect(MobEffects.RESISTANCE), "Cancelled feature no longer replenishes effects");
        });
        test.succeed();
    }

    @GameTest
    public void reviveKeepsItsEffectDurationAndReplenishesMissingProtectionEffects(GameTestHelper test) {
        withConfig(test, settings("{\"effects\":{\"minutes\":1,\"list\":[{\"id\":\"minecraft:night_vision\"}]},"
                + "\"revive\":{\"minutes\":-1,\"effects\":[{\"id\":\"minecraft:fire_resistance\",\"durationSeconds\":7}]}}"), () -> {
            var player = player(test, 100);
            player.removeAllEffects();
            player.hurtServer(test.getLevel(), player.damageSources().generic(), 1000);
            test.assertTrue(player.isAlive(), "Lethal damage is prevented");
            test.assertValueEqual(player.getEffect(MobEffects.NIGHT_VISION).getDuration(), 1100, "Revive replenishes the missing sustained effect");
            test.assertValueEqual(player.getEffect(MobEffects.FIRE_RESISTANCE).getDuration(), 140, "Revive-only effects retain configured seconds");
        });
        test.succeed();
    }
}
