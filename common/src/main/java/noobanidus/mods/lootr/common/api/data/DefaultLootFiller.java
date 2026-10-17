package noobanidus.mods.lootr.common.api.data;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.cycle.PersonalCycle;
import noobanidus.mods.lootr.common.itemban.ItemBans;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DefaultLootFiller implements LootFiller {
  private static final DefaultLootFiller INSTANCE = new DefaultLootFiller();
  // Per thread: loot tables are also rolled on other threads (world generation), which must not see the
  // state of a fill that is running on the server thread.
  private static final ThreadLocal<LootFillerState> STATE = new ThreadLocal<>();

  public static DefaultLootFiller getInstance() {
    return INSTANCE;
  }

  @Nullable
  public static LootFillerState getFillerState() {
    return STATE.get();
  }

  @Override
  public void unpackLootTable(ILootrInfoProvider provider, @NotNull Player player, Container inventory) {
    Level level = provider.getInfoLevel();
    if (level == null || level.isClientSide() || level.getServer() == null) {
      LootrAPI.LOG.error("Unable to fill loot container as the level is null, client-side, or the server is null!");
      return;
    }
    BlockPos pos = provider.getInfoPos();
    ResourceKey<LootTable> lootTable = provider.getInfoLootTable();
    if (provider.isInfoReferenceInventory() && provider.getInfoReferenceInventory() != null) {
      boolean checkBans = ItemBans.isActive();
      for (int i = 0; i < provider.getInfoReferenceInventory().size(); i++) {
        ItemStack stack = provider.getInfoReferenceInventory().get(i);
        if (checkBans && ItemBans.isBanned(stack)) {
          // Banned stacks are not handed to the player; the stored reference itself stays untouched.
          continue;
        }
        inventory.setItem(i, stack.copy());
      }
    } else if (lootTable == null) {
      LootrAPI.LOG.error("Unable to fill loot container in {} at {} as the loot table is null and the provider is not a reference inventory!", level.dimension()
          .location(), pos);
    } else {
      // A re-roll of the personal cycle must not repeat the loot when the seed is not randomised.
      long seed = PersonalCycle.rerollSeed(provider.getInfoLootSeed(), LootrAPI.getLootSeed(provider.getInfoLootSeed()));
      LootTable loottable = level.getServer().reloadableRegistries().getLootTable(lootTable);

      if (loottable == LootTable.EMPTY) {
        LootrAPI.LOG.error("Unable to fill loot container in {} at {} as the loot table '{}' couldn't be resolved! Please search the loot table in `latest.log` to see if there are errors in loading.", level.dimension()
            .location(), pos, lootTable.location());
        if (LootrAPI.reportUnresolvedTables()) {
          player.displayClientMessage(LootrAPI.getInvalidTableComponent(lootTable), false);
        }
      }

      if (player instanceof ServerPlayer sPlayer) {
        CriteriaTriggers.GENERATE_LOOT.trigger(sPlayer, lootTable);
      }

      LootParams.Builder builder = new LootParams.Builder((ServerLevel) level)
          .withParameter(LootContextParams.ORIGIN, provider.getInfoVec())
          .withLuck(player.getLuck()).withParameter(LootContextParams.THIS_ENTITY, player);

      fill(provider, player, lootTable, loottable, inventory, builder.create(LootContextParamSets.CHEST), seed);
    }
  }

  // This is used to allow for storing the state during the loot filling process, allowing for our loot table mixin to apply
  public static void performFill(ILootrInfoProvider provider, Player player, ResourceKey<LootTable> lootTableKey, LootTable lootTable, Container container, LootParams parameters, long seed) {
    setFillerState(new LootFillerState(provider, player, lootTableKey, lootTable, container, parameters, seed));
    try {
      lootTable.fill(container, parameters, seed);
    } finally {
      setFillerState(null);
    }
    // Lootr's own fills never keep banned stacks, also when not every loot roll is filtered (`filter_all_loot`)
    // or when something added stacks after the roll. Removal is permanent by design.
    if (ItemBans.isActive()) {
      ItemBans.scrub(container);
    }
  }

  public static void setFillerState(@Nullable LootFillerState newState) {
    if (newState == null) {
      STATE.remove();
    } else {
      STATE.set(newState);
    }
  }
}
