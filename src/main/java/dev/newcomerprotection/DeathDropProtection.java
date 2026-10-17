package dev.newcomerprotection;

/** Protection attached to the dropped entity, independently of its item stack. */
public interface DeathDropProtection {
    void newcomerProtection$protect();

    boolean newcomerProtection$isProtected();
}
