package dev.gentlestart;

import java.util.Arrays;

public enum Feature {
    INVENTORY("keepInventory"), EXPERIENCE("keepExperience"), SCORE("keepScore"), REVIVE("preventDeath"),
    EFFECTS("protectionEffects"), PVP("pvpProtection"), DAMAGE_REDUCTION("damageReduction"), MOB_RANGE("mobDetectionRange"),
    RESPAWN_PROTECTION("respawnInvulnerability"), DEATH_DROPS("protectDeathDrops"), CORPSE("deathCorpse");

    private final String id;

    Feature(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return switch (this) {
            case INVENTORY -> "物品保留";
            case EXPERIENCE -> "经验保留";
            case SCORE -> "分数保留";
            case REVIVE -> "原地免死";
            case EFFECTS -> "持续效果";
            case PVP -> "PvP 保护";
            case DAMAGE_REDUCTION -> "伤害减免";
            case MOB_RANGE -> "怪物感知范围";
            case RESPAWN_PROTECTION -> "重生无敌";
            case DEATH_DROPS -> "死亡掉落物保护";
            case CORPSE -> "死亡尸体";
        };
    }

    public static Feature parse(String id) {
        return Arrays.stream(values()).filter(feature -> feature.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown protection: " + id));
    }
}
