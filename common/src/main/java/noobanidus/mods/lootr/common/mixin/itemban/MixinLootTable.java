package noobanidus.mods.lootr.common.mixin.itemban;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.itemban.ItemBans;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

/**
 * Every loot roll (`fill`, all `getRandomItems` overloads, nested tables) ends up in
 * `getRandomItemsRaw(LootContext, Consumer)`. Its output consumer is replaced by one that drops banned
 * stacks; the stacks it sees already had the table's functions applied. When all-loot filtering is off the
 * consumer is handed back untouched.
 */
@Mixin(LootTable.class)
public class MixinLootTable {
  @ModifyVariable(method = "getRandomItemsRaw(Lnet/minecraft/world/level/storage/loot/LootContext;Ljava/util/function/Consumer;)V", at = @At("HEAD"), argsOnly = true)
  private Consumer<ItemStack> lootr$dropBannedItems(Consumer<ItemStack> output) {
    return ItemBans.wrapLootConsumer(output);
  }
}
