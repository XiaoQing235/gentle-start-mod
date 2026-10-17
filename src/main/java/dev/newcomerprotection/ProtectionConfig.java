package dev.newcomerprotection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

public record ProtectionConfig(boolean enabled, long protectionSeconds, boolean keepInventory,
                               boolean keepExperience, List<Effect> effects) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final ProtectionConfig DEFAULT = new ProtectionConfig(true, 7200, true, true, List.of(
            new Effect(MobEffects.RESISTANCE, 1, false, true),
            new Effect(MobEffects.NIGHT_VISION, 1, false, true)));

    public static final Codec<ProtectionConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            defaulted(Codec.BOOL, "enabled", true).forGetter(ProtectionConfig::enabled),
            defaulted(Codec.LONG.validate(seconds -> seconds >= 0 && seconds <= Long.MAX_VALUE / 20
                    ? DataResult.success(seconds)
                    : DataResult.error(() -> "protectionSeconds must be between 0 and " + Long.MAX_VALUE / 20)),
                    "protectionSeconds", 7200L)
                    .forGetter(ProtectionConfig::protectionSeconds),
            defaulted(Codec.BOOL, "keepInventory", true).forGetter(ProtectionConfig::keepInventory),
            defaulted(Codec.BOOL, "keepExperience", true).forGetter(ProtectionConfig::keepExperience),
            defaulted(Effect.CODEC.listOf(), "effects", DEFAULT.effects()).forGetter(ProtectionConfig::effects)
    ).apply(instance, ProtectionConfig::new));

    public ProtectionConfig {
        effects = List.copyOf(effects);
    }

    public boolean protects(long playedTicks) {
        return enabled && playedTicks < protectionSeconds * 20;
    }

    // Accept omitted values while keeping generated configuration fully discoverable.
    private static <T> MapCodec<T> defaulted(Codec<T> codec, String name, T fallback) {
        return codec.optionalFieldOf(name).xmap(value -> value.orElse(fallback), Optional::of);
    }

    public static ProtectionConfig load(Path file) {
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(CODEC.encodeStart(JsonOps.INSTANCE, DEFAULT).getOrThrow()) + "\n",
                        StandardCharsets.UTF_8);
                return DEFAULT;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load newcomer-protection.json: " + exception.getMessage(), exception);
        }
    }

    public record Effect(Holder<MobEffect> effect, int level, boolean showParticles, boolean showIcon) {
        private static final Codec<Holder<MobEffect>> EFFECT_CODEC = BuiltInRegistries.MOB_EFFECT.holderByNameCodec()
                .validate(effect -> {
                    if (!effect.getRegisteredName().startsWith("minecraft:")) {
                        return DataResult.error(() -> "Only vanilla effects are supported: " + effect.getRegisteredName());
                    }
                    if (effect.value().isInstantaneous()) {
                        return DataResult.error(() -> "Instant effects cannot be refreshed: " + effect.getRegisteredName());
                    }
                    return DataResult.success(effect);
                });
        public static final Codec<Effect> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EFFECT_CODEC.fieldOf("id").forGetter(Effect::effect),
                defaulted(Codec.intRange(1, 256), "level", 1).forGetter(Effect::level),
                defaulted(Codec.BOOL, "showParticles", false).forGetter(Effect::showParticles),
                defaulted(Codec.BOOL, "showIcon", true).forGetter(Effect::showIcon)
        ).apply(instance, Effect::new));
    }
}
