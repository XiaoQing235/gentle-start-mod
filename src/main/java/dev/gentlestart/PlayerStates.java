package dev.gentlestart;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PlayerStates {
    private static final Logger LOGGER = LoggerFactory.getLogger("gentle-start-mod");
    private static final State EMPTY = new State();

    public static final class State {
        private final Map<Feature, String> cancelled = new EnumMap<>(Feature.class);
        private final Set<String> observed = new HashSet<>();
        private int revivesUsed;
        private long invulnerableUntil;
        private long reviveInvulnerableUntil;

        private State() {
        }

        private State(State other) {
            cancelled.putAll(other.cancelled);
            observed.addAll(other.observed);
            revivesUsed = other.revivesUsed;
            invulnerableUntil = other.invulnerableUntil;
            reviveInvulnerableUntil = other.reviveInvulnerableUntil;
        }

        private boolean empty() {
            return cancelled.isEmpty() && observed.isEmpty() && revivesUsed == 0
                    && invulnerableUntil == 0 && reviveInvulnerableUntil == 0;
        }

        public String cancellation(Feature feature) {
            return cancelled.get(feature);
        }

        public Set<String> observed() {
            return Collections.unmodifiableSet(observed);
        }

        public int revivesUsed() {
            return revivesUsed;
        }

        public long reviveInvulnerableUntil() {
            return reviveInvulnerableUntil;
        }

        public long invulnerableUntil() {
            return invulnerableUntil;
        }
    }

    private final Path directory;
    // Bound clean read history, including empty lookups. Dirty/in-flight states remain authoritative even after eviction.
    private final Map<UUID, State> cache = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, State> eldest) {
            return size() > 4096;
        }
    };
    private Map<UUID, State> dirty = new HashMap<>();
    private Map<UUID, State> inFlight = Map.of();
    private boolean saveFailed;
    private CompletableFuture<SaveResult> pendingSave;

    private record SaveResult(Map<UUID, State> failed, RuntimeException failure) {}

    public PlayerStates(Path file) {
        directory = file.toAbsolutePath().resolveSibling(file.getFileName() + ".d");
        migrate(file);
    }

    private void migrate(Path legacy) {
        if (Files.isDirectory(directory)) return; // Published directory is authoritative, including deleted players.
        if (Files.exists(directory)) throw new IllegalStateException("Player state directory is not a directory");
        Path staging = null;
        try (Reader reader = Files.newBufferedReader(legacy); JsonReader json = new JsonReader(reader)) {
            LOGGER.info("Migrating legacy Gentle Start player state; the original file will be retained as a backup");
            staging = directory.resolveSibling(directory.getFileName() + ".migrating");
            removeMigration(staging); // Discard an unpublished attempt left by a terminated process.
            Files.createDirectory(staging);
            json.beginObject();
            while (json.hasNext()) {
                UUID id = UUID.fromString(json.nextName());
                State state = decode(JsonParser.parseReader(json).getAsJsonObject());
                if (state.empty()) Files.deleteIfExists(playerFile(staging, id));
                else JsonFiles.write(playerFile(staging, id), encode(state));
            }
            json.endObject();
            if (json.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing player state data");
            // Until all records are valid and written, a failed/interrupted migration never replaces the legacy source.
            Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
            staging = null;
            LOGGER.info("Gentle Start player state migration completed");
        } catch (NoSuchFileException exception) {
            if (!Files.notExists(legacy)) throw new IllegalStateException("Cannot migrate Gentle Start player state", exception);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot migrate Gentle Start player state", exception);
        } finally {
            if (staging != null) {
                try {
                    removeMigration(staging);
                } catch (IOException exception) {
                    LOGGER.warn("Cannot remove incomplete player state migration directory", exception);
                }
            }
        }
    }

    private static void removeMigration(Path staging) throws IOException {
        if (Files.notExists(staging)) return;
        try (var paths = Files.walk(staging)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static Path playerFile(Path directory, UUID id) {
        String name = id.toString();
        return directory.resolve(name.substring(0, 2)).resolve(name + ".json");
    }

    private State load(UUID id) {
        Path file = playerFile(directory, id);
        try (Reader reader = Files.newBufferedReader(file)) {
            return decode(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (NoSuchFileException exception) {
            return EMPTY;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load Gentle Start player state for " + id, exception);
        }
    }

    private static State decode(JsonObject data) {
        State state = new State();
        state.revivesUsed = Codec.intRange(0, Integer.MAX_VALUE).parse(JsonOps.INSTANCE, data.get("revivesUsed")).getOrThrow();
        state.invulnerableUntil = Codec.LONG.parse(JsonOps.INSTANCE, data.get("invulnerableUntil")).getOrThrow();
        state.reviveInvulnerableUntil = data.has("reviveInvulnerableUntil")
                ? Codec.LONG.parse(JsonOps.INSTANCE, data.get("reviveInvulnerableUntil")).getOrThrow() : 0;
        state.observed.addAll(Codec.STRING.listOf().parse(JsonOps.INSTANCE, data.get("observed")).getOrThrow());
        data.getAsJsonObject("cancelled").entrySet().forEach(cancelled -> state.cancelled.put(
                Feature.parse(cancelled.getKey()), Codec.STRING.parse(JsonOps.INSTANCE, cancelled.getValue()).getOrThrow()));
        return state;
    }

    public State get(UUID id) {
        State state = dirty.get(id);
        if (state == null) state = inFlight.get(id);
        return state != null ? state : cache.computeIfAbsent(id, this::load);
    }

    // Published states are never mutated; a batch can be handed to the I/O worker without copying any history.
    private void put(UUID id, State state) {
        cache.put(id, state);
        dirty.put(id, state);
    }

    public void cancel(UUID id, Feature feature, String reason) {
        if (get(id).cancellation(feature) == null) {
            State state = new State(get(id));
            state.cancelled.put(feature, reason);
            if (feature == Feature.RESPAWN_PROTECTION) {
                state.invulnerableUntil = 0;
            }
            if (feature == Feature.REVIVE) {
                state.reviveInvulnerableUntil = 0;
            }
            put(id, state);
        }
    }

    public boolean observe(UUID id, String condition) {
        if (get(id).observed.contains(condition)) return false;
        State state = new State(get(id));
        state.observed.add(condition);
        put(id, state);
        return true;
    }

    public void revive(UUID id, long invulnerableUntil) {
        State state = new State(get(id));
        state.revivesUsed = Math.addExact(state.revivesUsed, 1);
        state.reviveInvulnerableUntil = invulnerableUntil;
        put(id, state);
    }

    public void protectRespawn(UUID id, long invulnerableUntil) {
        if (get(id).invulnerableUntil == invulnerableUntil) return;
        State state = new State(get(id));
        state.invulnerableUntil = invulnerableUntil;
        put(id, state);
    }

    public void endReviveProtection(UUID id) {
        if (get(id).reviveInvulnerableUntil != 0) {
            State state = new State(get(id));
            state.reviveInvulnerableUntil = 0;
            put(id, state);
        }
    }

    public void endInvulnerability(UUID id) {
        if (get(id).invulnerableUntil == 0 && get(id).reviveInvulnerableUntil == 0) return;
        State state = new State(get(id));
        state.invulnerableUntil = 0;
        state.reviveInvulnerableUntil = 0;
        put(id, state);
    }

    public void reset(UUID id, Feature feature) {
        State previous = get(id);
        if (previous.empty()) return;
        State state = new State(previous);
        if (feature == null) {
            put(id, EMPTY);
            return;
        } else {
            state.cancelled.remove(feature);
            // Condition history is reset only for this feature; other protections keep their history.
            state.observed.removeIf(condition -> condition.startsWith(feature.id() + ":"));
            if (feature == Feature.REVIVE) {
                state.revivesUsed = 0;
                state.reviveInvulnerableUntil = 0;
            }
            if (feature == Feature.RESPAWN_PROTECTION) {
                state.invulnerableUntil = 0;
            }
        }
        put(id, state);
    }

    public void setRevivesUsed(UUID id, int used) {
        if (used < 0) {
            throw new IllegalArgumentException("Used revives cannot be negative");
        }
        if (get(id).revivesUsed == used) return;
        State state = new State(get(id));
        state.revivesUsed = used;
        put(id, state);
    }

    public boolean hasPendingChanges() {
        return !dirty.isEmpty() || pendingSave != null;
    }

    /** Called on the server thread; at most one write is in flight, and clean state does no work. */
    public void saveAsync() {
        if (pendingSave != null && pendingSave.isDone()) finishSave();
        if (pendingSave == null && !dirty.isEmpty()) {
            Map<UUID, State> batch = dirty;
            pendingSave = CompletableFuture.supplyAsync(() -> write(batch), Util.ioPool());
            inFlight = batch;
            dirty = new HashMap<>();
        }
    }

    /** Explicit world saves and shutdown wait for the latest state, including mutations during an older write. */
    public boolean save() {
        if (pendingSave != null) finishSave();
        saveAsync();
        if (pendingSave != null) finishSave();
        return dirty.isEmpty();
    }

    private void finishSave() {
        try {
            SaveResult result = pendingSave.join();
            if (result.failure() == null) {
                if (saveFailed) LOGGER.info("Gentle Start player state persistence recovered");
                saveFailed = false;
            } else {
                retry(result.failed(), result.failure());
            }
        } catch (CompletionException exception) {
            retry(inFlight, exception.getCause());
        } finally {
            pendingSave = null;
            inFlight = Map.of();
        }
    }

    private void retry(Map<UUID, State> failed, Throwable failure) {
        failed.forEach(dirty::putIfAbsent); // A newer mutation or deletion always wins over a failed older write.
        if (!saveFailed) LOGGER.error("Cannot save Gentle Start player state; changes remain pending and will be retried", failure);
        saveFailed = true;
    }

    private SaveResult write(Map<UUID, State> batch) {
        Map<UUID, State> failed = new HashMap<>();
        RuntimeException failure = null;
        for (var entry : batch.entrySet()) {
            try {
                Path file = playerFile(directory, entry.getKey());
                if (entry.getValue().empty()) Files.deleteIfExists(file);
                else JsonFiles.write(file, encode(entry.getValue()));
            } catch (IOException | RuntimeException exception) {
                failed.put(entry.getKey(), entry.getValue());
                if (failure == null) failure = new IllegalStateException("Cannot save player state for " + entry.getKey(), exception);
            }
        }
        return new SaveResult(failed, failure);
    }

    private static JsonObject encode(State state) {
        JsonObject data = new JsonObject();
        data.addProperty("revivesUsed", state.revivesUsed);
        data.addProperty("invulnerableUntil", state.invulnerableUntil);
        data.addProperty("reviveInvulnerableUntil", state.reviveInvulnerableUntil);
        data.add("observed", Codec.STRING.listOf().encodeStart(JsonOps.INSTANCE, state.observed.stream().sorted().toList()).getOrThrow());
        JsonObject cancelled = new JsonObject();
        state.cancelled.forEach((feature, reason) -> cancelled.addProperty(feature.id(), reason));
        data.add("cancelled", cancelled);
        return data;
    }
}
