package noobanidus.mods.lootr.common.itemban;

import net.minecraft.world.item.ItemStack;

/**
 * Something that can declare an item stack as banned. Sources are registered with {@link ItemBans}.
 * <br />
 * Implementations may be called from any thread (loot can be rolled off the server thread) and
 * must not rely on unsynchronised mutable state. They are only ever asked about non-empty stacks.
 */
public interface ItemBanSource {
  /**
   * @return a short, stable name. It is used for logging and to limit failure warnings to one per source.
   */
  String name();

  /**
   * @return true if the stack must not appear in loot. May throw; {@link ItemBans} treats a failing source as
   * "not banned" for that call.
   */
  boolean isBanned(ItemStack stack);
}
