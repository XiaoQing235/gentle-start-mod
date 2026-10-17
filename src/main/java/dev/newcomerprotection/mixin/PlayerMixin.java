package dev.newcomerprotection.mixin;

import dev.newcomerprotection.DeathProtection;
import dev.newcomerprotection.NewcomerProtection;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
abstract class PlayerMixin {
    @Unique
    private final Map<Integer, ItemStack> newcomerProtection$retainedItems = new HashMap<>();

    @Inject(method = "dropEquipment", at = @At("HEAD"))
    private void newcomerProtection$hideRetainedItems(ServerLevel level, CallbackInfo callback) {
        if ((Object) this instanceof DeathProtection death) {
            Player player = (Player) (Object) this;
            for (int slot : death.newcomerProtection$death().slots()) {
                newcomerProtection$retainedItems.put(slot, player.getInventory().removeItemNoUpdate(slot));
            }
        }
    }

    @Inject(method = "dropEquipment", at = @At("RETURN"))
    private void newcomerProtection$restoreRetainedItems(ServerLevel level, CallbackInfo callback) {
        Player player = (Player) (Object) this;
        newcomerProtection$retainedItems.forEach(player.getInventory()::setItem);
        newcomerProtection$retainedItems.clear();
    }

    @Inject(method = "getBaseExperienceReward", at = @At("RETURN"), cancellable = true)
    private void newcomerProtection$reduceExperienceDrop(ServerLevel level, CallbackInfoReturnable<Integer> callback) {
        if ((Object) this instanceof DeathProtection death) {
            callback.setReturnValue((int) Math.floor(callback.getReturnValue() * (1 - death.newcomerProtection$death().experienceRatio())));
        }
    }

    @Inject(method = {"giveExperiencePoints", "giveExperienceLevels"}, at = @At("RETURN"))
    private void newcomerProtection$observeLevel(int amount, CallbackInfo callback) {
        NewcomerProtection.checkConditions((Player) (Object) this);
    }
}
