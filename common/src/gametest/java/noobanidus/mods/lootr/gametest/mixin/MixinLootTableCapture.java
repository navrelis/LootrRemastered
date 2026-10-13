package noobanidus.mods.lootr.gametest.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.gametest.CapturingLootConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Test only. Stands in for a mod that takes over the output of every loot roll: at the head of the method the
 * consumer argument is replaced by a capturing one, and at the end the captured stacks are handed to the original
 * consumer, but only if the argument still is the capturing one. A roll comes out empty as soon as anything else
 * replaces the argument after this mixin did.
 * <br />
 * The priority applies this mixin before Lootr's, so that whatever Lootr does at the head of the method runs after
 * the capture: the order in which such a conflict shows.
 */
@Mixin(value = LootTable.class, priority = 900)
public class MixinLootTableCapture {
  @ModifyVariable(method = "getRandomItemsRaw(Lnet/minecraft/world/level/storage/loot/LootContext;Ljava/util/function/Consumer;)V", at = @At("HEAD"), argsOnly = true)
  private Consumer<ItemStack> lootrGametest$captureLoot(Consumer<ItemStack> output) {
    return new CapturingLootConsumer(output);
  }

  @Inject(method = "getRandomItemsRaw(Lnet/minecraft/world/level/storage/loot/LootContext;Ljava/util/function/Consumer;)V", at = @At("RETURN"))
  private void lootrGametest$releaseLoot(LootContext context, Consumer<ItemStack> output, CallbackInfo ci) {
    if (output instanceof CapturingLootConsumer capturing) {
      capturing.release();
    }
  }
}
