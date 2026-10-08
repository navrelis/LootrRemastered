package noobanidus.mods.lootr.common.mixin.integratedloot;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLoot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Creating an explorer map searches the world for a structure, which can take very long. While a source table is
 * rolled for an integrated loot extension the map is dropped instead; every other roll is untouched.
 */
@Mixin(ExplorationMapFunction.class)
public class MixinExplorationMapFunction {
  @Inject(method = "run", at = @At("HEAD"), cancellable = true)
  private void lootr$skipMapForIntegratedLoot(ItemStack stack, LootContext context, CallbackInfoReturnable<ItemStack> cir) {
    if (stack.is(Items.MAP) && IntegratedLoot.suppressMapCreation()) {
      cir.setReturnValue(ItemStack.EMPTY);
    }
  }
}
