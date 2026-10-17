package dev.gentlestart;

import java.util.Arrays;

public enum Feature {
    INVENTORY("inventory"), EXPERIENCE("experience"), SCORE("score"), REVIVE("revive"),
    EFFECTS("effects"), PVP("pvp"), DAMAGE_REDUCTION("damageReduction"), MOB_RANGE("mobRange"),
    RESPAWN_PROTECTION("respawnProtection"), DEATH_DROPS("deathDrops");

    private final String id;

    Feature(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String configName() {
        return switch (this) {
            case INVENTORY -> "keepInventory";
            case EXPERIENCE -> "keepExperience";
            case SCORE -> "keepScore";
            case REVIVE -> "preventDeath";
            case EFFECTS -> "protectionEffects";
            case PVP -> "pvpProtection";
            case DAMAGE_REDUCTION -> "damageReduction";
            case MOB_RANGE -> "mobDetectionRange";
            case RESPAWN_PROTECTION -> "respawnInvulnerability";
            case DEATH_DROPS -> "protectDeathDrops";
        };
    }

    public static Feature parse(String id) {
        return Arrays.stream(values()).filter(feature -> (feature.id.equals(id) || feature.configName().equals(id))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown protection: " + id));
    }
}
