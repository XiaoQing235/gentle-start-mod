package dev.gentlestart;

/** Immutable decisions for one death, retained until the player respawns. */
public interface DeathProtection {
    DeathSnapshot gentleStart$death();

    boolean gentleStart$protectDrops();

    int gentleStart$dropLifetimeSeconds();

    void gentleStart$createdCorpse(boolean savesExperience);

    boolean gentleStart$corpseExperience();

    default boolean gentleStart$keepInventory() {
        return !gentleStart$death().slots().isEmpty();
    }
}
