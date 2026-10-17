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

public final class PlayerStates {
    public static final class State {
        private final Map<Feature, String> cancelled = new EnumMap<>(Feature.class);
        private final Set<String> observed = new HashSet<>();
        private int revivesUsed;
        private long invulnerableUntil;
        private long reviveInvulnerableUntil;

        public String cancellation(Feature feature) {
            return cancelled.get(feature);
        }

        public Set<String> observed() {
            return Set.copyOf(observed);
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
                players.put(id, state);
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load Gentle Start player state", exception);
        }
    }

    public State get(UUID id) {
        return players.computeIfAbsent(id, ignored -> new State());
    }

    public void cancel(UUID id, Feature feature, String reason) {
        if (get(id).cancelled.putIfAbsent(feature, reason) == null) {
            if (feature == Feature.RESPAWN_PROTECTION) {
                get(id).invulnerableUntil = 0;
            }
            if (feature == Feature.REVIVE) {
                get(id).reviveInvulnerableUntil = 0;
            }
            save();
        }
    }

    public boolean observe(UUID id, String condition) {
        return get(id).observed.add(condition);
    }

    public void revive(UUID id, long invulnerableUntil) {
        State state = get(id);
        state.revivesUsed = Math.addExact(state.revivesUsed, 1);
        state.reviveInvulnerableUntil = invulnerableUntil;
        save();
    }

    public void protectRespawn(UUID id, long invulnerableUntil) {
        get(id).invulnerableUntil = invulnerableUntil;
        save();
    }

    public void endReviveProtection(UUID id) {
        State state = get(id);
        if (state.reviveInvulnerableUntil != 0) {
            state.reviveInvulnerableUntil = 0;
            save();
        }
    }

    public void reset(UUID id, Feature feature) {
        State state = get(id);
        if (feature == null) {
            players.remove(id);
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
        save();
    }

    public void setRevivesUsed(UUID id, int used) {
        if (used < 0) {
            throw new IllegalArgumentException("Used revives cannot be negative");
        }
        get(id).revivesUsed = used;
        save();
    }

    public void save() {
        JsonObject root = new JsonObject();
        players.forEach((id, state) -> {
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
