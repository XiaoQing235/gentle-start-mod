package dev.gentlestart.mixin;

import dev.gentlestart.DeathProtection;
import dev.gentlestart.GentleStart;
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
    private final Map<Integer, ItemStack> gentleStart$retainedItems = new HashMap<>();

    @Inject(method = "dropEquipment", at = @At("HEAD"))
    private void gentleStart$hideRetainedItems(ServerLevel level, CallbackInfo callback) {
        if ((Object) this instanceof DeathProtection death) {
            Player player = (Player) (Object) this;
            for (int slot : death.gentleStart$death().slots()) {
                gentleStart$retainedItems.put(slot, player.getInventory().removeItemNoUpdate(slot));
            }
        }
    }

    @Inject(method = "dropEquipment", at = @At("RETURN"))
    private void gentleStart$restoreRetainedItems(ServerLevel level, CallbackInfo callback) {
        Player player = (Player) (Object) this;
        gentleStart$retainedItems.forEach(player.getInventory()::setItem);
        gentleStart$retainedItems.clear();
    }

    @Inject(method = "getBaseExperienceReward", at = @At("RETURN"), cancellable = true)
    private void gentleStart$reduceExperienceDrop(ServerLevel level, CallbackInfoReturnable<Integer> callback) {
        if ((Object) this instanceof DeathProtection death) {
            callback.setReturnValue((int) Math.floor(callback.getReturnValue() * (1 - death.gentleStart$death().experienceRatio())));
        }
    }

    @Inject(method = {"giveExperiencePoints", "giveExperienceLevels"}, at = @At("RETURN"))
    private void gentleStart$observeLevel(int amount, CallbackInfo callback) {
        GentleStart.checkConditions((Player) (Object) this);
    }
}
