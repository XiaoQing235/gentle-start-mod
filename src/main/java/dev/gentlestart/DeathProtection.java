package dev.gentlestart;

/** Immutable decisions for one death, retained until the player respawns. */
public interface DeathProtection {
    DeathSnapshot gentleStart$death();

    boolean gentleStart$protectDrops();

    default boolean gentleStart$keepInventory() {
        return !gentleStart$death().slots().isEmpty();
    }
}
