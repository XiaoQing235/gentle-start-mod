package dev.newcomerprotection;

/** Immutable decisions for one death, retained until the player respawns. */
public interface DeathProtection {
    DeathSnapshot newcomerProtection$death();

    boolean newcomerProtection$protectDrops();

    default boolean newcomerProtection$keepInventory() {
        return !newcomerProtection$death().slots().isEmpty();
    }
}
