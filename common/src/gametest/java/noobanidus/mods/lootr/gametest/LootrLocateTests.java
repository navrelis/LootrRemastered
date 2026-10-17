package noobanidus.mods.lootr.gametest;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.registry.LootrRegistry;
import noobanidus.mods.lootr.common.locate.ChunkScan;
import noobanidus.mods.lootr.common.locate.ContainerCheck;
import noobanidus.mods.lootr.common.locate.ContainerInfo;
import noobanidus.mods.lootr.common.locate.ContainerLocator;
import noobanidus.mods.lootr.common.locate.LocateSearch;
import noobanidus.mods.lootr.common.locate.LootTableStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static noobanidus.mods.lootr.gametest.LootrTestSupport.EMPTY_STRUCTURE;

/**
 * Tests for `/lootr check` and `/lootr locate`. The logic lives in `noobanidus.mods.lootr.common.locate` and is
 * tested directly; a few tests also run the commands to see that they execute and say what the logic found. The
 * server has no translations of the mod, so the lines of a command are read by their translation keys and arguments.
 * <br />
 * The loot tables are in the test data pack, in `loot_table/locate/`. Tests that look for a loot table in the
 * world use a table of their own, as other tests place containers in the neighbouring test areas.
 */
public class LootrLocateTests {
  private static final String NAMESPACE = "lootr_gametest:";
  private static final int CONVERSION_TIMEOUT_TICKS = 200;
  private static final ResourceKey<LootTable> ITEMS = table("locate/items");
  private static final ResourceKey<LootTable> NEARBY = table("locate/nearby");
  private static final ResourceKey<LootTable> COMMAND = table("locate/command");
  private static final ResourceKey<LootTable> MINECARTS = table("locate/minecarts");
  private static final ResourceKey<LootTable> FAR = table("locate/far");
  private static final ResourceKey<LootTable> LOOK = table("locate/look");
  private static final ResourceKey<LootTable> OTHER = table("locate/other");
  private static final ResourceKey<LootTable> MISSING = table("does_not_exist");
  private static final ResourceKey<LootTable> NO_POOLS = table("verify/no_pools");

  private static ResourceKey<LootTable> table(String path) {
    return ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(NAMESPACE + path));
  }

  // ----- the status of a loot table -----

  @GameTest(template = EMPTY_STRUCTURE)
  public void statusJudgesLootTables(GameTestHelper helper) {
    MinecraftServer server = helper.getLevel().getServer();
    assertStatus(helper, server, ITEMS, LootTableStatus.Kind.OK, "");
    assertStatus(helper, server, table("integrated/target_a"), LootTableStatus.Kind.OK, "");
    assertStatus(helper, server, ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/simple_dungeon")), LootTableStatus.Kind.OK, "");
    assertStatus(helper, server, MISSING, LootTableStatus.Kind.MISSING, "");
    assertStatus(helper, server, table("verify/unknown_item"), LootTableStatus.Kind.MISSING, "");
    assertStatus(helper, server, NO_POOLS, LootTableStatus.Kind.EMPTY, "no_pools");
    assertStatus(helper, server, table("verify/only_empty"), LootTableStatus.Kind.EMPTY, "only_empty_entries");
    assertStatus(helper, server, table("verify/only_dead_references"), LootTableStatus.Kind.EMPTY, "only_unproductive_references");
    assertStatus(helper, server, null, LootTableStatus.Kind.NONE, "");
    LootTableStatus.Cache cache = new LootTableStatus.Cache(server);
    helper.assertTrue(cache.get(NO_POOLS) == cache.get(NO_POOLS), "The cache judges a table twice");
    helper.succeed();
  }

  private static void assertStatus(GameTestHelper helper, MinecraftServer server, ResourceKey<LootTable> table, LootTableStatus.Kind kind, String reason) {
    LootTableStatus.Result result = LootTableStatus.of(server, table);
    helper.assertTrue(result.kind() == kind && result.reason().equals(reason), "The status of " + table + " is " + result + ", expected " + kind + " " + reason);
  }

  @GameTest(template = EMPTY_STRUCTURE)
  public void sampleRollsTheTable(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    LootTableStatus.Sample sample = LootTableStatus.sample(level, ITEMS, Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 1, 1))));
    helper.assertTrue(sample.error() == null && sample.rolls() == LootTableStatus.SAMPLE_ROLLS && sample.emptyRolls() == 0 && sample.stacks() == LootTableStatus.SAMPLE_ROLLS, "Unexpected sample " + sample);
    helper.assertTrue(sample.averageStacks() == 1.0, "The average is " + sample.averageStacks());
    LootTableStatus.Sample again = LootTableStatus.sample(level, ITEMS, Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 1, 1))));
    helper.assertTrue(sample.equals(again), "The same table gave another sample");
    LootTableStatus.Sample empty = LootTableStatus.sample(level, NO_POOLS, Vec3.ZERO);
    helper.assertTrue(empty.error() == null && empty.emptyRolls() == LootTableStatus.SAMPLE_ROLLS && empty.stacks() == 0, "A table without pools gave " + empty);
    helper.succeed();
  }

  @GameTest(template = EMPTY_STRUCTURE)
  public void integratedLootIsReported(GameTestHelper helper) {
    LootTableStatus.Integrated extended = LootTableStatus.integrated(table("integrated/target_a"));
    helper.assertTrue(extended.extended() && extended.detail().equals("lootr_gametest:bcd"), "integrated/target_a is " + extended);
    helper.assertTrue(LootTableStatus.integrated(table("integrated/prio/excluded")).state() == LootTableStatus.IntegratedState.EXCLUDED, "integrated/prio/excluded is not excluded");
    helper.assertTrue(LootTableStatus.integrated(ITEMS).state() == LootTableStatus.IntegratedState.NONE, "A table without a rule is extended");
    helper.assertTrue(LootTableStatus.integrated(null).state() == LootTableStatus.IntegratedState.NONE, "No table is extended");
    helper.succeed();
  }

  // ----- /lootr check -----

  @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void checkReportsOkForAValidTable(GameTestHelper helper) {
    BlockPos relative = new BlockPos(1, 1, 1);
    BlockPos pos = helper.absolutePos(relative);
    ServerLevel level = helper.getLevel();
    LootrTestSupport.placeLootContainer(helper, relative, Blocks.CHEST, ITEMS, 7L);

    // Before Lootr converts it, it is a vanilla container with a loot table.
    ContainerInfo vanilla = ContainerLocator.at(level, pos);
    helper.assertTrue(vanilla != null && vanilla.kind() == ContainerInfo.Kind.VANILLA && ITEMS.equals(vanilla.table()) && vanilla.seed() == 7L && vanilla.provider() == null, "The unconverted chest is " + vanilla);
    ContainerCheck.Report vanillaReport = ContainerCheck.run(level, vanilla, null);
    helper.assertTrue(vanillaReport.status().kind() == LootTableStatus.Kind.OK && vanillaReport.uuid() == null, "The report of the unconverted chest is " + vanillaReport);

    ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, relative, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          ContainerInfo info = ContainerLocator.at(level, pos);
          helper.assertTrue(info != null && info.isLootr() && info.typeId().equals("lootr:chest") && ITEMS.equals(info.table()) && info.pos().equals(pos), "The Lootr chest is " + info);
          helper.assertTrue(info.dimension().equals(level.dimension()), "The container reports the dimension " + info.dimension());
          ContainerCheck.Report report = ContainerCheck.run(level, info, player);
          helper.assertTrue(report.status().kind() == LootTableStatus.Kind.OK, "The status is " + report.status());
          helper.assertTrue(report.uuid() != null && report.uuid().equals(info.provider().getInfoUUID()), "The report has the wrong UUID " + report.uuid());
          LootTableStatus.Sample sample = report.sample();
          helper.assertTrue(sample != null && sample.rolls() == 16 && sample.emptyRolls() == 0 && sample.averageStacks() == 1.0, "The sample is " + sample);
          helper.assertTrue(report.integrated().state() == LootTableStatus.IntegratedState.NONE, "Integrated loot is " + report.integrated());
          helper.assertTrue(report.structures().isEmpty(), "A chest in the empty test area is in the structures " + report.structures());
          helper.assertTrue(report.openers() == 0 && Boolean.FALSE.equals(report.playerHasInventory()), "Openers " + report.openers() + ", inventory " + report.playerHasInventory());

          helper.assertTrue(LootrAPI.getInventory(info.provider(), player) != null, "The player got no inventory");
          ContainerCheck.Report after = ContainerCheck.run(level, info, player);
          helper.assertTrue(Boolean.TRUE.equals(after.playerHasInventory()), "The player has an inventory now, the report says " + after.playerHasInventory());
        })
        .thenSucceed();
  }

  @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void checkReportsMissingForAnUnknownTable(GameTestHelper helper) {
    checkAfterConversion(helper, MISSING);
  }

  @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void checkReportsEmptyForATableWithoutPools(GameTestHelper helper) {
    checkAfterConversion(helper, NO_POOLS);
  }

  /**
   * Places a chest with the table and checks the verdict of the report before and after Lootr converts the chest.
   */
  private static void checkAfterConversion(GameTestHelper helper, ResourceKey<LootTable> table) {
    BlockPos relative = new BlockPos(2, 1, 2);
    BlockPos pos = helper.absolutePos(relative);
    ServerLevel level = helper.getLevel();
    LootrTestSupport.placeLootContainer(helper, relative, Blocks.CHEST, table, 3L);
    LootTableStatus.Kind expected = table.equals(MISSING) ? LootTableStatus.Kind.MISSING : LootTableStatus.Kind.EMPTY;

    // The verdict is the same before and after the conversion.
    ContainerInfo vanilla = ContainerLocator.at(level, pos);
    helper.assertTrue(vanilla != null && ContainerCheck.run(level, vanilla, null).status().kind() == expected, "The unconverted chest with " + table + " is not " + expected);
    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, relative, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          ContainerInfo info = ContainerLocator.at(level, pos);
          helper.assertTrue(info != null && info.isLootr() && table.equals(info.table()), "The Lootr chest is " + info);
          ContainerCheck.Report report = ContainerCheck.run(level, info, null);
          helper.assertTrue(report.status().kind() == expected, "The status of " + table + " is " + report.status());
          helper.assertTrue(report.sample() == null, "A table that is not OK was sampled: " + report.sample());
          if (expected == LootTableStatus.Kind.EMPTY) {
            helper.assertTrue(report.status().reason().equals("no_pools"), "The reason is " + report.status().reason());
          }
        })
        .thenSucceed();
  }

  @GameTest(template = EMPTY_STRUCTURE)
  public void checkReportsNoneForACustomInventory(GameTestHelper helper) {
    BlockPos relative = new BlockPos(3, 1, 3);
    helper.setBlock(relative, LootrRegistry.getInventoryBlock());
    ContainerInfo info = ContainerLocator.at(helper.getLevel(), helper.absolutePos(relative));
    helper.assertTrue(info != null && info.isLootr() && info.table() == null && info.typeId().equals("lootr:inventory"), "The custom inventory is " + info);
    ContainerCheck.Report report = ContainerCheck.run(helper.getLevel(), info, null);
    helper.assertTrue(report.status().kind() == LootTableStatus.Kind.NONE && report.sample() == null, "The status is " + report.status());
    helper.assertTrue(report.integrated().state() == LootTableStatus.IntegratedState.NONE, "Integrated loot is " + report.integrated());
    helper.succeed();
  }

  @GameTest(template = EMPTY_STRUCTURE)
  public void blocksWithoutALootTableAreNoContainers(GameTestHelper helper) {
    BlockPos relative = new BlockPos(4, 1, 4);
    helper.setBlock(relative, Blocks.CHEST);
    helper.setBlock(relative.above(), Blocks.STONE);
    helper.assertTrue(ContainerLocator.at(helper.getLevel(), helper.absolutePos(relative)) == null, "A chest without a loot table is a container");
    helper.assertTrue(ContainerLocator.at(helper.getLevel(), helper.absolutePos(relative.above())) == null, "Stone is a container");
    helper.assertTrue(ContainerLocator.at(helper.getLevel(), helper.absolutePos(relative).offset(0, 0, 100_000)) == null, "A position in an unloaded chunk is a container");
    helper.succeed();
  }

  @GameTest(template = EMPTY_STRUCTURE)
  public void structuresAtFindsTheStructuresWithAPieceAtThePosition(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    BlockPos pos = helper.absolutePos(new BlockPos(3, 2, 3));
    helper.assertTrue(ContainerCheck.structuresAt(level, pos).isEmpty(), "The test area is in a structure");
    Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.BURIED_TREASURE);
    LevelChunk chunk = level.getChunkAt(pos);
    ChunkPos chunkPos = chunk.getPos();
    StructureStart start = new StructureStart(structure, chunkPos, 0, new PiecesContainer(List.of(new BuriedTreasurePieces.BuriedTreasurePiece(pos))));
    try {
      // All of this happens within one tick, so that no other test can see the structure.
      chunk.setStartForStructure(structure, start);
      chunk.addReferenceForStructure(structure, chunkPos.toLong());
      List<ResourceLocation> inside = ContainerCheck.structuresAt(level, pos);
      helper.assertTrue(inside.equals(List.of(ResourceLocation.withDefaultNamespace("buried_treasure"))), "The structures at the piece are " + inside);
      helper.assertTrue(ContainerCheck.structuresAt(level, pos.east()).isEmpty(), "A position beside the piece is in " + ContainerCheck.structuresAt(level, pos.east()));
    } finally {
      Map<Structure, StructureStart> starts = new HashMap<>(chunk.getAllStarts());
      starts.remove(structure);
      chunk.setAllStarts(starts);
      Map<Structure, LongSet> references = new HashMap<>(chunk.getAllReferences());
      references.remove(structure);
      chunk.setAllReferences(references);
    }
    helper.assertTrue(ContainerCheck.structuresAt(level, pos).isEmpty(), "The structure was not removed again");
    helper.succeed();
  }

  @GameTest(template = EMPTY_STRUCTURE)
  public void vanillaMinecartsWithALootTableAreContainers(GameTestHelper helper) {
    MinecartChest cart = helper.spawn(EntityType.CHEST_MINECART, new BlockPos(5, 1, 5));
    cart.setLootTable(OTHER, 11L);
    ContainerInfo info = ContainerLocator.describe(cart);
    helper.assertTrue(info != null && info.entity() && ContainerLocator.hasTable(info, OTHER) && info.seed() == 11L, "The minecart is " + info);
    helper.assertTrue(info.typeId().equals("minecraft:chest_minecart") || info.isLootr(), "The minecart has the type " + info.typeId());
    cart.discard();
    helper.succeed();
  }

  // ----- /lootr locate table -----

  @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void locateTableFindsTheContainersNearestFirst(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    BlockPos origin = new BlockPos(0, 1, 0);
    // Placed in a different order than their distance from the origin: 1, 3, 5 and 6 blocks away.
    BlockPos far = new BlockPos(6, 1, 0);
    BlockPos middle = new BlockPos(3, 1, 0);
    BlockPos near = new BlockPos(1, 1, 0);
    BlockPos barrel = new BlockPos(0, 1, 5);
    BlockPos decoy = new BlockPos(2, 1, 0);
    BlockPos noTable = new BlockPos(4, 1, 0);
    LootrTestSupport.placeLootContainer(helper, far, Blocks.CHEST, NEARBY, 1L);
    LootrTestSupport.placeLootContainer(helper, middle, Blocks.CHEST, NEARBY, 2L);
    LootrTestSupport.placeLootContainer(helper, near, Blocks.CHEST, NEARBY, 3L);
    LootrTestSupport.placeLootContainer(helper, barrel, Blocks.BARREL, NEARBY, 4L);
    LootrTestSupport.placeLootContainer(helper, decoy, Blocks.CHEST, OTHER, 5L);
    helper.setBlock(noTable, Blocks.CHEST);

    Vec3 from = helper.absoluteVec(Vec3.atCenterOf(origin));
    AtomicReference<LocateSearch.TableResult> found = new AtomicReference<>();
    helper.startSequence()
        .thenWaitUntil(() -> {
          for (BlockPos pos : List.of(far, middle, near, decoy)) {
            LootrTestSupport.assertBlockIs(helper, pos, LootrRegistry.getChestBlock());
          }
          LootrTestSupport.assertBlockIs(helper, barrel, LootrRegistry.getBarrelBlock());
        })
        .thenExecute(() -> LocateSearch.table(level, from, NEARBY, 2, found::set))
        .thenWaitUntil(() -> helper.assertTrue(found.get() != null, "The search has not finished"))
        .thenExecute(() -> {
          LocateSearch.TableResult result = found.get();
          helper.assertTrue(result.status().kind() == LootTableStatus.Kind.OK, "The status is " + result.status());
          helper.assertTrue(result.total() == 4 && result.nearest().size() == 4, "Found " + result.total() + " containers, listed " + result.nearest().size());
          List<BlockPos> expected = List.of(helper.absolutePos(near), helper.absolutePos(middle), helper.absolutePos(barrel), helper.absolutePos(far));
          List<BlockPos> actual = result.nearest().stream().map(ContainerInfo::pos).toList();
          helper.assertTrue(actual.equals(expected), "The containers are " + actual + ", expected " + expected);
          helper.assertTrue(result.nearest().stream().allMatch(info -> NEARBY.equals(info.table()) && info.isLootr()), "A container with another table was found: " + result.nearest());
          helper.assertTrue(result.chunksScanned() >= 1 && result.chunksScanned() + result.chunksSkipped() == 25, "Chunks " + result.chunksScanned() + " + " + result.chunksSkipped());
        })
        .thenSucceed();
  }

  @GameTest(template = EMPTY_STRUCTURE)
  public void locateTableFindsMinecarts(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    MinecartChest cart = helper.spawn(EntityType.CHEST_MINECART, new BlockPos(2, 1, 2));
    cart.setNoGravity(true);
    cart.setLootTable(MINECARTS, 6L);
    MinecartChest decoy = helper.spawn(EntityType.CHEST_MINECART, new BlockPos(3, 1, 3));
    decoy.setNoGravity(true);
    decoy.setLootTable(OTHER, 7L);
    AtomicReference<LocateSearch.TableResult> found = new AtomicReference<>();
    // The chunk of the cart is loaded and has its entities, so the search is done within the call.
    LocateSearch.table(level, cart.position(), MINECARTS, 0, found::set);
    helper.startSequence()
        .thenWaitUntil(() -> helper.assertTrue(found.get() != null, "The search has not finished"))
        .thenExecute(() -> {
          LocateSearch.TableResult result = found.get();
          helper.assertTrue(result.total() == 1 && result.nearest().get(0).entity() && result.nearest().get(0).seed() == 6L, "The result is " + result);
          cart.discard();
          decoy.discard();
        })
        .thenSucceed();
  }

  @GameTest(template = EMPTY_STRUCTURE)
  public void locateTableFindsNothingForATableNobodyUses(GameTestHelper helper) {
    AtomicReference<LocateSearch.TableResult> found = new AtomicReference<>();
    LocateSearch.table(helper.getLevel(), helper.absoluteVec(Vec3.atCenterOf(BlockPos.ZERO)), table("locate/unused_by_anyone"), 1, found::set);
    helper.startSequence()
        .thenWaitUntil(() -> helper.assertTrue(found.get() != null, "The search has not finished"))
        .thenExecute(() -> {
          LocateSearch.TableResult result = found.get();
          helper.assertTrue(result.total() == 0 && result.nearest().isEmpty() && result.status().kind() == LootTableStatus.Kind.MISSING, "The result is " + result);
        })
        .thenSucceed();
  }

  @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
  public void locateTableSkipsChunksThatAreNotGenerated(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    ChunkPos center = new ChunkPos(-5000, -5000);
    helper.assertTrue(level.getChunkSource().chunkMap.read(center).join().isEmpty(), "The far chunk exists already");
    AtomicReference<LocateSearch.TableResult> found = new AtomicReference<>();
    LocateSearch.table(level, Vec3.atCenterOf(center.getWorldPosition()), NEARBY, 1, found::set);
    helper.startSequence()
        .thenWaitUntil(() -> helper.assertTrue(found.get() != null, "The search has not finished"))
        .thenExecute(() -> {
          LocateSearch.TableResult result = found.get();
          helper.assertTrue(result.total() == 0 && result.chunksScanned() == 0 && result.chunksSkipped() == 9, "The result is " + result);
          for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
              ChunkPos pos = new ChunkPos(center.x + x, center.z + z);
              helper.assertTrue(!level.getChunkSource().hasChunk(pos.x, pos.z), "The search loaded the chunk " + pos);
              helper.assertTrue(level.getChunkSource().chunkMap.read(pos).join().isEmpty(), "The search generated the chunk " + pos);
            }
          }
        })
        .thenSucceed();
  }

  @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 600)
  public void locateTableLoadsChunksThatAreGeneratedButNotLoaded(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    ChunkPos far = new ChunkPos(4000, 4000);
    BlockPos chestPos = new BlockPos(far.getMinBlockX() + 3, 70, far.getMinBlockZ() + 3);
    // Generates the chunk (a full chunk needs its neighbours to be generated a bit as well), puts a chest in it and
    // lets it go; it is saved when it is unloaded.
    level.getChunk(far.x, far.z);
    level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
    helper.assertTrue(level.getBlockEntity(chestPos) instanceof RandomizableContainerBlockEntity, "No chest at " + chestPos);
    ((RandomizableContainerBlockEntity) level.getBlockEntity(chestPos)).setLootTable(FAR, 9L);
    AtomicReference<LocateSearch.TableResult> found = new AtomicReference<>();
    helper.startSequence()
        .thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(far.x, far.z) == null, "The chunk is still loaded"))
        .thenExecute(() -> LocateSearch.table(level, Vec3.atCenterOf(chestPos), FAR, 1, found::set))
        .thenWaitUntil(() -> helper.assertTrue(found.get() != null, "The search has not finished"))
        .thenExecute(() -> {
          LocateSearch.TableResult result = found.get();
          helper.assertTrue(result.total() == 1 && result.nearest().size() == 1 && result.nearest().get(0).pos().equals(chestPos), "The result is " + result);
          helper.assertTrue(result.chunksScanned() == 1 && result.chunksSkipped() == 8, "Chunks " + result.chunksScanned() + " + " + result.chunksSkipped());
        })
        .thenSucceed();
  }

  @GameTest(template = EMPTY_STRUCTURE)
  public void chunkSquaresAreOrderedByDistance(GameTestHelper helper) {
    List<ChunkPos> square = ChunkScan.square(new ChunkPos(10, -4), 2);
    helper.assertTrue(square.size() == 25 && square.get(0).equals(new ChunkPos(10, -4)), "The square starts with " + square.get(0) + " and has " + square.size() + " chunks");
    long last = -1;
    for (ChunkPos pos : square) {
      long distance = (long) (pos.x - 10) * (pos.x - 10) + (long) (pos.z + 4) * (pos.z + 4);
      helper.assertTrue(distance >= last, "The chunk " + pos + " is out of order");
      last = distance;
    }
    helper.succeed();
  }

  // ----- /lootr locate structure -----

  @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void structureContentsListsTheContainersInsideTheBounds(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    BlockPos ok = new BlockPos(0, 1, 0);
    BlockPos missing = new BlockPos(1, 1, 0);
    BlockPos empty = new BlockPos(2, 1, 0);
    BlockPos outside = new BlockPos(6, 1, 6);
    LootrTestSupport.placeLootContainer(helper, ok, Blocks.CHEST, ITEMS, 1L);
    LootrTestSupport.placeLootContainer(helper, missing, Blocks.CHEST, MISSING, 2L);
    LootrTestSupport.placeLootContainer(helper, empty, Blocks.BARREL, NO_POOLS, 3L);
    LootrTestSupport.placeLootContainer(helper, outside, Blocks.CHEST, MISSING, 4L);
    BoundingBox bounds = BoundingBox.fromCorners(helper.absolutePos(new BlockPos(0, 0, 0)), helper.absolutePos(new BlockPos(3, 3, 3)));
    LocateSearch.StructureHit hit = new LocateSearch.StructureHit(ResourceLocation.withDefaultNamespace("test"), helper.absolutePos(BlockPos.ZERO), bounds);
    Vec3 from = helper.absoluteVec(Vec3.atCenterOf(BlockPos.ZERO));
    AtomicReference<LocateSearch.StructureResult> found = new AtomicReference<>();
    helper.startSequence()
        .thenWaitUntil(() -> {
          LootrTestSupport.assertBlockIs(helper, ok, LootrRegistry.getChestBlock());
          LootrTestSupport.assertBlockIs(helper, missing, LootrRegistry.getChestBlock());
          LootrTestSupport.assertBlockIs(helper, empty, LootrRegistry.getBarrelBlock());
          LootrTestSupport.assertBlockIs(helper, outside, LootrRegistry.getChestBlock());
        })
        .thenExecute(() -> helper.assertTrue(LocateSearch.structureContents(level, hit, from, found::set) != null, "The hit has bounds"))
        .thenWaitUntil(() -> helper.assertTrue(found.get() != null, "The search has not finished"))
        .thenExecute(() -> {
          LocateSearch.StructureResult result = found.get();
          helper.assertTrue(result.generated() && result.chunksSkipped() == 0, "The structure is not generated: " + result);
          List<BlockPos> positions = result.containers().stream().map(entry -> entry.container().pos()).toList();
          helper.assertTrue(positions.equals(List.of(helper.absolutePos(ok), helper.absolutePos(missing), helper.absolutePos(empty))), "The containers are " + positions);
          helper.assertTrue(result.missing() == 1 && result.empty() == 1, "Missing " + result.missing() + ", empty " + result.empty());
          helper.assertTrue(result.containers().get(0).status().kind() == LootTableStatus.Kind.OK
              && result.containers().get(1).status().kind() == LootTableStatus.Kind.MISSING
              && result.containers().get(2).status().kind() == LootTableStatus.Kind.EMPTY, "The statuses are " + result.containers());
        })
        .thenSucceed();
  }

  @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
  public void structureContentsOfAStructureThatIsNotGeneratedSaysSo(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    BlockPos pos = new BlockPos(-90_000, 64, -90_000);
    LocateSearch.StructureHit hit = new LocateSearch.StructureHit(ResourceLocation.withDefaultNamespace("test"), pos, new BoundingBox(pos.getX(), 60, pos.getZ(), pos.getX() + 40, 80, pos.getZ() + 40));
    AtomicReference<LocateSearch.StructureResult> found = new AtomicReference<>();
    LocateSearch.structureContents(level, hit, Vec3.atCenterOf(pos), found::set);
    helper.startSequence()
        .thenWaitUntil(() -> helper.assertTrue(found.get() != null, "The search has not finished"))
        .thenExecute(() -> {
          LocateSearch.StructureResult result = found.get();
          helper.assertTrue(!result.generated() && result.containers().isEmpty() && result.chunksSkipped() == result.chunksTotal() && result.chunksTotal() == 9, "The result is " + result);
          helper.assertTrue(!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4), "The search loaded a chunk");
        })
        .thenSucceed();
    LocateSearch.StructureHit unknown = new LocateSearch.StructureHit(ResourceLocation.withDefaultNamespace("test"), pos, null);
    helper.assertTrue(LocateSearch.structureContents(level, unknown, Vec3.ZERO, result -> {
    }) == null, "A hit without bounds was scanned");
  }

  // ----- the commands -----

  @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void checkCommandAnswersForAPositionAndForTheLookedAtContainer(GameTestHelper helper) {
    MinecraftServer server = helper.getLevel().getServer();
    BlockPos relative = new BlockPos(3, 1, 3);
    BlockPos pos = helper.absolutePos(relative);
    LootrTestSupport.placeLootContainer(helper, relative, Blocks.CHEST, LOOK, 21L);
    ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, relative, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          Output output = new Output();
          run(server, helper.getLevel(), output, null, null, "lootr check " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
          helper.assertTrue(output.has("lootr.commands.check.header") && output.has("lootr.commands.check.status.ok") && output.has("lootr.commands.check.sample"), "The answer is " + output.keys());
          helper.assertTrue(output.args("lootr.commands.check.kind.lootr")[0].equals("lootr:chest"), "The kind is " + output.keys());
          Object[] table = output.args("lootr.commands.check.table");
          helper.assertTrue(table[0].equals(LOOK.location().toString()) && table[1].equals(21L), "The table line is " + Arrays.toString(table));
          helper.assertTrue(output.has("lootr.commands.check.uuid") && output.has("lootr.commands.check.structure.none") && output.has("lootr.commands.check.integrated.none") && output.has("lootr.commands.check.openers"), "The answer is " + output.keys());
          helper.assertTrue(!output.has("lootr.commands.check.inventory.yes") && !output.has("lootr.commands.check.inventory.no"), "A source that is no player was asked about its inventory");

          Output empty = new Output();
          run(server, helper.getLevel(), empty, null, null, "lootr check " + pos.getX() + " " + (pos.getY() + 3) + " " + pos.getZ());
          helper.assertTrue(empty.has("lootr.commands.check.not_a_container"), "Air is a container: " + empty.keys());
          Output console = new Output();
          run(server, helper.getLevel(), console, null, null, "lootr check");
          helper.assertTrue(console.has("lootr.commands.check.needs_player"), "The console looked at something: " + console.keys());

          // Three blocks south of the chest, looking at it.
          face(player, pos.getX() + 0.5, pos.getY(), pos.getZ() + 3.5, 180.0F, 20.0F);
          Output looked = new Output();
          run(server, helper.getLevel(), looked, player, null, "lootr check");
          helper.assertTrue(looked.has("lootr.commands.check.status.ok") && looked.has("lootr.commands.check.inventory.no"), "The answer for the looked at chest is " + looked.keys());
          face(player, pos.getX() + 0.5, pos.getY(), pos.getZ() + 3.5, 0.0F, -60.0F);
          Output elsewhere = new Output();
          run(server, helper.getLevel(), elsewhere, player, null, "lootr check");
          helper.assertTrue(elsewhere.has("lootr.commands.check.no_target"), "The player looked at " + elsewhere.keys());
        })
        .thenSucceed();
  }

  @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void locateCommandsAnswer(GameTestHelper helper) {
    MinecraftServer server = helper.getLevel().getServer();
    BlockPos near = new BlockPos(1, 1, 1);
    BlockPos far = new BlockPos(5, 1, 5);
    LootrTestSupport.placeLootContainer(helper, far, Blocks.CHEST, COMMAND, 1L);
    LootrTestSupport.placeLootContainer(helper, near, Blocks.BARREL, COMMAND, 2L);
    Vec3 from = helper.absoluteVec(Vec3.atCenterOf(BlockPos.ZERO));
    Output table = new Output();
    Output structure = new Output();
    Output tooLarge = new Output();
    helper.startSequence()
        .thenWaitUntil(() -> {
          LootrTestSupport.assertBlockIs(helper, near, LootrRegistry.getBarrelBlock());
          LootrTestSupport.assertBlockIs(helper, far, LootrRegistry.getChestBlock());
        })
        .thenExecute(() -> {
          run(server, helper.getLevel(), table, null, from, "lootr locate table " + COMMAND.location() + " 1");
          run(server, helper.getLevel(), tooLarge, null, from, "lootr locate table " + COMMAND.location() + " 33");
          run(server, helper.getLevel(), structure, null, from, "lootr locate structure minecraft:buried_treasure 1");
        })
        .thenWaitUntil(() -> helper.assertTrue(table.has("lootr.commands.locate.total"), "The table search has not answered: " + table.keys()))
        .thenExecute(() -> {
          helper.assertTrue(table.has("lootr.commands.locate.table.start") && table.has("lootr.commands.locate.table.header"), "The answer is " + table.keys());
          List<Component> entries = table.all("lootr.commands.locate.entry");
          helper.assertTrue(entries.size() == 2, "Two containers are listed, not " + entries.size() + ": " + table.keys());
          Object[] first = Output.translatable(entries.get(0)).getArgs();
          Object[] second = Output.translatable(entries.get(1)).getArgs();
          helper.assertTrue(((Component) first[0]).getString().equals(helper.absolutePos(near).getX() + ", " + helper.absolutePos(near).getY() + ", " + helper.absolutePos(near).getZ()), "The nearest is " + ((Component) first[0]).getString());
          helper.assertTrue(((Component) first[0]).getStyle().getClickEvent() != null && ((Component) first[0]).getStyle().getClickEvent().getValue().startsWith("/tp @s "), "The position does not teleport");
          helper.assertTrue((Integer) first[1] < (Integer) second[1], "The distances are " + first[1] + ", " + second[1]);
          Object[] total = table.args("lootr.commands.locate.total");
          helper.assertTrue(total[0].equals(2), "The total is " + Arrays.toString(total));
          helper.assertTrue(!tooLarge.lines.isEmpty() && !tooLarge.has("lootr.commands.locate.total") && !tooLarge.has("lootr.commands.locate.table.start"), "A radius above the maximum was accepted: " + tooLarge.keys());
          // The structure is not near the test area: it is either not found or found without being generated.
          helper.assertTrue(structure.has("lootr.commands.locate.structure.not_found") || structure.has("lootr.commands.locate.structure.found"), "The structure search said " + structure.keys());
        })
        .thenSucceed();
  }

  // The line of sight follows the head, which `moveTo` leaves alone.
  private static void face(ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
    player.moveTo(x, y, z, yaw, pitch);
    player.setYHeadRot(yaw);
  }

  private static void run(MinecraftServer server, ServerLevel level, Output output, ServerPlayer player, Vec3 position, String command) {
    CommandSourceStack source = (player == null ? server.createCommandSourceStack() : player.createCommandSourceStack())
        .withLevel(level).withSource(output).withPermission(2);
    if (position != null) {
      source = source.withPosition(position);
    }
    server.getCommands().performPrefixedCommand(source, command);
  }

  /**
   * Collects what a command prints.
   */
  private static final class Output implements CommandSource {
    final List<Component> lines = new ArrayList<>();

    @Override
    public void sendSystemMessage(Component component) {
      lines.add(component);
    }

    @Override
    public boolean acceptsSuccess() {
      return true;
    }

    @Override
    public boolean acceptsFailure() {
      return true;
    }

    @Override
    public boolean shouldInformAdmins() {
      return false;
    }

    List<String> keys() {
      return lines.stream().map(line -> {
        TranslatableContents translatable = translatable(line);
        return translatable != null ? translatable.getKey() : line.getString();
      }).toList();
    }

    boolean has(String key) {
      return !all(key).isEmpty();
    }

    List<Component> all(String key) {
      return lines.stream().filter(line -> {
        TranslatableContents translatable = translatable(line);
        return translatable != null && translatable.getKey().equals(key);
      }).toList();
    }

    Object[] args(String key) {
      List<Component> found = all(key);
      if (found.isEmpty()) {
        throw new GameTestAssertException("No line " + key + " in " + keys());
      }
      return translatable(found.get(0)).getArgs();
    }

    // A failure is sent wrapped in an empty text that carries the red colour.
    static TranslatableContents translatable(Component line) {
      if (line.getContents() instanceof TranslatableContents translatable) {
        return translatable;
      }
      for (Component sibling : line.getSiblings()) {
        TranslatableContents found = translatable(sibling);
        if (found != null) {
          return found;
        }
      }
      return null;
    }
  }
}
