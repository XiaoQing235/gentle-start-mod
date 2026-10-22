package dev.gentlestart;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class CorpseData {
    public static final String TAG = "gentle-start-mod:corpse";
    public static final int SIZE = 54;
    public static final int EXPERIENCE_SLOT = 49;
    private final String ownerName;
    private int experience;

    public CorpseData(String ownerName, int experience) {
        this.ownerName = ownerName;
        this.experience = Math.max(0, experience);
    }

    public Component title() {
        return Component.literal(ownerName + " 的遗物");
    }

    public int experience() {
        return experience;
    }

    public int takeExperience() {
        int result = experience;
        experience = 0;
        return result;
    }

    public void save(ValueOutput output) {
        output.putString("ownerName", ownerName);
        output.putInt("experience", experience);
    }

    public static CorpseData load(ValueInput input) {
        return new CorpseData(input.getStringOr("ownerName", "玩家"), input.getIntOr("experience", 0));
    }
}
