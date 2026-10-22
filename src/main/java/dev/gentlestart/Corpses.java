package dev.gentlestart;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

public final class Corpses {
    private Corpses() {
    }

    /** Runs at the normal equipment-drop boundary, after death protection has made its decision. */
    public static boolean capture(ServerPlayer player) {
        ProtectionEngine engine = GentleStart.engine();
        if (player.isSpectator() || player.level().getGameRules().get(GameRules.KEEP_INVENTORY)
                || !engine.active(player, Feature.CORPSE)) return false;
        ProtectionRules.Corpse rule = engine.profile(player).corpse();
        CorpseHolder holder = create(player, rule.mode());
        if (holder == null) {
            player.sendSystemMessage(Component.literal("[Gentle Start] 附近没有可放置尸体的位置，本次使用普通死亡掉落。"));
            return false;
        }
        holder.gentleStart$initCorpse(new CorpseData(player.getGameProfile().name(),
                rule.saveExperience() ? experiencePoints(player) : 0));
        Container target = holder.gentleStart$inventory();
        Inventory source = player.getInventory();
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            ItemStack stack = source.removeItemNoUpdate(slot);
            if (!vanishes(stack)) target.setItem(corpseSlot(slot), stack);
        }
        // Remove cursor and crafting inputs before closing; vanilla must not return them twice.
        ItemStack carried = player.containerMenu.getCarried();
        if (storeExtra(target, carried)) player.containerMenu.setCarried(ItemStack.EMPTY);
        if (player.containerMenu instanceof AbstractCraftingMenu crafting) {
            for (var slot : crafting.getInputGridSlots()) {
                if (storeExtra(target, slot.getItem())) slot.set(ItemStack.EMPTY);
            }
        }
        player.closeContainer();
        // Closing other vanilla workstations can return their inputs to the now-empty inventory.
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            if (storeExtra(target, source.getItem(slot))) source.removeItemNoUpdate(slot);
        }
        target.setChanged();
        ((DeathProtection) player).gentleStart$createdCorpse(rule.saveExperience());
        return true;
    }

    public static int corpseSlot(int inventorySlot) {
        if (inventorySlot < 9) return inventorySlot + 27;
        if (inventorySlot < 36) return inventorySlot - 9;
        if (inventorySlot < 40) return 75 - inventorySlot;
        return inventorySlot == Inventory.SLOT_OFFHAND ? 44 : inventorySlot - 1;
    }

    public static int experiencePoints(ServerPlayer player) {
        int level = player.experienceLevel;
        double base = level <= 16 ? level * (double) level + 6.0 * level
                : level <= 31 ? 2.5 * level * level - 40.5 * level + 360
                : 4.5 * level * level - 162.5 * level + 2220;
        return (int) Math.min(Integer.MAX_VALUE, Math.floor(base + Math.floor(player.experienceProgress * player.getXpNeededForNextLevel())));
    }

    private static boolean vanishes(ItemStack stack) {
        return !stack.isEmpty() && EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP);
    }

    private static boolean storeExtra(Container target, ItemStack stack) {
        if (stack.isEmpty() || vanishes(stack)) return true;
        for (int slot = 0; slot < CorpseData.SIZE; slot++) {
            if (slot != CorpseData.EXPERIENCE_SLOT && target.getItem(slot).isEmpty()) {
                target.setItem(slot, stack);
                return true;
            }
        }
        return false; // Any excess remains with vanilla's normal drop path.
    }

    private static CorpseHolder create(ServerPlayer player, String mode) {
        ServerLevel level = player.level();
        if (mode.equals("player")) {
            // Void deaths need an in-bounds position so the corpse remains retrievable.
            Vec3 position = new Vec3(player.getX(), Math.clamp(player.getY(), level.getMinY() + 1, level.getMaxY() - 1), player.getZ());
            Mannequin model = CorpseModel.spawn(player, position);
            return model == null ? null : (CorpseHolder) model;
        }
        BlockPos origin = BlockPos.containing(player.getX(),
                Math.clamp(player.getY(), level.getMinY() + 1, level.getMaxY() - 1), player.getZ());
        for (BlockPos candidate : BlockPos.withinManhattan(origin, 2, 2, 2)) {
            if (!level.isInWorldBounds(candidate) || !level.getWorldBorder().isWithinBounds(candidate)
                    || !level.getBlockState(candidate).canBeReplaced() || level.getBlockEntity(candidate) != null) continue;
            if (level.setBlockAndUpdate(candidate, Blocks.BARREL.defaultBlockState())
                    && level.getBlockEntity(candidate) instanceof BarrelBlockEntity barrel) return (CorpseHolder) barrel;
        }
        return null;
    }
}
