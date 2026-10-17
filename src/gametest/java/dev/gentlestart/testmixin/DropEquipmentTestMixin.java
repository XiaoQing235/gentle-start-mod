package dev.gentlestart.testmixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Simulates another mod cancelling or throwing from the vanilla drop method. Only present in GameTests. */
@Mixin(Player.class)
abstract class DropEquipmentTestMixin {
    @Inject(method = "dropEquipment", at = @At("HEAD"), cancellable = true)
    private void interruptDrops(ServerLevel level, CallbackInfo callback) {
        var tags = ((Player) (Object) this).entityTags();
        if (tags.contains("gentlestart_test_throw_drops")) throw new IllegalStateException("intentional test drop failure");
        if (tags.contains("gentlestart_test_cancel_drops")) callback.cancel();
    }
}
