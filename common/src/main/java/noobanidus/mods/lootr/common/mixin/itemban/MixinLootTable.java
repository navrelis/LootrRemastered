package noobanidus.mods.lootr.common.mixin.itemban;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.itemban.ItemBans;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Consumer;

/**
 * Every loot roll (`fill`, all `getRandomItems` overloads, nested tables) ends up in
 * `getRandomItemsRaw(LootContext, Consumer)`, which hands its output consumer to `LootItemFunction.decorate` and
 * lets the pools write to what comes back. The consumer is wrapped on its way into that call by one that drops
 * banned stacks; the stacks it sees already had the table's functions applied. When all-loot filtering is off the
 * consumer is handed on untouched.
 * <br />
 * The consumer argument of the method is left alone on purpose. Other mods replace it at the head of the method and
 * look at it again at the end, to hand on what they collected: whoever replaces it after them hides their consumer
 * from them, and the whole roll is lost. Here the filter sits between the table's functions and whatever consumer
 * the argument holds by then.
 * <br />
 * The hook is optional (`require = 0`): if the call cannot be found in some combination of mods, the game starts
 * and loot is rolled as usual, and {@link noobanidus.mods.lootr.common.itemban.LootRollHook} says so in the log.
 */
@Mixin(LootTable.class)
public class MixinLootTable {
  @ModifyArg(method = "getRandomItemsRaw(Lnet/minecraft/world/level/storage/loot/LootContext;Ljava/util/function/Consumer;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/functions/LootItemFunction;decorate(Ljava/util/function/BiFunction;Ljava/util/function/Consumer;Lnet/minecraft/world/level/storage/loot/LootContext;)Ljava/util/function/Consumer;"), index = 1, require = 0)
  private Consumer<ItemStack> lootr$dropBannedItems(Consumer<ItemStack> output) {
    return ItemBans.wrapLootConsumer(output);
  }
}
