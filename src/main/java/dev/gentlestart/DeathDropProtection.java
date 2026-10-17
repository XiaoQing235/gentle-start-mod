package dev.gentlestart;

/** Protection attached to the dropped entity, independently of its item stack. */
public interface DeathDropProtection {
    void gentleStart$protect(int lifetimeSeconds);

    boolean gentleStart$isProtected();
}
