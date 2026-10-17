package dev.newcomerprotection;

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

    public static Feature parse(String id) {
        return Arrays.stream(values()).filter(feature -> feature.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown protection: " + id));
    }
}
