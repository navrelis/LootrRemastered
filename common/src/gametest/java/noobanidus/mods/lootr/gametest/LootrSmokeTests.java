package noobanidus.mods.lootr.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.ILootrSavedData;
import noobanidus.mods.lootr.common.api.data.blockentity.ILootrBlockEntity;
import noobanidus.mods.lootr.common.api.data.inventory.ILootrInventory;
import noobanidus.mods.lootr.common.api.registry.LootrRegistry;

import static noobanidus.mods.lootr.gametest.LootrTestSupport.EMPTY_STRUCTURE;

public class LootrSmokeTests {
  private static final BlockPos CHEST_POS = new BlockPos(0, 1, 0);
  private static final int CONVERSION_TIMEOUT_TICKS = 200;

  @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void chestConvertsToLootrChest(GameTestHelper helper) {
    LootrTestSupport.placeLootContainer(helper, CHEST_POS, Blocks.CHEST, BuiltInLootTables.SIMPLE_DUNGEON, 1L);

    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, CHEST_POS, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          ILootrBlockEntity lootrChest = LootrTestSupport.getLootrBlockEntity(helper, CHEST_POS);
          helper.assertTrue(BuiltInLootTables.SIMPLE_DUNGEON.equals(lootrChest.getInfoLootTable()), "Lootr chest reports loot table " + lootrChest.getInfoLootTable());
        })
        .thenSucceed();
  }

  @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void twoPlayersGetSeparateInventories(GameTestHelper helper) {
    LootrTestSupport.placeLootContainer(helper, CHEST_POS, Blocks.CHEST, BuiltInLootTables.SIMPLE_DUNGEON, 2L);

    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, CHEST_POS, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          ILootrBlockEntity lootrChest = LootrTestSupport.getLootrBlockEntity(helper, CHEST_POS);
          ServerPlayer first = LootrTestSupport.createRealPlayer(helper);
          ServerPlayer second = LootrTestSupport.createRealPlayer(helper);

          ILootrInventory firstInventory = LootrAPI.getInventory(lootrChest, first);
          ILootrInventory secondInventory = LootrAPI.getInventory(lootrChest, second);

          helper.assertTrue(firstInventory != null && secondInventory != null, "Lootr returned no inventory for a player");
          helper.assertTrue(firstInventory != secondInventory, "Both players received the same inventory object");
          helper.assertTrue(!firstInventory.isEmpty(), "First player's inventory is empty");
          helper.assertTrue(!secondInventory.isEmpty(), "Second player's inventory is empty");

          helper.assertTrue(countSavedInventories(helper, lootrChest) == 2, "Saved data should hold exactly two inventories but holds " + countSavedInventories(helper, lootrChest));

          NonNullList<ItemStack> firstSnapshot = snapshot(firstInventory);
          ILootrInventory firstAgain = LootrAPI.getInventory(lootrChest, first);
          helper.assertTrue(firstAgain != null && ItemStack.listMatches(firstSnapshot, snapshot(firstAgain)), "First player's inventory changed on second access");
          helper.assertTrue(countSavedInventories(helper, lootrChest) == 2, "Second access created another inventory");
        })
        .thenSucceed();
  }

  private static NonNullList<ItemStack> snapshot(ILootrInventory inventory) {
    NonNullList<ItemStack> result = NonNullList.withSize(inventory.getContainerSize(), ItemStack.EMPTY);
    for (int i = 0; i < result.size(); i++) {
      result.set(i, inventory.getItem(i).copy());
    }
    return result;
  }

  private static int countSavedInventories(GameTestHelper helper, ILootrBlockEntity lootrChest) {
    ILootrSavedData data = LootrAPI.getData(lootrChest);
    if (!(data instanceof SavedData savedData)) {
      throw new GameTestAssertException("Lootr saved data is missing for " + lootrChest.getInfoPos());
    }
    CompoundTag saved = savedData.save(new CompoundTag(), helper.getLevel().registryAccess());
    return saved.getList("inventories", Tag.TAG_COMPOUND).size();
  }
}
