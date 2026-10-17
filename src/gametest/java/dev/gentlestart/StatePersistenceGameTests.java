package dev.gentlestart;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class StatePersistenceGameTests {
    @GameTest
    public void readsDoNotCreateRecordsAndMutationsAreBatched(GameTestHelper test) throws Exception {
        Path root = Files.createTempDirectory("gentlestart-state-test-");
        try {
            Path file = root.resolve("state.json");
            PlayerStates states = new PlayerStates(file);
            for (int i = 0; i < 1000; i++) states.get(new UUID(0, i));
            test.assertFalse(states.hasPendingChanges(), "Read-only lookups do not create pending state");
            states.save();
            test.assertFalse(Files.exists(file), "Clean state does not write a file");
            UUID id = UUID.randomUUID();
            for (Feature feature : Feature.values()) states.cancel(id, feature, "test");
            test.assertFalse(Files.exists(file), "Ten cancellations are buffered without per-feature writes");
            test.assertTrue(states.save(), "One explicit flush saves all changes");
            test.assertValueEqual(JsonParser.parseString(Files.readString(file)).getAsJsonObject().size(), 1,
                    "Only the mutated player is persisted");
            PlayerStates loaded = new PlayerStates(file);
            for (Feature feature : Feature.values()) {
                test.assertValueEqual(loaded.get(id).cancellation(feature), "test", "All cancellations survive reload");
            }
        } finally {
            delete(root);
        }
        test.succeed();
    }

    @GameTest
    public void failedSaveKeepsPendingStateAndRecoversWithoutAnotherMutation(GameTestHelper test) throws Exception {
        Path root = Files.createTempDirectory("gentlestart-state-failure-");
        try {
            Path blocker = root.resolve("blocked");
            Path file = blocker.resolve("state.json");
            PlayerStates states = new PlayerStates(file);
            Files.writeString(blocker, "not a directory");
            UUID id = UUID.randomUUID();
            states.cancel(id, Feature.PVP, "test");
            states.revive(id, 100);
            test.assertFalse(states.save(), "Storage failure is reported without throwing through gameplay");
            test.assertTrue(states.hasPendingChanges(), "Failure retains the dirty state");
            test.assertValueEqual(states.get(id).revivesUsed(), 1, "Gameplay state remains authoritative");
            Files.delete(blocker);
            test.assertTrue(states.save(), "Retry without any new mutation persists the pending changes");
            test.assertFalse(states.hasPendingChanges(), "Successful retry clears pending state");
            PlayerStates loaded = new PlayerStates(file);
            test.assertValueEqual(loaded.get(id).cancellation(Feature.PVP), "test", "Cancellation survives retry");
            test.assertValueEqual(loaded.get(id).revivesUsed(), 1, "Charge survives retry");
        } finally {
            delete(root);
        }
        test.succeed();
    }

    @GameTest
    public void flushIncludesChangesAfterAnAsyncSnapshot(GameTestHelper test) throws Exception {
        Path root = Files.createTempDirectory("gentlestart-state-async-");
        try {
            Path file = root.resolve("state.json");
            PlayerStates states = new PlayerStates(file);
            UUID id = UUID.randomUUID();
            states.revive(id, 100);
            var first = states.get(id);
            states.saveAsync();
            states.revive(id, 200);
            states.cancel(id, Feature.PVP, "test");
            test.assertValueEqual(first.revivesUsed(), 1, "Published snapshots remain immutable");
            test.assertTrue(states.save(), "Flush waits for the latest snapshot");
            PlayerStates loaded = new PlayerStates(file);
            test.assertValueEqual(loaded.get(id).revivesUsed(), 2, "Newer mutation is not lost behind an old write");
            test.assertValueEqual(loaded.get(id).reviveInvulnerableUntil(), 200L, "Newest deadline wins");
            states.reset(id, null);
            test.assertTrue(states.save(), "Reset is persisted");
            test.assertTrue(JsonParser.parseString(Files.readString(file)).getAsJsonObject().isEmpty(),
                    "Reset removes the record instead of persisting an empty one");
        } finally {
            delete(root);
        }
        test.succeed();
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
