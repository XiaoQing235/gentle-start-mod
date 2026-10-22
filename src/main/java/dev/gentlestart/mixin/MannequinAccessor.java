package dev.gentlestart.mixin;

import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Mannequin.class)
public interface MannequinAccessor {
    @Invoker("setProfile")
    void gentleStart$setProfile(ResolvableProfile profile);

    @Invoker("setImmovable")
    void gentleStart$setImmovable(boolean immovable);

    @Invoker("setHideDescription")
    void gentleStart$setHideDescription(boolean hidden);
}
