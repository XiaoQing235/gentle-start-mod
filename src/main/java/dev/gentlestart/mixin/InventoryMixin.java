package dev.gentlestart.mixin;

import dev.gentlestart.GentleStart;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
abstract class InventoryMixin {
    @Shadow @Final
    public Player player;

    @Inject(method = "setItem", at = @At("RETURN"))
    private void gentleStart$observeSet(int slot, ItemStack stack, CallbackInfo callback) {
        GentleStart.checkConditions(player);
    }

    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("RETURN"))
    private void gentleStart$observeAdd(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> callback) {
        GentleStart.checkConditions(player);
    }
}
