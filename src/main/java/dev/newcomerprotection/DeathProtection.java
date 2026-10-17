package dev.newcomerprotection;

/** The decisions made for one death, retained until that player respawns. */
public interface DeathProtection {
    boolean newcomerProtection$keepInventory();

    boolean newcomerProtection$keepExperience();
}
