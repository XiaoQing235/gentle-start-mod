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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ProtectionConfig {
    private final JsonObject document;
    private final boolean enabled;
    private final ProtectionRules.Profile defaults;
    private final Map<UUID, ProtectionRules.Profile> players;

    private ProtectionConfig(JsonObject document, boolean enabled, ProtectionRules.Profile defaults,
                             Map<UUID, ProtectionRules.Profile> players) {
        this.document = document.deepCopy();
        this.enabled = enabled;
        this.defaults = defaults;
        this.players = Map.copyOf(players);
    }

    public static ProtectionConfig defaults() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", true);
        root.add("defaults", encode(ProtectionRules.Profile.DEFAULT));
        root.add("players", new JsonObject());
        return parse(root);
    }

    public boolean enabled() {
        return enabled;
    }

    public ProtectionRules.Profile profile(UUID player) {
        return players.getOrDefault(player, defaults);
    }

    public ProtectionRules.Profile defaultProfile() {
        return defaults;
    }

    public Set<UUID> configuredPlayers() {
        return players.keySet();
    }

    public JsonObject document() {
        return document.deepCopy();
    }

    public ProtectionConfig withPlayer(UUID id, JsonObject override) {
        JsonObject updated = document();
        updated.getAsJsonObject("players").add(id.toString(), override.deepCopy());
        return parse(updated);
    }

    public ProtectionConfig withoutPlayer(UUID id) {
        JsonObject updated = document();
        updated.getAsJsonObject("players").remove(id.toString());
        return parse(updated);
    }

    public static ProtectionConfig parse(JsonObject input) {
        JsonObject root = input.deepCopy();
        checkFields(root, Set.of("enabled", "defaults", "players"), "root");
        boolean enabled = !root.has("enabled") || Codec.BOOL.parse(JsonOps.INSTANCE, root.get("enabled")).getOrThrow();
        JsonObject defaultJson = root.has("defaults") ? object(root.get("defaults"), "defaults") : new JsonObject();
        validateProfileFields(defaultJson);
        ProtectionRules.Profile defaults = ProtectionRules.Profile.CODEC.parse(JsonOps.INSTANCE,
                merge(encode(ProtectionRules.Profile.DEFAULT), defaultJson)).getOrThrow();
        JsonObject entries = root.has("players") ? object(root.get("players"), "players") : new JsonObject();
        JsonObject canonicalEntries = new JsonObject();
        Map<UUID, ProtectionRules.Profile> players = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            UUID id = UUID.fromString(entry.getKey());
            if (!id.toString().equalsIgnoreCase(entry.getKey()) || players.containsKey(id)) {
                throw new IllegalArgumentException("Invalid or duplicate player UUID: " + entry.getKey());
            }
            JsonObject override = object(entry.getValue(), "player profile");
            validateProfileFields(override);
            players.put(id, ProtectionRules.Profile.CODEC.parse(JsonOps.INSTANCE, merge(encode(defaults), override)).getOrThrow());
            canonicalEntries.add(id.toString(), override.deepCopy());
        }
        root.addProperty("enabled", enabled);
        root.add("defaults", encode(defaults));
        root.add("players", canonicalEntries);
        return new ProtectionConfig(root, enabled, defaults, players);
    }

    public static ProtectionConfig load(Path file) {
        if (Files.notExists(file)) {
            ProtectionConfig config = defaults();
            config.save(file);
            return config;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = object(JsonParser.parseReader(reader), "configuration");
            return parse(root);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load gentle-start-mod.json: " + exception.getMessage(), exception);
        }
    }

    public void save(Path file) {
        JsonFiles.write(file, document);
    }

    public static JsonObject encode(ProtectionRules.Profile profile) {
        return ProtectionRules.Profile.CODEC.encodeStart(JsonOps.INSTANCE, profile).getOrThrow().getAsJsonObject();
    }

    public static JsonObject merge(JsonObject base, JsonObject override) {
        JsonObject result = base.deepCopy();
        override.entrySet().forEach(entry -> {
            JsonElement old = result.get(entry.getKey());
            result.add(entry.getKey(), old != null && old.isJsonObject() && entry.getValue().isJsonObject()
                    ? merge(old.getAsJsonObject(), entry.getValue().getAsJsonObject()) : entry.getValue().deepCopy());
        });
        return result;
    }

    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
        return value.getAsJsonObject();
    }

    private static void checkFields(JsonObject value, Set<String> fields, String name) {
        for (String field : value.keySet()) {
            if (!fields.contains(field)) {
                throw new IllegalArgumentException("Unknown field in " + name + ": " + field);
            }
        }
    }

    private static void validateProfileFields(JsonObject profile) {
        for (String name : profile.keySet()) {
            Feature feature = Feature.parse(name);
            JsonObject rule = object(profile.get(name), name);
            Set<String> fields = switch (feature) {
                case INVENTORY -> Set.of("minutes", "cancel", "keep");
                case EXPERIENCE -> Set.of("minutes", "cancel", "ratio");
                case SCORE, DEATH_DROPS -> Set.of("minutes", "cancel");
                case REVIVE -> Set.of("minutes", "cancel", "uses", "health", "effects");
                case RESPAWN_PROTECTION -> Set.of("minutes", "cancel", "durationSeconds");
                case EFFECTS -> Set.of("minutes", "cancel", "list");
                case PVP -> Set.of("minutes", "cancel", "removeOnAttack");
                case DAMAGE_REDUCTION -> Set.of("minutes", "cancel", "reduction");
                case MOB_RANGE -> Set.of("minutes", "cancel", "multiplier");
            };
            checkFields(rule, fields, name);
            for (String field : Set.of("uses", "durationSeconds")) {
                if (rule.has(field)) {
                    integer(rule.get(field), field);
                }
            }
            if (rule.has("cancel")) {
                JsonObject cancel = object(rule.get("cancel"), "cancel");
                checkFields(cancel, Set.of("requireAll", "advancements", "items",
                        "itemTags", "experienceLevel", "playMinutes"), "cancel");
                if (cancel.has("experienceLevel")) {
                    integer(cancel.get("experienceLevel"), "experienceLevel");
                }
            }
            if (rule.has("keep")) {
                JsonObject keep = object(rule.get("keep"), "keep");
                checkFields(keep, Set.of("all", "categories", "items", "tags", "slots"), "keep");
                if (keep.has("slots")) {
                    keep.getAsJsonArray("slots").forEach(slot -> integer(slot, "slot"));
                }
            }
            for (String field : Set.of("list", "effects")) {
                if (rule.has(field)) {
                    rule.getAsJsonArray(field).forEach(element -> {
                        JsonObject effect = object(element, "effect");
                        checkFields(effect, Set.of("id", "level", "durationSeconds", "showParticles", "showIcon"), "effect");
                        for (String number : Set.of("level", "durationSeconds")) {
                            if (effect.has(number)) {
                                integer(effect.get(number), number);
                            }
                        }
                    });
                }
            }
        }
    }

    private static void integer(JsonElement value, String name) {
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(name + " must be an integer");
            }
            value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }
}
