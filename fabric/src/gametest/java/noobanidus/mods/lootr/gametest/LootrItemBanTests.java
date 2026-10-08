package noobanidus.mods.lootr.gametest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import noobanidus.mods.lootr.common.api.BuiltInLootrTypes;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.CustomLootrInfoProvider;
import noobanidus.mods.lootr.common.api.data.DefaultLootFiller;
import noobanidus.mods.lootr.common.api.data.blockentity.ILootrBlockEntity;
import noobanidus.mods.lootr.common.api.data.inventory.ILootrInventory;
import noobanidus.mods.lootr.common.api.registry.LootrRegistry;
import noobanidus.mods.lootr.common.data.LootrSavedData;
import noobanidus.mods.lootr.common.itemban.ItemBanSettings;
import noobanidus.mods.lootr.common.itemban.ItemBanSource;
import noobanidus.mods.lootr.common.itemban.ItemBans;
import noobanidus.mods.lootr.common.itemban.ObliteratorBanSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Tests for the item ban layer.
 * <br />
 * Ban sources and settings are global, so every test changes them only inside one synchronous block on the
 * server thread and restores them in a `finally`: nothing can leak into another test, also when a test fails.
 * The tests additionally run in their own batch, apart from the smoke tests.
 */
public class LootrItemBanTests implements FabricGameTest {
  private static final String BATCH = "lootr_item_bans";
  private static final BlockPos CHEST_POS = new BlockPos(0, 1, 0);
  private static final int CONVERSION_TIMEOUT_TICKS = 200;
  private static final int ROLLS = 50;

  // The banned item ("X") and the allowed items ("Y") of the test loot tables.
  private static final Item BANNED = Items.BARRIER;
  private static final Item NESTED_BANNED = Items.STRUCTURE_VOID;
  private static final Item ALLOWED = Items.STICK;
  private static final Item ALSO_ALLOWED = Items.PAPER;

  // a. A table that always yields X and Y: no roll and no fill contains X, all of them contain Y.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void rollNeverYieldsBannedItem(GameTestHelper helper) {
    LootTable table = table(helper, "banned_and_allowed");
    LootParams params = params(helper);
    helper.assertTrue(has(table.getRandomItems(params), BANNED), "The test table must yield the item when it is not banned");

    whileBanned("roll", BANNED, () -> {
      for (int i = 0; i < ROLLS; i++) {
        ObjectArrayList<ItemStack> rolled = table.getRandomItems(params);
        helper.assertTrue(!has(rolled, BANNED), "Roll " + i + " contains the banned item");
        helper.assertTrue(has(rolled, ALLOWED), "Roll " + i + " lost the allowed item");

        SimpleContainer container = new SimpleContainer(27);
        table.fill(container, params, i);
        helper.assertTrue(!has(container, BANNED), "Fill " + i + " contains the banned item");
        helper.assertTrue(has(container, ALLOWED), "Fill " + i + " lost the allowed item");
      }
    });

    helper.assertTrue(has(table.getRandomItems(params), BANNED), "The ban is still in effect after its source was unregistered");
    helper.succeed();
  }

  // b. A banned item that comes out of a nested table reference is filtered as well.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void nestedTableIsFiltered(GameTestHelper helper) {
    LootTable table = table(helper, "nested_outer");
    LootParams params = params(helper);
    helper.assertTrue(has(table.getRandomItems(params), NESTED_BANNED), "The nested test table must yield the item when it is not banned");

    whileBanned("nested", NESTED_BANNED, () -> {
      for (int i = 0; i < ROLLS; i++) {
        ObjectArrayList<ItemStack> rolled = table.getRandomItems(params);
        helper.assertTrue(!has(rolled, NESTED_BANNED), "Roll " + i + " contains the banned item of the nested table");
        helper.assertTrue(has(rolled, ALLOWED), "Roll " + i + " lost the allowed item");
      }
    });
    helper.succeed();
  }

  // c. A per-player inventory generated while X is banned contains no X.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void freshLootrInventoryHasNoBannedItem(GameTestHelper helper) {
    withLootrChest(helper, 1L, (chest, player) -> whileBanned("fresh", BANNED, () -> {
      ILootrInventory inventory = LootrAPI.getInventory(chest, player);
      helper.assertTrue(inventory != null, "Lootr returned no inventory");
      helper.assertTrue(!has(inventory, BANNED), "The new inventory contains the banned item");
      helper.assertTrue(has(inventory, ALLOWED) && has(inventory, ALSO_ALLOWED), "The new inventory lost allowed items");
    }));
  }

  // d. An inventory generated before the ban loses X on the next access; nothing else changes and X stays gone
  // after the saved data went through save -> load.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void savedInventoryIsScrubbedOnAccess(GameTestHelper helper) {
    withLootrChest(helper, 2L, (chest, player) -> {
      HolderLookup.Provider registries = helper.getLevel().registryAccess();
      ILootrInventory generated = LootrAPI.getInventory(chest, player);
      helper.assertTrue(generated != null && has(generated, BANNED), "Without a ban the inventory must contain the item");
      NonNullList<ItemStack> before = snapshot(generated);
      LootrSavedData data = savedData(chest);
      data.setDirty(false);

      whileBanned("saved", BANNED, () -> {
        ILootrInventory accessed = LootrAPI.getInventory(chest, player);
        helper.assertTrue(accessed == generated, "The inventory was replaced instead of being cleaned in place");
        assertOnlyBannedRemoved(helper, before, accessed, BANNED);
        helper.assertTrue(data.isDirty(), "Removing a banned stack did not mark the saved data as changed");
      });

      // The ban is lifted again at this point, so what is checked here is what was really stored.
      LootrSavedData reloaded = LootrSavedData.load(data.save(new CompoundTag(), registries), registries);
      ILootrInventory fromDisk = reloaded.getInventory(LootrAPI.resolveServerPlayerTeam(player));
      helper.assertTrue(fromDisk != null, "The inventory is missing after save and load");
      assertOnlyBannedRemoved(helper, before, fromDisk, BANNED);
    });
  }

  // d (reverse order). Saved with X inside, loaded, then banned: X is gone on access.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void loadedInventoryIsScrubbedOnAccess(GameTestHelper helper) {
    withLootrChest(helper, 3L, (chest, player) -> {
      HolderLookup.Provider registries = helper.getLevel().registryAccess();
      UUID owner = LootrAPI.resolveServerPlayerTeam(player);
      ILootrInventory generated = LootrAPI.getInventory(chest, player);
      helper.assertTrue(generated != null && has(generated, BANNED), "Without a ban the inventory must contain the item");
      NonNullList<ItemStack> before = snapshot(generated);

      LootrSavedData loaded = LootrSavedData.load(savedData(chest).save(new CompoundTag(), registries), registries);
      ILootrInventory untouched = loaded.getInventory(owner);
      helper.assertTrue(untouched != null && ItemStack.listMatches(before, snapshot(untouched)), "Loading changed the inventory although nothing is banned");
      loaded.setDirty(false);

      whileBanned("loaded", BANNED, () -> {
        ILootrInventory accessed = loaded.getOrCreateInventory(chest, player, chest.getDefaultFiller());
        helper.assertTrue(accessed == untouched, "The loaded inventory was replaced instead of being cleaned in place");
        assertOnlyBannedRemoved(helper, before, accessed, BANNED);
        helper.assertTrue(loaded.isDirty(), "Removing a banned stack did not mark the loaded data as changed");
      });

      LootrSavedData reloaded = LootrSavedData.load(loaded.save(new CompoundTag(), registries), registries);
      assertOnlyBannedRemoved(helper, before, reloaded.getInventory(owner), BANNED);
    });
  }

  // e. With `filter_all_loot` off a plain loot roll still contains X, Lootr's own inventory does not.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void lootrFillsStayFilteredWhenAllLootFilterIsOff(GameTestHelper helper) {
    LootTable table = table(helper, "chest");
    LootParams params = params(helper);
    withLootrChest(helper, 4L, (chest, player) -> withSettings(settings -> settings.withFilterAllLoot(false), () -> whileBanned("own_fills", BANNED, () -> {
      helper.assertTrue(ItemBans.isActive() && !ItemBans.isFilteringAllLoot(), "All-loot filtering should be off while a source is active");
      helper.assertTrue(has(table.getRandomItems(params), BANNED), "A plain roll was filtered although filter_all_loot is off");
      SimpleContainer container = new SimpleContainer(27);
      table.fill(container, params, 1L);
      helper.assertTrue(has(container, BANNED), "A plain fill was filtered although filter_all_loot is off");

      ILootrInventory inventory = LootrAPI.getInventory(chest, player);
      helper.assertTrue(inventory != null, "Lootr returned no inventory");
      helper.assertTrue(!has(inventory, BANNED), "The Lootr inventory contains the banned item");
      helper.assertTrue(has(inventory, ALLOWED) && has(inventory, ALSO_ALLOWED), "The Lootr inventory lost allowed items");
    })));
  }

  // With `scrub_saved_inventories` off an existing inventory is left alone, a new one is still filtered.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void savedInventoryIsKeptWhenScrubbingIsOff(GameTestHelper helper) {
    withLootrChest(helper, 5L, (chest, player) -> {
      ILootrInventory generated = LootrAPI.getInventory(chest, player);
      helper.assertTrue(generated != null && has(generated, BANNED), "Without a ban the inventory must contain the item");
      NonNullList<ItemStack> before = snapshot(generated);
      ServerPlayer newcomer = LootrTestSupport.createRealPlayer(helper);

      withSettings(settings -> settings.withScrubSavedInventories(false), () -> whileBanned("no_scrub", BANNED, () -> {
        helper.assertTrue(!ItemBans.isScrubbingSavedInventories(), "Scrubbing should be off");
        ILootrInventory accessed = LootrAPI.getInventory(chest, player);
        helper.assertTrue(accessed != null && ItemStack.listMatches(before, snapshot(accessed)), "The saved inventory was changed although scrub_saved_inventories is off");
        ILootrInventory fresh = LootrAPI.getInventory(chest, newcomer);
        helper.assertTrue(fresh != null && !has(fresh, BANNED), "A newly generated inventory contains the banned item");
      }));
    });
  }

  // A reference ("custom") inventory: banned stacks are not copied to the player, the reference keeps them.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void referenceInventoryCopySkipsBannedItem(GameTestHelper helper) {
    NonNullList<ItemStack> reference = NonNullList.withSize(27, ItemStack.EMPTY);
    reference.set(0, new ItemStack(BANNED));
    reference.set(1, new ItemStack(ALLOWED, 3));
    CustomLootrInfoProvider provider = new CustomLootrInfoProvider(UUID.randomUUID(), "lootr/gametest/reference", helper.absolutePos(CHEST_POS), 27, null, 0L, Component.literal("Reference"), helper.getLevel().dimension(), reference, null, null, BuiltInLootrTypes.CHEST);
    ServerPlayer player = LootrTestSupport.createRealPlayer(helper);

    whileBanned("reference", BANNED, () -> {
      SimpleContainer copy = new SimpleContainer(27);
      DefaultLootFiller.getInstance().unpackLootTable(provider, player, copy);
      helper.assertTrue(copy.getItem(0).isEmpty(), "The banned stack of the reference inventory was copied");
      helper.assertTrue(copy.getItem(1).is(ALLOWED) && copy.getItem(1).getCount() == 3, "The allowed stack of the reference inventory was not copied");
    });
    helper.assertTrue(reference.get(0).is(BANNED), "The stored reference inventory was modified");

    SimpleContainer copy = new SimpleContainer(27);
    DefaultLootFiller.getInstance().unpackLootTable(provider, player, copy);
    helper.assertTrue(copy.getItem(0).is(BANNED), "Without a ban the reference inventory must be copied completely");
    helper.succeed();
  }

  // f. A source that throws counts as "not banned", does not break the roll and is reported exactly once.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void throwingSourceIsIgnoredAndReportedOnce(GameTestHelper helper) {
    LootTable table = table(helper, "banned_and_allowed");
    LootParams params = params(helper);
    String name = "gametest:throwing";
    ItemBanSource throwing = new ItemBanSource() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public boolean isBanned(ItemStack stack) {
        // An Error on purpose: a broken optional mod surfaces as a LinkageError, not as an Exception.
        throw new NoClassDefFoundError("simulated failure of an item ban source");
      }
    };

    int warningsBefore = ItemBans.warningCount();
    ItemBans.register(throwing);
    try {
      helper.assertTrue(ItemBans.isActive(), "A registered source must make the service active");
      helper.assertTrue(!ItemBans.isBanned(new ItemStack(BANNED)), "A failing source must count as \"not banned\"");
      for (int i = 0; i < ROLLS; i++) {
        ObjectArrayList<ItemStack> rolled = table.getRandomItems(params);
        helper.assertTrue(has(rolled, BANNED) && has(rolled, ALLOWED), "Roll " + i + " is incomplete although the only source fails");
      }
      helper.assertTrue(ItemBans.hasWarned(name), "The failing source was not reported");
      helper.assertTrue(ItemBans.warningCount() == warningsBefore + 1, "Expected exactly one warning but got " + (ItemBans.warningCount() - warningsBefore));

      // Other sources keep working next to a failing one.
      whileBanned("next_to_throwing", BANNED, () -> helper.assertTrue(ItemBans.isBanned(new ItemStack(BANNED)), "A working source was ignored because another one fails"));

      // A data reload allows one more report. The event is fired exactly the way Fabric API does after data
      // packs were loaded, so this also checks that Lootr listens to it. The failing source is taken out
      // meanwhile because other mods roll loot in their own listeners.
      ItemBans.unregister(throwing);
      CommonLifecycleEvents.TAGS_LOADED.invoker().onTagsLoaded(helper.getLevel().getServer().reloadableRegistries().get(), false);
      helper.assertTrue(!ItemBans.hasWarned(name), "The data reload did not reset the reported sources");
      ItemBans.register(throwing);
      ItemBans.isBanned(new ItemStack(BANNED));
      ItemBans.isBanned(new ItemStack(BANNED));
      helper.assertTrue(ItemBans.warningCount() == warningsBefore + 2, "Expected exactly one more warning after the data reload");
    } finally {
      ItemBans.unregister(throwing);
      ItemBans.onDataReload();
    }
    helper.succeed();
  }

  // The two removal helpers (the brushable filler uses the list form) report how many stacks they removed.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void removalHelpersReportRemovedStacks(GameTestHelper helper) {
    ObjectArrayList<ItemStack> stacks = new ObjectArrayList<>(List.of(new ItemStack(BANNED), new ItemStack(ALLOWED), new ItemStack(BANNED)));
    SimpleContainer container = new SimpleContainer(9);
    container.setItem(0, new ItemStack(BANNED));
    container.setItem(1, new ItemStack(ALLOWED, 2));
    container.setItem(5, new ItemStack(BANNED, 7));

    helper.assertTrue(ItemBans.removeBanned(stacks) == 0 && stacks.size() == 3, "Stacks were removed from the list although nothing is banned");
    helper.assertTrue(ItemBans.scrub(container) == 0 && has(container, BANNED), "Stacks were removed from the container although nothing is banned");

    whileBanned("helpers", BANNED, () -> {
      helper.assertTrue(ItemBans.removeBanned(stacks) == 2, "Expected two stacks to be removed from the list");
      helper.assertTrue(stacks.size() == 1 && stacks.getFirst().is(ALLOWED), "The list does not hold exactly the allowed stack");
      helper.assertTrue(ItemBans.scrub(container) == 2, "Expected two stacks to be removed from the container");
      helper.assertTrue(container.getItem(0).isEmpty() && container.getItem(5).isEmpty(), "The banned stacks are still in the container");
      helper.assertTrue(container.getItem(1).is(ALLOWED) && container.getItem(1).getCount() == 2, "The allowed stack was changed");
    });
    helper.succeed();
  }

  // g. Without Item Obliterator (the test runtime) the factory yields nothing and does not throw.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void obliteratorSourceIsAbsentWithoutTheMod(GameTestHelper helper) {
    Optional<ItemBanSource> source;
    try {
      source = ObliteratorBanSource.create();
    } catch (Throwable t) {
      throw new GameTestAssertException("The Item Obliterator factory threw " + t);
    }
    helper.assertTrue(source.isEmpty(), "The Item Obliterator source exists although the mod is not installed");

    // The lookup itself: a missing class or a method with another signature yields nothing (and one log line) ...
    helper.assertTrue(ObliteratorBanSource.resolve("lootr.gametest.NoSuchClass", "isDisabled").isEmpty(), "A missing class must not resolve");
    helper.assertTrue(ObliteratorBanSource.resolve(ObliteratorStandIn.class.getName(), "isDisabledByName").isEmpty(), "A method with another signature must not resolve");
    // ... and a method of the same shape as Item Obliterator's check is found and called.
    Optional<ItemBanSource> standIn = ObliteratorBanSource.resolve(ObliteratorStandIn.class.getName(), "isDisabled");
    helper.assertTrue(standIn.isPresent(), "A matching static method was not resolved");
    helper.assertTrue(standIn.get().isBanned(new ItemStack(BANNED)), "The resolved method was not called correctly");
    helper.assertTrue(!standIn.get().isBanned(new ItemStack(ALLOWED)), "The resolved method was not called correctly");
    helper.succeed();
  }

  // h. No source: the service is inactive and the loot table mixin hands the caller's consumer through as it is.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void noSourceMeansInactiveAndUntouchedConsumer(GameTestHelper helper) {
    LootTable table = table(helper, "banned_and_allowed");
    LootParams params = params(helper);
    helper.assertTrue(ItemBans.getSources().isEmpty(), "No ban source is expected in the test runtime but found " + ItemBans.getSources().stream().map(ItemBanSource::name).toList());
    helper.assertTrue(!ItemBans.isActive() && !ItemBans.isFilteringAllLoot() && !ItemBans.isScrubbingSavedInventories(), "The service is active without any source");

    Consumer<ItemStack> consumer = stack -> {
    };
    helper.assertTrue(ItemBans.wrapLootConsumer(consumer) == consumer, "The consumer was wrapped although no source is registered");
    // The capturing consumer of the test mixin takes the place of the caller's consumer during the roll, so the
    // probe watches there.
    FilterProbe inactive = new FilterProbe();
    CapturingLootConsumer.withTap(inactive, () -> table.getRandomItemsRaw(params, consumer));
    helper.assertTrue(inactive.accepted == 2, "Expected two stacks from the test table but got " + inactive.accepted);
    helper.assertTrue(!inactive.calledThroughFilter, "The loot table mixin wrapped the consumer although no source is registered");

    // Counter-check with a source, so that the two assertions above cannot pass by accident.
    whileBanned("probe", NESTED_BANNED, () -> {
      Consumer<ItemStack> wrapped = ItemBans.wrapLootConsumer(consumer);
      helper.assertTrue(wrapped != consumer, "The consumer was not wrapped although a source is registered");
      helper.assertTrue(ItemBans.wrapLootConsumer(wrapped) == wrapped, "An already wrapped consumer was wrapped again");
      FilterProbe active = new FilterProbe();
      CapturingLootConsumer.withTap(active, () -> table.getRandomItemsRaw(params, consumer));
      helper.assertTrue(active.accepted == 2 && active.calledThroughFilter, "The loot table mixin did not wrap the consumer although a source is registered");
    });
    helper.succeed();
  }

  // i. Another mod may take over the output of a roll: it replaces the consumer argument of the roll and hands the
  // stacks on at the end only if the argument is still its own (the test mixin does that to every roll). The item
  // ban filter must leave that argument alone: every kind of roll still delivers the allowed items and none of the
  // banned ones.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void lootArrivesWhenAnotherModCapturesTheOutput(GameTestHelper helper) {
    LootTable table = table(helper, "banned_and_allowed");
    LootTable nested = table(helper, "nested_outer");
    LootParams params = params(helper);
    long releases = CapturingLootConsumer.releases();
    helper.assertTrue(has(table.getRandomItems(params), ALLOWED) && CapturingLootConsumer.releases() > releases, "The test mixin that captures the output of a roll is not applied");

    whileBanned("capture", BANNED, () -> whileBanned("capture_nested", NESTED_BANNED, () -> {
      helper.assertTrue(ItemBans.isFilteringAllLoot(), "All-loot filtering should be on while a source is active");
      for (int i = 0; i < ROLLS; i++) {
        assertOnlyAllowed(helper, table.getRandomItems(params), "List roll " + i);
        assertOnlyAllowed(helper, table.getRandomItems(params, i), "Seeded list roll " + i);
        assertOnlyAllowed(helper, nested.getRandomItems(params), "Roll " + i + " of the table with a nested table");

        // The way mob drops are rolled.
        ObjectArrayList<ItemStack> dropped = new ObjectArrayList<>();
        table.getRandomItems(params, i, dropped::add);
        assertOnlyAllowed(helper, dropped, "Consumer roll " + i);
        ObjectArrayList<ItemStack> raw = new ObjectArrayList<>();
        table.getRandomItemsRaw(params, raw::add);
        assertOnlyAllowed(helper, raw, "Raw roll " + i);

        SimpleContainer container = new SimpleContainer(27);
        table.fill(container, params, i);
        helper.assertTrue(has(container, ALLOWED) && !has(container, BANNED), "Fill " + i + " does not hold exactly the allowed item");
      }
    }));
    helper.succeed();
  }

  // `extra_banned_items`: item ids and item tags ban, broken entries are ignored, an empty list means no source.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void configuredItemsAndTagsAreBanned(GameTestHelper helper) {
    LootTable table = table(helper, "banned_and_allowed");
    LootParams params = params(helper);
    List<String> entries = List.of("minecraft:barrier", "#minecraft:planks", "not a valid id!", "minecraft:no_such_item", "#minecraft:no_such_tag");

    withSettings(settings -> settings.withExtraBannedItems(entries), () -> {
      helper.assertTrue(ItemBans.isActive(), "A non-empty extra_banned_items list must activate the service");
      helper.assertTrue(ItemBans.isBanned(new ItemStack(Items.BARRIER)), "An item listed by id is not banned");
      helper.assertTrue(ItemBans.isBanned(new ItemStack(Items.OAK_PLANKS)), "An item of a listed tag is not banned");
      helper.assertTrue(!ItemBans.isBanned(new ItemStack(ALLOWED)), "An item that is not listed is banned");
      helper.assertTrue(!ItemBans.isBanned(ItemStack.EMPTY), "The empty stack is banned");
      ObjectArrayList<ItemStack> rolled = table.getRandomItems(params);
      helper.assertTrue(!has(rolled, BANNED) && has(rolled, ALLOWED), "The roll does not follow extra_banned_items");
    });

    helper.assertTrue(!ItemBans.isActive(), "The service is still active after the list was emptied again");
    helper.succeed();
  }

  // ----- helpers -----

  /**
   * Same shape as Item Obliterator's check (`public static boolean (ItemStack)`), to exercise the method handle call.
   */
  public static final class ObliteratorStandIn {
    private ObliteratorStandIn() {
    }

    public static boolean isDisabled(ItemStack stack) {
      return stack.is(BANNED);
    }

    public static boolean isDisabledByName(String name) {
      return false;
    }
  }

  private record SingleItemBan(String name, Item item) implements ItemBanSource {
    @Override
    public boolean isBanned(ItemStack stack) {
      return stack.is(item);
    }
  }

  /**
   * Records whether it is called from inside the item ban filter, i.e. whether the mixin wrapped it.
   */
  private static final class FilterProbe implements Consumer<ItemStack> {
    private int accepted = 0;
    private boolean calledThroughFilter = false;

    @Override
    public void accept(ItemStack stack) {
      accepted++;
      if (StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getClassName().startsWith(ItemBans.class.getName() + "$")))) {
        calledThroughFilter = true;
      }
    }
  }

  private static void whileBanned(String testName, Item item, Runnable body) {
    ItemBanSource source = new SingleItemBan("gametest:" + testName, item);
    ItemBans.register(source);
    try {
      body.run();
    } finally {
      ItemBans.unregister(source);
    }
  }

  private static void withSettings(UnaryOperator<ItemBanSettings> change, Runnable body) {
    ItemBanSettings original = ItemBans.getSettings();
    ItemBans.applySettings(change.apply(original));
    try {
      body.run();
    } finally {
      ItemBans.applySettings(original);
    }
  }

  /**
   * Places a chest with the test loot table, waits until Lootr converted it and then runs the body in one go.
   */
  private static void withLootrChest(GameTestHelper helper, long seed, BiConsumer<ILootrBlockEntity, ServerPlayer> body) {
    LootrTestSupport.placeLootContainer(helper, CHEST_POS, Blocks.CHEST, tableKey("chest"), seed);
    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, CHEST_POS, LootrRegistry.getChestBlock()))
        .thenExecute(() -> body.accept(LootrTestSupport.getLootrBlockEntity(helper, CHEST_POS), LootrTestSupport.createRealPlayer(helper)))
        .thenSucceed();
  }

  private static ResourceKey<LootTable> tableKey(String name) {
    return ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath("lootr_gametest", "item_ban/" + name));
  }

  private static LootTable table(GameTestHelper helper, String name) {
    ResourceKey<LootTable> key = tableKey(name);
    LootTable table = helper.getLevel().getServer().reloadableRegistries().getLootTable(key);
    if (table == LootTable.EMPTY) {
      throw new GameTestAssertException("Test loot table " + key.location() + " was not loaded");
    }
    return table;
  }

  private static LootParams params(GameTestHelper helper) {
    return new LootParams.Builder(helper.getLevel())
        .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(CHEST_POS)))
        .create(LootContextParamSets.CHEST);
  }

  private static LootrSavedData savedData(ILootrBlockEntity chest) {
    if (LootrAPI.getData(chest) instanceof LootrSavedData data) {
      return data;
    }
    throw new GameTestAssertException("Lootr saved data is missing for " + chest.getInfoPos());
  }

  private static boolean has(List<ItemStack> stacks, Item item) {
    return stacks.stream().anyMatch(stack -> stack.is(item));
  }

  private static boolean has(Container container, Item item) {
    return container.hasAnyMatching(stack -> stack.is(item));
  }

  private static void assertOnlyAllowed(GameTestHelper helper, List<ItemStack> rolled, String what) {
    helper.assertTrue(has(rolled, ALLOWED), what + " lost the allowed item");
    helper.assertTrue(!has(rolled, BANNED) && !has(rolled, NESTED_BANNED), what + " contains a banned item");
  }

  private static NonNullList<ItemStack> snapshot(Container container) {
    NonNullList<ItemStack> result = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
    for (int i = 0; i < result.size(); i++) {
      result.set(i, container.getItem(i).copy());
    }
    return result;
  }

  /**
   * Every slot that held the banned item is empty now, every other slot is exactly as it was.
   */
  private static void assertOnlyBannedRemoved(GameTestHelper helper, NonNullList<ItemStack> before, Container now, Item banned) {
    helper.assertTrue(now != null && now.getContainerSize() == before.size(), "The inventory is missing or changed its size");
    int removed = 0;
    for (int i = 0; i < before.size(); i++) {
      if (before.get(i).is(banned)) {
        helper.assertTrue(now.getItem(i).isEmpty(), "Slot " + i + " still holds the banned item");
        removed++;
      } else {
        helper.assertTrue(ItemStack.matches(before.get(i), now.getItem(i)), "Slot " + i + " changed although it did not hold a banned item");
      }
    }
    helper.assertTrue(removed > 0, "The inventory never contained the banned item");
  }
}
