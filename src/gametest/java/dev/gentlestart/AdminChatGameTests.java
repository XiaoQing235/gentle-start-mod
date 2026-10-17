package dev.gentlestart;

import static dev.gentlestart.ExpandedProtectionGameTests.settings;
import static dev.gentlestart.ProtectionGameTests.player;
import static dev.gentlestart.ProtectionGameTests.withConfig;

import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

public class AdminChatGameTests {
    @GameTest
    public void chatButtonsEditOnlySelectedFieldAndPreserveInheritance(GameTestHelper test) {
        withConfig(test, ProtectionConfig.defaults(), () -> {
            var engine = GentleStart.engine();
            var viewer = player(test, 0);
            var admin = test.getLevel().getServer().createCommandSourceStack().withEntity(viewer);
            UUID target = UUID.randomUUID();
            JsonObject before = engine.config().document();
            for (Feature feature : Feature.values()) {
                var fields = AdminChat.fields(engine.config(), target, feature);
                for (String field : fields.keySet()) {
                    List<Component> lines = AdminChat.rule(engine.config(), target, feature,
                            AdminChat.fieldPage(engine.config(), target, feature, field));
                    String prefix = "/gentlestart admin ui set " + target + " " + feature.id() + " " + field + " ";
                    test.assertTrue(clicks(lines).stream().anyMatch(event -> command(event).startsWith(prefix)),
                            "Every field has an edit/toggle action, including list fields: " + field);
                }
            }
            test.assertValueEqual(engine.config().document(), before, "Rendering and suggesting edits never writes configuration");
            List<Component> drops = AdminChat.rule(engine.config(), target, Feature.DEATH_DROPS, 1);
            String lifetime = clicks(drops).stream().filter(event -> event instanceof ClickEvent.SuggestCommand)
                    .map(AdminChatGameTests::command).filter(value -> value.contains(" lifetimeSeconds ")).findFirst().orElseThrow();
            execute(test, lifetime.substring(0, lifetime.lastIndexOf(' ') + 1) + "42", admin);
            engine.reload();
            test.assertValueEqual(engine.config().profile(target).deathDrops().lifetimeSeconds(), 42, "Chat edit persists selected value");
            test.assertValueEqual(engine.config().document().getAsJsonObject("players").getAsJsonObject(target.toString())
                    .getAsJsonObject("deathDrops").size(), 1, "Only edited field becomes a UUID override");
            execute(test, "/gentlestart admin ui set defaults deathDrops minutes 77", admin);
            test.assertValueEqual(engine.config().profile(target).deathDrops().window().minutes(), 77.0,
                    "Unedited fields continue inheriting changed defaults");
            execute(test, "/gentlestart admin ui set " + target + " inventory cancel.items [\"minecraft:diamond\"]", admin);
            test.assertValueEqual(engine.config().profile(target).inventory().window().cancel().items().size(), 1,
                    "Nested list can be edited from chat");
            var pvp = clicks(AdminChat.rule(engine.config(), target, Feature.PVP, 1));
            String toggle = pvp.stream().filter(event -> event instanceof ClickEvent.RunCommand)
                    .map(AdminChatGameTests::command).filter(value -> value.contains(" removeOnAttack ")).findFirst().orElseThrow();
            execute(test, toggle, admin);
            test.assertFalse(engine.config().profile(target).pvp().removeOnAttack(), "Boolean chat action toggles the field");
            execute(test, "/gentlestart admin ui", admin);
            execute(test, "/gentlestart admin ui players", admin);
            execute(test, "/gentlestart admin ui player " + target, admin);
        });
        test.succeed();
    }

    @GameTest
    public void chatEditsValidateValuesAndEnforcePermissions(GameTestHelper test) {
        withConfig(test, settings("{}"), () -> {
            var engine = GentleStart.engine();
            var viewer = player(test, 0);
            var admin = test.getLevel().getServer().createCommandSourceStack().withEntity(viewer);
            JsonObject before = engine.config().document();
            for (String invalid : List.of("0", "-1", "1.5", "86401", "not-a-number", "null")) {
                reject(test, "/gentlestart admin ui set defaults deathDrops lifetimeSeconds " + invalid, admin);
                test.assertValueEqual(engine.config().document(), before, "Invalid edit is atomic");
            }
            reject(test, "/gentlestart admin ui set defaults deathDrops unknown 42", admin);
            for (String command : List.of("/gentlestart admin ui", "/gentlestart admin settings false 2",
                    "/gentlestart admin ui set defaults deathDrops lifetimeSeconds 42")) {
                reject(test, command, viewer.createCommandSourceStack());
            }
            test.assertValueEqual(engine.config().document(), before, "Forged chat commands do not bypass permissions");
        });
        test.succeed();
    }

    private static List<ClickEvent> clicks(List<Component> lines) {
        List<ClickEvent> result = new ArrayList<>();
        lines.forEach(line -> {
            var encoded = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, line).getOrThrow();
            collect(ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow(), result);
        });
        return result;
    }

    private static void collect(Component component, List<ClickEvent> result) {
        if (component.getStyle().getClickEvent() != null) {
            result.add(component.getStyle().getClickEvent());
        }
        component.getSiblings().forEach(child -> collect(child, result));
    }

    private static String command(ClickEvent event) {
        return switch (event) {
            case ClickEvent.RunCommand run -> run.command();
            case ClickEvent.SuggestCommand suggest -> suggest.command();
            default -> throw new AssertionError("Unexpected chat action");
        };
    }

    private static void execute(GameTestHelper test, String command, CommandSourceStack source) {
        try {
            test.getLevel().getServer().getCommands().getDispatcher().execute(command.substring(1), source);
        } catch (CommandSyntaxException exception) {
            throw new AssertionError(command, exception);
        }
    }

    private static void reject(GameTestHelper test, String command, CommandSourceStack source) {
        boolean denied = false;
        try {
            test.getLevel().getServer().getCommands().getDispatcher().execute(command.substring(1), source);
        } catch (CommandSyntaxException expected) {
            denied = true;
        }
        test.assertTrue(denied, "Invalid or unauthorized command must fail");
    }
}
