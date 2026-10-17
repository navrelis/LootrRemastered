package noobanidus.mods.lootr.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.NewTickingData;
import noobanidus.mods.lootr.common.api.data.blockentity.ILootrBlockEntity;
import noobanidus.mods.lootr.common.api.data.inventory.ILootrInventory;
import noobanidus.mods.lootr.common.api.registry.LootrRegistry;
import noobanidus.mods.lootr.common.cycle.CycleRecord;
import noobanidus.mods.lootr.common.cycle.CycleSettings;
import noobanidus.mods.lootr.common.cycle.PersonalCycle;
import noobanidus.mods.lootr.common.cycle.WallClock;
import noobanidus.mods.lootr.common.data.DataStorage;
import noobanidus.mods.lootr.common.data.LootrSavedData;
import noobanidus.mods.lootr.common.mixin.accessor.AccessorMixinDimensionDataStorage;
import noobanidus.mods.lootr.gametest.platform.TestPlatform;
import noobanidus.mods.lootr.gametest.platform.TestPlatforms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static noobanidus.mods.lootr.gametest.LootrTestSupport.EMPTY_STRUCTURE;

/**
 * Tests for the personal cycle (per-player re-roll and decay by real time). Time is moved with a fake
 * {@link WallClock}; the clock and the configuration are global, so each test changes them only inside one
 * synchronous block on the server thread and restores them in a `finally`.
 */
public class LootrPersonalCycleTests {
  private static final String BATCH = "lootr_personal_cycle";
  private static final int TIMEOUT_TICKS = 400;
  private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
  private static final ResourceKey<LootTable> OTHER_TABLE = BuiltInLootTables.ABANDONED_MINESHAFT;
  // One hour, the interval the tests configure.
  private static final long HOUR = 3_600_000L;
  // A fixed point in time (2026-01-01) the fake clock starts at.
  private static final long T0 = 1_767_225_600_000L;

  // ----- The schedule itself -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void scheduleFollowsTheOwnersExample(GameTestHelper helper) {
    // 72 h, 3 re-rolls: loot at 0 h, re-rolled at 72, 144 and 216 h, decayed at 288 h.
    CycleSettings.Schedule schedule = new CycleSettings.Schedule(72.0, 3, true);
    long h = HOUR;
    assertEvaluation(helper, PersonalCycle.evaluate(0, 0, 71 * h, schedule, true), 0, false, 72 * h, false);
    assertEvaluation(helper, PersonalCycle.evaluate(0, 0, 72 * h, schedule, true), 1, false, 144 * h, false);
    assertEvaluation(helper, PersonalCycle.evaluate(0, 1, 150 * h, schedule, true), 2, false, 216 * h, false);
    assertEvaluation(helper, PersonalCycle.evaluate(0, 2, 216 * h, schedule, true), 3, false, 288 * h, true);
    assertEvaluation(helper, PersonalCycle.evaluate(0, 3, 287 * h, schedule, true), 3, false, 288 * h, true);
    assertEvaluation(helper, PersonalCycle.evaluate(0, 3, 288 * h, schedule, true), 3, true, -1, false);
    // Without decay the last roll stays; a container type that cannot decay behaves the same.
    assertEvaluation(helper, PersonalCycle.evaluate(0, 3, 10_000 * h, new CycleSettings.Schedule(72.0, 3, false), true), 3, false, -1, false);
    assertEvaluation(helper, PersonalCycle.evaluate(0, 3, 10_000 * h, schedule, false), 3, false, -1, false);
    // Count 0: decay after the first period.
    assertEvaluation(helper, PersonalCycle.evaluate(0, 0, 71 * h, new CycleSettings.Schedule(72.0, 0, true), true), 0, false, 72 * h, true);
    assertEvaluation(helper, PersonalCycle.evaluate(0, 0, 72 * h, new CycleSettings.Schedule(72.0, 0, true), true), 0, true, -1, false);
    // A clock before the start counts as no time; applied re-rolls are never taken back.
    assertEvaluation(helper, PersonalCycle.evaluate(1000 * h, 0, 0, schedule, true), 0, false, 1072 * h, false);
    assertEvaluation(helper, PersonalCycle.evaluate(1000 * h, 2, 0, schedule, true), 0, false, 1216 * h, false);
    // The interval is clamped to at least one minute.
    helper.assertTrue(new CycleSettings.Schedule(0.0, 1, true).intervalMillis() >= 60_000L, "The interval is not clamped to a minute");
    helper.succeed();
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void settingsParseOverridesAndExclusions(GameTestHelper helper) {
    CycleSettings settings = CycleSettings.parse(true, 72.0, 3, true,
        List.of("minecraft:chests/abandoned_mineshaft", "othermod:*", "not a valid id"),
        List.of("minecraft:the_end"),
        List.of("minecraft:*=5,1,true", "minecraft:chests/simple_dungeon=2,4,false", "broken", "minecraft:chests/igloo_chest=x,1,true", "minecraft:chests/desert_pyramid=1,1,maybe"));
    CycleSettings.Schedule exact = settings.scheduleFor(TABLE.location());
    helper.assertTrue(exact.intervalMillis() == 2 * HOUR && exact.count() == 4 && !exact.decay(), "The exact override does not win: " + exact);
    CycleSettings.Schedule namespace = settings.scheduleFor(BuiltInLootTables.IGLOO_CHEST.location());
    helper.assertTrue(namespace.intervalMillis() == 5 * HOUR && namespace.count() == 1 && namespace.decay(), "The namespace override does not apply (or a malformed entry did): " + namespace);
    CycleSettings.Schedule fallback = settings.scheduleFor(ResourceLocation.fromNamespaceAndPath("somemod", "chests/x"));
    helper.assertTrue(fallback.equals(settings.defaultSchedule()) && fallback.intervalMillis() == 72 * HOUR, "A table without override does not get the section's values: " + fallback);
    helper.assertTrue(settings.isTableExcluded(OTHER_TABLE.location()) && settings.isTableExcluded(ResourceLocation.fromNamespaceAndPath("othermod", "a/b")), "Excluded tables are not matched");
    helper.assertTrue(!settings.isTableExcluded(TABLE.location()), "A table that is not listed is excluded");
    helper.assertTrue(settings.isDimensionExcluded(ResourceLocation.withDefaultNamespace("the_end")) && !settings.isDimensionExcluded(ResourceLocation.withDefaultNamespace("overworld")), "Excluded dimensions are not matched");
    helper.succeed();
  }

  // ----- Opening -----

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void firstOpenStartsTheCycleAndKeepsItemsWithinAnInterval(GameTestHelper helper) {
    withChest(helper, 201L, f -> {
      ServerPlayer player = f.player();
      ILootrInventory first = f.open(player);
      CycleRecord record = f.record(player);
      helper.assertTrue(record != null && record.start() == T0 && record.refreshes() == 0 && !record.decayed(), "The first opening did not start a cycle at the fake time: " + describe(record));

      f.now = T0 + HOUR - 1;
      ILootrInventory again = f.open(player);
      helper.assertTrue(again == first && ItemStack.listMatches(snapshot(first), snapshot(again)), "The loot changed within the first interval");
      helper.assertTrue(f.record(player).refreshes() == 0, "A re-roll was counted within the first interval");
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void openingAfterAnIntervalRerollsOnce(GameTestHelper helper) {
    withChest(helper, 202L, f -> {
      ServerPlayer player = f.player();
      ILootrInventory first = f.open(player);
      f.now = T0 + HOUR;
      ILootrInventory rerolled = f.open(player);
      CycleRecord record = f.record(player);
      helper.assertTrue(rerolled != first && f.data().getInventory(f.key(player)) == rerolled, "The loot was not re-rolled after one interval");
      helper.assertTrue(record.refreshes() == 1 && record.start() == T0, "After one re-roll: " + describe(record));
      ILootrInventory again = f.open(player);
      helper.assertTrue(again == rerolled && f.record(player).refreshes() == 1, "Opening again re-rolled a second time");
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void missedPeriodsRerollOnlyOnce(GameTestHelper helper) {
    withChest(helper, 203L, f -> {
      ServerPlayer player = f.player();
      ILootrInventory first = f.open(player);
      f.now = T0 + 5 * HOUR / 2;
      ILootrInventory rerolled = f.open(player);
      helper.assertTrue(rerolled != first, "The loot was not re-rolled after 2.5 intervals");
      helper.assertTrue(f.record(player).refreshes() == 2 && f.record(player).start() == T0, "After 2.5 intervals: " + describe(f.record(player)));
      helper.assertTrue(f.open(player) == rerolled, "The missed periods were re-rolled more than once");
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void containerDecaysForThePlayerAndStaysDecayed(GameTestHelper helper) {
    withChest(helper, 204L, f -> {
      ServerPlayer player = f.player();
      UUID key = f.key(player);
      f.open(player);
      f.now = T0 + 4 * HOUR;
      f.refusedOpen(player);
      CycleRecord record = f.record(player);
      helper.assertTrue(record != null && record.decayed(), "The cycle is not marked as decayed: " + describe(record));
      helper.assertTrue(f.data().getInventory(key) == null, "The decayed player still has an inventory");
      helper.assertTrue(f.data().getVisualOpeners().contains(key), "The decayed container does not look opened to the player");
      helper.assertTrue(f.data().createInventory(f.chest, player, f.chest.getDefaultFiller()) == null, "Another caller still gets new loot for the decayed player");
      helper.assertTrue(f.chest.asBlockEntity().getLevel().getBlockState(f.chest.getInfoPos()).is(LootrRegistry.getChestBlock()), "The container itself was removed");

      // The record survives saving although the player has no inventory any more.
      f.restart();
      CycleRecord reloaded = f.record(player);
      helper.assertTrue(reloaded != null && reloaded.decayed() && reloaded.start() == T0, "The decayed record did not survive a restart: " + describe(reloaded));
      f.refusedOpen(player);

      // `/lootr clear` drops the cycle: the next opening rolls fresh loot and starts a new cycle.
      helper.assertTrue(LootrAPI.clearPlayerLoot(key), "Clearing the player's loot found nothing to clear");
      helper.assertTrue(f.record(player) == null, "Clearing the player's loot kept the cycle");
      f.open(player);
      helper.assertTrue(f.record(player) != null && f.record(player).start() == f.now && !f.record(player).decayed(), "No new cycle after clearing: " + describe(f.record(player)));
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void playersHaveIndependentCycles(GameTestHelper helper) {
    withChest(helper, 205L, f -> {
      ServerPlayer first = f.player();
      ServerPlayer second = f.player();
      f.open(first);
      f.now = T0 + 2 * HOUR;
      ILootrInventory secondLoot = f.open(second);
      helper.assertTrue(f.record(second).start() == T0 + 2 * HOUR, "The second player's cycle did not start at their first opening");
      helper.assertTrue(f.record(first).refreshes() == 0, "The second player's opening changed the first player's cycle");

      f.now = T0 + 4 * HOUR;
      f.refusedOpen(first);
      ILootrInventory secondNow = f.open(second);
      helper.assertTrue(secondNow != secondLoot && f.record(second).refreshes() == 2 && !f.record(second).decayed(), "The second player's cycle followed the first player's: " + describe(f.record(second)));
      helper.assertTrue(f.record(first).decayed(), "The first player's decay was undone");
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void withoutDecayTheLastRollIsKept(GameTestHelper helper) {
    withChest(helper, 206L, List.of(), List.of(), false, 3, f -> {
      ServerPlayer player = f.player();
      f.open(player);
      f.now = T0 + 10 * HOUR;
      ILootrInventory last = f.open(player);
      helper.assertTrue(f.record(player).refreshes() == 3 && !f.record(player).decayed(), "After the last re-roll without decay: " + describe(f.record(player)));
      f.now = T0 + 100 * HOUR;
      helper.assertTrue(f.open(player) == last && !f.record(player).decayed(), "The last roll was not kept");
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void zeroRefreshesDecayAfterOnePeriod(GameTestHelper helper) {
    withChest(helper, 207L, List.of(), List.of(), true, 0, f -> {
      ServerPlayer player = f.player();
      ILootrInventory first = f.open(player);
      f.now = T0 + HOUR - 1;
      helper.assertTrue(f.open(player) == first, "The loot changed before the only period ended");
      f.now = T0 + HOUR;
      f.refusedOpen(player);
      helper.assertTrue(f.record(player).decayed() && f.record(player).refreshes() == 0, "With no re-rolls: " + describe(f.record(player)));
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void cycleSurvivesARestartAndDowntimeCounts(GameTestHelper helper) {
    withChest(helper, 208L, f -> {
      ServerPlayer player = f.player();
      UUID key = f.key(player);
      ILootrInventory first = f.open(player);
      NonNullList<ItemStack> before = snapshot(first);
      CompoundTag saved = f.data().save(new CompoundTag(), helper.getLevel().registryAccess());
      ListTag cycles = saved.getList("cycles", Tag.TAG_COMPOUND);
      helper.assertTrue(cycles.size() == 1, "Saved cycles: " + saved.get("cycles"));
      CompoundTag entry = cycles.getCompound(0);
      helper.assertTrue(entry.hasUUID("id") && entry.getUUID("id").equals(key) && entry.getLong("start") == T0 && entry.getInt("refreshes") == 0 && entry.contains("decayed", Tag.TAG_BYTE) && !entry.getBoolean("decayed"), "A saved cycle has the form " + entry);

      f.restart();
      helper.assertTrue(f.record(player) != null && f.record(player).start() == T0, "The cycle did not survive a restart");
      helper.assertTrue(ItemStack.listMatches(before, snapshot(f.data().getInventory(key))), "The loot changed across a restart");
      // Time passes "while the server is down".
      f.restart();
      f.now = T0 + 3 * HOUR / 2;
      ILootrInventory after = f.open(player);
      helper.assertTrue(f.record(player).refreshes() == 1 && f.record(player).start() == T0, "Downtime did not count: " + describe(f.record(player)));
      helper.assertTrue(after == f.data().getInventory(key), "The menu does not show the stored re-rolled inventory");
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void clockGoingBackwardsChangesNothing(GameTestHelper helper) {
    withChest(helper, 209L, f -> {
      ServerPlayer player = f.player();
      ILootrInventory first = f.open(player);
      f.now = T0 - 50 * HOUR;
      helper.assertTrue(f.open(player) == first && f.record(player).refreshes() == 0, "A clock that went backwards re-rolled");
      f.now = T0 + HOUR;
      ILootrInventory rerolled = f.open(player);
      helper.assertTrue(rerolled != first && f.record(player).refreshes() == 1, "The re-roll after the clock recovered is missing");
      f.now = T0;
      helper.assertTrue(f.open(player) == rerolled && f.record(player).refreshes() == 1, "A clock that went backwards undid a re-roll");
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void lootrsOwnRefreshWins(GameTestHelper helper) {
    withChest(helper, 210L, f -> {
      TestPlatform platform = TestPlatforms.get();
      MinecraftServer server = helper.getLevel().getServer();
      ServerPlayer player = f.player();
      platform.setRefreshAll(true);
      try {
        ILootrInventory first = f.open(player);
        helper.assertTrue(f.record(player) == null, "A container that refreshes the old way got a personal cycle");
        f.now = T0 + 100 * HOUR;
        helper.assertTrue(f.open(player) == first, "The personal cycle touched a container that refreshes the old way");
        helper.assertTrue(PersonalCycle.exclusion(f.chest, LootrAPI.getPersonalCycleSettings()) == PersonalCycle.Exclusion.OLD_REFRESH, "The exclusion reason is not the old refresh");
      } finally {
        platform.setRefreshAll(false);
        NewTickingData.getRefreshData().clearTicking(server, f.chest.getInfoUUID());
      }
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void overrideIsHonoured(GameTestHelper helper) {
    withChest(helper, 211L, List.of(), List.of("minecraft:*=5,1,true", TABLE.location() + "=2,1,false"), true, 3, f -> {
      ServerPlayer player = f.player();
      ILootrInventory first = f.open(player);
      f.now = T0 + 3 * HOUR / 2;
      helper.assertTrue(f.open(player) == first, "The section's interval was used instead of the override's");
      f.now = T0 + 2 * HOUR;
      ILootrInventory rerolled = f.open(player);
      helper.assertTrue(rerolled != first && f.record(player).refreshes() == 1, "The override's interval did not re-roll");
      f.now = T0 + 100 * HOUR;
      helper.assertTrue(f.open(player) == rerolled && !f.record(player).decayed() && f.record(player).refreshes() == 1, "The override's count or decay was not honoured: " + describe(f.record(player)));
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void excludedTableKeepsItsLoot(GameTestHelper helper) {
    withChest(helper, 212L, List.of("minecraft:chests/simple_dungeon"), List.of(), true, 3, f -> {
      ServerPlayer player = f.player();
      ILootrInventory first = f.open(player);
      helper.assertTrue(f.record(player) == null, "An excluded loot table got a personal cycle");
      f.now = T0 + 100 * HOUR;
      helper.assertTrue(f.open(player) == first, "An excluded loot table was re-rolled or decayed");
      helper.assertTrue(PersonalCycle.exclusion(f.chest, LootrAPI.getPersonalCycleSettings()) == PersonalCycle.Exclusion.EXCLUDED_TABLE, "The exclusion reason is not the table");
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void disabledBehavesAsBefore(GameTestHelper helper) {
    withChest(helper, 213L, f -> {
      TestPlatforms.get().setPersonalCycle(false, 1.0, 3, true, List.of(), List.of(), List.of());
      ServerPlayer player = f.player();
      ILootrInventory first = f.open(player);
      helper.assertTrue(f.record(player) == null && !f.data().hasCycles(), "A disabled cycle created a record");
      f.now = T0 + 1000 * HOUR;
      helper.assertTrue(f.open(player) == first, "A disabled cycle changed the loot");
      CompoundTag saved = f.data().save(new CompoundTag(), helper.getLevel().registryAccess());
      helper.assertTrue(!saved.contains("cycles"), "The saved data of a disabled cycle has the key `cycles`");
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void dueRefreshMakesTheContainerLookUnopened(GameTestHelper helper) {
    withChest(helper, 214L, f -> {
      ServerPlayer player = f.player();
      UUID key = f.key(player);
      f.open(player);
      helper.assertTrue(!PersonalCycle.refreshVisualOpeners(f.chest) && f.data().getVisualOpeners().contains(key), "The container looks unopened before a re-roll is due");
      f.now = T0 + HOUR;
      helper.assertTrue(PersonalCycle.refreshVisualOpeners(f.chest) && !f.data().getVisualOpeners().contains(key), "A due re-roll does not make the container look unopened");
      f.open(player);
      helper.assertTrue(f.data().getVisualOpeners().contains(key) && !PersonalCycle.refreshVisualOpeners(f.chest), "The opening after the re-roll does not look opened");
      // Once the container decayed for the player, it keeps looking opened.
      f.now = T0 + 4 * HOUR;
      f.refusedOpen(player);
      helper.assertTrue(!PersonalCycle.refreshVisualOpeners(f.chest) && f.data().getVisualOpeners().contains(key), "A decayed container looks unopened");
      LootrAPI.handleProviderSneak(f.chest, player);
      helper.assertTrue(f.data().getVisualOpeners().contains(key), "Sneaking made a decayed container look unopened");
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
  public void commandsShowExpireAndResetTheCycle(GameTestHelper helper) {
    withChest(helper, 215L, f -> {
      ServerPlayer player = f.player();
      UUID key = f.key(player);
      f.open(player);
      helper.assertTrue(f.command("lootr cycle info @p") == 1, "`/lootr cycle info` did not report on the player");
      helper.assertTrue(f.command("lootr cycle expire @p") == 1 && f.record(player).start() == T0 - HOUR, "`/lootr cycle expire` did not move the start back by one interval: " + describe(f.record(player)));
      helper.assertTrue(f.command("lootr cycle reset @p") == 1, "`/lootr cycle reset` found nothing to reset");
      helper.assertTrue(f.record(player) == null && f.data().getInventory(key) == null && !f.data().getVisualOpeners().contains(key), "`/lootr cycle reset` left the cycle, the inventory or the opener behind");
      f.open(player);
      helper.assertTrue(f.record(player) != null && f.record(player).start() == T0 && f.record(player).refreshes() == 0, "No new cycle after the reset: " + describe(f.record(player)));
    });
  }

  // ----- Fixture -----

  private void withChest(GameTestHelper helper, long seed, Consumer<Fixture> body) {
    withChest(helper, seed, List.of(), List.of(), true, 3, body);
  }

  // Places a loot chest and runs the body in one synchronous block with a fake clock at T0 and a one-hour interval.
  private void withChest(GameTestHelper helper, long seed, List<String> excludedTables, List<String> overrides, boolean decay, int count, Consumer<Fixture> body) {
    BlockPos pos = new BlockPos(1, 1, 1);
    LootrTestSupport.placeLootContainer(helper, pos, Blocks.CHEST, TABLE, seed);
    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, pos, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          TestPlatform platform = TestPlatforms.get();
          Fixture fixture = new Fixture(helper, LootrTestSupport.getLootrBlockEntity(helper, pos), pos);
          WallClock.setForTesting(() -> fixture.now);
          platform.setPersonalCycle(true, 1.0, count, decay, excludedTables, List.of(), overrides);
          try {
            body.accept(fixture);
          } finally {
            fixture.closeAll();
            WallClock.setForTesting(null);
            platform.setPersonalCycle(CycleSettings.DEFAULT_ENABLED, CycleSettings.DEFAULT_INTERVAL_HOURS, CycleSettings.DEFAULT_COUNT, CycleSettings.DEFAULT_DECAY, List.of(), List.of(), List.of());
          }
        })
        .thenSucceed();
  }

  private static final class Fixture {
    private final GameTestHelper helper;
    private final ILootrBlockEntity chest;
    private final BlockPos pos;
    private final List<ServerPlayer> players = new ArrayList<>();
    private volatile long now = T0;

    private Fixture(GameTestHelper helper, ILootrBlockEntity chest, BlockPos pos) {
      this.helper = helper;
      this.chest = chest;
      this.pos = pos;
    }

    ServerPlayer player() {
      ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
      BlockPos absolute = helper.absolutePos(pos);
      player.moveTo(absolute.getX() + 0.5, absolute.getY() + 1.0, absolute.getZ() + 0.5);
      players.add(player);
      return player;
    }

    UUID key(ServerPlayer player) {
      return LootrAPI.resolveServerPlayerTeam(player);
    }

    LootrSavedData data() {
      LootrSavedData data = DataStorage.getExistingData(chest.getInfoKey());
      if (data == null) {
        throw new GameTestAssertException("The container has no saved data");
      }
      return data;
    }

    CycleRecord record(ServerPlayer player) {
      return data().getCycle(key(player));
    }

    // Opens the container for the player (closing a menu that is still open first) and returns the inventory shown.
    ILootrInventory open(ServerPlayer player) {
      player.closeContainer();
      LootrAPI.handleProviderOpen(chest, player);
      if (player.containerMenu instanceof ChestMenu menu && menu.getContainer() instanceof ILootrInventory inventory) {
        return inventory;
      }
      throw new GameTestAssertException("The player has no Lootr inventory open but " + player.containerMenu);
    }

    void refusedOpen(ServerPlayer player) {
      player.closeContainer();
      LootrAPI.handleProviderOpen(chest, player);
      if (player.containerMenu != player.inventoryMenu) {
        throw new GameTestAssertException("The decayed container opened a menu: " + player.containerMenu);
      }
    }

    // What a server restart does to the data storage: everything is written, nothing stays in memory.
    void restart() {
      players.forEach(ServerPlayer::closeContainer);
      DimensionDataStorage storage = DataStorage.getDataStorage();
      if (storage == null) {
        throw new GameTestAssertException("The data storage is not available");
      }
      storage.save();
      TestPlatforms.get().awaitSavedDataWrites();
      Map<String, SavedData> cache = ((AccessorMixinDimensionDataStorage) storage).getCache();
      cache.remove(chest.getInfoKey());
    }

    // Runs a command as the server console standing in the container; returns the command's result.
    int command(String command) {
      MinecraftServer server = helper.getLevel().getServer();
      CommandSourceStack source = server.createCommandSourceStack().withLevel(helper.getLevel()).withPosition(Vec3.atCenterOf(helper.absolutePos(pos))).withSuppressedOutput();
      try {
        return server.getCommands().getDispatcher().execute(command, source);
      } catch (CommandSyntaxException e) {
        throw new GameTestAssertException("`/" + command + "` failed: " + e.getMessage());
      }
    }

    void closeAll() {
      players.forEach(ServerPlayer::closeContainer);
    }
  }

  private static void assertEvaluation(GameTestHelper helper, PersonalCycle.Evaluation evaluation, int due, boolean decayDue, long nextEventAt, boolean nextIsDecay) {
    helper.assertTrue(evaluation.due() == due && evaluation.decayDue() == decayDue && evaluation.nextEventAt() == nextEventAt && evaluation.nextIsDecay() == nextIsDecay,
        "Expected due " + due + ", decay due " + decayDue + ", next " + nextEventAt + " (decay " + nextIsDecay + ") but got " + evaluation);
  }

  private static String describe(CycleRecord record) {
    return record == null ? "no record" : "start " + record.start() + ", refreshes " + record.refreshes() + ", decayed " + record.decayed() + ", exempt " + record.exempt();
  }

  private static NonNullList<ItemStack> snapshot(Container container) {
    NonNullList<ItemStack> result = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
    for (int i = 0; i < result.size(); i++) {
      result.set(i, container.getItem(i).copy());
    }
    return result;
  }
}
