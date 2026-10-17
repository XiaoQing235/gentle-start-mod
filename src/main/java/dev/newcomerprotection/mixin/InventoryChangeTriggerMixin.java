package dev.newcomerprotection.mixin;

import dev.newcomerprotection.NewcomerProtection;
import net.minecraft.advancements.triggers.InventoryChangeTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryChangeTrigger.class)
abstract class InventoryChangeTriggerMixin {
    @Inject(method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("RETURN"))
    private void newcomerProtection$observeItem(ServerPlayer player, Inventory inventory, ItemStack stack, CallbackInfo callback) {
        NewcomerProtection.engine().observeItem(player, stack);
    }
}
