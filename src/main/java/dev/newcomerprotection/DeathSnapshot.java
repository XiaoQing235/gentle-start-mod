package dev.newcomerprotection;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

public record DeathSnapshot(List<Integer> slots, double experienceRatio, boolean keepScore, int level,
                            int totalExperience, float experienceProgress, int score) {
    public static final DeathSnapshot NONE = new DeathSnapshot(List.of(), 0, false, 0, 0, 0, 0);
    public static final Codec<DeathSnapshot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, 42).listOf().fieldOf("slots").forGetter(DeathSnapshot::slots),
            ProtectionRules.FRACTION.fieldOf("experienceRatio").forGetter(DeathSnapshot::experienceRatio),
            Codec.BOOL.fieldOf("keepScore").forGetter(DeathSnapshot::keepScore),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("level").forGetter(DeathSnapshot::level),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("totalExperience").forGetter(DeathSnapshot::totalExperience),
            Codec.floatRange(0, 1).fieldOf("experienceProgress").forGetter(DeathSnapshot::experienceProgress),
            Codec.INT.fieldOf("score").forGetter(DeathSnapshot::score)
    ).apply(instance, DeathSnapshot::new));

    public DeathSnapshot {
        slots = List.copyOf(slots);
    }

    public static DeathSnapshot capture(ServerPlayer player, boolean inventory, double ratio, boolean score) {
        List<Integer> slots = new ArrayList<>();
        ProtectionRules.Selection selection = NewcomerProtection.engine().profile(player).inventory().keep();
        if (inventory) {
            player.closeContainer();
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                if (matches(selection, player.getInventory().getItem(slot), slot)) {
                    slots.add(slot);
                }
            }
        }
        return new DeathSnapshot(slots, ratio, score, player.experienceLevel, player.totalExperience,
                player.experienceProgress, player.getScore());
    }

    public static boolean matches(ProtectionRules.Selection selection, ItemStack stack, int slot) {
        if (selection.all() || selection.slots().contains(slot)) {
            return true;
        }
        String category = slot < 9 ? "hotbar" : slot < 36 ? "main" : slot < 40 ? "armor"
                : slot == Inventory.SLOT_OFFHAND ? "offhand" : slot == Inventory.SLOT_BODY_ARMOR ? "body" : "saddle";
        return selection.categories().contains(category) || !stack.isEmpty()
                && (selection.categories().stream().anyMatch(type -> switch (type) {
                    case "weapons" -> stack.has(DataComponents.WEAPON) || stack.has(DataComponents.PIERCING_WEAPON)
                            || stack.has(DataComponents.KINETIC_WEAPON) || stack.getItem() instanceof ProjectileWeaponItem;
                    case "tools" -> stack.has(DataComponents.TOOL);
                    case "wearable" -> stack.has(DataComponents.EQUIPPABLE);
                    case "food" -> stack.has(DataComponents.FOOD);
                    case "blocks" -> stack.getItem() instanceof BlockItem;
                    default -> false;
                }) || selection.items().contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                || selection.tags().stream().anyMatch(tag -> stack.is(TagKey.create(Registries.ITEM, tag))));
    }

    public void restore(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
        for (int slot : slots) {
            newPlayer.getInventory().setItem(slot, oldPlayer.getInventory().getItem(slot));
        }
        if (!slots.isEmpty()) {
            newPlayer.getInventory().setSelectedSlot(oldPlayer.getInventory().getSelectedSlot());
        }
        if (experienceRatio == 1) {
            newPlayer.experienceLevel = level;
            newPlayer.totalExperience = totalExperience;
            newPlayer.experienceProgress = experienceProgress;
        } else if (experienceRatio > 0) {
            // Vanilla totalExperience is not reduced by enchanting, so derive spendable XP from level/progress.
            double base = level <= 16 ? level * (double) level + 6.0 * level
                    : level <= 31 ? 2.5 * level * level - 40.5 * level + 360
                    : 4.5 * level * level - 162.5 * level + 2220;
            int next = level >= 30 ? 112 + (level - 30) * 9 : level >= 15 ? 37 + (level - 15) * 5 : 7 + level * 2;
            int points = (int) Math.min(Integer.MAX_VALUE, Math.floor((base + Math.floor(experienceProgress * next)) * experienceRatio));
            newPlayer.giveExperiencePoints(points);
        }
        // giveExperiencePoints changes score as well; score retention is an independent protection.
        newPlayer.setScore(keepScore ? score : 0);
    }
}
