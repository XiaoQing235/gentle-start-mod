package dev.gentlestart;

import static dev.gentlestart.ExpandedProtectionGameTests.settings;
import static dev.gentlestart.ProtectionGameTests.withConfig;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class CorpseConfigGameTests {
    @GameTest
    public void corpseConfigurationDefaultsInheritsAndRoundTrips(GameTestHelper test) {
        var defaults = ProtectionConfig.defaults().defaultProfile().corpse();
        test.assertValueEqual(defaults.window().minutes(), 0.0, "Corpses are disabled by default");
        test.assertValueEqual(defaults.mode(), "barrel", "Barrel is the default presentation");
        test.assertTrue(defaults.saveExperience(), "Experience saving defaults to enabled");
        UUID id = UUID.randomUUID();
        var config = settings("{\"deathCorpse\":{\"minutes\":-1,\"mode\":\"player\",\"saveExperience\":false}}")
                .withPlayer(id, json("{\"deathCorpse\":{\"saveExperience\":true}}"));
        test.assertValueEqual(config.profile(id).corpse().mode(), "player", "Per-player rules inherit the presentation");
        test.assertValueEqual(config.profile(id).window(Feature.CORPSE).minutes(), -1.0, "Per-player rules inherit the window");
        test.assertTrue(config.profile(id).corpse().saveExperience(), "Per-player rules can override experience saving");
        test.assertFalse(config.defaultProfile().corpse().saveExperience(), "Player overrides preserve defaults");
        String saved = ConfigFormat.write(config.document());
        test.assertTrue(saved.contains("\"deathCorpse\""), "Saved configuration uses the public feature name");
        test.assertValueEqual(ProtectionConfig.parse(json(saved)).profile(id), config.profile(id), "Commented configuration round trips");
        test.assertTrue(AdminChat.fields(config, id, Feature.CORPSE).keySet().containsAll(List.of("mode", "saveExperience")),
                "Chat editor exposes both corpse options");
        test.succeed();
    }

    @GameTest
    public void corpseRejectsInvalidOptionsAndInheritedInventoryConflicts(GameTestHelper test) {
        for (String invalid : List.of(
                "{\"defaults\":{\"deathCorpse\":{\"mode\":\"chest\"}}}",
                "{\"defaults\":{\"deathCorpse\":{\"mode\":1}}}",
                "{\"defaults\":{\"deathCorpse\":{\"saveExperience\":\"yes\"}}}",
                "{\"defaults\":{\"deathCorpse\":{\"unknown\":true}}}",
                "{\"defaults\":{\"deathCorpse\":{\"minutes\":-1}}}")) {
            rejected(test, () -> ProtectionConfig.parse(json(invalid)), "Invalid corpse options or default conflict must fail");
        }
        UUID id = UUID.randomUUID();
        rejected(test, () -> ProtectionConfig.defaults().withPlayer(id, json("{\"deathCorpse\":{\"minutes\":-1}}")),
                "A player corpse rule cannot silently coexist with inherited inventory protection");
        var corpse = settings("{\"deathCorpse\":{\"minutes\":-1}}");
        rejected(test, () -> corpse.withPlayer(id, json("{\"keepInventory\":{\"minutes\":1}}")),
                "A player inventory rule cannot coexist with inherited corpse protection");
        var inventoryOverride = settings("{}").withPlayer(id, json("{\"keepInventory\":{\"minutes\":1}}"));
        JsonObject changedDefaults = inventoryOverride.document();
        changedDefaults.getAsJsonObject("defaults").getAsJsonObject("deathCorpse").addProperty("minutes", -1);
        rejected(test, () -> ProtectionConfig.parse(changedDefaults), "Default updates also validate effective player rules");
        var switched = corpse.withPlayer(id, json("{\"deathCorpse\":{\"minutes\":0},\"keepInventory\":{\"minutes\":-1}}"));
        test.assertValueEqual(switched.profile(id).inventory().window().minutes(), -1.0,
                "An explicit player override can switch between mutually exclusive features");
        test.succeed();
    }

    @GameTest
    public void corpseAdminCommandsValidateBeforeSaving(GameTestHelper test) {
        withConfig(test, ProtectionConfig.defaults(), () -> {
            var engine = GentleStart.engine();
            var dispatcher = test.getLevel().getServer().getCommands().getDispatcher();
            var console = test.getLevel().getServer().createCommandSourceStack();
            JsonObject before = engine.config().document();
            boolean denied = false;
            try {
                dispatcher.execute("gentlestart admin defaults set deathCorpse -1", console);
            } catch (CommandSyntaxException expected) {
                denied = true;
            }
            test.assertTrue(denied, "Enabling corpses while keeping inventory is rejected");
            test.assertValueEqual(engine.config().document(), before, "Rejected command does not alter live configuration");
            try {
                dispatcher.execute("gentlestart admin defaults set keepInventory 0", console);
                dispatcher.execute("gentlestart admin defaults rule deathCorpse {\"minutes\":-1,\"mode\":\"player\",\"saveExperience\":false}", console);
                dispatcher.execute("gentlestart admin ui set defaults deathCorpse saveExperience true", console);
            } catch (CommandSyntaxException exception) {
                throw new AssertionError("Valid corpse commands should succeed", exception);
            }
            engine.reload();
            test.assertValueEqual(engine.config().defaultProfile().corpse().mode(), "player", "Commands persist the selected mode");
            test.assertTrue(engine.config().defaultProfile().corpse().saveExperience(), "Chat boolean edit persists after reload");
        });
        test.succeed();
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void rejected(GameTestHelper test, Runnable action, String message) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            rejected = true;
        }
        test.assertTrue(rejected, message);
    }
}
