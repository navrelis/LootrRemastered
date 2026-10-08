package noobanidus.mods.lootr.common.integratedloot;

/**
 * Immutable snapshot of the `integrated_loot` configuration section. The platform pushes a new snapshot into
 * {@link IntegratedLoot#applySettings(IntegratedLootSettings)} whenever its configuration is loaded or saved.
 *
 * @param enabled           Master switch. When off no loot roll is extended.
 * @param moddedItemWeight  Draw weight of candidates whose item is not in the `minecraft` namespace (vanilla items weigh 1).
 * @param skipExistingItems Do not add an item that the extended roll already contains.
 * @param skipMapItems      Do not create explorer maps (structure searches) while rolling a source table.
 * @param maxExtraStacks    Upper limit of stacks added by one extension, whatever the rule asks for.
 * @param debugLog          Log one line per extension.
 */
public record IntegratedLootSettings(boolean enabled, int moddedItemWeight, boolean skipExistingItems, boolean skipMapItems, int maxExtraStacks, boolean debugLog) {
  public static final int MIN_MODDED_ITEM_WEIGHT = 1;
  // Keeps the sum of candidate weights far away from an int overflow.
  public static final int MAX_MODDED_ITEM_WEIGHT = 1000;
  public static final int MIN_EXTRA_STACKS = 0;
  public static final int MAX_EXTRA_STACKS = 9;

  public static final IntegratedLootSettings DEFAULT = new IntegratedLootSettings(true, 2, true, true, 4, false);

  public IntegratedLootSettings {
    moddedItemWeight = Math.clamp(moddedItemWeight, MIN_MODDED_ITEM_WEIGHT, MAX_MODDED_ITEM_WEIGHT);
    maxExtraStacks = Math.clamp(maxExtraStacks, MIN_EXTRA_STACKS, MAX_EXTRA_STACKS);
  }

  public IntegratedLootSettings withEnabled(boolean value) {
    return new IntegratedLootSettings(value, moddedItemWeight, skipExistingItems, skipMapItems, maxExtraStacks, debugLog);
  }

  public IntegratedLootSettings withModdedItemWeight(int value) {
    return new IntegratedLootSettings(enabled, value, skipExistingItems, skipMapItems, maxExtraStacks, debugLog);
  }

  public IntegratedLootSettings withSkipExistingItems(boolean value) {
    return new IntegratedLootSettings(enabled, moddedItemWeight, value, skipMapItems, maxExtraStacks, debugLog);
  }

  public IntegratedLootSettings withSkipMapItems(boolean value) {
    return new IntegratedLootSettings(enabled, moddedItemWeight, skipExistingItems, value, maxExtraStacks, debugLog);
  }

  public IntegratedLootSettings withMaxExtraStacks(int value) {
    return new IntegratedLootSettings(enabled, moddedItemWeight, skipExistingItems, skipMapItems, value, debugLog);
  }

  public IntegratedLootSettings withDebugLog(boolean value) {
    return new IntegratedLootSettings(enabled, moddedItemWeight, skipExistingItems, skipMapItems, maxExtraStacks, value);
  }
}
