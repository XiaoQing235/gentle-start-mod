package dev.gentlestart;

/** Immutable decisions for one death, retained until the player respawns. */
public interface DeathProtection {
    DeathSnapshot gentleStart$death();

    boolean gentleStart$protectDrops();

    int gentleStart$dropLifetimeSeconds();

    default boolean gentleStart$keepInventory() {
        return !gentleStart$death().slots().isEmpty();
    }
}
