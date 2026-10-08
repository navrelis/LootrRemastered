package noobanidus.mods.lootr.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.blockentity.ILootrBlockEntity;

public final class LootrTestSupport {
  private LootrTestSupport() {
  }

  /**
   * Places a vanilla container at the test-relative position and gives it a loot table, the same way other mods do.
   * Lootr queues it for replacement a few server ticks later.
   */
  public static RandomizableContainerBlockEntity placeLootContainer(GameTestHelper helper, BlockPos relativePos, Block vanillaBlock, ResourceKey<LootTable> lootTable, long seed) {
    helper.setBlock(relativePos, vanillaBlock);
    if (!(helper.getBlockEntity(relativePos) instanceof RandomizableContainerBlockEntity container)) {
      throw new GameTestAssertException(vanillaBlock + " has no randomizable container block entity");
    }
    container.setLootTable(lootTable, seed);
    return container;
  }

  /**
   * Throws (and thereby makes the surrounding wait retry) until the block at the position is the given block.
   */
  public static void assertBlockIs(GameTestHelper helper, BlockPos relativePos, Block expected) {
    helper.assertBlockPresent(expected, relativePos);
  }

  public static ILootrBlockEntity getLootrBlockEntity(GameTestHelper helper, BlockPos relativePos) {
    BlockEntity blockEntity = helper.getBlockEntity(relativePos);
    if (LootrAPI.resolveBlockEntity(blockEntity) instanceof ILootrBlockEntity lootrBlockEntity) {
      return lootrBlockEntity;
    }
    throw new GameTestAssertException("Expected a Lootr block entity but found " + blockEntity);
  }

  /**
   * Lootr treats a ServerPlayer without a connection (and Fabric API's FakePlayer) as a fake player and hands it no loot.
   * GameTestHelper#makeMockServerPlayerInLevel registers the mock with the player list through a connection, so it is
   * not a fake player; this is verified here so that a change in vanilla fails loudly instead of yielding empty loot.
   */
  @SuppressWarnings("removal")
  public static ServerPlayer createRealPlayer(GameTestHelper helper) {
    ServerPlayer player = helper.makeMockServerPlayerInLevel();
    if (LootrAPI.isFakePlayer(player)) {
      throw new GameTestAssertException("Mock player " + player.getGameProfile().getName() + " is treated as a fake player by Lootr");
    }
    return player;
  }
}
