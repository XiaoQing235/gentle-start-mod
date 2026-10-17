package dev.gentlestart.mixin;

import dev.gentlestart.DeathProtection;
import dev.gentlestart.GentleStart;
import dev.gentlestart.ProtectionEngine.ConditionChange;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
abstract class PlayerMixin {
    @WrapMethod(method = "dropEquipment")
    private void gentleStart$dropEquipment(ServerLevel level, Operation<Void> original) {
        if (!((Object) this instanceof DeathProtection death) || death.gentleStart$death().slots().isEmpty()) {
            original.call(level);
            return;
        }
        Player player = (Player) (Object) this;
        Map<Integer, ItemStack> retained = new HashMap<>();
        try {
            for (int slot : death.gentleStart$death().slots()) {
                retained.put(slot, player.getInventory().removeItemNoUpdate(slot));
            }
            // Let vanilla handle curse deletion, equipment and ordinary drops. Inner cancellation/throws still restore our slots.
            original.call(level);
        } finally {
            retained.forEach(player.getInventory()::setItem);
        }
    }

    @Inject(method = "getBaseExperienceReward", at = @At("RETURN"), cancellable = true)
    private void gentleStart$reduceExperienceDrop(ServerLevel level, CallbackInfoReturnable<Integer> callback) {
        if ((Object) this instanceof DeathProtection death) {
            callback.setReturnValue((int) Math.floor(callback.getReturnValue() * (1 - death.gentleStart$death().experienceRatio())));
        }
    }

    @Inject(method = {"giveExperiencePoints", "giveExperienceLevels"}, at = @At("RETURN"))
    private void gentleStart$observeLevel(int amount, CallbackInfo callback) {
        GentleStart.checkConditions((Player) (Object) this, ConditionChange.LEVEL);
    }
}
