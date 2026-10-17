package dev.gentlestart;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

public final class ProtectionRules {
    public static final Codec<Double> MINUTES = finiteRange(-Double.MAX_VALUE, Long.MAX_VALUE / 1200.0);
    public static final Codec<Double> FRACTION = finiteRange(0, 1);
    private static final Codec<Identifier> ITEM = Identifier.CODEC.validate(id -> BuiltInRegistries.ITEM.containsKey(id)
            ? DataResult.success(id) : DataResult.error(() -> "Unknown item: " + id));

    private ProtectionRules() {
    }

    public static Codec<Double> finiteRange(double min, double max) {
        return Codec.DOUBLE.validate(value -> Double.isFinite(value) && value >= min && value <= max
                ? DataResult.success(value) : DataResult.error(() -> "Expected a finite number in [" + min + ", " + max + "]"));
    }

    public static <T> MapCodec<T> field(Codec<T> codec, String name, T fallback) {
        return codec.optionalFieldOf(name).xmap(value -> value.orElse(fallback), Optional::of);
    }

    public record Cancellation(boolean requireAll, List<Identifier> advancements, List<Identifier> items,
                               List<Identifier> itemTags, int experienceLevel, double playMinutes) {
        public static final Cancellation NONE = new Cancellation(false, List.of(), List.of(), List.of(), 0, 0);
        public static final Codec<Cancellation> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                field(Codec.BOOL, "requireAll", false).forGetter(Cancellation::requireAll),
                field(Identifier.CODEC.listOf(), "advancements", List.<Identifier>of()).forGetter(Cancellation::advancements),
                field(ITEM.listOf(), "items", List.<Identifier>of()).forGetter(Cancellation::items),
                field(Identifier.CODEC.listOf(), "itemTags", List.<Identifier>of()).forGetter(Cancellation::itemTags),
                field(Codec.intRange(0, Integer.MAX_VALUE), "experienceLevel", 0).forGetter(Cancellation::experienceLevel),
                field(finiteRange(0, Long.MAX_VALUE / 1200.0), "playMinutes", 0.0).forGetter(Cancellation::playMinutes)
        ).apply(instance, Cancellation::new));

        public Cancellation {
            advancements = List.copyOf(advancements);
            items = List.copyOf(items);
            itemTags = List.copyOf(itemTags);
        }
    }

    public record Window(double minutes, Cancellation cancel) {
        public static final Window DISABLED = new Window(0, Cancellation.NONE);
        public static final MapCodec<Window> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                field(MINUTES, "minutes", 0.0).forGetter(Window::minutes),
                field(Cancellation.CODEC, "cancel", Cancellation.NONE).forGetter(Window::cancel)
        ).apply(instance, Window::new));

        public boolean includes(long playedTicks) {
            return minutes < 0 || minutes > 0 && playedTicks < minutes * 1200.0;
        }
    }

    public record Selection(boolean all, List<String> categories, List<Identifier> items,
                            List<Identifier> tags, List<Integer> slots) {
        public static final List<String> CATEGORIES = List.of("hotbar", "main", "armor", "offhand", "body", "saddle",
                "weapons", "tools", "wearable", "food", "blocks");
        public static final Selection ALL = new Selection(true, List.of(), List.of(), List.of(), List.of());
        public static final Codec<Selection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                field(Codec.BOOL, "all", true).forGetter(Selection::all),
                field(Codec.STRING.validate(category -> CATEGORIES.contains(category) ? DataResult.success(category)
                        : DataResult.error(() -> "Unknown inventory category: " + category)).listOf(),
                        "categories", List.<String>of()).forGetter(Selection::categories),
                field(ITEM.listOf(), "items", List.<Identifier>of()).forGetter(Selection::items),
                field(Identifier.CODEC.listOf(), "tags", List.<Identifier>of()).forGetter(Selection::tags),
                field(Codec.intRange(0, 42).listOf(), "slots", List.<Integer>of()).forGetter(Selection::slots)
        ).apply(instance, Selection::new));

        public Selection {
            categories = List.copyOf(categories);
            items = List.copyOf(items);
            tags = List.copyOf(tags);
            slots = List.copyOf(slots);
        }
    }

    public record Inventory(Window window, Selection keep) {
        public static final Inventory DEFAULT = new Inventory(new Window(120, Cancellation.NONE), Selection.ALL);
        public static final Codec<Inventory> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Window.CODEC.forGetter(Inventory::window),
                field(Selection.CODEC, "keep", Selection.ALL).forGetter(Inventory::keep)
        ).apply(instance, Inventory::new));
    }

    public record Experience(Window window, double ratio) {
        public static final Experience DEFAULT = new Experience(new Window(120, Cancellation.NONE), 1);
        public static final Codec<Experience> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Window.CODEC.forGetter(Experience::window),
                field(FRACTION, "ratio", 1.0).forGetter(Experience::ratio)
        ).apply(instance, Experience::new));
    }

    public record Effect(Holder<MobEffect> effect, int level, int durationSeconds, boolean showParticles, boolean showIcon) {
        private static final Codec<Holder<MobEffect>> TYPE = BuiltInRegistries.MOB_EFFECT.holderByNameCodec().validate(effect ->
                effect.getRegisteredName().startsWith("minecraft:") && !effect.value().isInstantaneous()
                        ? DataResult.success(effect)
                        : DataResult.error(() -> "Only sustained vanilla effects are supported: " + effect.getRegisteredName()));
        public static final Codec<Effect> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                TYPE.fieldOf("id").forGetter(Effect::effect),
                field(Codec.intRange(1, 256), "level", 1).forGetter(Effect::level),
                field(Codec.intRange(1, 86400), "durationSeconds", 15).forGetter(Effect::durationSeconds),
                field(Codec.BOOL, "showParticles", false).forGetter(Effect::showParticles),
                field(Codec.BOOL, "showIcon", true).forGetter(Effect::showIcon)
        ).apply(instance, Effect::new));
    }

    public record Effects(Window window, List<Effect> list) {
        public static final Effects DEFAULT = new Effects(new Window(120, Cancellation.NONE), List.of(
                new Effect(MobEffects.RESISTANCE, 1, 15, false, true),
                new Effect(MobEffects.NIGHT_VISION, 1, 15, false, true)));
        public static final Codec<Effects> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Window.CODEC.forGetter(Effects::window),
                field(Effect.CODEC.listOf(), "list", List.<Effect>of()).forGetter(Effects::list)
        ).apply(instance, Effects::new));

        public Effects {
            list = List.copyOf(list);
        }
    }

    public record Revive(Window window, int uses, float health, List<Effect> effects) {
        private static final Codec<Float> HEALTH = Codec.FLOAT.validate(value -> Float.isFinite(value) && value > 0
                ? DataResult.success(value) : DataResult.error(() -> "Revive health must be a finite positive number"));
        public static final Revive DEFAULT = new Revive(Window.DISABLED, 3, 10.0F, List.of());
        public static final Codec<Revive> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Window.CODEC.forGetter(Revive::window),
                field(Codec.intRange(-1, Integer.MAX_VALUE), "uses", 3).forGetter(Revive::uses),
                field(HEALTH, "health", 10.0F).forGetter(Revive::health),
                field(Effect.CODEC.listOf(), "effects", List.<Effect>of()).forGetter(Revive::effects)
        ).apply(instance, Revive::new));

        public Revive {
            effects = List.copyOf(effects);
        }
    }

    public record RespawnProtection(Window window, int durationSeconds) {
        public static final RespawnProtection DEFAULT = new RespawnProtection(Window.DISABLED, 5);
        public static final Codec<RespawnProtection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Window.CODEC.forGetter(RespawnProtection::window),
                field(Codec.intRange(0, 86400), "durationSeconds", 5).forGetter(RespawnProtection::durationSeconds)
        ).apply(instance, RespawnProtection::new));
    }

    public record Pvp(Window window, boolean removeOnAttack) {
        public static final Pvp DEFAULT = new Pvp(Window.DISABLED, true);
        public static final Codec<Pvp> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Window.CODEC.forGetter(Pvp::window),
                field(Codec.BOOL, "removeOnAttack", true).forGetter(Pvp::removeOnAttack)
        ).apply(instance, Pvp::new));
    }

    public record Reduction(Window window, double reduction) {
        public static final Reduction DEFAULT = new Reduction(Window.DISABLED, 0.5);
        public static final Codec<Reduction> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Window.CODEC.forGetter(Reduction::window),
                field(FRACTION, "reduction", 0.5).forGetter(Reduction::reduction)
        ).apply(instance, Reduction::new));
    }

    public record MobRange(Window window, double multiplier) {
        public static final MobRange DEFAULT = new MobRange(Window.DISABLED, 0.5);
        public static final Codec<MobRange> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Window.CODEC.forGetter(MobRange::window),
                field(FRACTION, "multiplier", 0.5).forGetter(MobRange::multiplier)
        ).apply(instance, MobRange::new));
    }

    public record DeathDrops(Window window, int lifetimeSeconds) {
        public static final DeathDrops DEFAULT = new DeathDrops(Window.DISABLED, 600);
        public static final Codec<DeathDrops> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Window.CODEC.forGetter(DeathDrops::window),
                field(Codec.intRange(1, 86400), "lifetimeSeconds", 600).forGetter(DeathDrops::lifetimeSeconds)
        ).apply(instance, DeathDrops::new));
    }

    public record Profile(Inventory inventory, Experience experience, Window score, Revive revive,
                          Effects effects, Pvp pvp, Reduction damageReduction, MobRange mobRange,
                          RespawnProtection respawnProtection, DeathDrops deathDrops) {
        public static final Profile DEFAULT = new Profile(Inventory.DEFAULT, Experience.DEFAULT,
                new Window(120, Cancellation.NONE), Revive.DEFAULT, Effects.DEFAULT, Pvp.DEFAULT,
                Reduction.DEFAULT, MobRange.DEFAULT, RespawnProtection.DEFAULT, DeathDrops.DEFAULT);
        public static final Codec<Profile> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                field(Inventory.CODEC, "inventory", Inventory.DEFAULT).forGetter(Profile::inventory),
                field(Experience.CODEC, "experience", Experience.DEFAULT).forGetter(Profile::experience),
                field(Window.CODEC.codec(), "score", DEFAULT.score()).forGetter(Profile::score),
                field(Revive.CODEC, "revive", Revive.DEFAULT).forGetter(Profile::revive),
                field(Effects.CODEC, "effects", Effects.DEFAULT).forGetter(Profile::effects),
                field(Pvp.CODEC, "pvp", Pvp.DEFAULT).forGetter(Profile::pvp),
                field(Reduction.CODEC, "damageReduction", Reduction.DEFAULT).forGetter(Profile::damageReduction),
                field(MobRange.CODEC, "mobRange", MobRange.DEFAULT).forGetter(Profile::mobRange),
                field(RespawnProtection.CODEC, "respawnProtection", RespawnProtection.DEFAULT).forGetter(Profile::respawnProtection),
                field(DeathDrops.CODEC, "deathDrops", DeathDrops.DEFAULT).forGetter(Profile::deathDrops)
        ).apply(instance, Profile::new));

        public Window window(Feature feature) {
            return switch (feature) {
                case INVENTORY -> inventory.window();
                case EXPERIENCE -> experience.window();
                case SCORE -> score;
                case REVIVE -> revive.window();
                case EFFECTS -> effects.window();
                case PVP -> pvp.window();
                case DAMAGE_REDUCTION -> damageReduction.window();
                case MOB_RANGE -> mobRange.window();
                case RESPAWN_PROTECTION -> respawnProtection.window();
                case DEATH_DROPS -> deathDrops.window();
            };
        }
    }
}
