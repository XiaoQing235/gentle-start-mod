package dev.gentlestart;

import static dev.gentlestart.ExpandedProtectionGameTests.settings;
import static dev.gentlestart.ProtectionGameTests.player;
import static dev.gentlestart.ProtectionGameTests.respawn;
import static dev.gentlestart.ProtectionGameTests.withConfig;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class CorpseGameTests {
    @GameTest
    public void barrelDeathPreservesLayoutWithoutRespawnCopies(GameTestHelper test) {
        withConfig(test, config("barrel", true), () -> assertDeathLayout(test, "barrel"));
        test.succeed();
    }

    @GameTest
    public void playerDeathPreservesLayoutAndOpensFromRightClick(GameTestHelper test) {
        withConfig(test, config("player", true), () -> {
            ServerPlayer visitor = assertDeathLayout(test, "player");
            CorpseHolder holder = corpses(test).getFirst();
            Mannequin model = (Mannequin) holder;
            visitor.snapTo(model.position().add(1, 0, 0));
            visitor.interactOn(model, InteractionHand.MAIN_HAND, Vec3.ZERO);
            test.assertTrue(visitor.containerMenu instanceof CorpseMenu, "Right-click opens the corpse's inventory");
            CorpseMenu menu = (CorpseMenu) visitor.containerMenu;
            test.assertValueEqual(menu.getRowCount(), 6, "Player corpse uses the vanilla six-row menu");
            test.assertTrue(menu.getContainer() == holder.gentleStart$inventory(), "Interaction opens the persisted inventory");
            visitor.interactOn(model, InteractionHand.OFF_HAND, Vec3.ZERO);
            test.assertTrue(visitor.containerMenu == menu, "The second hand's interaction does not replace an already-open corpse menu");
            test.assertValueEqual(UseItemCallback.EVENT.invoker().interact(visitor, test.getLevel(), InteractionHand.MAIN_HAND),
                    InteractionResult.FAIL, "A use-item packet cannot consume or place held items while the corpse menu is open");
            visitor.closeContainer();
            test.assertValueEqual(UseItemCallback.EVENT.invoker().interact(visitor, test.getLevel(), InteractionHand.MAIN_HAND),
                    InteractionResult.PASS, "Closing the corpse restores ordinary item use");
        });
        test.succeed();
    }

    @GameTest
    public void experienceButtonClaimsOnceWithoutBecomingAnItem(GameTestHelper test) {
        withConfig(test, settings("{\"corpse\":{\"minutes\":-1,\"mode\":\"barrel\",\"saveExperience\":true},"
                + "\"experience\":{\"minutes\":-1}}"), () -> {
            ServerPlayer owner = player(test, 0);
            owner.getInventory().setItem(0, new ItemStack(Items.BREAD, 3));
            owner.experienceLevel = 10;
            owner.experienceProgress = 0.5F;
            owner.totalExperience = 9999;
            owner.kill(test.getLevel());
            CorpseHolder holder = corpses(test).getFirst();
            ServerPlayer first = respawn(owner);
            ServerPlayer second = player(test, 0);
            BarrelBlockEntity barrel = (BarrelBlockEntity) holder;
            Vec3 viewingPosition = Vec3.atCenterOf(barrel.getBlockPos()).add(1, 0, 0);
            first.snapTo(viewingPosition);
            second.snapTo(viewingPosition);
            test.assertTrue(test.getLevel().getBlockEntity(barrel.getBlockPos()) == barrel, "The opened barrel remains in the world");
            for (ServerPlayer visitor : List.of(first, second)) {
                test.assertTrue(visitor.position().distanceToSqr(Vec3.atCenterOf(barrel.getBlockPos())) < 4,
                        "The visitor is physically beside the captured barrel");
                test.assertTrue(visitor.isWithinBlockInteractionRange(barrel.getBlockPos(), 4), "Native block interaction range accepts the visitor");
                test.assertFalse(visitor.isSpectator(), "Both test players are ordinary nonspectators");
            }
            CorpseMenu firstMenu = new CorpseMenu(1, first.getInventory(), holder);
            CorpseMenu secondMenu = new CorpseMenu(2, second.getInventory(), holder);
            test.assertTrue(firstMenu.stillValid(first), "The respawned owner's menu is valid before claiming");
            test.assertFalse(secondMenu.stillValid(second), "The concurrent nonowner's menu is invalid before claiming");
            test.assertTrue(firstMenu.getSlot(49).getItem().is(Items.EXPERIENCE_BOTTLE), "Experience is shown in row six, column five");
            test.assertValueEqual(first.totalExperience, 0, "Corpse storage prevents personal experience retention from duplicating the claim");
            secondMenu.clicked(49, 0, ContainerInput.PICKUP, second);
            secondMenu.clicked(27, 0, ContainerInput.QUICK_MOVE, second);
            test.assertValueEqual(second.totalExperience, 0, "A nonowner cannot claim experience before the owner");
            test.assertValueEqual(second.getInventory().countItem(Items.BREAD), 0, "A nonowner cannot take items before the owner");
            test.assertValueEqual(holder.gentleStart$corpse().experience(), 173, "Denied access preserves all experience for the owner");
            int drops = drops(test).size();
            first.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
            firstMenu.clicked(49, 0, ContainerInput.SWAP, first);
            firstMenu.clicked(49, 0, ContainerInput.THROW, first);
            test.assertValueEqual(holder.gentleStart$corpse().experience(), 173, "Swap and drop do not consume stored experience");
            test.assertTrue(first.getInventory().getItem(0).is(Items.DIAMOND), "Number-key swap preserves the real hotbar stack");
            test.assertTrue(firstMenu.getCarried().isEmpty(), "The experience icon never reaches the cursor");
            test.assertValueEqual(drops(test).size(), drops, "The experience icon cannot be dropped");
            firstMenu.clicked(49, 0, ContainerInput.PICKUP, first);
            test.assertValueEqual(first.totalExperience, 173, "Click awards spendable experience, not the historical total");
            test.assertValueEqual(holder.gentleStart$corpse().experience(), 0, "The claim consumes the shared experience balance");
            firstMenu.clicked(49, 0, ContainerInput.PICKUP, first);
            secondMenu.clicked(49, 0, ContainerInput.PICKUP, second);
            test.assertValueEqual(first.totalExperience, 173, "Repeat clicks cannot claim again");
            test.assertValueEqual(second.totalExperience, 0, "The nonowner still cannot claim after the owner");
            test.assertTrue(firstMenu.getCarried().isEmpty(), "Claiming awards points without taking a bottle");
            test.assertValueEqual(first.getInventory().countItem(Items.EXPERIENCE_BOTTLE), 0, "Claiming does not create a usable bottle");
            test.assertTrue(firstMenu.getSlot(49).getItem().isEmpty(), "The claimed experience icon disappears");
            firstMenu.clicked(27, 0, ContainerInput.QUICK_MOVE, first);
            secondMenu.clicked(27, 0, ContainerInput.QUICK_MOVE, second);
            test.assertValueEqual(first.getInventory().countItem(Items.BREAD), 3, "Shift-click transfers the original stored stack");
            test.assertValueEqual(second.getInventory().countItem(Items.BREAD), 0, "Concurrent menus cannot transfer the same stack twice");
            test.assertTrue(holder.gentleStart$inventory().getItem(27).isEmpty(), "Taking a stack empties the persisted source slot");
            firstMenu.removed(first);
            secondMenu.removed(second);
        });
        test.succeed();
    }

    @GameTest
    public void corpsePersistenceRetainsLastSlotAndExperience(GameTestHelper test) {
        for (String mode : List.of("barrel", "player")) {
            withConfig(test, config(mode, true), () -> {
                List<CorpseHolder> before = corpses(test);
                ServerPlayer owner = player(test, 0);
                owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
                owner.experienceLevel = 10;
                owner.kill(test.getLevel());
                CorpseHolder holder = corpses(test).stream().filter(corpse -> !before.contains(corpse)).findFirst().orElseThrow();
                holder.gentleStart$inventory().setItem(53, new ItemStack(Items.EMERALD, 17));
                CorpseHolder restored;
                if (holder instanceof BarrelBlockEntity barrel) {
                    var tag = barrel.saveWithFullMetadata(test.getLevel().registryAccess());
                    BarrelBlockEntity loaded = new BarrelBlockEntity(barrel.getBlockPos(), barrel.getBlockState());
                    loaded.setLevel(test.getLevel());
                    loaded.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), tag));
                    restored = (CorpseHolder) loaded;
                } else {
                    Mannequin model = (Mannequin) holder;
                    TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, test.getLevel().registryAccess());
                    model.saveWithoutId(output);
                    Mannequin loaded = EntityTypes.MANNEQUIN.create(test.getLevel(), EntitySpawnReason.LOAD);
                    loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), output.buildResult()));
                    restored = (CorpseHolder) loaded;
                    test.assertValueEqual(loaded.getPose(), Pose.SLEEPING, "The corpse remains horizontal after loading");
                    test.assertValueEqual(loaded.getProfile(), model.getProfile(), "The player's skin profile survives saving");
                }
                test.assertTrue(restored.gentleStart$corpse() != null, "Corpse metadata survives saving: " + mode);
                test.assertTrue(restored.gentleStart$corpse().isOwner(owner), "The owner's UUID survives saving: " + mode);
                test.assertValueEqual(restored.gentleStart$inventory().getContainerSize(), 54, "All six rows survive loading: " + mode);
                assertStack(test, restored.gentleStart$inventory().getItem(27), new ItemStack(Items.DIAMOND, 7), "Original hotbar stack survives: " + mode);
                assertStack(test, restored.gentleStart$inventory().getItem(53), new ItemStack(Items.EMERALD, 17), "Last extra slot survives: " + mode);
                test.assertValueEqual(restored.gentleStart$corpse().experience(), 160, "Saved experience survives: " + mode);
            });
        }
        test.succeed();
    }

    @GameTest
    public void corpseOwnerIdentityUsesPersistedUuidAndFailsClosedForLegacyData(GameTestHelper test) {
        ServerPlayer owner = player(test, 0);
        CorpseData original = new CorpseData(owner.getUUID(), "OriginalOwner", 160);
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, test.getLevel().registryAccess());
        original.save(output);
        var tag = output.buildResult();
        CorpseData restored = CorpseData.load(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), tag));
        Player renamedOwner = identityPlayer(test, owner.getUUID(), "RenamedOwner");
        Player sameNameStranger = identityPlayer(test, UUID.randomUUID(), "OriginalOwner");
        test.assertTrue(restored.canAccess(owner), "A saved corpse accepts its original owner's UUID");
        test.assertTrue(restored.canAccess(renamedOwner), "A name change does not lock out the same owner UUID");
        test.assertFalse(restored.isOwner(sameNameStranger), "Matching the stored display name never grants ownership");
        test.assertFalse(restored.canAccess(sameNameStranger), "Another UUID cannot access a same-name corpse");
        tag.remove("ownerId");
        CorpseData legacy = CorpseData.load(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), tag));
        test.assertFalse(legacy.canAccess(owner), "A legacy corpse without an owner UUID is not public");
        test.assertFalse(legacy.canAccess(sameNameStranger), "Legacy names cannot be used to claim an unowned corpse");
        test.assertValueEqual(legacy.experience(), 160, "Rejecting a legacy identity preserves its saved contents");
        tag.putString("ownerId", "not-a-uuid");
        CorpseData malformed = CorpseData.load(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), tag));
        test.assertFalse(malformed.canAccess(owner), "An invalid saved owner UUID also fails closed");
        test.succeed();
    }

    @GameTest
    public void bothCorpseModesDenyNonownerOpenAndForgedMenuTransfers(GameTestHelper test) {
        for (String mode : List.of("barrel", "player")) {
            withConfig(test, config(mode, true), () -> {
                List<CorpseHolder> before = corpses(test);
                ServerPlayer deadOwner = player(test, 0);
                deadOwner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
                deadOwner.giveExperiencePoints(160);
                deadOwner.kill(test.getLevel());
                CorpseHolder holder = corpses(test).stream().filter(corpse -> !before.contains(corpse)).findFirst().orElseThrow();
                ServerPlayer owner = respawn(deadOwner);
                ServerPlayer stranger = player(test, 0);
                Vec3 position = holder instanceof BarrelBlockEntity barrel
                        ? Vec3.atCenterOf(barrel.getBlockPos()) : ((Mannequin) holder).position();
                owner.snapTo(position.add(1, 0, 0));
                stranger.snapTo(position.add(1, 0, 0));
                openCorpse(stranger, holder);
                test.assertTrue(stranger.containerMenu == stranger.inventoryMenu,
                        "The actual interaction refuses a nonowner's open request: " + mode);
                CorpseMenu forged = new CorpseMenu(5, stranger.getInventory(), holder);
                test.assertFalse(forged.stillValid(stranger), "A forged nonowner menu is invalid: " + mode);
                test.assertFalse(forged.getSlot(27).mayPickup(stranger), "Stored slots reject nonowner pickup: " + mode);
                test.assertTrue(forged.quickMoveStack(stranger, 27).isEmpty(), "Direct quick-move rejects nonowners: " + mode);
                forged.clicked(27, 0, ContainerInput.PICKUP, stranger);
                forged.clicked(27, 0, ContainerInput.QUICK_MOVE, stranger);
                forged.clicked(49, 0, ContainerInput.PICKUP, stranger);
                forged.clicked(49, 0, ContainerInput.QUICK_MOVE, stranger);
                test.assertTrue(forged.getCarried().isEmpty(), "Denied clicks never move a stack to the cursor: " + mode);
                test.assertValueEqual(stranger.getInventory().countItem(Items.DIAMOND), 0, "Denied clicks never transfer stored items: " + mode);
                test.assertValueEqual(stranger.totalExperience, 0, "Denied clicks never award experience: " + mode);
                test.assertValueEqual(holder.gentleStart$inventory().countItem(Items.DIAMOND), 7, "All items remain for the owner: " + mode);
                test.assertValueEqual(holder.gentleStart$corpse().experience(), 160, "All experience remains for the owner: " + mode);
                forged.removed(stranger);
                openCorpse(owner, holder);
                test.assertTrue(owner.containerMenu instanceof CorpseMenu, "The respawned owner can open the corpse: " + mode);
                CorpseMenu authorized = (CorpseMenu) owner.containerMenu;
                test.assertTrue(authorized.stillValid(owner), "The owner's actual menu remains valid: " + mode);
                authorized.clicked(27, 0, ContainerInput.QUICK_MOVE, owner);
                authorized.clicked(49, 0, ContainerInput.PICKUP, owner);
                test.assertValueEqual(owner.getInventory().countItem(Items.DIAMOND), 7, "The owner can retrieve the original items: " + mode);
                test.assertValueEqual(owner.totalExperience, 160, "The owner can retrieve the original experience: " + mode);
                owner.closeContainer();
            });
        }
        test.succeed();
    }

    @GameTest
    public void hoppersCannotExtractCorpseItemsButCanExtractOrdinaryBarrels(GameTestHelper test) {
        withConfig(test, config("barrel", true), () -> {
            ServerPlayer owner = player(test, 0);
            owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
            owner.kill(test.getLevel());
            BarrelBlockEntity corpse = (BarrelBlockEntity) corpses(test).getFirst();
            BlockPos hopperPosition = corpse.getBlockPos().below();
            test.getLevel().setBlockAndUpdate(hopperPosition, Blocks.HOPPER.defaultBlockState());
            HopperBlockEntity hopper = (HopperBlockEntity) test.getLevel().getBlockEntity(hopperPosition);
            test.assertFalse(HopperBlockEntity.suckInItems(test.getLevel(), hopper), "A real hopper cannot bypass corpse ownership");
            test.assertValueEqual(hopper.countItem(Items.DIAMOND), 0, "The hopper receives no corpse items");
            test.assertValueEqual(corpse.countItem(Items.DIAMOND), 7, "Hopper extraction preserves every corpse item");
            BlockPos ordinaryPosition = corpse.getBlockPos().east(2);
            test.getLevel().setBlockAndUpdate(ordinaryPosition, Blocks.BARREL.defaultBlockState());
            test.getLevel().setBlockAndUpdate(ordinaryPosition.below(), Blocks.HOPPER.defaultBlockState());
            BarrelBlockEntity ordinary = (BarrelBlockEntity) test.getLevel().getBlockEntity(ordinaryPosition);
            HopperBlockEntity ordinaryHopper = (HopperBlockEntity) test.getLevel().getBlockEntity(ordinaryPosition.below());
            ordinary.setItem(0, new ItemStack(Items.EMERALD, 3));
            test.assertTrue(HopperBlockEntity.suckInItems(test.getLevel(), ordinaryHopper), "An ordinary barrel still permits hopper extraction");
            test.assertValueEqual(ordinaryHopper.countItem(Items.EMERALD), 1, "Ordinary extraction transfers one item");
            test.assertValueEqual(ordinary.countItem(Items.EMERALD), 2, "Ordinary extraction removes only the transferred item");
        });
        test.succeed();
    }

    @GameTest
    public void corpseBarrelsBlockNonownerBreakingAndExplosionsButAllowOwnerBreaking(GameTestHelper test) {
        withConfig(test, config("barrel", true), () -> {
            ServerPlayer deadOwner = player(test, 0);
            deadOwner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
            deadOwner.giveExperiencePoints(160);
            deadOwner.kill(test.getLevel());
            CorpseHolder holder = corpses(test).getFirst();
            BarrelBlockEntity barrel = (BarrelBlockEntity) holder;
            BlockPos position = barrel.getBlockPos();
            ServerPlayer stranger = player(test, 0);
            test.assertFalse(stranger.gameMode.destroyBlock(position), "A survival nonowner cannot break the corpse barrel");
            stranger.setGameMode(GameType.CREATIVE);
            test.assertFalse(stranger.gameMode.destroyBlock(position), "Creative mode does not bypass corpse ownership");
            test.assertTrue(test.getLevel().getBlockEntity(position) == barrel, "Denied breaking keeps the same corpse in the world");
            BlockPos ordinaryPosition = position.east(2);
            test.getLevel().setBlockAndUpdate(ordinaryPosition, Blocks.BARREL.defaultBlockState());
            ServerExplosion explosion = new ServerExplosion(test.getLevel(), stranger, null, null,
                    Vec3.atCenterOf(position), 4, false, Explosion.BlockInteraction.DESTROY);
            int diamonds = droppedCount(test, Items.DIAMOND);
            int experience = droppedExperience(test);
            test.getLevel().getBlockState(position).onExplosionHit(test.getLevel(), position, explosion, (stack, pos) -> {});
            test.getLevel().getBlockState(ordinaryPosition).onExplosionHit(test.getLevel(), ordinaryPosition, explosion, (stack, pos) -> {});
            test.assertTrue(test.getLevel().getBlockEntity(position) == barrel, "An explosion cannot destroy the locked corpse barrel");
            test.assertTrue(test.getLevel().getBlockState(ordinaryPosition).isAir(), "Ordinary barrels retain vanilla explosion destruction");
            test.assertValueEqual(barrel.countItem(Items.DIAMOND), 7, "Denied destruction preserves the stored items");
            test.assertValueEqual(holder.gentleStart$corpse().experience(), 160, "Denied destruction preserves the stored experience");
            test.assertValueEqual(droppedCount(test, Items.DIAMOND), diamonds, "Denied destruction releases no loot");
            test.assertValueEqual(droppedExperience(test), experience, "Denied destruction releases no experience");
            ServerPlayer owner = respawn(deadOwner);
            owner.snapTo(Vec3.atCenterOf(position).add(1, 0, 0));
            test.assertTrue(owner.gameMode.destroyBlock(position), "The owner can deliberately break their own corpse barrel");
            test.assertTrue(test.getLevel().getBlockState(position).isAir(), "Owner breaking removes the barrel");
            test.assertValueEqual(droppedCount(test, Items.DIAMOND), diamonds + 7, "Owner breaking releases the stored items once");
            test.assertValueEqual(droppedExperience(test), experience + 160, "Owner breaking releases the stored experience once");
        });
        test.succeed();
    }

    @GameTest
    public void replacingCorpseDataInvalidatesExistingMenus(GameTestHelper test) {
        for (String mode : List.of("barrel", "player")) {
            withConfig(test, config(mode, true), () -> {
                List<CorpseHolder> before = corpses(test);
                ServerPlayer deadOwner = player(test, 0);
                deadOwner.giveExperiencePoints(160);
                deadOwner.kill(test.getLevel());
                CorpseHolder holder = corpses(test).stream().filter(corpse -> !before.contains(corpse)).findFirst().orElseThrow();
                ServerPlayer owner = respawn(deadOwner);
                Vec3 position = holder instanceof BarrelBlockEntity barrel
                        ? Vec3.atCenterOf(barrel.getBlockPos()) : ((Mannequin) holder).position();
                owner.snapTo(position.add(1, 0, 0));
                CorpseMenu obsolete = new CorpseMenu(8, owner.getInventory(), holder);
                test.assertTrue(obsolete.stillValid(owner), "The original corpse menu starts valid: " + mode);
                CorpseData original = holder.gentleStart$corpse();
                CorpseData replacement = new CorpseData(owner.getUUID(), owner.getGameProfile().name(), 20);
                holder.gentleStart$initCorpse(replacement);
                holder.gentleStart$inventory().setItem(27, new ItemStack(Items.EMERALD, 3));
                test.assertFalse(obsolete.stillValid(owner), "Replacing metadata invalidates an open menu even for the same owner: " + mode);
                obsolete.clicked(49, 0, ContainerInput.PICKUP, owner);
                obsolete.clicked(27, 0, ContainerInput.PICKUP, owner);
                test.assertTrue(obsolete.quickMoveStack(owner, 27).isEmpty(), "A stale menu cannot directly shift-click the replacement inventory: " + mode);
                test.assertValueEqual(owner.totalExperience, 0, "A stale menu cannot award obsolete experience: " + mode);
                test.assertValueEqual(original.experience(), 160, "Rejected stale clicks do not consume the old balance: " + mode);
                test.assertValueEqual(replacement.experience(), 20, "Rejected stale clicks do not consume the new balance: " + mode);
                test.assertTrue(obsolete.getCarried().isEmpty(), "A stale menu cannot take replacement items onto its cursor: " + mode);
                test.assertValueEqual(holder.gentleStart$inventory().countItem(Items.EMERALD), 3, "Replacement items remain intact: " + mode);
                obsolete.removed(owner);
            });
        }
        test.succeed();
    }

    @GameTest
    public void ordinaryBarrelsRemainThreeRows(GameTestHelper test) {
        withConfig(test, config("barrel", true), () -> {
            BlockPos position = test.absolutePos(new BlockPos(1, 2, 1));
            test.getLevel().setBlockAndUpdate(position, Blocks.BARREL.defaultBlockState());
            BarrelBlockEntity barrel = (BarrelBlockEntity) test.getLevel().getBlockEntity(position);
            test.assertTrue(((CorpseHolder) barrel).gentleStart$corpse() == null, "An ordinary barrel is not a corpse");
            test.assertValueEqual(barrel.getContainerSize(), 27, "Enabling corpses does not expand ordinary barrels");
            ServerPlayer visitor = player(test, 0);
            visitor.openMenu(barrel);
            test.assertValueEqual(((net.minecraft.world.inventory.ChestMenu) visitor.containerMenu).getRowCount(), 3,
                    "Ordinary barrels still open their normal three-row menu");
            visitor.closeContainer();
            BarrelBlockEntity restored = new BarrelBlockEntity(position, barrel.getBlockState());
            restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(),
                    barrel.saveWithFullMetadata(test.getLevel().registryAccess())));
            test.assertValueEqual(restored.getContainerSize(), 27, "Ordinary barrel loading keeps its original size");
        });
        test.succeed();
    }

    @GameTest
    public void globalKeepInventoryBypassesCorpseCapture(GameTestHelper test) {
        withConfig(test, config("barrel", true), () -> {
            test.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, true, test.getLevel().getServer());
            ServerPlayer owner = player(test, 0);
            owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 11));
            owner.giveExperiencePoints(160);
            owner.kill(test.getLevel());
            test.assertTrue(corpses(test).isEmpty(), "Global keepInventory prevents corpse creation");
            ServerPlayer next = respawn(owner);
            test.assertValueEqual(next.getInventory().countItem(Items.DIAMOND), 11, "Global keepInventory still retains the original items");
            test.assertValueEqual(next.totalExperience, 160, "Global keepInventory still retains experience");
        });
        test.succeed();
    }

    @GameTest
    public void disablingExperienceStorageLeavesVanillaDeathExperience(GameTestHelper test) {
        withConfig(test, config("barrel", false), () -> {
            ServerPlayer owner = player(test, 0);
            owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
            owner.giveExperiencePoints(160);
            int orbs = ProtectionGameTests.orbs(owner);
            owner.kill(test.getLevel());
            CorpseHolder holder = corpses(test).getFirst();
            test.assertValueEqual(holder.gentleStart$corpse().experience(), 0, "Disabled experience saving stores no points");
            test.assertTrue(ProtectionGameTests.orbs(owner) > orbs, "Vanilla experience orbs still spawn");
            ServerPlayer next = respawn(owner);
            CorpseMenu menu = new CorpseMenu(1, next.getInventory(), holder);
            test.assertTrue(menu.getSlot(49).getItem().isEmpty(), "No experience bottle appears when saving is disabled");
            test.assertValueEqual(next.totalExperience, 0, "Vanilla respawn does not duplicate the dropped experience");
            menu.removed(next);
        });
        test.succeed();
    }

    @GameTest
    public void disabledCorpseExperienceHonorsPersonalExperienceRetention(GameTestHelper test) {
        withConfig(test, settings("{\"corpse\":{\"minutes\":-1,\"saveExperience\":false},"
                + "\"experience\":{\"minutes\":-1}}"), () -> {
            ServerPlayer owner = player(test, 0);
            owner.giveExperiencePoints(160);
            int orbs = ProtectionGameTests.orbs(owner);
            owner.kill(test.getLevel());
            test.assertValueEqual(corpses(test).getFirst().gentleStart$corpse().experience(), 0,
                    "Disabled corpse experience does not copy personally retained points");
            test.assertValueEqual(ProtectionGameTests.orbs(owner), orbs, "Personal retention still suppresses vanilla experience drops");
            test.assertValueEqual(respawn(owner).totalExperience, 160, "Existing personal experience retention still restores the points");
        });
        test.succeed();
    }

    @GameTest
    public void fullInventoryCursorAndCraftingInputsFitWithoutCopyingResult(GameTestHelper test) {
        withConfig(test, config("barrel", true), () -> {
            ServerPlayer owner = player(test, 0);
            populateInventory(owner);
            BlockPos tablePosition = owner.blockPosition().offset(1, 0, 0);
            test.getLevel().setBlockAndUpdate(tablePosition, Blocks.CRAFTING_TABLE.defaultBlockState());
            CraftingMenu crafting = new CraftingMenu(1, owner.getInventory(), ContainerLevelAccess.create(test.getLevel(), tablePosition));
            owner.containerMenu = crafting;
            crafting.setCarried(new ItemStack(Items.EMERALD, 5));
            for (int slot = 1; slot <= 9; slot++) crafting.getSlot(slot).set(new ItemStack(Items.IRON_INGOT, slot + 1));
            crafting.getSlot(0).set(new ItemStack(Items.IRON_BLOCK));
            int previousDrops = drops(test).size();
            owner.kill(test.getLevel());
            Container inventory = corpses(test).getFirst().gentleStart$inventory();
            test.assertValueEqual(inventory.countItem(Items.OAK_LOG), 666, "The full main inventory remains intact");
            test.assertValueEqual(inventory.countItem(Items.EMERALD), 5, "The cursor stack is captured exactly once");
            test.assertValueEqual(inventory.countItem(Items.IRON_INGOT), 54, "All nine crafting ingredient stacks are captured exactly once");
            test.assertValueEqual(inventory.countItem(Items.IRON_BLOCK), 0, "The untaken crafting result is never copied");
            int occupied = 0;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (!inventory.getItem(slot).isEmpty()) occupied++;
            }
            test.assertValueEqual(occupied, 53, "Forty-three inventory slots, cursor and nine inputs fit around the experience button");
            test.assertTrue(inventory.getItem(49).isEmpty(), "Extra stacks never occupy the experience action slot");
            test.assertTrue(crafting.getCarried().isEmpty(), "The old menu no longer owns the cursor stack");
            for (var slot : crafting.getInputGridSlots()) test.assertTrue(slot.getItem().isEmpty(), "Captured crafting inputs leave the old menu");
            test.assertValueEqual(drops(test).size(), previousDrops, "No captured ingredients leak into vanilla death drops");
            ServerPlayer next = respawn(owner);
            test.assertTrue(next.getInventory().isEmpty(), "Closing the old menu during respawn does not duplicate captured inputs");
            test.assertValueEqual(drops(test).size(), previousDrops, "Later menu cleanup does not drop copied items");
        });
        test.succeed();
    }

    @GameTest
    public void removingEitherCorpseReleasesItemsAndExperienceOnce(GameTestHelper test) {
        for (String mode : List.of("barrel", "player")) {
            withConfig(test, config(mode, true), () -> {
                int diamonds = droppedCount(test, Items.DIAMOND);
                int emeralds = droppedCount(test, Items.EMERALD);
                int experience = droppedExperience(test);
                ServerPlayer owner = player(test, 0);
                owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
                owner.giveExperiencePoints(160);
                owner.kill(test.getLevel());
                CorpseHolder holder = corpses(test).getFirst();
                holder.gentleStart$inventory().setItem(53, new ItemStack(Items.EMERALD, 17));
                Runnable remove = holder instanceof BarrelBlockEntity barrel
                        ? () -> test.getLevel().destroyBlock(barrel.getBlockPos(), false, null)
                        : () -> ((Mannequin) holder).kill(test.getLevel());
                remove.run();
                test.assertTrue(corpses(test).isEmpty(), "Removing the presentation removes the usable corpse: " + mode);
                test.assertValueEqual(droppedCount(test, Items.DIAMOND), diamonds + 7, "Removing the corpse releases its original items: " + mode);
                test.assertValueEqual(droppedCount(test, Items.EMERALD), emeralds + 17, "Removing the corpse releases slot 53: " + mode);
                test.assertValueEqual(droppedExperience(test), experience + 160, "Removing the corpse releases the full stored experience: " + mode);
                remove.run();
                test.assertValueEqual(droppedCount(test, Items.DIAMOND), diamonds + 7, "Repeated removal does not duplicate original items: " + mode);
                test.assertValueEqual(droppedCount(test, Items.EMERALD), emeralds + 17, "Repeated removal does not duplicate extra-slot items: " + mode);
                test.assertValueEqual(droppedExperience(test), experience + 160, "Repeated removal does not duplicate experience: " + mode);
            });
        }
        test.succeed();
    }

    @GameTest
    public void blockedBarrelPlacementKeepsVanillaDropsAndWorldBlocks(GameTestHelper test) {
        withConfig(test, config("barrel", true), () -> {
            ServerPlayer owner = player(test, 0);
            BlockPos center = owner.blockPosition();
            for (BlockPos position : BlockPos.betweenClosed(center.offset(-2, -2, -2), center.offset(2, 2, 2))) {
                test.getLevel().setBlockAndUpdate(position, Blocks.BEDROCK.defaultBlockState());
            }
            int diamonds = droppedCount(test, Items.DIAMOND);
            int experience = droppedExperience(test);
            owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
            owner.giveExperiencePoints(160);
            owner.kill(test.getLevel());
            test.assertTrue(corpses(test).isEmpty(), "A completely obstructed death position produces no partial corpse");
            test.assertValueEqual(droppedCount(test, Items.DIAMOND), diamonds + 7, "Failed placement retains the complete vanilla item drop");
            test.assertTrue(droppedExperience(test) > experience, "Failed placement retains vanilla experience drops");
            for (BlockPos position : BlockPos.betweenClosed(center.offset(-2, -2, -2), center.offset(2, 2, 2))) {
                test.assertTrue(test.getLevel().getBlockState(position).is(Blocks.BEDROCK), "Failed placement does not replace solid blocks");
            }
            ServerPlayer next = respawn(owner);
            test.assertTrue(next.getInventory().isEmpty(), "The dropped inventory is not copied on respawn");
            test.assertValueEqual(next.totalExperience, 0, "The failed corpse does not restore or save experience");
        });
        test.succeed();
    }

    private static ServerPlayer assertDeathLayout(GameTestHelper test, String mode) {
        ServerPlayer owner = player(test, 0);
        ItemStack[] original = populateInventory(owner);
        owner.giveExperiencePoints(160);
        int drops = drops(test).size();
        int orbs = ProtectionGameTests.orbs(owner);
        owner.kill(test.getLevel());
        List<CorpseHolder> found = corpses(test);
        test.assertValueEqual(found.size(), 1, "One death creates exactly one corpse");
        CorpseHolder holder = found.getFirst();
        test.assertTrue(mode.equals("barrel") ? holder instanceof BarrelBlockEntity : holder instanceof Mannequin,
                "The configured presentation is created");
        Container inventory = holder.gentleStart$inventory();
        test.assertValueEqual(inventory.getContainerSize(), 54, "The corpse holds six rows");
        for (int source = 0; source < 36; source++) {
            int target = source < 9 ? source + 27 : source - 9;
            assertStack(test, inventory.getItem(target), original[source], "Inventory layout preserves source slot " + source);
        }
        int[] equipmentTargets = {39, 38, 37, 36, 44, 40, 41};
        for (int index = 0; index < equipmentTargets.length; index++) {
            assertStack(test, inventory.getItem(equipmentTargets[index]), original[index + 36], "Equipment layout preserves source slot " + (index + 36));
        }
        test.assertValueEqual(holder.gentleStart$corpse().experience(), 160, "All spendable experience is captured");
        test.assertValueEqual(drops(test).size(), drops, "Captured stacks are not also dropped on the ground");
        test.assertValueEqual(ProtectionGameTests.orbs(owner), orbs, "Captured experience is not also dropped as orbs");
        if (holder instanceof Mannequin model) {
            test.assertValueEqual(model.getPose(), Pose.SLEEPING, "The player corpse lies horizontally");
            test.assertValueEqual(model.getProfile(), owner.getProfile(), "The corpse uses the dead player's skin profile");
            test.assertValueEqual(model.position(), owner.position(), "The model appears at the death position");
        }
        ServerPlayer next = respawn(owner);
        for (int slot = 0; slot < next.getInventory().getContainerSize(); slot++) {
            test.assertTrue(next.getInventory().getItem(slot).isEmpty(), "Respawn does not duplicate captured slot " + slot);
        }
        test.assertValueEqual(next.totalExperience, 0, "Captured experience is not also restored on respawn");
        return next;
    }

    private static ItemStack[] populateInventory(ServerPlayer owner) {
        ItemStack[] original = new ItemStack[owner.getInventory().getContainerSize()];
        for (int slot = 0; slot < 36; slot++) original[slot] = new ItemStack(Items.OAK_LOG, slot + 1);
        original[36] = new ItemStack(Items.LEATHER_BOOTS);
        original[37] = new ItemStack(Items.IRON_LEGGINGS);
        original[38] = new ItemStack(Items.NETHERITE_CHESTPLATE);
        original[39] = new ItemStack(Items.DIAMOND_HELMET);
        original[40] = new ItemStack(Items.SHIELD);
        original[41] = new ItemStack(Items.WOLF_ARMOR);
        original[42] = new ItemStack(Items.SADDLE);
        for (int slot = 0; slot < original.length; slot++) owner.getInventory().setItem(slot, original[slot].copy());
        return original;
    }

    private static ProtectionConfig config(String mode, boolean saveExperience) {
        return settings("{\"corpse\":{\"minutes\":-1,\"mode\":\"" + mode + "\",\"saveExperience\":" + saveExperience + "}}");
    }

    private static Player identityPlayer(GameTestHelper test, UUID id, String name) {
        return new Player(test.getLevel(), new GameProfile(id, name)) {
            @Override
            public GameType gameMode() {
                return GameType.SURVIVAL;
            }

            @Override
            public boolean isClientAuthoritative() {
                return false;
            }
        };
    }

    private static void openCorpse(ServerPlayer player, CorpseHolder holder) {
        if (holder instanceof BarrelBlockEntity barrel) player.openMenu(barrel);
        else player.interactOn((Mannequin) holder, InteractionHand.MAIN_HAND, Vec3.ZERO);
    }

    private static List<CorpseHolder> corpses(GameTestHelper test) {
        BlockPos center = test.absolutePos(new BlockPos(1, 2, 1));
        List<CorpseHolder> result = new ArrayList<>();
        for (BlockPos position : BlockPos.betweenClosed(center.offset(-4, -4, -4), center.offset(4, 4, 4))) {
            if (test.getLevel().getBlockEntity(position) instanceof CorpseHolder holder && holder.gentleStart$corpse() != null) {
                result.add(holder);
            }
        }
        for (Mannequin model : test.getLevel().getEntitiesOfClass(Mannequin.class, new AABB(center).inflate(4))) {
            CorpseHolder holder = (CorpseHolder) model;
            if (holder.gentleStart$corpse() != null) result.add(holder);
        }
        return result;
    }

    private static List<ItemEntity> drops(GameTestHelper test) {
        return test.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(test.absolutePos(new BlockPos(1, 2, 1))).inflate(8));
    }

    private static int droppedCount(GameTestHelper test, net.minecraft.world.item.Item item) {
        return drops(test).stream().map(ItemEntity::getItem).filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static int droppedExperience(GameTestHelper test) {
        int experience = 0;
        for (ExperienceOrb orb : test.getLevel().getEntitiesOfClass(ExperienceOrb.class,
                new AABB(test.absolutePos(new BlockPos(1, 2, 1))).inflate(8))) {
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, test.getLevel().registryAccess());
            orb.saveWithoutId(output);
            experience += orb.getValue() * output.buildResult().getIntOr("Count", 1);
        }
        return experience;
    }

    private static void assertStack(GameTestHelper test, ItemStack actual, ItemStack expected, String message) {
        test.assertTrue(ItemStack.matches(actual, expected), message);
    }
}
