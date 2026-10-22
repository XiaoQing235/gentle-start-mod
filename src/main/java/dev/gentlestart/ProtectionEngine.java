package dev.gentlestart;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;

public final class ProtectionEngine {
    public enum ConditionChange {
        ALL, ITEMS, LEVEL, ADVANCEMENTS, TIME, HISTORY;

        boolean includes(ConditionChange kind) {
            return this == ALL || this == kind;
        }
    }

    private final MinecraftServer server;
    private final Path configFile;
    private final PlayerStates states;
    private ProtectionConfig config;
    private int nextConditionPlayer;

    public ProtectionEngine(MinecraftServer server, Path configFile, Path stateDirectory) {
        this.server = server;
        this.configFile = configFile;
        this.config = ProtectionConfig.load(configFile);
        this.states = new PlayerStates(stateDirectory);
        validate(config);
    }

    public ProtectionConfig config() {
        return config;
    }

    public PlayerStates states() {
        return states;
    }

    public ProtectionRules.Profile profile(ServerPlayer player) {
        return config.profile(player.getUUID());
    }

    public static long playedTicks(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
    }

    public void reload() {
        ProtectionConfig candidate = ProtectionConfig.load(configFile);
        validate(candidate);
        config = candidate;
        checkOnlineConditions();
    }

    public void replaceConfig(ProtectionConfig candidate) {
        validate(candidate);
        candidate.save(configFile);
        config = candidate;
        checkOnlineConditions();
    }

    private void validate(ProtectionConfig candidate) {
        List<ProtectionRules.Profile> profiles = new ArrayList<>();
        profiles.add(candidate.defaultProfile());
        candidate.configuredPlayers().forEach(id -> profiles.add(candidate.profile(id)));
        for (ProtectionRules.Profile profile : profiles) {
            for (Feature feature : Feature.values()) {
                ProtectionRules.Cancellation cancel = profile.window(feature).cancel();
                for (Identifier id : cancel.advancements()) {
                    if (server.getAdvancements().get(id) == null) {
                        throw new IllegalArgumentException("Unknown advancement: " + id);
                    }
                }
                for (Identifier id : cancel.itemTags()) {
                    validateTag(id);
                }
            }
            profile.inventory().keep().tags().forEach(this::validateTag);
        }
    }

    private void validateTag(Identifier id) {
        if (BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, id)).isEmpty()) {
            throw new IllegalArgumentException("Unknown item tag: " + id);
        }
    }

    public boolean active(ServerPlayer player, Feature feature) {
        if (!config.enabled() || player.isSpectator()) {
            return false;
        }
        // Item, level and advancement state is recorded by their change events.
        evaluateCancellation(player, feature, ConditionChange.TIME);
        return states.get(player.getUUID()).cancellation(feature) == null
                && profile(player).window(feature).includes(playedTicks(player));
    }

    public void checkConditions(ServerPlayer player) {
        checkConditions(player, ConditionChange.ALL);
    }

    public void checkOnlineConditions() {
        server.getPlayerList().getPlayers().forEach(player -> {
            checkConditions(player);
            refreshEffects(player);
        });
    }

    public void checkConditions(ServerPlayer player, ConditionChange change) {
        if (config.enabled()) {
            for (Feature feature : Feature.values()) {
                evaluateCancellation(player, feature, change);
            }
        }
    }

    void checkNextPlayers(List<ServerPlayer> players) {
        if (players.isEmpty()) {
            nextConditionPlayer = 0;
            return;
        }
        if (!config.enabled()) {
            return;
        }
        int count = Math.min(config.timeConditionChecksPerTick(), players.size());
        for (int checked = 0; checked < count; checked++) {
            nextConditionPlayer %= players.size();
            ServerPlayer player = players.get(nextConditionPlayer);
            checkConditions(player, ConditionChange.TIME);
            refreshEffects(player);
            nextConditionPlayer++;
        }
    }

    public void observeItem(ServerPlayer player, ItemStack stack) {
        if (!config.enabled() || stack.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Feature feature : Feature.values()) {
            ProtectionRules.Window window = profile(player).window(feature);
            if (window.minutes() == 0 || states.get(player.getUUID()).cancellation(feature) != null) continue;
            ProtectionRules.Cancellation cancel = window.cancel();
            for (Identifier id : cancel.items()) {
                if (stack.is(BuiltInRegistries.ITEM.getValue(id))) {
                    changed |= states.observe(player.getUUID(), feature.id() + ":item:" + id);
                }
            }
            for (Identifier id : cancel.itemTags()) {
                if (stack.is(TagKey.create(Registries.ITEM, id))) {
                    changed |= states.observe(player.getUUID(), feature.id() + ":tag:" + id);
                }
            }
        }
        if (changed) {
            checkConditions(player, ConditionChange.HISTORY);
        }
    }

    private void evaluateCancellation(ServerPlayer player, Feature feature, ConditionChange change) {
        ProtectionRules.Window window = profile(player).window(feature);
        UUID id = player.getUUID();
        if (window.minutes() == 0 || states.get(id).cancellation(feature) != null) {
            return;
        }
        ProtectionRules.Cancellation cancel = window.cancel();
        boolean relevant = switch (change) {
            case ITEMS -> !cancel.items().isEmpty() || !cancel.itemTags().isEmpty();
            case LEVEL -> cancel.experienceLevel() > 0;
            case ADVANCEMENTS -> !cancel.advancements().isEmpty();
            case TIME -> cancel.playMinutes() > 0;
            case ALL, HISTORY -> true;
        };
        if (!relevant) {
            return;
        }
        List<String> required = new ArrayList<>();
        List<String> observedNow = new ArrayList<>();
        for (Identifier advancement : cancel.advancements()) {
            String condition = "advancement:" + advancement;
            required.add(condition);
            if (change.includes(ConditionChange.ADVANCEMENTS)) {
                var holder = server.getAdvancements().get(advancement);
                if (holder != null && player.getAdvancements().getOrStartProgress(holder).isDone()) {
                    observedNow.add(condition);
                }
            }
        }
        for (Identifier item : cancel.items()) {
            String condition = "item:" + item;
            required.add(condition);
            if (change.includes(ConditionChange.ITEMS) && hasItem(player, stack -> stack.is(BuiltInRegistries.ITEM.getValue(item)))) {
                observedNow.add(condition);
            }
        }
        for (Identifier tag : cancel.itemTags()) {
            String condition = "tag:" + tag;
            required.add(condition);
            if (change.includes(ConditionChange.ITEMS) && hasItem(player, stack -> stack.is(TagKey.create(Registries.ITEM, tag)))) {
                observedNow.add(condition);
            }
        }
        if (cancel.experienceLevel() > 0) {
            String condition = "level:" + cancel.experienceLevel();
            required.add(condition);
            if (change.includes(ConditionChange.LEVEL) && player.experienceLevel >= cancel.experienceLevel()) {
                observedNow.add(condition);
            }
        }
        if (cancel.playMinutes() > 0) {
            String condition = "playMinutes:" + cancel.playMinutes();
            required.add(condition);
            if (change.includes(ConditionChange.TIME) && playedTicks(player) >= cancel.playMinutes() * 1200.0) {
                observedNow.add(condition);
            }
        }
        if (required.isEmpty()) {
            return;
        }
        for (String condition : observedNow) {
            states.observe(id, feature.id() + ":" + condition);
        }
        var observed = states.get(id).observed();
        boolean triggered = cancel.requireAll()
                ? required.stream().allMatch(condition -> observed.contains(feature.id() + ":" + condition))
                : required.stream().anyMatch(condition -> observed.contains(feature.id() + ":" + condition));
        if (triggered) {
            states.cancel(id, feature, "auto: " + String.join(", ", required.stream()
                    .filter(condition -> observed.contains(feature.id() + ":" + condition)).toList()));
            player.sendSystemMessage(Component.literal("[Gentle Start] " + feature.displayName() + "（" + feature.id() + "）已因达到取消条件而结束。"));
        }
    }

    private static boolean hasItem(ServerPlayer player, java.util.function.Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return true;
            }
        }
        return false;
    }

    public void refreshEffects(ServerPlayer player) {
        if (player.isAlive() && active(player, Feature.EFFECTS)) {
            var effects = profile(player).effects();
            int duration = effects.window().minutes() < 0 ? MobEffectInstance.INFINITE_DURATION
                    : (int) Math.min(Integer.MAX_VALUE, Math.ceil(effects.window().minutes() * 1200 - playedTicks(player)));
            for (ProtectionRules.SustainedEffect effect : effects.list()) {
                MobEffectInstance current = player.getEffect(effect.effect());
                int amplifier = effect.level() - 1;
                // Keep vanilla periodic-effect timing and stronger external effects. A small tolerance covers tick ordering.
                if (current == null || current.getAmplifier() < amplifier
                        || current.getAmplifier() == amplifier && !current.isInfiniteDuration()
                        && (duration == MobEffectInstance.INFINITE_DURATION || (long) current.getDuration() + 20 < duration)) {
                    player.addEffect(new MobEffectInstance(effect.effect(), duration, amplifier,
                            false, effect.showParticles(), effect.showIcon()));
                }
            }
        }
    }

    private static void applyEffects(ServerPlayer player, List<ProtectionRules.Effect> effects) {
        for (ProtectionRules.Effect effect : effects) {
            player.addEffect(new MobEffectInstance(effect.effect(), effect.durationSeconds() * 20,
                    effect.level() - 1, false, effect.showParticles(), effect.showIcon()));
        }
    }

    public boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!config.enabled() || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return true;
        }
        if (entity instanceof ServerPlayer victim) {
            ServerPlayer attacker = attacker(source);
            if (attacker != null && attacker != victim) {
                states.endInvulnerability(attacker.getUUID());
                if (active(attacker, Feature.PVP)) {
                    if (profile(attacker).pvp().removeOnAttack()) {
                        states.cancel(attacker.getUUID(), Feature.PVP, "offensive player damage");
                        attacker.sendSystemMessage(Component.literal("[Gentle Start] 主动攻击已解除你的 PvP 保护。"));
                    } else {
                        return false;
                    }
                }
                if (active(victim, Feature.PVP)) {
                    return false;
                }
            }
            if (respawnInvulnerabilityTicks(victim) > 0 || reviveInvulnerabilityTicks(victim) > 0) {
                return false;
            }
            return !active(victim, Feature.DAMAGE_REDUCTION) || profile(victim).damageReduction().reduction() < 1;
        }
        return true;
    }

    public float scaleDamage(ServerPlayer player, DamageSource source, float amount) {
        return source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || !active(player, Feature.DAMAGE_REDUCTION) ? amount
                : (float) (amount * (1 - profile(player).damageReduction().reduction()));
    }

    public boolean revive(ServerPlayer player, DamageSource source) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || !active(player, Feature.REVIVE)) {
            return false;
        }
        ProtectionRules.Revive revive = profile(player).revive();
        int used = states.get(player.getUUID()).revivesUsed();
        if (revive.uses() == 0 || revive.uses() > 0 && used >= revive.uses()) {
            return false;
        }
        states.revive(player.getUUID(), server.overworld().getGameTime() + revive.invulnerabilitySeconds() * 20L);
        player.setHealth(revive.health());
        player.clearFire();
        player.setTicksFrozen(0);
        player.resetFallDistance();
        applyEffects(player, revive.effects());
        refreshEffects(player);
        player.sendSystemMessage(Component.literal("[Gentle Start] 已原地免死，剩余次数："
                + (revive.uses() < 0 ? "不限" : revive.uses() - used - 1)));
        return true;
    }

    public void protectRespawn(ServerPlayer player) {
        if (active(player, Feature.RESPAWN_PROTECTION)) {
            states.protectRespawn(player.getUUID(), server.overworld().getGameTime()
                    + profile(player).respawnProtection().durationSeconds() * 20L);
        }
    }

    public long reviveInvulnerabilityTicks(ServerPlayer player) {
        return active(player, Feature.REVIVE)
                ? Math.max(0, states.get(player.getUUID()).reviveInvulnerableUntil() - server.overworld().getGameTime()) : 0;
    }

    public long respawnInvulnerabilityTicks(ServerPlayer player) {
        return active(player, Feature.RESPAWN_PROTECTION)
                ? Math.max(0, states.get(player.getUUID()).invulnerableUntil() - server.overworld().getGameTime()) : 0;
    }

    public boolean withinMobRange(LivingEntity mob, ServerPlayer player, double originalRange) {
        if (!(mob instanceof Enemy) || !active(player, Feature.MOB_RANGE) || mob.getLastHurtByMob() == player) {
            return true;
        }
        double range = originalRange * profile(player).mobRange().multiplier();
        return mob.distanceToSqr(player) <= range * range;
    }

    private static ServerPlayer attacker(DamageSource source) {
        ServerPlayer player = responsiblePlayer(source.getEntity());
        return player != null ? player : responsiblePlayer(source.getDirectEntity());
    }

    private static ServerPlayer responsiblePlayer(Entity entity) {
        if (entity instanceof ServerPlayer player) return player;
        if (!(entity instanceof OwnableEntity) && !(entity instanceof TraceableEntity)) return null;
        // Owner chains may be supplied by other mods; stop cycles rather than recursing through untrusted ownership.
        var visited = new HashSet<Entity>();
        while (entity != null && visited.add(entity)) {
            if (entity instanceof ServerPlayer player) return player;
            if (entity instanceof OwnableEntity owned) entity = owned.getRootOwner();
            else if (entity instanceof TraceableEntity traceable) entity = traceable.getOwner();
            else return null;
        }
        return null;
    }
}
