package noobanidus.mods.lootr.common.itemban;

import net.minecraft.world.item.ItemStack;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.PlatformAPI;
import org.jetbrains.annotations.VisibleForTesting;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Optional;

/**
 * Asks the mod Item Obliterator whether a stack is disabled. Lootr only calls into that mod; none of its
 * code is used here.
 * <br />
 * Item Obliterator reads its live configuration (reloaded on `/reload`), so answers are never cached.
 */
public final class ObliteratorBanSource implements ItemBanSource {
  public static final String MOD_ID = "item_obliterator";
  public static final String NAME = MOD_ID;

  // Item Obliterator names the class after the loader it was built for (Fabric/Quilt, NeoForge, Forge).
  private static final List<String> UTILS_CLASSES = List.of(
      "elocindev.item_obliterator.fabric_quilt.util.Utils",
      "elocindev.item_obliterator.neoforge.utils.Utils",
      "elocindev.item_obliterator.forge.utils.Utils"
  );
  private static final String IS_DISABLED = "isDisabled";

  private final MethodHandle isDisabled;

  private ObliteratorBanSource(MethodHandle isDisabled) {
    this.isDisabled = isDisabled;
  }

  /**
   * @return the source, or empty if Item Obliterator is not installed or its check could not be resolved.
   * Never throws.
   */
  public static Optional<ItemBanSource> create() {
    try {
      if (!PlatformAPI.isModLoaded(MOD_ID)) {
        return Optional.empty();
      }
    } catch (Throwable t) {
      LootrAPI.LOG.warn("Unable to determine whether Item Obliterator is installed; its item bans will not be applied to Lootr loot.", t);
      return Optional.empty();
    }
    for (String className : UTILS_CLASSES) {
      if (exists(className)) {
        return resolve(className, IS_DISABLED);
      }
    }
    LootrAPI.LOG.warn("Item Obliterator is installed but none of its classes {} was found; its item bans will not be applied to Lootr loot.", UTILS_CLASSES);
    return Optional.empty();
  }

  private static boolean exists(String className) {
    try {
      Class.forName(className, false, ObliteratorBanSource.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    } catch (Throwable t) {
      // The class is there but cannot be loaded; resolving it reports why.
      return true;
    }
  }

  /**
   * Resolves `public static boolean methodName(ItemStack)` on the named class.
   * <br />
   * A method handle lookup is used on purpose: it links only this one method. Reflection
   * (`getMethod`/`getDeclaredMethods`) resolves every signature of the class and fails with a
   * NoClassDefFoundError when an optional mod referenced by another overload (EMI) is absent.
   * The parameter type is the class literal so that it survives remapping.
   */
  @VisibleForTesting
  public static Optional<ItemBanSource> resolve(String className, String methodName) {
    try {
      Class<?> owner = Class.forName(className, false, ObliteratorBanSource.class.getClassLoader());
      MethodHandle handle = MethodHandles.publicLookup()
          .findStatic(owner, methodName, MethodType.methodType(boolean.class, ItemStack.class));
      return Optional.of(new ObliteratorBanSource(handle));
    } catch (Throwable t) {
      // Can be a LinkageError, hence Throwable.
      LootrAPI.LOG.warn("Item Obliterator is installed but '{}#{}' could not be resolved; its item bans will not be applied to Lootr loot.", className, methodName, t);
      return Optional.empty();
    }
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isBanned(ItemStack stack) {
    try {
      return (boolean) isDisabled.invokeExact(stack);
    } catch (RuntimeException | Error e) {
      // Reported (once) and treated as "not banned" by ItemBans.
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }
}
