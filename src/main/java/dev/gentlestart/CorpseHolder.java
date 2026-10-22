package dev.gentlestart;

import net.minecraft.world.Container;

/** Storage shared by the two vanilla corpse representations. */
public interface CorpseHolder {
    CorpseData gentleStart$corpse();

    Container gentleStart$inventory();

    void gentleStart$initCorpse(CorpseData data);
}
