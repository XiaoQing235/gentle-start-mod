package dev.gentlestart.mixin;

import dev.gentlestart.CorpseData;
import dev.gentlestart.CorpseHolder;
import dev.gentlestart.CorpseMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BarrelBlockEntity.class)
abstract class CorpseBarrelMixin extends RandomizableContainerBlockEntity implements CorpseHolder {
    @Shadow private NonNullList<ItemStack> items;
    @Unique private CorpseData gentleStart$corpse;

    protected CorpseBarrelMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public CorpseData gentleStart$corpse() {
        return gentleStart$corpse;
    }

    @Override
    public Container gentleStart$inventory() {
        return this;
    }

    @Override
    public void gentleStart$initCorpse(CorpseData data) {
        gentleStart$corpse = data;
        items = NonNullList.withSize(CorpseData.SIZE, ItemStack.EMPTY);
        setChanged();
    }

    @Override
    public Component getName() {
        return gentleStart$corpse == null ? super.getName() : gentleStart$corpse.title();
    }

    @Override
    public boolean canOpen(Player player) {
        return (gentleStart$corpse == null || gentleStart$corpse.canAccess(player)) && super.canOpen(player);
    }

    @Override
    public boolean stillValid(Player player) {
        return (gentleStart$corpse == null || gentleStart$corpse.canAccess(player)) && super.stillValid(player);
    }

    @Inject(method = "getContainerSize", at = @At("HEAD"), cancellable = true)
    private void gentleStart$size(CallbackInfoReturnable<Integer> callback) {
        if (gentleStart$corpse != null) callback.setReturnValue(CorpseData.SIZE);
    }

    @Inject(method = "loadAdditional", at = @At("HEAD"))
    private void gentleStart$load(ValueInput input, CallbackInfo callback) {
        // Vanilla allocates the item list using getContainerSize immediately afterwards.
        gentleStart$corpse = input.child(CorpseData.TAG).map(CorpseData::load).orElse(null);
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void gentleStart$save(ValueOutput output, CallbackInfo callback) {
        if (gentleStart$corpse != null) gentleStart$corpse.save(output.child(CorpseData.TAG));
    }

    @Inject(method = "createMenu", at = @At("HEAD"), cancellable = true)
    private void gentleStart$menu(int id, Inventory inventory, CallbackInfoReturnable<AbstractContainerMenu> callback) {
        if (gentleStart$corpse != null) callback.setReturnValue(new CorpseMenu(id, inventory, this));
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return gentleStart$corpse == null && super.canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItem(Container into, int slot, ItemStack stack) {
        return gentleStart$corpse == null && super.canTakeItem(into, slot, stack);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (gentleStart$corpse != null && level instanceof ServerLevel serverLevel) {
            ExperienceOrb.award(serverLevel, Vec3.atCenterOf(pos), gentleStart$corpse.takeExperience());
        }
    }
}
