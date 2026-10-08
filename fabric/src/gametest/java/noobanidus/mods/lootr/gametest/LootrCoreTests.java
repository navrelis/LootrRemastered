package noobanidus.mods.lootr.gametest;

import com.mojang.datafixers.util.Pair;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.LockCode;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import noobanidus.mods.lootr.common.api.BuiltInLootrTypes;
import noobanidus.mods.lootr.common.api.ILootrAPI;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.LootrTags;
import noobanidus.mods.lootr.common.api.NBTConstants;
import noobanidus.mods.lootr.common.api.data.CustomLootrInfoProvider;
import noobanidus.mods.lootr.common.api.data.DefaultLootFiller;
import noobanidus.mods.lootr.common.api.data.ILootrInfo;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import noobanidus.mods.lootr.common.api.data.LootFiller;
import noobanidus.mods.lootr.common.api.data.LootrBlockType;
import noobanidus.mods.lootr.common.api.data.NewTickingData;
import noobanidus.mods.lootr.common.api.data.TickingData;
import noobanidus.mods.lootr.common.api.data.blockentity.ILootrBlockEntity;
import noobanidus.mods.lootr.common.api.data.blockentity.LockMessageSuppression;
import noobanidus.mods.lootr.common.api.data.inventory.ILootrInventory;
import noobanidus.mods.lootr.common.api.registry.LootrRegistry;
import noobanidus.mods.lootr.common.block.entity.BlockEntityTicker;
import noobanidus.mods.lootr.common.block.entity.LootrChestBlockEntity;
import noobanidus.mods.lootr.common.chunk.LoadedChunks;
import noobanidus.mods.lootr.common.data.DataStorage;
import noobanidus.mods.lootr.common.data.LootrSavedData;
import noobanidus.mods.lootr.common.entity.LootrChestMinecartEntity;
import noobanidus.mods.lootr.common.impl.DefaultLootrAPIImpl;
import noobanidus.mods.lootr.common.mixin.accessor.AccessorMixinBaseContainerBlockEntity;
import noobanidus.mods.lootr.common.mixin.accessor.AccessorMixinDimensionDataStorage;
import noobanidus.mods.lootr.fabric.config.ConfigManager;
import noobanidus.mods.lootr.fabric.impl.LootrAPIImpl;
import noobanidus.mods.lootr.fabric.impl.PlatformAPIImpl;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Tests for the core of the mod: saved data, ticking data, the conversion queue and the static flags.
 * <br />
 * Whatever is global (configuration, the data storage cache, `LootrAPI.INSTANCE`) is changed only inside one
 * synchronous block on the server thread and restored in a `finally`. Lines logged with `[T07]` and `[T08b]`
 * are the measurements the tests are built on.
 */
public class LootrCoreTests implements FabricGameTest {
  private static final String BATCH = "lootr_core";
  private static final int CONVERSION_TIMEOUT_TICKS = 400;
  private static final int CHUNK_WAIT_SECONDS = 60;
  private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
  private static final Consumer<CompoundTag> UNCHANGED = tag -> {
  };

  // ----- 1. Saved data becomes dirty only when something that is persisted changed -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void savedDataIsNotRewrittenWithoutChanges(GameTestHelper helper) {
    List<BlockPos> positions = grid(20);
    placeLootChests(helper, positions, 100L);
    List<String> keys = new ArrayList<>();

    helper.startSequence()
        .thenWaitUntil(() -> assertConverted(helper, positions))
        .thenExecute(() -> {
          HolderLookup.Provider registries = helper.getLevel().registryAccess();
          List<ILootrBlockEntity> chests = lootrChests(helper, positions);
          chests.forEach(chest -> keys.add(chest.getInfoKey()));
          ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
          for (ILootrBlockEntity chest : chests) {
            LootrAPI.handleProviderOpen(chest, player);
            player.closeContainer();
          }
          storage().save();
          helper.assertTrue(countFiles(helper, keys) == 20, "Every opened container must have a data file");

          int sameSession = requestUpdateTagsAndCountRewrites(chests, keys, registries, 5);
          simulateRestart(keys);
          int afterRestart = requestUpdateTagsAndCountRewrites(chests, keys, registries, 5);
          LootrAPI.LOG.info("[T07] item 1: 20 opened chests, 5 rounds of update tags + world save: data files rewritten in the same session = {}, after a restart = {}", sameSession, afterRestart);
          helper.assertTrue(sameSession == 0, "Update tags rewrote " + sameSession + " data files although nothing changed");
          helper.assertTrue(afterRestart == 0, "Update tags rewrote " + afterRestart + " data files after a restart although nothing changed");

          for (int i = 0; i < chests.size(); i++) {
            ILootrBlockEntity chest = chests.get(i);
            LootrSavedData data = cached(keys.get(i));
            helper.assertTrue(!(data.getRedirect() instanceof BlockEntity), "The cached saved data references the live block entity");
            helper.assertTrue(data.getInfoPos().equals(chest.getInfoPos()) && data.getInfoContainerSize() == 27 && data.getInfoNewType() == BuiltInLootrTypes.CHEST, "The saved data lost information about its container");
            helper.assertTrue(data.getInfoContainer() == chest.asBlockEntity(), "The saved data no longer finds its container");
          }
        })
        .thenIdle(40)
        .thenExecute(() -> helper.assertTrue(countDirty(keys) == 0, "Ticking alone marked " + countDirty(keys) + " saved data objects as changed"))
        .thenSucceed();
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void savedDataFollowsChangedInfo(GameTestHelper helper) {
    UUID id = UUID.randomUUID();
    String key = ILootrInfo.generateInfoKey(id);
    BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
    BlockPos second = helper.absolutePos(new BlockPos(2, 1, 2));
    try {
      LootrSavedData data = DataStorage.getData(provider(helper, id, first, "First"));
      helper.assertTrue(!data.isDirty(), "New saved data without content must not be saved");
      DataStorage.getData(provider(helper, id, first, "First"));
      helper.assertTrue(!data.isDirty(), "Asking again with the same info marked the data as changed");

      helper.assertTrue(DataStorage.getData(provider(helper, id, second, "First")) == data, "The same container yielded another data object");
      helper.assertTrue(data.isDirty(), "A changed position was not marked as changed");
      helper.assertTrue(data.getInfoPos().equals(second), "The saved data does not report the current position");
      data.setDirty(false);
      DataStorage.getData(provider(helper, id, second, "First"));
      helper.assertTrue(!data.isDirty(), "The data stays dirty forever once its info changed");

      DataStorage.getData(provider(helper, id, second, "Second"));
      helper.assertTrue(data.isDirty(), "A changed name was not marked as changed");
      helper.assertTrue(Component.literal("Second").equals(data.getInfoDisplayName()), "The saved data does not report the current name");
    } finally {
      storageCache().remove(key);
    }
    helper.succeed();
  }

  // Menu title, container size and validity of an open menu still follow the container.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void openMenuKeepsWorkingAfterRestart(GameTestHelper helper) {
    BlockPos pos = new BlockPos(1, 1, 1);
    LootrTestSupport.placeLootContainer(helper, pos, Blocks.CHEST, TABLE, 3L);
    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, pos, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          ILootrBlockEntity chest = LootrTestSupport.getLootrBlockEntity(helper, pos);
          ServerPlayer player = playerAt(helper, pos);
          LootrAPI.handleProviderOpen(chest, player);
          NonNullList<ItemStack> generated = snapshot(openInventory(helper, player));
          player.closeContainer();
          simulateRestart(List.of(chest.getInfoKey()));

          LootrAPI.handleProviderOpen(chest, player);
          ILootrInventory inventory = openInventory(helper, player);
          helper.assertTrue(ItemStack.listMatches(generated, snapshot(inventory)), "The stored inventory changed across a restart");
          helper.assertTrue(inventory.getContainerSize() == 27, "The inventory reports size " + inventory.getContainerSize());
          helper.assertTrue(inventory.getDisplayName().equals(chest.getInfoDisplayName()), "The menu title is " + inventory.getDisplayName());
          helper.assertTrue(inventory.stillValid(player), "The open inventory is not valid for a player next to the container");
          helper.assertTrue(chest.getPhysicalOpenerCount() == 1, "The container does not count its open menu");
          player.closeContainer();
          helper.assertTrue(chest.getPhysicalOpenerCount() == 0, "Closing the menu did not reach the container");
        })
        .thenSucceed();
  }

  // ----- 2. Update tags of containers that were never opened need no saved data -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void unopenedContainersNeedNoSavedDataForUpdateTags(GameTestHelper helper) {
    List<BlockPos> positions = grid(50);
    placeLootChests(helper, positions, 200L);

    helper.startSequence()
        .thenWaitUntil(() -> assertConverted(helper, positions))
        .thenExecute(() -> {
          HolderLookup.Provider registries = helper.getLevel().registryAccess();
          List<ILootrBlockEntity> chests = lootrChests(helper, positions);
          List<String> keys = chests.stream().map(ILootrInfo::getInfoKey).toList();

          // As converted: the ids of these containers were generated in this session, nothing can be saved for them.
          for (ILootrBlockEntity chest : chests) {
            helper.assertTrue(openers(chest, registries).isEmpty(), "A container that was never opened reports openers");
          }
          long objects = countCached(keys);
          long lookups = countLookedUp(keys);
          LootrAPI.LOG.info("[T07] item 2: update tags of 50 unopened chests, as converted: saved data objects = {}, file lookups = {}", objects, lookups);
          helper.assertTrue(objects == 0, "Update tags created saved data for " + objects + " containers that were never opened");
          helper.assertTrue(lookups == 0, "Update tags looked for the data files of " + lookups + " containers that cannot have one");

          // As loaded with their chunk after a restart: whether a data file exists is looked up, once.
          List<ILootrBlockEntity> reloaded = chests.stream().map(chest -> reload(helper, chest, UNCHANGED)).toList();
          for (int round = 0; round < 3; round++) {
            for (ILootrBlockEntity chest : reloaded) {
              helper.assertTrue(openers(chest, registries).isEmpty(), "A container that was never opened reports openers");
            }
          }
          objects = countCached(keys);
          lookups = countLookedUp(keys);
          storage().save();
          long files = countFiles(helper, keys);
          LootrAPI.LOG.info("[T07] item 2: update tags of 50 unopened chests, as loaded from disk, requested 3 times: saved data objects = {}, file lookups = {}, data files after a world save = {}", objects, lookups, files);
          helper.assertTrue(objects == 0, "Update tags created saved data for " + objects + " containers that were never opened");
          helper.assertTrue(lookups == 50, "Expected one file lookup per container but there were " + lookups);
          helper.assertTrue(files == 0, files + " data files were written for containers that were never opened");

          // Opened containers still report who opened them: at once, and after a restart.
          ILootrBlockEntity chest = chests.getFirst();
          String key = keys.getFirst();
          ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
          UUID playerId = LootrAPI.resolveServerPlayerTeam(player);
          helper.assertTrue(LootrAPI.getLoadedData(key) == null && LootrAPI.getExistingData(key) == null, "There is data for a container that was never opened");
          LootrAPI.handleProviderOpen(chest, player);
          player.closeContainer();
          helper.assertTrue(LootrAPI.getLoadedData(key) == cached(key) && LootrAPI.getExistingData(key) == cached(key), "The data of an opened container is not found");
          helper.assertTrue(openers(chest, registries).contains(playerId), "The update tag does not name the player who opened the container");
          simulateRestart(List.of(key));
          helper.assertTrue(LootrAPI.getLoadedData(key) == null, "Data counts as loaded although it is only on disk");
          helper.assertTrue(openers(chest, registries).contains(playerId), "The update tag lost the opener after a restart");

          // An opener that exists before the container was physically opened (as while it is being opened) is reported too.
          ILootrBlockEntity other = chests.get(1);
          helper.assertTrue(DataStorage.getData(other).addVisualOpener(player) && !other.hasBeenOpened(), "The opener was not added");
          helper.assertTrue(openers(other, registries).contains(playerId), "An opener in loaded data is not reported while the container counts as unopened");

          // Old worlds: a container that was opened and saved before `LootrHasBeenOpened` existed has no flag;
          // once a version that knows the flag saved it again, it has the flag, set to false. Its data file
          // still names the openers.
          List<Consumer<CompoundTag>> oldForms = List.of(tag -> tag.remove(NBTConstants.HAS_BEEN_OPENED), tag -> tag.putBoolean(NBTConstants.HAS_BEEN_OPENED, false));
          for (Consumer<CompoundTag> oldForm : oldForms) {
            simulateRestart(List.of(key));
            ILootrBlockEntity old = reload(helper, chest, oldForm);
            helper.assertTrue(!old.hasBeenOpened(), "The block entity was not loaded in its old form");
            helper.assertTrue(openers(old, registries).contains(playerId), "A container of an old world lost its openers");
          }
        })
        .thenSucceed();
  }

  // ----- 3. Legacy ticking data -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void legacyTickingFilesAreNotCreated(GameTestHelper helper) throws IOException {
    UUID id = UUID.randomUUID();
    ILootrInfoProvider provider = provider(helper, id, helper.absolutePos(BlockPos.ZERO), "Ticking");
    Path decayFile = dataFile(helper, DataStorage.DECAYS);
    Path refreshFile = dataFile(helper, DataStorage.REFRESHES);
    // Start as a world without legacy data.
    storageCache().remove(DataStorage.DECAYS);
    storageCache().remove(DataStorage.REFRESHES);
    Files.deleteIfExists(decayFile);
    Files.deleteIfExists(refreshFile);
    try {
      helper.assertTrue(DataStorage.getDecayValue(provider) == -1 && DataStorage.getRefreshValue(provider) == -1, "A new container is already ticking");
      DataStorage.setDecaying(provider);
      DataStorage.setRefreshing(provider);
      helper.assertTrue(DataStorage.getDecayValue(provider) == LootrAPI.getDecayValue(), "Decay was not started");
      helper.assertTrue(DataStorage.getRefreshValue(provider) == LootrAPI.getRefreshValue(), "Refresh was not started");

      long dirty = countDirty(List.of(DataStorage.DECAYS, DataStorage.REFRESHES));
      storage().save();
      boolean created = Files.exists(decayFile) || Files.exists(refreshFile);
      LootrAPI.LOG.info("[T07] item 3: ticking queries without legacy data: legacy data objects marked dirty = {}, legacy files created by the world save = {}", dirty, created);
      helper.assertTrue(dirty == 0, "Legacy ticking data was marked as changed although there is nothing to migrate");
      helper.assertTrue(!created, "A legacy ticking file was created");
    } finally {
      NewTickingData.getDecayData().clearTicking(helper.getLevel().getServer(), id);
      NewTickingData.getRefreshData().clearTicking(helper.getLevel().getServer(), id);
    }
    helper.succeed();
  }

  @SuppressWarnings("deprecation")
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void legacyTickingDataIsMigratedOnce(GameTestHelper helper) {
    MinecraftServer server = helper.getLevel().getServer();
    UUID pending = UUID.randomUUID();
    UUID complete = UUID.randomUUID();
    TickingData legacy = new TickingData();
    legacy.setValue(pending, 500);
    legacy.setValue(complete, 0);
    legacy.setDirty(false);
    SavedData previous = storageCache().put(DataStorage.DECAYS, legacy);
    try {
      int remaining = DataStorage.getDecayValue(provider(helper, pending, helper.absolutePos(BlockPos.ZERO), "Pending"));
      helper.assertTrue(remaining == 500, "The legacy decay time became " + remaining);
      helper.assertTrue(DataStorage.isDecayed(provider(helper, complete, helper.absolutePos(BlockPos.ZERO), "Complete")), "A completed legacy entry is not complete any more");
      helper.assertTrue(legacy.getTickMap().isEmpty(), "The legacy data was not emptied");
      helper.assertTrue(legacy.isDirty(), "The emptied legacy data must be saved once");

      legacy.setDirty(false);
      helper.assertTrue(DataStorage.getDecayValue(provider(helper, pending, helper.absolutePos(BlockPos.ZERO), "Pending")) == 500, "The migrated value changed");
      helper.assertTrue(!legacy.isDirty(), "The empty legacy data is marked as changed on every query");
    } finally {
      if (previous == null) {
        storageCache().remove(DataStorage.DECAYS);
      } else {
        storageCache().put(DataStorage.DECAYS, previous);
      }
      NewTickingData.getDecayData().clearTicking(server, pending);
      NewTickingData.getDecayData().clearTicking(server, complete);
    }
    helper.succeed();
  }

  // ----- 4. Nothing refreshes or decays with the default configuration -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void nothingRefreshesOrDecaysByDefault(GameTestHelper helper) {
    ConfigManager config = ConfigManager.get();
    helper.assertTrue(!config.refresh.refresh_all && !config.decay.decay_all && ConfigManager.getRefreshDimensions().isEmpty() && ConfigManager.getDecayDimensions().isEmpty(), "The test needs the default configuration");
    boolean refreshing = LootrAPI.isAnythingRefreshing();
    boolean decaying = LootrAPI.isAnythingDecaying();
    LootrAPI.LOG.info("[T07] item 4: default configuration: isAnythingRefreshing = {}, isAnythingDecaying = {}", refreshing, decaying);
    helper.assertTrue(!refreshing, "The empty refresh structure tag counts as \"something refreshes\"");
    helper.assertTrue(!decaying, "The empty decay structure tag counts as \"something decays\"");

    config.refresh.refresh_all = true;
    config.decay.decay_all = true;
    try {
      helper.assertTrue(LootrAPI.isAnythingRefreshing(), "`refresh_all` does not enable refreshing");
      helper.assertTrue(LootrAPI.isAnythingDecaying(), "`decay_all` does not enable decaying");
    } finally {
      config.refresh.refresh_all = false;
      config.decay.decay_all = false;
    }

    // A structure tag enables it as soon as it has content; the tags the mod ships are empty.
    MinecraftServer server = helper.getLevel().getServer();
    helper.assertTrue(DefaultLootrAPIImpl.hasTaggedStructures(server, StructureTags.VILLAGE), "A structure tag with content does not count");
    helper.assertTrue(!DefaultLootrAPIImpl.hasTaggedStructures(server, LootrTags.Structure.REFRESH_STRUCTURES) && !DefaultLootrAPIImpl.hasTaggedStructures(server, LootrTags.Structure.DECAY_STRUCTURES), "The shipped structure tags are expected to be empty");
    helper.assertTrue(!DefaultLootrAPIImpl.hasTaggedStructures(server, TagKey.create(Registries.STRUCTURE, LootrAPI.rl("gametest_no_such_tag"))), "A structure tag that does not exist counts");
    helper.succeed();
  }

  // ----- 5. Conversion queue -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void placedContainerConvertsWithoutDelay(GameTestHelper helper) {
    BlockPos pos = new BlockPos(1, 1, 1);
    long placedAt = helper.getLevel().getGameTime();
    LootrTestSupport.placeLootContainer(helper, pos, Blocks.CHEST, TABLE, 5L);
    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, pos, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          long ticks = helper.getLevel().getGameTime() - placedAt;
          LootrAPI.LOG.info("[T07] item 5: a container placed in a loaded area was converted after {} ticks", ticks);
          helper.assertTrue(ticks <= 2, "Conversion took " + ticks + " ticks");
        })
        .thenSucceed();
  }

  /**
   * A container in a chunk at the edge of the loaded area waits in the queue. Chunk (x, z) is forced, which
   * loads the chunks up to two away as border chunks; the container sits in (x + 2, z), whose own
   * neighbours are not all loaded.
   */
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 1200)
  public void waitingConversionsAreBounded(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    MinecraftServer server = level.getServer();
    ChunkPos forced = new ChunkPos(new ChunkPos(helper.absolutePos(BlockPos.ZERO)).x + 300, 300);
    ChunkPos waiting = new ChunkPos(forced.x + 2, forced.z);
    BlockPos chestPos = waiting.getBlockAt(8, 100, 8);
    AtomicReference<Long> readyAt = new AtomicReference<>();
    level.setChunkForced(forced.x, forced.z, true);
    awaitChunks(helper, () -> LoadedChunks.getLoadedChunks(level.dimension()).contains(waiting), "The border chunk was not loaded");

    helper.startSequence()
        .thenExecute(() -> {
          level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
          ((ChestBlockEntity) level.getBlockEntity(chestPos)).setLootTable(TABLE, 6L);
        })
        .thenIdle(5)
        .thenExecute(() -> {
          helper.assertTrue(level.getBlockState(chestPos).is(Blocks.CHEST), "The container was converted although its surroundings are not loaded");
          helper.assertTrue(isQueued(level, waiting), "The container is not waiting in the queue");

          // How often is the waiting entry looked at over 100 server ticks?
          AtomicInteger checks = new AtomicInteger();
          ILootrAPI original = LootrAPI.INSTANCE;
          LootrAPI.INSTANCE = new LootrAPIImpl() {
            @Override
            public boolean isWorldBorderSafe(Level borderLevel, ChunkPos pos) {
              if (pos.equals(waiting)) {
                checks.incrementAndGet();
              }
              return super.isWorldBorderSafe(borderLevel, pos);
            }
          };
          try {
            for (int i = 0; i < 100; i++) {
              BlockEntityTicker.onServerTick(server);
            }
          } finally {
            LootrAPI.INSTANCE = original;
          }
          LootrAPI.LOG.info("[T07] item 5: one waiting queue entry over 100 server ticks: load status evaluated {} times (up to 25 chunk lookups each)", checks.get());
          helper.assertTrue(isQueued(level, waiting), "The waiting entry was dropped although its chunk is loaded");
          helper.assertTrue(checks.get() <= 25, "A waiting entry is re-checked on every tick (" + checks.get() + " times in 100 ticks)");

          // Complete the surroundings: the container must be converted soon after. Loading takes its own time
          // and no tick passes while it is waited for; the conversion after it is counted in ticks.
          level.setChunkForced(waiting.x, waiting.z, true);
          awaitChunks(helper, () -> surroundingsLoaded(level, waiting), "The surroundings of the waiting chunk were not loaded");
          readyAt.set(level.getGameTime());
        })
        .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(chestPos).is(LootrRegistry.getChestBlock()), "Waiting for the conversion"))
        .thenExecute(() -> {
          long delay = level.getGameTime() - readyAt.get();
          LootrAPI.LOG.info("[T07] item 5: a waiting container was converted {} ticks after its surroundings were loaded", delay);
          helper.assertTrue(delay <= 7, "A waiting container was converted only " + delay + " ticks after its surroundings were loaded");
          helper.assertTrue(!isQueued(level, waiting), "The converted container is still queued");

          // Loaded and unloaded again: the entry of an unloaded chunk leaves the queue.
          level.setBlockAndUpdate(chestPos, Blocks.AIR.defaultBlockState());
          ChunkPos border = new ChunkPos(waiting.x + 2, waiting.z);
          BlockPos borderPos = border.getBlockAt(8, 100, 8);
          level.setBlockAndUpdate(borderPos, Blocks.CHEST.defaultBlockState());
          ((ChestBlockEntity) level.getBlockEntity(borderPos)).setLootTable(TABLE, 7L);
          level.setBlockAndUpdate(borderPos, Blocks.AIR.defaultBlockState());
          helper.assertTrue(isQueued(level, border), "The second container is not waiting in the queue");
          level.setChunkForced(forced.x, forced.z, false);
          level.setChunkForced(waiting.x, waiting.z, false);
        })
        .thenWaitUntil(() -> helper.assertTrue(!isQueued(level, new ChunkPos(waiting.x + 2, waiting.z)), "Waiting for the entry of the unloaded chunk to be dropped"))
        .thenExecute(() -> LootrAPI.LOG.info("[T07] item 5: after its chunk was unloaded the waiting entry left the queue; queued chunks now = {}", queueSize(level)))
        .thenSucceed();
  }

  // Alone in its batch: clearing the queue must not drop conversions that other tests are waiting for.
  @GameTest(template = EMPTY_STRUCTURE, batch = "lootr_core_queue_clear", timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void conversionQueueCanBeCleared(GameTestHelper helper) {
    BlockPos pos = new BlockPos(1, 1, 1);
    ServerLevel level = helper.getLevel();
    LootrTestSupport.placeLootContainer(helper, pos, Blocks.CHEST, TABLE, 13L);
    helper.assertTrue(queueSize(level) > 0, "The placed container was not queued");
    BlockEntityTicker.clear();
    helper.assertTrue(queueSize(level) == 0, "The queue was not cleared");

    helper.startSequence()
        .thenIdle(20)
        .thenExecute(() -> {
          helper.assertBlockPresent(Blocks.CHEST, pos);
          // The queue keeps working after it was cleared.
          ((ChestBlockEntity) helper.getBlockEntity(pos)).setLootTable(TABLE, 13L);
        })
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, pos, LootrRegistry.getChestBlock()))
        .thenSucceed();
  }

  // ----- 6. Static flags -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void fillerStateIsResetAndThreadConfined(GameTestHelper helper) throws InterruptedException {
    ServerLevel level = helper.getLevel();
    ILootrInfoProvider provider = provider(helper, UUID.randomUUID(), helper.absolutePos(BlockPos.ZERO), "Filler");
    ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
    LootTable table = level.getServer().reloadableRegistries().getLootTable(TABLE);
    LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO))).create(LootContextParamSets.CHEST);
    Container failing = new SimpleContainer(27) {
      @Override
      public int getContainerSize() {
        throw new IllegalStateException("simulated failure while filling");
      }
    };

    boolean thrown = false;
    try {
      DefaultLootFiller.performFill(provider, player, TABLE, table, failing, params, 1L);
    } catch (IllegalStateException expected) {
      thrown = true;
    }
    boolean stuck = DefaultLootFiller.getFillerState() != null;
    DefaultLootFiller.setFillerState(null);
    helper.assertTrue(thrown, "The simulated failure did not happen");
    helper.assertTrue(!stuck, "The filler state stays set after a failed fill");

    LootFiller.LootFillerState state = new LootFiller.LootFillerState(provider, player, TABLE, table, new SimpleContainer(27), params, 1L);
    AtomicReference<LootFiller.LootFillerState> seenElsewhere = new AtomicReference<>();
    DefaultLootFiller.setFillerState(state);
    try {
      Thread other = new Thread(() -> seenElsewhere.set(DefaultLootFiller.getFillerState()));
      other.start();
      other.join();
      helper.assertTrue(DefaultLootFiller.getFillerState() == state, "The filler state is not readable on the thread that set it");
    } finally {
      DefaultLootFiller.setFillerState(null);
    }
    helper.assertTrue(seenElsewhere.get() == null, "Another thread observes the filler state of a running fill");
    helper.succeed();
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void lockMessageSuppressionIsReset(GameTestHelper helper) {
    ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
    LootrChestBlockEntity failing = new LootrChestBlockEntity(helper.absolutePos(BlockPos.ZERO), LootrRegistry.getChestBlock().defaultBlockState()) {
      @Override
      public boolean canOpen(Player opener) {
        throw new IllegalStateException("simulated failure of a lock check");
      }
    };
    boolean thrown = false;
    try {
      failing.canPlayerOpen(player);
    } catch (IllegalStateException expected) {
      thrown = true;
    }
    boolean stuck = LockMessageSuppression.isSuppressed();
    LockMessageSuppression.setSuppressableLock(false);
    helper.assertTrue(thrown, "The simulated failure did not happen");
    helper.assertTrue(!stuck, "Lock messages stay suppressed after a failed lock check");
    helper.succeed();
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void structureSavingDiscardsIdsOnlyWhileSaving(GameTestHelper helper) {
    BlockPos lootrPos = new BlockPos(1, 1, 1);
    BlockPos plainPos = new BlockPos(3, 1, 3);
    LootrTestSupport.placeLootContainer(helper, lootrPos, Blocks.CHEST, TABLE, 8L);
    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, lootrPos, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          ServerLevel level = helper.getLevel();
          HolderLookup.Provider registries = level.registryAccess();
          ILootrBlockEntity chest = LootrTestSupport.getLootrBlockEntity(helper, lootrPos);

          StructureTemplate template = new StructureTemplate();
          template.fillFromWorld(level, helper.absolutePos(lootrPos), new Vec3i(1, 1, 1), false, null);
          String saved = template.save(new CompoundTag()).toString();
          helper.assertTrue(saved.contains("LootTable") && !saved.contains(NBTConstants.INSTANCE_ID), "A structure must be saved without container ids: " + saved);
          helper.assertTrue(!LootrAPI.shouldDiscard(), "Ids are still discarded after the structure was saved");
          helper.assertTrue(chest.asBlockEntity().saveWithoutMetadata(registries).hasUUID(NBTConstants.INSTANCE_ID), "The container lost its id");

          // A block entity that cannot be saved: a stack count that the item codec rejects.
          helper.setBlock(plainPos, Blocks.CHEST);
          ItemStack invalid = new ItemStack(Items.STICK);
          ((ChestBlockEntity) helper.getBlockEntity(plainPos)).setItem(0, invalid);
          invalid.setCount(200);
          boolean thrown = false;
          boolean stuck;
          try {
            new StructureTemplate().fillFromWorld(level, helper.absolutePos(plainPos), new Vec3i(1, 1, 1), false, null);
          } catch (RuntimeException expected) {
            thrown = true;
          } finally {
            stuck = LootrAPI.shouldDiscard();
            LootrAPI.shouldDiscardIdAndOpeners = false;
            invalid.setCount(1);
            helper.setBlock(plainPos, Blocks.AIR);
          }
          helper.assertTrue(thrown, "The simulated failure did not happen");
          helper.assertTrue(!stuck, "Container ids stay discarded after a failed structure save: every container saved from then on would lose its id");

          // The switch concerns the saving thread only: a chunk that is saved elsewhere meanwhile keeps its ids.
          AtomicReference<Boolean> seenElsewhere = new AtomicReference<>();
          LootrAPI.setDiscardingIdAndOpeners(true);
          try {
            helper.assertTrue(LootrAPI.shouldDiscard(), "The switch is not seen on the thread that set it");
            Thread other = new Thread(() -> seenElsewhere.set(LootrAPI.shouldDiscard()));
            other.start();
            other.join();
          } catch (InterruptedException e) {
            throw new GameTestAssertException("Interrupted");
          } finally {
            LootrAPI.setDiscardingIdAndOpeners(false);
          }
          helper.assertTrue(Boolean.FALSE.equals(seenElsewhere.get()), "Another thread discards container ids while a structure is saved");
          helper.assertTrue(!LootrAPI.shouldDiscard(), "The switch was not reset");

          // The public field is still honoured.
          LootrAPI.shouldDiscardIdAndOpeners = true;
          try {
            helper.assertTrue(LootrAPI.shouldDiscard(), "`LootrAPI.shouldDiscardIdAndOpeners` is ignored");
          } finally {
            LootrAPI.shouldDiscardIdAndOpeners = false;
          }
        })
        .thenSucceed();
  }

  // The existing "close containers" mechanism: a vanilla container that gets a loot table while a player has it open.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void openVanillaContainerIsClosedWhenItBecomesLoot(GameTestHelper helper) {
    BlockPos pos = new BlockPos(1, 1, 1);
    helper.setBlock(pos, Blocks.CHEST);
    ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(pos);
    ServerPlayer player = playerAt(helper, pos);
    player.openMenu(chest);
    helper.assertTrue(player.containerMenu instanceof ChestMenu, "The mock player could not open the chest");
    chest.setLootTable(TABLE, 9L);
    helper.assertTrue(player.containerMenu == player.inventoryMenu, "The open vanilla container was not closed");
    helper.succeed();
  }

  // ----- 7. Ticking data: file names and format -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void tickingDataKeepsItsFormat(GameTestHelper helper) {
    MinecraftServer server = helper.getLevel().getServer();
    UUID id = UUID.randomUUID();
    String shard = id.toString().substring(0, 2);
    try {
      NewTickingData.getRefreshData().setCompletesIn(server, id, 1000);
      NewTickingData.getDecayData().setCompletesIn(server, id, 2000);
      helper.assertTrue(NewTickingData.getRefreshData().howLongUntilComplete(server, id) == 1000, "The refresh time was not stored");
      helper.assertTrue(NewTickingData.getDecayData().howLongUntilComplete(server, id) == 2000, "The decay time was not stored");

      SavedData section = storageCache().get("lootr/ticking/refresh/" + shard);
      helper.assertTrue(section != null && section.isDirty(), "The refresh time is not in the section lootr/ticking/refresh/" + shard);
      helper.assertTrue(storageCache().get("lootr/ticking/decay/" + shard) != null, "The decay time is not in the section lootr/ticking/decay/" + shard);
      CompoundTag saved = section.save(new CompoundTag(), helper.getLevel().registryAccess());
      helper.assertTrue(saved.getAllKeys().equals(Set.of("result", "cachedName")), "The section has the keys " + saved.getAllKeys());
      helper.assertTrue(saved.getString("cachedName").equals(shard), "The section is named " + saved.getString("cachedName"));
      boolean found = false;
      for (Tag entry : saved.getList("result", Tag.TAG_COMPOUND)) {
        CompoundTag compound = (CompoundTag) entry;
        helper.assertTrue(compound.getAllKeys().equals(Set.of("id", "value")) && compound.contains("value", Tag.TAG_LONG), "A section entry has the form " + compound);
        if (compound.getUUID("id").equals(id)) {
          found = compound.getLong("value") == server.getWorldData().overworldData().getGameTime() + 1000;
        }
      }
      helper.assertTrue(found, "The section does not hold the refresh time: " + saved);
    } finally {
      NewTickingData.getRefreshData().clearTicking(server, id);
      NewTickingData.getDecayData().clearTicking(server, id);
    }
    helper.succeed();
  }

  // ----- 8. A refresh while the container is open -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void refreshClosesOpenMenus(GameTestHelper helper) {
    BlockPos pos = new BlockPos(1, 1, 1);
    LootrTestSupport.placeLootContainer(helper, pos, Blocks.CHEST, TABLE, 10L);
    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, pos, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          MinecraftServer server = helper.getLevel().getServer();
          ILootrBlockEntity chest = LootrTestSupport.getLootrBlockEntity(helper, pos);
          ServerPlayer first = playerAt(helper, pos);
          ServerPlayer second = playerAt(helper, pos);
          UUID firstId = LootrAPI.resolveServerPlayerTeam(first);
          try {
            LootrAPI.handleProviderOpen(chest, first);
            ILootrInventory shown = openInventory(helper, first);
            LootrSavedData data = cached(chest.getInfoKey());
            helper.assertTrue(data.getInventory(firstId) == shown, "The open menu does not show the stored inventory");

            // The refresh is due; the second player opening the container performs it.
            NewTickingData.getRefreshData().setCompletesIn(server, chest.getInfoUUID(), 0);
            helper.assertTrue(LootrAPI.isRefreshed(chest), "The refresh is not due");
            LootrAPI.handleProviderOpen(chest, second);

            boolean stillOpen = first.containerMenu != first.inventoryMenu;
            boolean stored = data.getInventory(firstId) == shown;
            LootrAPI.LOG.info("[T07] item 8: refresh while a menu is open: menu of the first player still open = {}, the inventory it showed is still stored = {}", stillOpen, stored);
            helper.assertTrue(!stored, "The refresh did not discard the old inventory");
            helper.assertTrue(!stillOpen, "The first player still has a menu open that shows an inventory which is not stored any more");
            helper.assertTrue(openInventory(helper, second) == data.getInventory(LootrAPI.resolveServerPlayerTeam(second)), "The player who triggered the refresh does not see the stored inventory");

            LootrAPI.handleProviderOpen(chest, first);
            ILootrInventory fresh = openInventory(helper, first);
            helper.assertTrue(fresh != shown && fresh == data.getInventory(firstId), "Opening again after the refresh does not show the new stored inventory");
          } finally {
            first.closeContainer();
            second.closeContainer();
            NewTickingData.getRefreshData().clearTicking(server, chest.getInfoUUID());
          }
        })
        .thenSucceed();
  }

  // ----- 9. Creating an inventory for a player who is offline -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void offlinePlayerGetsNoInventory(GameTestHelper helper) {
    UUID id = UUID.randomUUID();
    ILootrInfoProvider provider = provider(helper, id, helper.absolutePos(BlockPos.ZERO), "Offline");
    UUID offline = UUID.randomUUID();
    try {
      LootrSavedData data = DataStorage.getData(provider);
      ILootrInventory inventory;
      try {
        inventory = data.createInventory(provider, offline, DefaultLootFiller.getInstance());
      } catch (NullPointerException e) {
        throw new GameTestAssertException("Creating an inventory for an offline player crashes: " + e);
      }
      helper.assertTrue(inventory == null, "An inventory was created for a player who is not online");
      helper.assertTrue(data.getInventory(offline) == null && !data.hasBeenOpened() && !data.isDirty(), "The failed creation left traces in the saved data");

      ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
      ILootrInventory online = data.createInventory(provider, player.getUUID(), DefaultLootFiller.getInstance());
      helper.assertTrue(online != null && !online.isEmpty() && data.getInventory(player.getUUID()) == online, "No inventory was created for a player who is online");
    } finally {
      storageCache().remove(ILootrInfo.generateInfoKey(id));
    }
    helper.succeed();
  }

  // ----- 11. `tickDelay` 0 -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void zeroTickDelayDoesNotCrash(GameTestHelper helper) {
    ILootrInfoProvider provider = provider(helper, UUID.randomUUID(), helper.absolutePos(BlockPos.ZERO), "Tick");
    helper.assertTrue(provider.getRandomOffset() == 0, "The test needs a provider without a random offset");
    ConfigManager config = ConfigManager.get();
    int tickDelay = config.conversion.tickDelay;
    config.conversion.tickDelay = 0;
    try {
      LootrAPI.handleProviderTick(provider);
    } catch (ArithmeticException e) {
      throw new GameTestAssertException("A tick delay of 0 crashes the container tick: " + e);
    } finally {
      config.conversion.tickDelay = tickDelay;
    }
    helper.succeed();
  }

  // ----- 12. The walk over all container files -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void containerFileWalkSkipsOtherData(GameTestHelper helper) {
    MinecraftServer server = helper.getLevel().getServer();
    UUID ticking = UUID.randomUUID();
    String section = "lootr/ticking/refresh/" + ticking.toString().substring(0, 2);
    UUID id = UUID.randomUUID();
    String key = ILootrInfo.generateInfoKey(id);
    ILootrInfoProvider provider = provider(helper, id, helper.absolutePos(BlockPos.ZERO), "Walk");
    ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
    try {
      // One container with an inventory, in memory and on disk, and one ticking section on disk.
      LootrSavedData data = DataStorage.getData(provider);
      helper.assertTrue(data.createInventory(provider, player, DefaultLootFiller.getInstance()) != null, "No inventory was created");
      NewTickingData.getRefreshData().setCompletesIn(server, ticking, 1000);
      storage().save();
      helper.assertTrue(Files.exists(dataFile(helper, section)) && Files.exists(dataFile(helper, key)), "The test files were not written");
      NewTickingData.getRefreshData().setCompletesIn(server, ticking, 2000);
      SavedData dirtySection = storageCache().get(section);

      Set<String> files = DataStorage.getAllLootrFiles();
      List<String> foreign = files.stream().filter(file -> !(storageCache().get(file) instanceof LootrSavedData) && !Files.exists(dataFile(helper, file))).toList();
      List<String> tickingFiles = files.stream().filter(file -> file.startsWith("lootr/ticking/")).toList();
      LootrAPI.LOG.info("[T07] item 12: container file walk: {} names, {} of them ticking sections, {} of them names that are neither loaded container data nor a file", files.size(), tickingFiles.size(), foreign.size());
      helper.assertTrue(files.contains(key), "The walk does not find the container data");
      helper.assertTrue(tickingFiles.isEmpty(), "The walk treats ticking sections as container data: " + tickingFiles);
      helper.assertTrue(foreign.isEmpty(), "The walk yields names that are no container data: " + foreign);

      // Clearing a player's loot must not throw unsaved ticking data away.
      storageCache().remove(key);
      helper.assertTrue(DataStorage.clearInventories(player.getUUID()), "The inventory of the player was not cleared");
      helper.assertTrue(storageCache().get(section) == dirtySection, "Clearing inventories dropped a ticking section with unsaved changes");
      helper.assertTrue(cached(key).getInventory(player.getUUID()) == null, "The inventory is still stored");
    } finally {
      NewTickingData.getRefreshData().clearTicking(server, ticking);
      storageCache().remove(key);
    }
    helper.succeed();
  }

  // ----- 13. Clearing a player's inventories while one of them is open -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void clearingInventoriesClosesOpenMenu(GameTestHelper helper) {
    BlockPos pos = new BlockPos(1, 1, 1);
    LootrTestSupport.placeLootContainer(helper, pos, Blocks.CHEST, TABLE, 20L);
    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, pos, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          ILootrBlockEntity chest = LootrTestSupport.getLootrBlockEntity(helper, pos);
          ServerPlayer cleared = playerAt(helper, pos);
          ServerPlayer other = playerAt(helper, pos);
          UUID clearedId = LootrAPI.resolveServerPlayerTeam(cleared);
          try {
            LootrAPI.handleProviderOpen(chest, cleared);
            LootrAPI.handleProviderOpen(chest, other);
            ILootrInventory shown = openInventory(helper, cleared);
            ILootrInventory otherShown = openInventory(helper, other);
            LootrSavedData data = cached(chest.getInfoKey());

            helper.assertTrue(DataStorage.clearInventories(clearedId), "The inventory of the player was not cleared");
            boolean stillOpen = cleared.containerMenu != cleared.inventoryMenu;
            boolean stored = data.getInventory(clearedId) != null;
            LootrAPI.LOG.info("[T08b] item a: inventories cleared while a menu is open: menu of the cleared player still open = {}, the inventory it showed is still stored = {}", stillOpen, stored);
            helper.assertTrue(!stored, "The inventory is still stored");
            helper.assertTrue(!stillOpen, "The cleared player still has a menu open that shows an inventory which is not stored any more");
            helper.assertTrue(openInventory(helper, other) == otherShown && data.getInventory(LootrAPI.resolveServerPlayerTeam(other)) == otherShown, "Clearing one player's inventory touched the open menu of another player");

            LootrAPI.handleProviderOpen(chest, cleared);
            ILootrInventory fresh = openInventory(helper, cleared);
            helper.assertTrue(fresh != shown && fresh == data.getInventory(clearedId), "Opening again after the clear does not show the new stored inventory");
          } finally {
            cleared.closeContainer();
            other.closeContainer();
          }
        })
        .thenSucceed();
  }

  // ----- 14. Closing containers from a thread that is not the server thread -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void closingContainersOffThreadIsDeferred(GameTestHelper helper) throws InterruptedException {
    BlockPos pos = new BlockPos(1, 1, 1);
    BlockPos removedPos = new BlockPos(4, 1, 4);
    helper.setBlock(pos, Blocks.CHEST);
    helper.setBlock(removedPos, Blocks.CHEST);
    ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(pos);
    ChestBlockEntity removed = (ChestBlockEntity) helper.getBlockEntity(removedPos);
    ServerPlayer player = playerAt(helper, pos);
    ServerPlayer second = playerAt(helper, removedPos);
    player.openMenu(chest);
    // A menu that vanilla does not close by itself once its block entity is gone: only Lootr could close it.
    second.openMenu(new SimpleMenuProvider((id, inventory, opener) -> new ChestMenu(MenuType.GENERIC_9x3, id, inventory, removed, 3) {
      @Override
      public boolean stillValid(Player viewer) {
        return true;
      }
    }, Component.empty()));
    helper.assertTrue(player.containerMenu instanceof ChestMenu && second.containerMenu instanceof ChestMenu, "The mock players could not open the chests");

    // As a world generation thread does when it gives a loot table to a container of a chunk that is already loaded.
    Thread other = new Thread(() -> {
      LootrAPI.closeContainers(removed);
      LootrAPI.closeContainers(chest);
    });
    other.start();
    other.join();
    // The server thread was blocked meanwhile: whatever is closed by now was closed on the other thread.
    boolean closedOffThread = player.containerMenu == player.inventoryMenu || second.containerMenu == second.inventoryMenu;
    LootrAPI.LOG.info("[T08b] item b: closeContainers called from another thread: players and their menus were touched on that thread = {}", closedOffThread);
    helper.assertTrue(!closedOffThread, "A menu was closed on a thread that is not the server thread");
    removed.setRemoved();

    helper.startSequence()
        .thenWaitUntil(() -> helper.assertTrue(player.containerMenu == player.inventoryMenu, "Waiting for the open container to be closed on the server thread"))
        .thenExecute(() -> {
          // Both requests were queued in order, so the one for the removed block entity has been handled as well.
          helper.assertTrue(second.containerMenu != second.inventoryMenu, "The deferred close acted on a block entity that was removed in the meantime");
          second.closeContainer();
        })
        .thenSucceed();
  }

  // ----- 15. Containers that nobody opened have no data file -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void unopenedContainersGetNoDataFile(GameTestHelper helper) {
    List<BlockPos> positions = grid(20);
    placeLootChests(helper, positions, 300L);

    helper.startSequence()
        .thenWaitUntil(() -> assertConverted(helper, positions))
        .thenExecute(() -> {
          List<ILootrBlockEntity> chests = lootrChests(helper, positions);
          List<String> keys = chests.stream().map(ILootrInfo::getInfoKey).toList();

          storage().save();
          long objects = countCached(keys);
          long files = countFiles(helper, keys);
          LootrAPI.LOG.info("[T08b] item c: 20 converted chests, one world save: saved data objects = {}, data files = {}", objects, files);
          helper.assertTrue(objects == 0 && files == 0, "Converting created " + objects + " saved data objects and " + files + " data files");

          // What a landed falling container, the initial save of other platforms and addons do.
          chests.forEach(ILootrBlockEntity::performUpdate);
          objects = countCached(keys);
          long dirty = countDirty(keys);
          storage().save();
          files = countFiles(helper, keys);
          LootrAPI.LOG.info("[T08b] item c: `performUpdate` on 20 unopened chests, one world save: saved data objects = {}, of them marked as changed = {}, data files = {}", objects, dirty, files);
          helper.assertTrue(objects == 0, "`performUpdate` created saved data for " + objects + " containers that were never opened");
          helper.assertTrue(files == 0, files + " data files were written for containers that were never opened");

          // Asking who opened a container creates an empty data object; marked as changed it is still no file.
          ServerPlayer player = playerAt(helper, positions.getFirst());
          UUID playerId = LootrAPI.resolveServerPlayerTeam(player);
          for (ILootrBlockEntity chest : chests) {
            helper.assertTrue(!chest.hasVisualOpened(player), "A container that was never opened reports an opener");
            chest.performUpdate();
          }
          objects = countCached(keys);
          dirty = countDirty(keys);
          storage().save();
          files = countFiles(helper, keys);
          LootrAPI.LOG.info("[T08b] item c: opener query and `performUpdate` on 20 unopened chests, one world save: saved data objects = {}, of them marked as changed = {}, data files = {}", objects, dirty, files);
          helper.assertTrue(files == 0, files + " data files were written for containers that were never opened");
          helper.assertTrue(countDirty(keys) == 0, "Data that was not written stays marked as changed");

          // An opened container is saved, and changes to its data keep being saved when it is only on disk.
          ILootrBlockEntity chest = chests.getFirst();
          String key = keys.getFirst();
          LootrAPI.handleProviderOpen(chest, player);
          player.closeContainer();
          simulateRestart(List.of(key));
          helper.assertTrue(countFiles(helper, keys) == 1 && Files.exists(dataFile(helper, key)), "The opened container has no data file");
          chest.performUpdate();
          LootrSavedData reloaded = DataStorage.getExistingData(key);
          helper.assertTrue(reloaded != null && reloaded.isDirty(), "Data that is only on disk is not marked as changed");
          helper.assertTrue(reloaded.getVisualOpeners().contains(playerId) && reloaded.getActualOpeners().contains(playerId) && reloaded.getInventory(playerId) != null, "The data of the opened container was not read back");

          // Sneak-clicking marks the container as unopened for the player; that is an opener change made through the container.
          LootrAPI.handleProviderSneak(chest, player);
          simulateRestart(List.of(key));
          reloaded = DataStorage.getExistingData(key);
          helper.assertTrue(reloaded != null && !reloaded.getVisualOpeners().contains(playerId), "A removed opener came back after a restart");
          helper.assertTrue(reloaded.getActualOpeners().contains(playerId) && reloaded.getInventory(playerId) != null && reloaded.hasBeenOpened(), "The data of the opened container lost state");
        })
        .thenSucceed();
  }

  // ----- 16. Culling keeps data that names openers -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void dataWithOpenersIsNotCulled(GameTestHelper helper) {
    UUID id = UUID.randomUUID();
    ILootrInfoProvider provider = provider(helper, id, helper.absolutePos(BlockPos.ZERO), "Cull");
    ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
    try {
      LootrSavedData data = DataStorage.getData(provider);
      helper.assertTrue(data.canBeCulled(), "Data without any content cannot be culled");

      data.addVisualOpener(player);
      boolean withVisual = data.canBeCulled();
      data.removeVisualOpener(player);
      helper.assertTrue(data.canBeCulled(), "Data without any content cannot be culled");
      data.addActualOpener(player);
      boolean withActual = data.canBeCulled();

      // A refresh, as `ILootrInfoProvider.performRefresh` does it: the actual openers stay.
      helper.assertTrue(data.createInventory(provider, player, DefaultLootFiller.getInstance()) != null && data.addVisualOpener(player), "No inventory was created");
      helper.assertTrue(!data.canBeCulled(), "Data with an inventory can be culled");
      data.refresh();
      data.clearOpeners();
      boolean refreshed = data.canBeCulled();
      LootrAPI.LOG.info("[T08b] item d: data without inventories can be culled: with a visual opener = {}, with an actual opener = {}, after a refresh that keeps its actual opener = {}", withVisual, withActual, refreshed);
      helper.assertTrue(!withVisual, "Culling would forget that a player sees the container as opened");
      helper.assertTrue(!withActual, "Culling would forget that a player was counted for the container");
      helper.assertTrue(data.getInventory(player.getUUID()) == null && data.getActualOpeners().contains(player.getUUID()), "The refresh did not leave the expected data");
      helper.assertTrue(!refreshed, "Culling would forget who was counted for a refreshed container");
    } finally {
      storageCache().remove(ILootrInfo.generateInfoKey(id));
    }
    helper.succeed();
  }

  // ----- 17. An opening that fails leaves no traces -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void failedOpenLeavesNoTraces(GameTestHelper helper) {
    BlockPos pos = new BlockPos(1, 1, 1);
    LootrTestSupport.placeLootContainer(helper, pos, Blocks.CHEST, TABLE, 30L);
    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, pos, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          MinecraftServer server = helper.getLevel().getServer();
          ILootrBlockEntity chest = LootrTestSupport.getLootrBlockEntity(helper, pos);
          AccessorMixinBaseContainerBlockEntity lock = (AccessorMixinBaseContainerBlockEntity) chest.asBlockEntity();
          ServerPlayer player = playerAt(helper, pos);
          UUID playerId = LootrAPI.resolveServerPlayerTeam(player);
          int looted = player.getStats().getValue(LootrRegistry.getLootedStat());
          ConfigManager config = ConfigManager.get();
          config.decay.decay_all = true;
          config.refresh.refresh_all = true;
          try {
            lock.setLockKey(new LockCode("gametest"));
            LootrAPI.handleProviderOpen(chest, player);
            String locked = openingTraces(chest, player, looted);
            lock.setLockKey(LockCode.NO_LOCK);

            // A menu that is refused: the builder yields none.
            LootrAPI.INSTANCE.handleProviderOpen(chest, player, (id, inventory, container, rows) -> null, false);
            String refused = openingTraces(chest, player, looted);
            LootrAPI.LOG.info("[T08b] item e: traces of an opening that failed: locked container = [{}], menu refused = [{}]", locked, refused);
            helper.assertTrue(locked.isEmpty(), "Trying to open a locked container left traces: " + locked);
            helper.assertTrue(refused.isEmpty(), "An opening whose menu was refused left traces: " + refused);

            // The successful opening records all of it.
            LootrAPI.INSTANCE.handleProviderOpen(chest, player, (id, inventory, container, rows) -> ChestMenu.threeRows(id, inventory, container), false);
            LootrSavedData data = cached(chest.getInfoKey());
            helper.assertTrue(openInventory(helper, player) == data.getInventory(playerId), "The menu does not show the stored inventory");
            helper.assertTrue(data.getVisualOpeners().contains(playerId) && data.getActualOpeners().contains(playerId), "The opening was not recorded");
            helper.assertTrue(player.getStats().getValue(LootrRegistry.getLootedStat()) == looted + 1, "The opening was not counted exactly once");
            helper.assertTrue(DataStorage.getDecayValue(chest) == LootrAPI.getDecayValue() && DataStorage.getRefreshValue(chest) == LootrAPI.getRefreshValue(), "Decay and refresh were not started");
            helper.assertTrue(chest.hasBeenOpened() && chest.isClientDecaying() && chest.isClientRefreshing(), "The container does not know that it was opened");
            helper.assertTrue(openers(chest, helper.getLevel().registryAccess()).contains(playerId), "The update tag does not name the opener");

            // Opening again counts nothing twice.
            player.closeContainer();
            LootrAPI.handleProviderOpen(chest, player);
            helper.assertTrue(player.getStats().getValue(LootrRegistry.getLootedStat()) == looted + 1, "Opening again was counted");
          } finally {
            config.decay.decay_all = false;
            config.refresh.refresh_all = false;
            lock.setLockKey(LockCode.NO_LOCK);
            player.closeContainer();
            NewTickingData.getDecayData().clearTicking(server, chest.getInfoUUID());
            NewTickingData.getRefreshData().clearTicking(server, chest.getInfoUUID());
          }
        })
        .thenSucceed();
  }

  // ----- 18. Decay and refresh checks for a container without a server level -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void decayAndRefreshChecksTolerateMissingLevel(GameTestHelper helper) {
    UUID id = UUID.randomUUID();
    ResourceKey<Level> missing = ResourceKey.create(Registries.DIMENSION, LootrAPI.rl("gametest_no_such_dimension"));
    ILootrInfoProvider provider = new CustomLootrInfoProvider(id, ILootrInfo.generateInfoKey(id), helper.absolutePos(BlockPos.ZERO), 27, TABLE, 1L, Component.literal("Nowhere"), missing, null, null, null, BuiltInLootrTypes.CHEST);
    helper.assertTrue(provider.getInfoLevel() == null, "The test needs a container without a level");
    helper.assertTrue(!ConfigManager.get().decay.decay_all && !ConfigManager.get().refresh.refresh_all, "The test needs the default configuration");
    boolean decaying;
    boolean refreshing;
    try {
      decaying = LootrAPI.isDecaying(provider);
      refreshing = LootrAPI.isRefreshing(provider);
    } catch (NullPointerException | ClassCastException e) {
      throw new GameTestAssertException("Asking whether a container without a server level decays or refreshes crashes: " + e);
    }
    helper.assertTrue(!decaying && !refreshing, "A container without a level counts as decaying or refreshing");
    helper.succeed();
  }

  // ----- 19. Entities that were never opened need no saved data to be synchronised -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void unopenedEntitiesNeedNoSavedDataForSync(GameTestHelper helper) {
    BlockPos center = new BlockPos(3, 2, 3);
    ServerPlayer player = playerAt(helper, center);
    List<LootrChestMinecartEntity> carts = new ArrayList<>();
    // The sync data covers the view distance, which the test server leaves at 0. Chunk loading clamps it to at
    // least 2, so setting 2 here (and the old value afterwards) changes nothing for the chunks.
    PlayerList players = helper.getLevel().getServer().getPlayerList();
    int viewDistance = players.getViewDistance();
    players.setViewDistance(Math.max(viewDistance, 2));
    try {
      for (int i = 0; i < 10; i++) {
        LootrChestMinecartEntity cart = (LootrChestMinecartEntity) helper.spawn(LootrRegistry.getMinecart(), new BlockPos(1 + i % 5, 2, 2 + i / 5));
        cart.setLootTableInternal(TABLE, 400L + i);
        carts.add(cart);
      }
      List<String> keys = carts.stream().map(ILootrInfo::getInfoKey).toList();

      // What tracking a container entity does for every player who starts to see it, three times over.
      for (int round = 0; round < 3; round++) {
        carts.forEach(cart -> cart.startSeenByPlayer(player));
      }
      long objects = countCached(keys);
      long lookups = countLookedUp(keys);
      LootrAPI.LOG.info("[T08b] item g: 10 unopened loot minecarts, tracking started 3 times each: saved data objects = {}, file lookups = {}", objects, lookups);
      helper.assertTrue(objects == 0, "Starting to track created saved data for " + objects + " entities that were never opened");
      helper.assertTrue(lookups == 10, "Expected one file lookup per entity but there were " + lookups);

      // What a team change does for every player of the team.
      Pair<List<Integer>, List<Integer>> sync = syncData(player);
      objects = countCached(keys);
      storage().save();
      long files = countFiles(helper, keys);
      LootrAPI.LOG.info("[T08b] item g: 10 unopened loot minecarts, sync data after a team change built once: saved data objects = {}, data files after a world save = {}", objects, files);
      helper.assertTrue(objects == 0, "Building the sync data created saved data for " + objects + " entities that were never opened");
      helper.assertTrue(files == 0, files + " data files were written for entities that were never opened");
      helper.assertTrue(carts.stream().allMatch(cart -> sync.getSecond().contains(cart.getId())) && carts.stream().noneMatch(cart -> sync.getFirst().contains(cart.getId())), "Unopened entities are not reported as closed");

      // An opened entity is still reported as opened: at once, and after a restart.
      LootrChestMinecartEntity opened = carts.getFirst();
      LootrAPI.handleProviderOpen(opened, player);
      player.closeContainer();
      helper.assertTrue(syncData(player).getFirst().contains(opened.getId()), "An opened entity is not reported as opened");
      simulateRestart(List.of(keys.getFirst()));
      Pair<List<Integer>, List<Integer>> afterRestart = syncData(player);
      helper.assertTrue(afterRestart.getFirst().contains(opened.getId()) && afterRestart.getSecond().contains(carts.get(1).getId()), "The sync data is wrong after a restart");
    } finally {
      players.setViewDistance(viewDistance);
      player.closeContainer();
      carts.forEach(Entity::discard);
    }
    helper.succeed();
  }

  // ----- On-disk format -----

  @SuppressWarnings("deprecation")
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void savedDataKeepsItsFormat(GameTestHelper helper) {
    BlockPos pos = new BlockPos(1, 1, 1);
    LootrTestSupport.placeLootContainer(helper, pos, Blocks.CHEST, TABLE, 11L);
    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, pos, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          HolderLookup.Provider registries = helper.getLevel().registryAccess();
          ILootrBlockEntity chest = LootrTestSupport.getLootrBlockEntity(helper, pos);
          ServerPlayer first = LootrTestSupport.createRealPlayer(helper);
          ServerPlayer second = LootrTestSupport.createRealPlayer(helper);
          LootrSavedData data = DataStorage.getData(chest);
          ILootrInventory firstInventory = data.createInventory(chest, first, chest.getDefaultFiller());
          ILootrInventory secondInventory = data.createInventory(chest, second, chest.getDefaultFiller());
          helper.assertTrue(firstInventory != null && secondInventory != null && !firstInventory.isEmpty() && !secondInventory.isEmpty(), "The inventories were not created");
          data.addOpener(first);
          data.addActualOpener(second);

          CompoundTag saved = data.save(new CompoundTag(), registries);
          assertSavedForm(helper, saved, chest, List.of(first.getUUID(), second.getUUID()));
          helper.assertTrue(uuids(saved, "openers").equals(Set.of(first.getUUID())), "Saved openers: " + saved.get("openers"));
          helper.assertTrue(uuids(saved, "actualOpeners").equals(Set.of(first.getUUID(), second.getUUID())), "Saved actual openers: " + saved.get("actualOpeners"));

          LootrSavedData loaded = LootrSavedData.load(saved, registries);
          helper.assertTrue(loaded.hasBeenOpened() && loaded.getVisualOpeners().equals(data.getVisualOpeners()) && loaded.getActualOpeners().equals(data.getActualOpeners()), "The openers changed in save and load");
          helper.assertTrue(ItemStack.listMatches(snapshot(firstInventory), snapshot(loaded.getInventory(first.getUUID()))), "The first inventory changed in save and load");
          helper.assertTrue(ItemStack.listMatches(snapshot(secondInventory), snapshot(loaded.getInventory(second.getUUID()))), "The second inventory changed in save and load");
          helper.assertTrue(loaded.getInfoUUID().equals(chest.getInfoUUID()) && loaded.getInfoPos().equals(chest.getInfoPos()) && loaded.getInfoDimension().equals(chest.getInfoDimension()) && loaded.getInfoContainerSize() == 27, "The container info changed in save and load");
          helper.assertTrue(loaded.getInfoNewType() == BuiltInLootrTypes.CHEST && TABLE.equals(loaded.getInfoLootTable()) && loaded.getInfoLootSeed() == 11L && loaded.getInfoKey().equals(chest.getInfoKey()), "The container info changed in save and load");

          // The same file after a restart, read through the storage and written again.
          simulateRestart(List.of(chest.getInfoKey()));
          LootrSavedData reloaded = DataStorage.getData(chest);
          helper.assertTrue(reloaded != data && !reloaded.isDirty(), "Loading the data and asking for it marked it as changed");
          CompoundTag rewritten = reloaded.save(new CompoundTag(), registries);
          assertSavedForm(helper, rewritten, chest, List.of(first.getUUID(), second.getUUID()));
          helper.assertTrue(rewritten.getAllKeys().equals(saved.getAllKeys()), "The keys changed from " + saved.getAllKeys() + " to " + rewritten.getAllKeys());
        })
        .thenSucceed();
  }

  @SuppressWarnings("deprecation")
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void legacySavedDataIsAccepted(GameTestHelper helper) {
    HolderLookup.Provider registries = helper.getLevel().registryAccess();
    UUID id = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));

    CompoundTag legacy = new CompoundTag();
    legacy.putInt("type", ILootrInfo.LootrInfoType.CONTAINER_BLOCK_ENTITY.ordinal());
    legacy.putInt("blockType", LootrBlockType.BARREL.ordinal());
    legacy.put("position", NbtUtils.writeBlockPos(pos));
    legacy.putString("key", ILootrInfo.generateInfoKey(id));
    legacy.putString("dimension", helper.getLevel().dimension().location().toString());
    legacy.putUUID("uuid", id);
    legacy.putInt("size", 27);
    legacy.putString("table", TABLE.location().toString());
    legacy.putLong("seed", 12L);
    CompoundTag stick = new CompoundTag();
    stick.putByte("Slot", (byte) 4);
    stick.putString("id", "minecraft:stick");
    stick.putInt("count", 3);
    ListTag items = new ListTag();
    items.add(stick);
    CompoundTag chest = new CompoundTag();
    chest.put("Items", items);
    CompoundTag inventory = new CompoundTag();
    inventory.putUUID("uuid", owner);
    inventory.put("chest", chest);
    ListTag inventories = new ListTag();
    inventories.add(inventory);
    legacy.put("inventories", inventories);
    ListTag openers = new ListTag();
    openers.add(NbtUtils.createUUID(owner));
    legacy.put("openers", openers);

    LootrSavedData loaded = LootrSavedData.load(legacy, registries);
    helper.assertTrue(loaded.getInfoNewType() == BuiltInLootrTypes.BARREL, "The legacy block type was read as " + loaded.getInfoNewType());
    helper.assertTrue(loaded.getInfoUUID().equals(id) && loaded.getInfoPos().equals(pos) && loaded.getInfoContainerSize() == 27 && TABLE.equals(loaded.getInfoLootTable()) && loaded.getInfoLootSeed() == 12L, "The legacy info was not read completely");
    helper.assertTrue(loaded.getInventory(owner) != null && loaded.getInventory(owner).getItem(4).is(Items.STICK) && loaded.getInventory(owner).getItem(4).getCount() == 3, "The legacy inventory was not read");
    helper.assertTrue(loaded.getVisualOpeners().equals(Set.of(owner)) && loaded.getActualOpeners().isEmpty() && !loaded.hasBeenOpened(), "The legacy openers were not read");

    // The oldest form knows only `type`: a chest.
    legacy.remove("blockType");
    helper.assertTrue(LootrSavedData.load(legacy, registries).getInfoNewType() == BuiltInLootrTypes.CHEST, "Data with only the legacy `type` is not read as a chest");

    // What was read is written in the current form and can be read again.
    CompoundTag rewritten = loaded.save(new CompoundTag(), registries);
    helper.assertTrue(rewritten.getString("newType").equals(BuiltInLootrTypes.TYPE_BARREL), "The legacy data is written with the type " + rewritten.getString("newType"));
    helper.assertTrue(LootrSavedData.load(rewritten, registries).getInventory(owner).getItem(4).getCount() == 3, "The rewritten legacy data cannot be read");
    helper.succeed();
  }

  // ----- helpers -----

  @SuppressWarnings("deprecation")
  private static void assertSavedForm(GameTestHelper helper, CompoundTag saved, ILootrBlockEntity chest, List<UUID> owners) {
    Set<String> expected = Set.of("type", "newType", "blockType", "position", "key", "dimension", "uuid", "size", "table", "seed", "name", "inventories", "openers", "actualOpeners", "hasBeenOpened");
    helper.assertTrue(saved.getAllKeys().equals(expected), "Saved data has the keys " + saved.getAllKeys());
    helper.assertTrue(saved.contains("type", Tag.TAG_INT) && saved.getInt("type") == ILootrInfo.LootrInfoType.CONTAINER_BLOCK_ENTITY.ordinal(), "Saved `type`: " + saved.get("type"));
    helper.assertTrue(saved.contains("blockType", Tag.TAG_INT) && saved.getInt("blockType") == LootrBlockType.CHEST.ordinal(), "Saved `blockType`: " + saved.get("blockType"));
    helper.assertTrue(saved.contains("newType", Tag.TAG_STRING) && saved.getString("newType").equals(BuiltInLootrTypes.TYPE_CHEST), "Saved `newType`: " + saved.get("newType"));
    helper.assertTrue(NbtUtils.readBlockPos(saved, "position").filter(chest.getInfoPos()::equals).isPresent(), "Saved `position`: " + saved.get("position"));
    helper.assertTrue(saved.contains("key", Tag.TAG_STRING) && saved.getString("key").equals(chest.getInfoKey()), "Saved `key`: " + saved.get("key"));
    helper.assertTrue(saved.contains("dimension", Tag.TAG_STRING) && saved.getString("dimension").equals(chest.getInfoDimension().location().toString()), "Saved `dimension`: " + saved.get("dimension"));
    helper.assertTrue(saved.hasUUID("uuid") && saved.getUUID("uuid").equals(chest.getInfoUUID()), "Saved `uuid`: " + saved.get("uuid"));
    helper.assertTrue(saved.contains("size", Tag.TAG_INT) && saved.getInt("size") == 27, "Saved `size`: " + saved.get("size"));
    helper.assertTrue(saved.contains("table", Tag.TAG_STRING) && saved.getString("table").equals(TABLE.location().toString()), "Saved `table`: " + saved.get("table"));
    helper.assertTrue(saved.contains("seed", Tag.TAG_LONG) && saved.getLong("seed") == 11L, "Saved `seed`: " + saved.get("seed"));
    helper.assertTrue(saved.contains("name", Tag.TAG_STRING), "Saved `name`: " + saved.get("name"));
    helper.assertTrue(saved.contains("hasBeenOpened", Tag.TAG_BYTE) && saved.getBoolean("hasBeenOpened"), "Saved `hasBeenOpened`: " + saved.get("hasBeenOpened"));
    helper.assertTrue(saved.contains("openers", Tag.TAG_LIST) && saved.contains("actualOpeners", Tag.TAG_LIST), "The opener lists are missing");

    ListTag inventories = saved.getList("inventories", Tag.TAG_COMPOUND);
    Set<UUID> savedOwners = new HashSet<>();
    for (Tag entry : inventories) {
      CompoundTag inventory = (CompoundTag) entry;
      helper.assertTrue(inventory.getAllKeys().equals(Set.of("uuid", "chest")) && inventory.hasUUID("uuid") && inventory.contains("chest", Tag.TAG_COMPOUND), "A saved inventory has the form " + inventory.getAllKeys());
      helper.assertTrue(!inventory.getCompound("chest").getList("Items", Tag.TAG_COMPOUND).isEmpty(), "A saved inventory holds no items: " + inventory);
      savedOwners.add(inventory.getUUID("uuid"));
    }
    helper.assertTrue(inventories.size() == owners.size() && savedOwners.equals(new HashSet<>(owners)), "Saved inventories belong to " + savedOwners);
  }

  // What an opening leaves behind for the player, as text; empty when there is nothing.
  private static String openingTraces(ILootrBlockEntity chest, ServerPlayer player, int lootedBefore) {
    List<String> traces = new ArrayList<>();
    LootrSavedData data = DataStorage.getExistingData(chest.getInfoKey());
    if (data != null && data.hasVisualOpened(player)) {
      traces.add("visual opener");
    }
    if (data != null && data.hasServerOpened(player)) {
      traces.add("actual opener");
    }
    if (player.getStats().getValue(LootrRegistry.getLootedStat()) != lootedBefore) {
      traces.add("looted statistic");
    }
    if (DataStorage.getDecayValue(chest) != -1) {
      traces.add("decay started");
    }
    if (DataStorage.getRefreshValue(chest) != -1) {
      traces.add("refresh started");
    }
    if (player.containerMenu != player.inventoryMenu) {
      traces.add("open menu");
    }
    return String.join(", ", traces);
  }

  // The sync data that is sent after a team change (ids of opened and of closed entities); a protected method builds it.
  private static Pair<List<Integer>, List<Integer>> syncData(ServerPlayer player) {
    return new PlatformAPIImpl() {
      Pair<List<Integer>, List<Integer>> build() {
        return getSyncData(player);
      }
    }.build();
  }

  private static Set<UUID> uuids(CompoundTag tag, String name) {
    Set<UUID> result = new HashSet<>();
    for (Tag entry : tag.getList(name, Tag.TAG_INT_ARRAY)) {
      result.add(NbtUtils.loadUUID(entry));
    }
    return result;
  }

  private static ILootrInfoProvider provider(GameTestHelper helper, UUID id, BlockPos pos, String name) {
    return new CustomLootrInfoProvider(id, ILootrInfo.generateInfoKey(id), pos, 27, TABLE, 1L, Component.literal(name), helper.getLevel().dimension(), null, null, null, BuiltInLootrTypes.CHEST);
  }

  private static List<BlockPos> grid(int count) {
    List<BlockPos> result = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      result.add(new BlockPos(i % 8, 1, i / 8));
    }
    return result;
  }

  private static void placeLootChests(GameTestHelper helper, List<BlockPos> positions, long firstSeed) {
    for (int i = 0; i < positions.size(); i++) {
      LootrTestSupport.placeLootContainer(helper, positions.get(i), Blocks.CHEST, TABLE, firstSeed + i);
    }
  }

  private static void assertConverted(GameTestHelper helper, List<BlockPos> positions) {
    for (BlockPos pos : positions) {
      LootrTestSupport.assertBlockIs(helper, pos, LootrRegistry.getChestBlock());
    }
  }

  private static List<ILootrBlockEntity> lootrChests(GameTestHelper helper, List<BlockPos> positions) {
    return positions.stream().map(pos -> LootrTestSupport.getLootrBlockEntity(helper, pos)).toList();
  }

  private static ServerPlayer playerAt(GameTestHelper helper, BlockPos relativePos) {
    ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
    BlockPos pos = helper.absolutePos(relativePos);
    player.moveTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    return player;
  }

  private static ILootrInventory openInventory(GameTestHelper helper, ServerPlayer player) {
    if (player.containerMenu instanceof ChestMenu menu && menu.getContainer() instanceof ILootrInventory inventory) {
      return inventory;
    }
    throw new GameTestAssertException("The player has no Lootr inventory open but " + player.containerMenu);
  }

  private static NonNullList<ItemStack> snapshot(Container container) {
    NonNullList<ItemStack> result = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
    for (int i = 0; i < result.size(); i++) {
      result.set(i, container.getItem(i).copy());
    }
    return result;
  }

  // A block entity as it is after its chunk was saved and loaded again; `change` edits the saved NBT in between.
  private static ILootrBlockEntity reload(GameTestHelper helper, ILootrBlockEntity chest, Consumer<CompoundTag> change) {
    HolderLookup.Provider registries = helper.getLevel().registryAccess();
    CompoundTag saved = chest.asBlockEntity().saveWithoutMetadata(registries);
    change.accept(saved);
    LootrChestBlockEntity loaded = new LootrChestBlockEntity(chest.getInfoPos(), chest.asBlockEntity().getBlockState());
    loaded.setLevel(helper.getLevel());
    loaded.loadWithComponents(saved, registries);
    return loaded;
  }

  private static Set<UUID> openers(ILootrBlockEntity chest, HolderLookup.Provider registries) {
    return uuids(chest.asBlockEntity().getUpdateTag(registries), NBTConstants.OPENERS);
  }

  // Requests the update tag of every container (as sending its chunk does) and saves the world, `rounds` times.
  private static int requestUpdateTagsAndCountRewrites(List<ILootrBlockEntity> chests, List<String> keys, HolderLookup.Provider registries, int rounds) {
    int rewrites = 0;
    for (int round = 0; round < rounds; round++) {
      for (ILootrBlockEntity chest : chests) {
        chest.asBlockEntity().getUpdateTag(registries);
      }
      rewrites += (int) countDirty(keys);
      storage().save();
    }
    return rewrites;
  }

  private static DimensionDataStorage storage() {
    DimensionDataStorage storage = DataStorage.getDataStorage();
    if (storage == null) {
      throw new GameTestAssertException("The data storage is not available");
    }
    return storage;
  }

  private static Map<String, SavedData> storageCache() {
    return ((AccessorMixinDimensionDataStorage) storage()).getCache();
  }

  private static LootrSavedData cached(String key) {
    if (storageCache().get(key) instanceof LootrSavedData data) {
      return data;
    }
    throw new GameTestAssertException("No saved data is loaded for " + key);
  }

  private static long countCached(Collection<String> keys) {
    return keys.stream().filter(key -> storageCache().get(key) != null).count();
  }

  // The storage remembers every name it was asked for, also when there is no file: one file lookup each.
  private static long countLookedUp(Collection<String> keys) {
    return keys.stream().filter(storageCache()::containsKey).count();
  }

  private static long countDirty(Collection<String> keys) {
    return keys.stream().map(storageCache()::get).filter(data -> data != null && data.isDirty()).count();
  }

  private static Path dataFile(GameTestHelper helper, String name) {
    return helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(name + ".dat");
  }

  private static long countFiles(GameTestHelper helper, Collection<String> keys) {
    return keys.stream().filter(key -> Files.exists(dataFile(helper, key))).count();
  }

  // What a server restart does to the data storage: everything is written, nothing stays in memory.
  private static void simulateRestart(Collection<String> keys) {
    storage().save();
    keys.forEach(storageCache()::remove);
  }

  // Chunks are generated and loaded on other threads in their own time, while the test server runs its ticks
  // without a pause in between: under load a limit in ticks passes within a second, long before the chunks
  // are there. So chunks are waited for against the clock, the way a synchronous chunk load does it: the
  // server thread works off the chunk tasks until the condition holds. No tick passes meanwhile, which
  // leaves the tick limit of the test to what is measured in ticks.
  private static void awaitChunks(GameTestHelper helper, BooleanSupplier condition, String failure) {
    ServerChunkCache chunks = helper.getLevel().getChunkSource();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CHUNK_WAIT_SECONDS);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      if (!chunks.pollTask()) {
        LockSupport.parkNanos(100_000L);
      }
    }
    helper.assertTrue(condition.getAsBoolean(), failure + " within " + CHUNK_WAIT_SECONDS + " seconds");
  }

  private static boolean surroundingsLoaded(ServerLevel level, ChunkPos center) {
    Set<ChunkPos> loaded = LoadedChunks.getLoadedChunks(level.dimension());
    for (int x = center.x - 2; x <= center.x + 2; x++) {
      for (int z = center.z - 2; z <= center.z + 2; z++) {
        if (!loaded.contains(new ChunkPos(x, z))) {
          return false;
        }
      }
    }
    return true;
  }

  // The conversion queue is private; the tests read it to measure it.
  private static List<Map<?, ?>> queues(ServerLevel level) {
    try {
      Field tickers = BlockEntityTicker.class.getDeclaredField("TICKERS");
      tickers.setAccessible(true);
      Object ticker = ((Map<?, ?>) tickers.get(null)).get(level.dimension());
      if (ticker == null) {
        return List.of();
      }
      List<Map<?, ?>> result = new ArrayList<>();
      for (String name : List.of("blockEntityEntries", "pendingEntries")) {
        Field field = BlockEntityTicker.class.getDeclaredField(name);
        field.setAccessible(true);
        result.add((Map<?, ?>) field.get(ticker));
      }
      return result;
    } catch (ReflectiveOperationException e) {
      throw new GameTestAssertException("The conversion queue cannot be read: " + e);
    }
  }

  private static boolean isQueued(ServerLevel level, ChunkPos pos) {
    return queues(level).stream().anyMatch(queue -> queue.containsKey(pos));
  }

  private static int queueSize(ServerLevel level) {
    return queues(level).stream().mapToInt(Map::size).sum();
  }
}
