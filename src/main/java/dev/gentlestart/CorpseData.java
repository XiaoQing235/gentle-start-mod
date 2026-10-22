package dev.gentlestart;

import java.util.UUID;
import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class CorpseData {
    public static final String TAG = "gentle-start-mod:corpse";
    public static final int SIZE = 54;
    public static final int EXPERIENCE_SLOT = 49;
    private final UUID ownerId;
    private final String ownerName;
    private int experience;

    public CorpseData(UUID ownerId, String ownerName, int experience) {
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.experience = experience;
    }

    public Component title() {
        return Component.literal(ownerName + " 的遗物");
    }

    public boolean isOwner(Player player) {
        return ownerId.equals(player.getUUID());
    }

    public boolean canAccess(Player player) {
        return !player.isSpectator() && isOwner(player);
    }

    public void denyAccess(Player player) {
        player.sendOverlayMessage(Component.literal("[Gentle Start] 只有尸体所有者可以取回遗物。"));
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
        output.store("ownerId", UUIDUtil.CODEC, ownerId);
        output.putString("ownerName", ownerName);
        output.putInt("experience", experience);
    }

    public static CorpseData load(ValueInput input) {
        return new CorpseData(input.read("ownerId", UUIDUtil.CODEC).orElseThrow(),
                input.getString("ownerName").orElseThrow(),
                input.read("experience", Codec.intRange(0, Integer.MAX_VALUE)).orElseThrow());
    }
}
