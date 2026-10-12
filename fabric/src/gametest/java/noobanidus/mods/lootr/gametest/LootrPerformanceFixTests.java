package noobanidus.mods.lootr.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import noobanidus.mods.lootr.common.api.registry.LootrRegistry;
import noobanidus.mods.lootr.fabric.network.LootrNetworkingInit;

/**
 * Tests for the two changes of T21: the position check of the update request and the block entity lookup of the hopper hook.
 */
public class LootrPerformanceFixTests implements FabricGameTest {
  private static final String BATCH = "lootr_t21";
  private static final int CONVERSION_TIMEOUT_TICKS = 200;

  // ----- 1. The position check of the update request -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void updateRequestForUnloadedChunkIsIgnoredWithoutLoadingIt(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
    int viewDistance = level.getServer().getPlayerList().getViewDistance();
    BlockPos far = new BlockPos(1_000_000, 64, 1_000_000);
    ChunkPos chunk = new ChunkPos(far);
    helper.assertTrue(!level.getChunkSource().hasChunk(chunk.x, chunk.z), "The far chunk is loaded before the request");

    helper.assertTrue(!LootrNetworkingInit.mayRequestUpdate(player, level, far, viewDistance), "A position far from the player was accepted");
    // With a view distance that covers the position only the loaded check is left.
    helper.assertTrue(!LootrNetworkingInit.mayRequestUpdate(player, level, far, 1_000_000), "A position in an unloaded chunk was accepted");
    helper.assertTrue(!level.getChunkSource().hasChunk(chunk.x, chunk.z), "The check loaded the far chunk");
    helper.succeed();
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 400)
  public void updateRequestForOtherDimensionIsIgnored(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    ServerLevel nether = level.getServer().getLevel(Level.NETHER);
    if (nether == null) {
      throw new GameTestAssertException("The test server has no nether");
    }
    ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
    // The test level reaches below the build height of the nether, where nothing is ever loaded, so the height is set here.
    BlockPos pos = new BlockPos(helper.absolutePos(BlockPos.ZERO).getX(), 64, helper.absolutePos(BlockPos.ZERO).getZ());
    ChunkPos chunk = new ChunkPos(pos);
    int viewDistance = level.getServer().getPlayerList().getViewDistance();
    player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

    // The same chunk is kept loaded in the nether, so that only the dimension is left to refuse the request.
    // The chunk is generated here and not over the ticks of the test, as that can take longer than the ticks of a fast test run.
    nether.setChunkForced(chunk.x, chunk.z, true);
    nether.getChunk(chunk.x, chunk.z);
    helper.startSequence()
        .thenWaitUntil(() -> helper.assertTrue(nether.isLoaded(pos), "The chunk is not loaded in the nether yet"))
        .thenExecute(() -> {
          try {
            helper.assertTrue(player.level() == level && level.isLoaded(pos), "The test needs the player and the position in the test level");
            helper.assertTrue(LootrNetworkingInit.mayRequestUpdate(player, level, pos, viewDistance), "The same request in the player's own dimension was refused");
            helper.assertTrue(!LootrNetworkingInit.mayRequestUpdate(player, nether, pos, viewDistance), "A position in another dimension than the player's was accepted");
          } finally {
            nether.setChunkForced(chunk.x, chunk.z, false);
          }
        })
        .thenSucceed();
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void updateRequestForLoadedChunkNearPlayerIsAccepted(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
    int viewDistance = level.getServer().getPlayerList().getViewDistance();
    BlockPos pos = helper.absolutePos(new BlockPos(0, 1, 0));
    ChunkPos chunk = new ChunkPos(pos);
    helper.assertTrue(level.getChunkSource().hasChunk(chunk.x, chunk.z), "The test needs the chunk to be loaded");

    // The player is moved, not ticked, so the loaded chunk stays where it is; the limit is the view distance plus one chunk.
    movePlayerToChunk(player, chunk.x, chunk.z, pos.getY());
    helper.assertTrue(LootrNetworkingInit.mayRequestUpdate(player, level, pos, viewDistance), "A loaded position in the player's chunk was refused");
    movePlayerToChunk(player, chunk.x + viewDistance + 1, chunk.z - viewDistance - 1, pos.getY());
    helper.assertTrue(LootrNetworkingInit.mayRequestUpdate(player, level, pos, viewDistance), "A loaded position at the edge of the view distance was refused");
    movePlayerToChunk(player, chunk.x + viewDistance + 2, chunk.z, pos.getY());
    helper.assertTrue(!LootrNetworkingInit.mayRequestUpdate(player, level, pos, viewDistance), "A loaded position outside the view distance was accepted");
    movePlayerToChunk(player, chunk.x, chunk.z - viewDistance - 2, pos.getY());
    helper.assertTrue(!LootrNetworkingInit.mayRequestUpdate(player, level, pos, viewDistance), "A loaded position outside the view distance (other axis) was accepted");
    helper.succeed();
  }

  private static void movePlayerToChunk(ServerPlayer player, int chunkX, int chunkZ, int y) {
    player.setPos(chunkX * 16 + 8.5, y, chunkZ * 16 + 8.5);
    ChunkPos now = player.chunkPosition();
    if (now.x != chunkX || now.z != chunkZ) {
      throw new GameTestAssertException("The player is in chunk " + now + " instead of " + chunkX + ", " + chunkZ);
    }
  }

  // ----- 2. The block entity lookup of the hopper hook -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void hopperStillIgnoresLootrContainers(GameTestHelper helper) {
    BlockPos chest = new BlockPos(0, 1, 0);
    BlockPos hopperToChest = new BlockPos(1, 1, 0);
    BlockPos barrel = new BlockPos(0, 1, 4);
    BlockPos hopperToBarrel = new BlockPos(1, 1, 4);
    BlockPos empty = new BlockPos(0, 1, 8);
    BlockPos stone = new BlockPos(0, 1, 10);
    ItemStack stack = new ItemStack(Items.COBBLESTONE, 4);
    LootrTestSupport.placeLootContainer(helper, chest, Blocks.CHEST, BuiltInLootTables.SIMPLE_DUNGEON, 21L);

    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, chest, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          helper.setBlock(barrel, Blocks.BARREL);
          helper.setBlock(stone, Blocks.STONE);
          // Both hoppers face west, one into the Lootr chest and one into a plain barrel that shows that hoppers move items here.
          for (BlockPos hopper : new BlockPos[]{hopperToChest, hopperToBarrel}) {
            helper.setBlock(hopper, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.WEST));
            if (!(helper.getBlockEntity(hopper) instanceof HopperBlockEntity hopperEntity)) {
              throw new GameTestAssertException("The hopper at " + hopper + " has no block entity");
            }
            hopperEntity.setItem(0, stack.copy());
          }
          ServerLevel level = helper.getLevel();
          helper.assertTrue(HopperBlockEntity.getContainerAt(level, helper.absolutePos(chest)) == null, "A hopper can reach the Lootr chest");
          helper.assertTrue(HopperBlockEntity.getContainerAt(level, helper.absolutePos(barrel)) != null, "A hopper cannot reach a plain barrel");
          helper.assertTrue(HopperBlockEntity.getContainerAt(level, helper.absolutePos(empty)) == null, "A hopper reached air");
          helper.assertTrue(HopperBlockEntity.getContainerAt(level, helper.absolutePos(stone)) == null, "A hopper reached stone");
        })
        .thenWaitUntil(() -> helper.assertTrue(containerAt(helper, barrel).countItem(Items.COBBLESTONE) >= 3, "The hopper has not filled the barrel yet"))
        .thenExecute(() -> {
          LootrTestSupport.getLootrBlockEntity(helper, chest);
          helper.assertTrue(containerAt(helper, hopperToChest).countItem(Items.COBBLESTONE) == 4, "The hopper next to the Lootr chest gave away items");
          helper.assertTrue(containerAt(helper, hopperToBarrel).countItem(Items.COBBLESTONE) + containerAt(helper, barrel).countItem(Items.COBBLESTONE) == 4, "The control hopper lost items");
          helper.assertTrue(((Container) LootrTestSupport.getLootrBlockEntity(helper, chest)).isEmpty(), "Items arrived in the Lootr chest");
        })
        .thenSucceed();
  }

  private static Container containerAt(GameTestHelper helper, BlockPos pos) {
    BlockEntity blockEntity = helper.getBlockEntity(pos);
    if (blockEntity instanceof Container container) {
      return container;
    }
    throw new GameTestAssertException("No container at " + pos + " but " + blockEntity);
  }
}
