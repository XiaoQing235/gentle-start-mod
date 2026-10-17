package dev.gentlestart;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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

    private final Path file;
    private final Map<UUID, State> players = new HashMap<>();
    private boolean dirty;
    private boolean saveFailed;
    private CompletableFuture<Void> pendingSave;

    public PlayerStates(Path file) {
        this.file = file;
        if (Files.notExists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                UUID id = UUID.fromString(entry.getKey());
                JsonObject data = entry.getValue().getAsJsonObject();
                State state = new State();
                state.revivesUsed = Codec.intRange(0, Integer.MAX_VALUE).parse(JsonOps.INSTANCE, data.get("revivesUsed")).getOrThrow();
                state.invulnerableUntil = Codec.LONG.parse(JsonOps.INSTANCE, data.get("invulnerableUntil")).getOrThrow();
                state.reviveInvulnerableUntil = data.has("reviveInvulnerableUntil")
                        ? Codec.LONG.parse(JsonOps.INSTANCE, data.get("reviveInvulnerableUntil")).getOrThrow() : 0;
                state.observed.addAll(Codec.STRING.listOf().parse(JsonOps.INSTANCE, data.get("observed")).getOrThrow());
                data.getAsJsonObject("cancelled").entrySet().forEach(cancelled -> state.cancelled.put(
                        Feature.parse(cancelled.getKey()), Codec.STRING.parse(JsonOps.INSTANCE, cancelled.getValue()).getOrThrow()));
                if (!state.empty()) players.put(id, state);
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load Gentle Start player state", exception);
        }
    }

    public State get(UUID id) {
        return players.getOrDefault(id, EMPTY);
    }

    // Published states are never mutated: the I/O worker can safely serialize a shallow map snapshot.
    private void put(UUID id, State state) {
        if (state.empty()) players.remove(id);
        else players.put(id, state);
        dirty = true;
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
        if (!players.containsKey(id)) return;
        State state = new State(get(id));
        if (feature == null) {
            players.remove(id);
            dirty = true;
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
        return dirty || pendingSave != null;
    }

    /** Called on the server thread; at most one write is in flight, and clean state does no work. */
    public void saveAsync() {
        if (pendingSave != null && pendingSave.isDone()) finishSave();
        if (pendingSave == null && dirty) {
            Map<UUID, State> snapshot = Map.copyOf(players);
            pendingSave = CompletableFuture.runAsync(() -> write(snapshot), Util.ioPool());
            dirty = false;
        }
    }

    /** Explicit world saves and shutdown wait for the latest state, including mutations during an older write. */
    public boolean save() {
        if (pendingSave != null) finishSave();
        saveAsync();
        if (pendingSave != null) finishSave();
        return !dirty;
    }

    private void finishSave() {
        try {
            pendingSave.join();
            if (saveFailed) LOGGER.info("Gentle Start player state persistence recovered");
            saveFailed = false;
        } catch (CompletionException exception) {
            dirty = true;
            if (!saveFailed) LOGGER.error("Cannot save Gentle Start player state; changes remain pending and will be retried", exception.getCause());
            saveFailed = true;
        } finally {
            pendingSave = null;
        }
    }

    private void write(Map<UUID, State> snapshot) {
        JsonObject root = new JsonObject();
        snapshot.forEach((id, state) -> {
            JsonObject data = new JsonObject();
            data.addProperty("revivesUsed", state.revivesUsed);
            data.addProperty("invulnerableUntil", state.invulnerableUntil);
            data.addProperty("reviveInvulnerableUntil", state.reviveInvulnerableUntil);
            data.add("observed", Codec.STRING.listOf().encodeStart(JsonOps.INSTANCE, state.observed.stream().sorted().toList()).getOrThrow());
            JsonObject cancelled = new JsonObject();
            state.cancelled.forEach((feature, reason) -> cancelled.addProperty(feature.id(), reason));
            data.add("cancelled", cancelled);
            root.add(id.toString(), data);
        });
        JsonFiles.write(file, root);
    }
}
