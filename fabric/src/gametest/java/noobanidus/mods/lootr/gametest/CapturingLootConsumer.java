package noobanidus.mods.lootr.gametest;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The consumer the test mixin `MixinLootTableCapture` puts in place of the consumer argument of a loot roll: it
 * keeps the stacks of the roll and hands them to the original consumer when the mixin tells it to.
 */
public final class CapturingLootConsumer implements Consumer<ItemStack> {
  private static final AtomicLong RELEASES = new AtomicLong();
  // Per thread: loot can be rolled off the server thread.
  private static final ThreadLocal<Consumer<ItemStack>> TAP = new ThreadLocal<>();
  private static volatile boolean discarding = false;

  private final Consumer<ItemStack> original;
  private final List<ItemStack> captured = new ArrayList<>();

  public CapturingLootConsumer(Consumer<ItemStack> original) {
    this.original = original;
  }

  @Override
  public void accept(ItemStack stack) {
    Consumer<ItemStack> tap = TAP.get();
    if (tap != null) {
      tap.accept(stack);
    }
    captured.add(stack);
  }

  public void release() {
    RELEASES.incrementAndGet();
    if (!discarding) {
      captured.forEach(original);
    }
    captured.clear();
  }

  /**
   * While this is on, nothing that was captured is handed on, on any thread: every loot roll comes out empty, the
   * way it does when a capturing consumer never gets to hand on what it holds.
   */
  public static void setDiscarding(boolean value) {
    discarding = value;
  }

  /**
   * @return how many rolls were handed on so far. Lets a test make sure that the mixin is applied at all.
   */
  public static long releases() {
    return RELEASES.get();
  }

  /**
   * Runs the body while the tap sees every stack of this thread at the moment it is captured, which is where the
   * consumer argument of the roll is written to; the original consumer sees the stacks only after the roll.
   */
  public static void withTap(Consumer<ItemStack> tap, Runnable body) {
    TAP.set(tap);
    try {
      body.run();
    } finally {
      TAP.remove();
    }
  }
}
