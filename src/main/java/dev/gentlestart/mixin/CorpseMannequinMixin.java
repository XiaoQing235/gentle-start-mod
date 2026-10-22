package dev.gentlestart.mixin;

import dev.gentlestart.CorpseData;
import dev.gentlestart.CorpseHolder;
import dev.gentlestart.CorpseModel;
import dev.gentlestart.CorpseMenu;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mannequin.class)
abstract class CorpseMannequinMixin extends Avatar implements CorpseHolder {
    @Unique
    private CorpseData gentleStart$corpse;
    @Unique
    private final SimpleContainer gentleStart$inventory = CorpseModel.inventory((Mannequin) (Object) this);

    protected CorpseMannequinMixin(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public CorpseData gentleStart$corpse() {
        return gentleStart$corpse;
    }

    @Override
    public Container gentleStart$inventory() {
        return gentleStart$inventory;
    }

    @Override
    public void gentleStart$initCorpse(CorpseData data) {
        gentleStart$corpse = data;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (gentleStart$corpse == null) return super.interact(player, hand, location);
        if (!player.isSpectator() && gentleStart$inventory.stillValid(player)) {
            if (player.containerMenu instanceof CorpseMenu menu && menu.getContainer() == gentleStart$inventory) {
                return InteractionResult.SUCCESS;
            }
            player.openMenu(new SimpleMenuProvider((id, inventory, viewer) -> new CorpseMenu(id, inventory, this),
                    gentleStart$corpse.title()));
        }
        return InteractionResult.SUCCESS;
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void gentleStart$saveCorpse(ValueOutput output, CallbackInfo callback) {
        if (gentleStart$corpse != null) {
            ValueOutput corpse = output.child(CorpseData.TAG);
            gentleStart$corpse.save(corpse);
            ContainerHelper.saveAllItems(corpse, gentleStart$inventory.getItems());
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void gentleStart$loadCorpse(ValueInput input, CallbackInfo callback) {
        gentleStart$corpse = null;
        gentleStart$inventory.clearContent();
        input.child(CorpseData.TAG).ifPresent(corpse -> {
            gentleStart$corpse = CorpseData.load(corpse);
            ContainerHelper.loadAllItems(corpse, gentleStart$inventory.getItems());
        });
    }

    @Override
    public void kill(ServerLevel level) {
        if (gentleStart$corpse == null) {
            super.kill(level);
            return;
        }
        Containers.dropContents(level, this, gentleStart$inventory);
        gentleStart$inventory.clearContent();
        int experience = gentleStart$corpse.takeExperience();
        if (experience > 0) {
            ExperienceOrb.award(level, position(), experience);
        }
        discard();
    }
}
