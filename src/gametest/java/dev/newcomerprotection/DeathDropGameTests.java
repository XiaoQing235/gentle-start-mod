package dev.newcomerprotection;

import static dev.newcomerprotection.ExpandedProtectionGameTests.settings;
import static dev.newcomerprotection.ProtectionGameTests.player;
import static dev.newcomerprotection.ProtectionGameTests.respawn;
import static dev.newcomerprotection.ProtectionGameTests.withConfig;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class DeathDropGameTests {
    private static ProtectionEngine engine() {
        return NewcomerProtection.engine();
    }

    private static boolean protectedDrop(ItemEntity item) {
        return ((DeathDropProtection) item).newcomerProtection$isProtected();
    }

    private static List<ItemEntity> drops(GameTestHelper test) {
        return test.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(test.absoluteVec(new Vec3(1, 2, 1)),
                test.absoluteVec(new Vec3(1, 2, 1))).inflate(8));
    }

    private static ItemEntity deathDrop(GameTestHelper test) {
        ServerPlayer player = player(test, 0);
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
        player.kill(test.getLevel());
        return drops(test).stream().filter(DeathDropGameTests::protectedDrop).findFirst().orElseThrow();
    }

    @GameTest
    public void onlyActualDeathDropsReceiveExtendedLifetime(GameTestHelper test) {
        withConfig(test, settings("{\"inventory\":{\"minutes\":-1,\"keep\":{\"all\":false,\"slots\":[0]}},"
                + "\"deathDrops\":{\"minutes\":-1}}"), () -> {
            ServerPlayer player = player(test, 0);
            ItemEntity manual = player.drop(new ItemStack(Items.COAL), false, true);
            test.assertFalse(protectedDrop(manual), "A live player's manual drop is ordinary");
            test.assertValueEqual(manual.getAge(), 0, "Manual drops keep vanilla age");
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
            player.getInventory().setItem(1, new ItemStack(Items.BREAD, 3));
            player.getInventory().setItem(2, new ItemStack(Items.OAK_LOG, 4));
            player.kill(test.getLevel());
            List<ItemEntity> protectedItems = drops(test).stream().filter(DeathDropGameTests::protectedDrop).toList();
            test.assertValueEqual(protectedItems.size(), 2, "Only unretained stacks become protected entities");
            for (ItemEntity item : protectedItems) {
                test.assertValueEqual(item.getAge(), -6000, "Death drop uses vanilla setExtendedLifetime");
                test.assertTrue(item.fireImmune(), "Protected death drop is fire immune");
            }
            ServerPlayer next = respawn(player);
            test.assertValueEqual(next.getInventory().countItem(Items.DIAMOND), 2, "Retained items still restore exactly once");
            test.assertValueEqual(next.getInventory().countItem(Items.BREAD), 0, "Extended drops are not copied into the new inventory");
        });
        test.succeed();
    }

    @GameTest
    public void ageAndProtectionPersistWithoutRefreshingLifetime(GameTestHelper test) {
        withConfig(test, settings("{\"deathDrops\":{\"minutes\":-1}}"), () -> {
            ItemEntity item = deathDrop(test);
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, test.getLevel().registryAccess());
            item.saveWithoutId(output);
            var tag = output.buildResult();
            tag.putShort("Age", (short)-1234);
            ItemEntity restored = new ItemEntity(test.getLevel(), item.getX(), item.getY(), item.getZ(), new ItemStack(Items.DIAMOND));
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), tag));
            test.assertTrue(protectedDrop(restored), "Entity protection persists through NBT");
            test.assertValueEqual(restored.getAge(), -1234, "Loading does not reset age to minus six thousand");
            engine().replaceConfig(settings("{}"));
            test.assertTrue(restored.fireImmune(), "Already granted drop protection survives configuration changes");
            tag.putShort("Age", (short)0);
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), tag));
            restored.tick();
            test.assertFalse(restored.isRemoved(), "Drop survives the original five-minute age boundary");
            tag.putShort("Age", (short)5998);
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), tag));
            restored.tick();
            test.assertFalse(restored.isRemoved(), "Drop survives the tick before its extended deadline");
            restored.tick();
            test.assertTrue(restored.isRemoved(), "Protected item still despawns at the finite extended deadline");
        });
        test.succeed();
    }

    @GameTest(maxTicks = 90)
    public void protectedEntitiesDoNotMergeButOrdinaryDropsStillDo(GameTestHelper test) {
        ItemEntity[] marked = new ItemEntity[2];
        ItemEntity[] ordinary = new ItemEntity[2];
        withConfig(test, settings("{\"deathDrops\":{\"minutes\":-1}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
            player.getInventory().setItem(1, new ItemStack(Items.DIAMOND));
            player.kill(test.getLevel());
            var found = drops(test).stream().filter(DeathDropGameTests::protectedDrop).toList();
            marked[0] = found.get(0);
            marked[1] = found.get(1);
            for (int index = 0; index < ordinary.length; index++) {
                ordinary[index] = new ItemEntity(test.getLevel(), marked[0].getX(), marked[0].getY(), marked[0].getZ(),
                        new ItemStack(Items.DIAMOND));
                test.getLevel().addFreshEntity(ordinary[index]);
            }
            for (ItemEntity item : new ItemEntity[]{marked[0], marked[1], ordinary[0], ordinary[1]}) {
                item.setPos(marked[0].position());
                item.setNoGravity(true);
                item.setDeltaMovement(Vec3.ZERO);
            }
        });
        test.runAfterDelay(45, () -> {
            for (ItemEntity item : marked) {
                test.assertFalse(item.isRemoved(), "A marked item cannot merge into either marked or ordinary entities");
                test.assertValueEqual(item.getItem().getCount(), 1, "Marked stack count stays unchanged");
            }
            int ordinaryCount = 0;
            for (ItemEntity item : ordinary) {
                if (!item.isRemoved()) {
                    ordinaryCount++;
                    test.assertValueEqual(item.getItem().getCount(), 2, "Ordinary item stacks still merge");
                }
            }
            test.assertValueEqual(ordinaryCount, 1, "Exactly one ordinary merged entity survives");
            test.succeed();
        });
    }

    @GameTest
    public void deathDropsResistOrdinaryDamageButRespectSpecialRemoval(GameTestHelper test) {
        withConfig(test, settings("{\"deathDrops\":{\"minutes\":-1}}"), () -> {
            ItemEntity item = deathDrop(test);
            for (DamageSource source : new DamageSource[]{item.damageSources().inFire(), item.damageSources().lava(),
                    item.damageSources().cactus(), item.damageSources().explosion(null, null), item.damageSources().generic()}) {
                item.hurtServer(test.getLevel(), source, 1000);
                test.assertFalse(item.isRemoved(), "Protected drop survives ordinary damage: " + source);
            }
            item.lavaIgnite();
            item.tick();
            test.assertFalse(item.isOnFire(), "Lava cannot ignite the protected entity");
            item.hurtServer(test.getLevel(), item.damageSources().genericKill(), 1000);
            test.assertTrue(item.isRemoved(), "Special command damage still destroys the item");
            ItemEntity voidItem = deathDrop(test);
            voidItem.setPos(voidItem.getX(), test.getLevel().getMinY() - 65, voidItem.getZ());
            voidItem.tick();
            test.assertTrue(voidItem.isRemoved(), "Below-world removal is not replaced with void recovery");
            ItemEntity ordinary = new ItemEntity(test.getLevel(), 0, 0, 0, new ItemStack(Items.BREAD));
            test.assertFalse(ordinary.fireImmune(), "Ordinary items do not gain fire immunity");
            ordinary.hurtServer(test.getLevel(), ordinary.damageSources().generic(), 1000);
            test.assertTrue(ordinary.isRemoved(), "Ordinary items still take damage");
        });
        test.succeed();
    }

    @GameTest
    public void deathDropEligibilityUsesWindowAndPermanentCancellation(GameTestHelper test) {
        withConfig(test, settings("{\"deathDrops\":{\"minutes\":1,\"cancel\":{\"experienceLevel\":2}}}"), () -> {
            ServerPlayer expired = player(test, 1200);
            expired.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
            expired.kill(test.getLevel());
            test.assertTrue(drops(test).stream().noneMatch(DeathDropGameTests::protectedDrop), "Expired qualification yields ordinary drops");
            ServerPlayer cancelled = player(test, 0);
            cancelled.giveExperienceLevels(2);
            cancelled.giveExperienceLevels(-2);
            cancelled.getInventory().setItem(0, new ItemStack(Items.BREAD));
            cancelled.kill(test.getLevel());
            test.assertTrue(drops(test).stream().noneMatch(DeathDropGameTests::protectedDrop), "Auto-cancelled qualification stays cancelled");
        });
        test.succeed();
    }

    @GameTest
    public void laterDeathContainerCleanupKeepsOriginalDropDecision(GameTestHelper test) {
        withConfig(test, settings("{\"deathDrops\":{\"minutes\":-1}}"), () -> {
            ServerPlayer player = player(test, 0);
            player.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND, 7));
            player.kill(test.getLevel());
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, test.getLevel().registryAccess());
            player.saveWithoutId(output);
            ServerPlayer reloaded = new ServerPlayer(test.getLevel().getServer(), test.getLevel(),
                    player.getGameProfile(), player.clientInformation());
            reloaded.connection = player.connection;
            reloaded.load(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), output.buildResult()));
            test.assertTrue(((DeathProtection) reloaded).newcomerProtection$protectDrops(), "Pending death drop decision survives player save/load");
            engine().replaceConfig(settings("{}"));
            respawn(player);
            var carried = drops(test).stream().filter(item -> item.getItem().is(Items.DIAMOND)).findFirst().orElseThrow();
            test.assertTrue(protectedDrop(carried), "Later removal drops use the decision captured before configuration changes");
            test.assertValueEqual(carried.getItem().getCount(), 7, "Cursor stack is dropped once with its full count");
        });
        test.succeed();
    }
}
