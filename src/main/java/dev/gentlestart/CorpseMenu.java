package dev.gentlestart;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A vanilla six-row menu; the experience icon is never a stored or transferable item. */
public final class CorpseMenu extends ChestMenu {
    private final CorpseData corpse;

    public CorpseMenu(int id, Inventory inventory, CorpseHolder holder) {
        super(MenuType.GENERIC_9x6, id, inventory, holder.gentleStart$inventory(), 6);
        corpse = holder.gentleStart$corpse();
        for (int i = 0; i < CorpseData.SIZE; i++) {
            Slot old = slots.get(i);
            final int index = i;
            Slot replacement = new Slot(getContainer(), i, old.x, old.y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean mayPickup(Player player) {
                    return index != CorpseData.EXPERIENCE_SLOT;
                }

                @Override
                public ItemStack getItem() {
                    if (index != CorpseData.EXPERIENCE_SLOT) return super.getItem();
                    if (corpse.experience() == 0) return ItemStack.EMPTY;
                    ItemStack bottle = new ItemStack(Items.EXPERIENCE_BOTTLE);
                    bottle.set(DataComponents.CUSTOM_NAME, Component.literal("点击领取 " + corpse.experience() + " 点经验"));
                    return bottle;
                }
            };
            replacement.index = i;
            slots.set(i, replacement);
        }
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (!stillValid(player) || player.isSpectator()) return;
        if (slot == CorpseData.EXPERIENCE_SLOT) {
            if ((input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) && (button == 0 || button == 1)) {
                int points = corpse.takeExperience();
                getContainer().setChanged();
                player.giveExperiencePoints(points);
            }
            broadcastChanges();
            return;
        }
        super.clicked(slot, button, input, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return slot < 0 || slot >= CorpseData.SIZE || slot == CorpseData.EXPERIENCE_SLOT
                ? ItemStack.EMPTY : super.quickMoveStack(player, slot);
    }
}
