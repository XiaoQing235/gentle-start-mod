package dev.gentlestart;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;

public final class ProtectionEngine {
    private final MinecraftServer server;
    private final Path configFile;
    private final PlayerStates states;
    private ProtectionConfig config;
    private int nextConditionPlayer;

    public ProtectionEngine(MinecraftServer server, Path configFile, Path stateFile) {
        this.server = server;
        this.configFile = configFile;
        this.config = ProtectionConfig.load(configFile);
        this.states = new PlayerStates(stateFile);
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
    }

    public void replaceConfig(ProtectionConfig candidate) {
        validate(candidate);
        candidate.save(configFile);
        config = candidate;
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
        evaluateCancellation(player, feature);
        return states.get(player.getUUID()).cancellation(feature) == null
                && profile(player).window(feature).includes(playedTicks(player));
    }

    public void checkConditions(ServerPlayer player) {
        if (config.enabled()) {
            for (Feature feature : Feature.values()) {
                evaluateCancellation(player, feature);
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
        int count = Math.min(config.playersPerTick(), players.size());
        for (int checked = 0; checked < count; checked++) {
            nextConditionPlayer %= players.size();
            checkConditions(players.get(nextConditionPlayer));
            nextConditionPlayer++;
        }
    }

    public void observeItem(ServerPlayer player, ItemStack stack) {
        if (!config.enabled() || stack.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Feature feature : Feature.values()) {
            ProtectionRules.Cancellation cancel = profile(player).window(feature).cancel();
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
            states.save();
            checkConditions(player);
        }
    }

    private void evaluateCancellation(ServerPlayer player, Feature feature) {
        ProtectionRules.Window window = profile(player).window(feature);
        UUID id = player.getUUID();
        if (window.minutes() == 0 || states.get(id).cancellation(feature) != null) {
            return;
        }
        ProtectionRules.Cancellation cancel = window.cancel();
        List<String> required = new ArrayList<>();
        List<String> observedNow = new ArrayList<>();
        for (Identifier advancement : cancel.advancements()) {
            String condition = "advancement:" + advancement;
            required.add(condition);
            var holder = server.getAdvancements().get(advancement);
            if (holder != null && player.getAdvancements().getOrStartProgress(holder).isDone()) {
                observedNow.add(condition);
            }
        }
        for (Identifier item : cancel.items()) {
            String condition = "item:" + item;
            required.add(condition);
            if (hasItem(player, stack -> stack.is(BuiltInRegistries.ITEM.getValue(item)))) {
                observedNow.add(condition);
            }
        }
        for (Identifier tag : cancel.itemTags()) {
            String condition = "tag:" + tag;
            required.add(condition);
            if (hasItem(player, stack -> stack.is(TagKey.create(Registries.ITEM, tag)))) {
                observedNow.add(condition);
            }
        }
        if (cancel.experienceLevel() > 0) {
            String condition = "level:" + cancel.experienceLevel();
            required.add(condition);
            if (player.experienceLevel >= cancel.experienceLevel()) {
                observedNow.add(condition);
            }
        }
        if (cancel.playMinutes() > 0) {
            String condition = "playMinutes:" + cancel.playMinutes();
            required.add(condition);
            if (playedTicks(player) >= cancel.playMinutes() * 1200.0) {
                observedNow.add(condition);
            }
        }
        if (required.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String condition : observedNow) {
            changed |= states.observe(id, feature.id() + ":" + condition);
        }
        var observed = states.get(id).observed();
        boolean triggered = cancel.requireAll()
                ? required.stream().allMatch(condition -> observed.contains(feature.id() + ":" + condition))
                : required.stream().anyMatch(condition -> observed.contains(feature.id() + ":" + condition));
        if (triggered) {
            states.cancel(id, feature, "auto: " + String.join(", ", required.stream()
                    .filter(condition -> observed.contains(feature.id() + ":" + condition)).toList()));
            player.sendSystemMessage(Component.literal("[Gentle Start] " + feature.id() + " 已因达到取消条件而结束。"));
        } else if (changed) {
            states.save();
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
            applyEffects(player, profile(player).effects().list());
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
            if (respawnInvulnerabilityTicks(victim) > 0) {
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
        states.revive(player.getUUID());
        player.setHealth(revive.health());
        player.clearFire();
        player.setTicksFrozen(0);
        player.resetFallDistance();
        applyEffects(player, revive.effects());
        protectRespawn(player);
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
        Entity attacker = source.getEntity();
        if (attacker == null && source.getDirectEntity() instanceof Projectile projectile) {
            attacker = projectile.getOwner();
        }
        if (attacker instanceof TamableAnimal tame) {
            attacker = tame.getOwner();
        }
        return attacker instanceof ServerPlayer player ? player : null;
    }
}
