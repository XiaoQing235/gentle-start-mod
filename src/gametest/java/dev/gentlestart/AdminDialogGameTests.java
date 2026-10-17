package dev.gentlestart;

import static dev.gentlestart.ExpandedProtectionGameTests.settings;
import static dev.gentlestart.ProtectionGameTests.player;
import static dev.gentlestart.ProtectionGameTests.withConfig;

import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.TextInput;

public class AdminDialogGameTests {
    @GameTest
    public void dialogsRoundTripAndSubmitCurrentNumericRules(GameTestHelper test) {
        withConfig(test, ProtectionConfig.defaults(), () -> {
            var engine = GentleStart.engine();
            var viewer = player(test, 0);
            var admin = test.getLevel().getServer().createCommandSourceStack().withEntity(viewer);
            UUID target = UUID.randomUUID();
            roundTrip(AdminDialogs.home(engine.config()));
            roundTrip(AdminDialogs.features(engine.config(), null));
            roundTrip(AdminDialogs.features(engine.config(), target));
            roundTrip(AdminDialogs.players(engine, viewer, 1));
            for (Feature feature : Feature.values()) {
                for (boolean personal : new boolean[]{false, true}) {
                    UUID id = personal ? target : null;
                    JsonObject before = ProtectionConfig.encode(personal ? engine.config().profile(target) : engine.config().defaultProfile());
                    var dialog = (ConfirmationDialog) roundTrip(AdminDialogs.rule(engine.config(), id, feature));
                    execute(test, submit(dialog, Map.of()), admin);
                    JsonObject after = ProtectionConfig.encode(personal ? engine.config().profile(target) : engine.config().defaultProfile());
                    test.assertValueEqual(after, before, "Saving unchanged form preserves all rules and lists");
                }
            }
            var drops = (ConfirmationDialog) roundTrip(AdminDialogs.rule(engine.config(), target, Feature.DEATH_DROPS));
            execute(test, submit(drops, Map.of("minutes", "-1", "lifetimeSeconds", "42")), admin);
            engine.reload();
            test.assertValueEqual(engine.config().profile(target).deathDrops().lifetimeSeconds(), 42, "Form persists lifetime to disk");
            test.assertValueEqual(engine.config().defaultProfile().deathDrops().lifetimeSeconds(), 600, "Player edit does not change defaults");
            var home = (MultiActionDialog) roundTrip(AdminDialogs.home(engine.config()));
            var values = values(home);
            values.put("playersPerTick", Action.ValueGetter.of("3"));
            String command = ((ClickEvent.RunCommand) home.actions().getFirst().action().orElseThrow().createAction(values).orElseThrow()).command();
            execute(test, command, admin);
            test.assertValueEqual(engine.config().playersPerTick(), 3, "Global form sets periodic budget");
            execute(test, "/gentlestart admin ui", admin);
            execute(test, "/gentlestart admin ui players", admin);
            execute(test, "/gentlestart admin ui player " + target, admin);
        });
        test.succeed();
    }

    @GameTest
    public void formSubmissionRejectsInvalidValuesAndUnauthorizedPlayers(GameTestHelper test) {
        withConfig(test, settings("{}"), () -> {
            var engine = GentleStart.engine();
            var viewer = player(test, 0);
            var admin = test.getLevel().getServer().createCommandSourceStack().withEntity(viewer);
            var dialog = (ConfirmationDialog) roundTrip(AdminDialogs.rule(engine.config(), null, Feature.DEATH_DROPS));
            JsonObject before = engine.config().document();
            for (String invalid : List.of("0", "-1", "1.5", "86401", "not-a-number", "")) {
                reject(test, submit(dialog, Map.of("lifetimeSeconds", invalid)), admin);
                test.assertValueEqual(engine.config().document(), before, "Invalid form is atomic");
            }
            for (String command : List.of("/gentlestart admin ui", "/gentlestart admin settings false 2",
                    submit(dialog, Map.of("lifetimeSeconds", "42")))) {
                reject(test, command, viewer.createCommandSourceStack());
            }
            test.assertValueEqual(engine.config().document(), before, "Forged UI commands do not bypass permissions");
            roundTrip(AdminDialogs.rule(engine.config(), null, Feature.DEATH_DROPS)).onCancel();
            test.assertValueEqual(engine.config().document(), before, "Building or cancelling a form does not write config");
        });
        test.succeed();
    }

    private static Dialog roundTrip(JsonObject document) {
        var buffer = Unpooled.buffer();
        try {
            Dialog.CONTEXT_FREE_STREAM_CODEC.encode(buffer, AdminDialogs.decode(document).value());
            return Dialog.CONTEXT_FREE_STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static Map<String, Action.ValueGetter> values(Dialog dialog) {
        Map<String, Action.ValueGetter> values = new HashMap<>();
        for (var input : dialog.common().inputs()) {
            String value = switch (input.control()) {
                case TextInput text -> text.initial();
                case BooleanInput bool -> bool.initial() ? bool.onTrue() : bool.onFalse();
                default -> throw new AssertionError("Unexpected input control");
            };
            values.put(input.key(), Action.ValueGetter.of(value));
        }
        return values;
    }

    private static String submit(ConfirmationDialog dialog, Map<String, String> overrides) {
        var values = values(dialog);
        overrides.forEach((name, value) -> values.put(name, Action.ValueGetter.of(value)));
        return ((ClickEvent.RunCommand) dialog.yesButton().action().orElseThrow().createAction(values).orElseThrow()).command();
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
