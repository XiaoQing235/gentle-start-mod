package dev.gentlestart;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class StatePersistenceGameTests {
    @GameTest
    public void readsDoNotCreateRecordsAndMutationsAreBatched(GameTestHelper test) throws Exception {
        Path root = Files.createTempDirectory("gentlestart-state-test-");
        try {
            Path file = root.resolve("players");
            PlayerStates states = new PlayerStates(file);
            for (int i = 0; i < 1000; i++) states.get(new UUID(0, i));
            test.assertFalse(states.hasPendingChanges(), "Read-only lookups do not create pending state");
            states.save();
            test.assertFalse(Files.exists(file), "Clean state does not create the state directory");
            UUID id = UUID.randomUUID();
            for (Feature feature : Feature.values()) states.cancel(id, feature, "test");
            test.assertFalse(Files.exists(file), "Ten cancellations are buffered without per-feature writes");
            test.assertTrue(states.save(), "One explicit flush saves all changes");
            try (var files = Files.walk(file)) {
                test.assertValueEqual(files.filter(Files::isRegularFile).count(), 1L, "Only the mutated player is persisted");
            }
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
            Path file = blocker.resolve("players");
            PlayerStates states = new PlayerStates(file);
            UUID id = UUID.randomUUID();
            states.cancel(id, Feature.PVP, "test");
            states.revive(id, 100);
            Files.writeString(blocker, "not a directory");
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
            Path file = root.resolve("players");
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
            test.assertFalse(Files.exists(playerFile(file, id)), "Reset deletes the player's file");
            test.assertValueEqual(new PlayerStates(file).get(id).revivesUsed(), 0, "Deleted state stays reset after restart");
        } finally {
            delete(root);
        }
        test.succeed();
    }

    @GameTest
    public void savesOnlyChangedPlayersAndRetriesOnlyFailures(GameTestHelper test) throws Exception {
        Path root = Files.createTempDirectory("gentlestart-incremental-");
        try {
            Path file = root.resolve("players");
            PlayerStates states = new PlayerStates(file);
            UUID unchanged = UUID.randomUUID(), changed = UUID.randomUUID(), blocked = UUID.randomUUID();
            states.revive(unchanged, 100);
            states.revive(changed, 100);
            states.revive(blocked, 100);
            test.assertTrue(states.save(), "Initial records saved");
            FileTime marker = FileTime.fromMillis(1000000);
            Files.setLastModifiedTime(playerFile(file, unchanged), marker);
            Files.delete(playerFile(file, blocked));
            Files.createDirectory(playerFile(file, blocked));
            Files.writeString(playerFile(file, blocked).resolve("block"), "prevent atomic replacement");
            states.revive(changed, 200);
            states.revive(blocked, 200);
            states.saveAsync();
            states.reset(blocked, null); // New deletion must win over the older failed write.
            test.assertFalse(states.save(), "One blocked file is reported without preventing other writes");
            test.assertValueEqual(new PlayerStates(file).get(changed).revivesUsed(), 2, "Healthy player saved despite another failure");
            test.assertValueEqual(Files.getLastModifiedTime(playerFile(file, unchanged)), marker, "Unchanged history is never rewritten");
            Files.setLastModifiedTime(playerFile(file, changed), marker);
            Files.delete(playerFile(file, blocked).resolve("block"));
            Files.delete(playerFile(file, blocked));
            test.assertTrue(states.save(), "Failed deletion is retried");
            test.assertFalse(Files.exists(playerFile(file, blocked)), "Retry never resurrects the older state");
            test.assertValueEqual(Files.getLastModifiedTime(playerFile(file, changed)), marker, "Successful writes are not retried");
        } finally {
            delete(root);
        }
        test.succeed();
    }

    @GameTest
    public void evictionKeepsUnsavedStateAndLoadsHistoryOnDemand(GameTestHelper test) throws Exception {
        Path root = Files.createTempDirectory("gentlestart-state-cache-");
        try {
            Path file = root.resolve("players");
            PlayerStates states = new PlayerStates(file);
            UUID id = UUID.randomUUID();
            states.revive(id, 100);
            for (int i = 0; i < 5000; i++) states.get(new UUID(0, i));
            test.assertValueEqual(states.get(id).revivesUsed(), 1, "Dirty state survives clean cache eviction");
            states.saveAsync();
            test.assertValueEqual(states.get(id).revivesUsed(), 1, "In-flight state remains readable");
            states.revive(id, 200);
            test.assertTrue(states.save(), "Latest state saved after eviction and in-flight change");
            UUID corrupt = UUID.randomUUID();
            Path corruptFile = playerFile(file, corrupt);
            Files.createDirectories(corruptFile.getParent());
            Files.writeString(corruptFile, "invalid state");
            PlayerStates loaded = new PlayerStates(file);
            test.assertValueEqual(loaded.get(id).revivesUsed(), 2, "Startup does not enumerate or parse unrelated history");
            for (int i = 0; i < 5000; i++) loaded.get(new UUID(0, i));
            JsonObject updated = JsonParser.parseString(Files.readString(playerFile(file, id))).getAsJsonObject();
            updated.addProperty("revivesUsed", 3);
            Files.writeString(playerFile(file, id), updated.toString());
            test.assertValueEqual(loaded.get(id).revivesUsed(), 3, "Evicted clean state actually reloads from disk");
            boolean rejected = false;
            try { loaded.revive(corrupt, 300); }
            catch (IllegalStateException expected) { rejected = true; }
            test.assertTrue(rejected, "Corrupt state fails explicitly rather than silently resetting charges");
            test.assertFalse(loaded.hasPendingChanges(), "A failed read cannot overwrite corrupt state");
        } finally {
            delete(root);
        }
        test.succeed();
    }

    static Path playerFile(Path directory, UUID id) {
        String name = id.toString();
        return directory.resolve(name.substring(0, 2)).resolve(name + ".json");
    }

    static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
