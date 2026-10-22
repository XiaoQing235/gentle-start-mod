package dev.gentlestart.mixin;

import dev.gentlestart.CorpseHolder;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BarrelBlock.class)
abstract class CorpseBarrelBlockMixin extends BaseEntityBlock {
    protected CorpseBarrelBlockMixin(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected void onExplosionHit(BlockState state, ServerLevel level, BlockPos pos, Explosion explosion,
                                  BiConsumer<ItemStack, BlockPos> onHit) {
        if (level.getBlockEntity(pos) instanceof CorpseHolder holder && holder.gentleStart$corpse() != null) return;
        super.onExplosionHit(state, level, pos, explosion, onHit);
    }
}
