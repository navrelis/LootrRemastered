package noobanidus.mods.lootr.common.mixin.integratedloot;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLoot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A loot table produces a result list in exactly four places: `fill` and the three public list-returning
 * `getRandomItems` overloads, all of which call the private `getRandomItems(LootContext)`. Each of the four is
 * hooked once, so every list roll is extended at most once, and the `Consumer` overloads (mob drops, nested table
 * references) are never touched.
 * <br />
 * `fill` is hooked where vanilla hands the result to `shuffleAndSplitItems`, because that call carries the
 * number of empty slots of the container; the overloads are hooked at their call of the private method, which
 * carries the roll's loot context. Only method arguments are captured, no locals.
 */
@Mixin(LootTable.class)
public class MixinLootTable {
  @WrapOperation(method = "fill", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/LootTable;shuffleAndSplitItems(Lit/unimi/dsi/fastutil/objects/ObjectArrayList;ILnet/minecraft/util/RandomSource;)V"))
  private void lootr$extendFill(LootTable table, ObjectArrayList<ItemStack> stacks, int emptySlots, RandomSource random, Operation<Void> original, @Local(argsOnly = true) LootParams params) {
    IntegratedLoot.extendFill(table, params, random, stacks, emptySlots);
    original.call(table, stacks, emptySlots, random);
  }

  @WrapOperation(method = {
      "getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;Lnet/minecraft/util/RandomSource;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",
      "getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;J)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",
      "getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"
  }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootContext;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"), require = 3)
  private ObjectArrayList<ItemStack> lootr$extendList(LootTable table, LootContext context, Operation<ObjectArrayList<ItemStack>> original, @Local(argsOnly = true) LootParams params) {
    ObjectArrayList<ItemStack> stacks = original.call(table, context);
    IntegratedLoot.extendList(table, params, context.getRandom(), stacks);
    return stacks;
  }
}
