package dev.gentlestart;

import dev.gentlestart.mixin.MannequinAccessor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class CorpseModel {
    private static final String CORPSE_TAG = "gentle_start_corpse";

    private CorpseModel() {
    }

    public static SimpleContainer inventory(Mannequin mannequin) {
        return new SimpleContainer(CorpseData.SIZE) {
            @Override
            public boolean stillValid(Player player) {
                return !mannequin.isRemoved() && player.level() == mannequin.level()
                        && player.distanceToSqr(mannequin) <= 64;
            }
        };
    }

    public static Mannequin spawn(ServerPlayer owner, Vec3 position) {
        Mannequin mannequin = EntityTypes.MANNEQUIN.create(owner.level(), EntitySpawnReason.EVENT);
        if (mannequin == null) {
            return null;
        }
        MannequinAccessor access = (MannequinAccessor) mannequin;
        access.gentleStart$setProfile(owner.getProfile());
        access.gentleStart$setImmovable(true);
        access.gentleStart$setHideDescription(true);
        mannequin.setPos(position);
        mannequin.setYRot(owner.getYRot());
        mannequin.setYBodyRot(owner.getYRot());
        mannequin.setYHeadRot(owner.getYRot());
        mannequin.setMainArm(owner.getMainArm());
        // A pose without a sleeping position stays horizontal without requiring a bed.
        mannequin.setPose(Pose.SLEEPING);
        mannequin.setInvulnerable(true);
        mannequin.setNoGravity(true);
        mannequin.setSilent(true);
        mannequin.setCustomName(Component.literal(owner.getGameProfile().name() + "'s corpse"));
        mannequin.addTag(CORPSE_TAG);
        return owner.level().addFreshEntity(mannequin) ? mannequin : null;
    }
}
