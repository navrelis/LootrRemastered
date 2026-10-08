package noobanidus.mods.lootr.common.itemban;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Knows whether the hook that filters every loot roll (`mixin.itemban.MixinLootTable`) made it into the loot table
 * class. The hook is optional, so that a combination of mods in which it cannot be applied still starts; this
 * class looks at the loot table class once all mixins were applied to it and reports a missing hook with one line.
 * <br />
 * It is the plugin of Lootr's mixin configuration and runs while game classes are being loaded: it must not
 * refer to any class of the game or to a class of Lootr that does.
 */
public final class LootRollHook implements IMixinConfigPlugin {
  private static final Logger LOG = LogManager.getLogger();
  private static final String MIXIN = "noobanidus.mods.lootr.common.mixin.itemban.MixinLootTable";
  // Mixin keeps the name of a handler method at the end of the name it gives it in the target class.
  private static final String HANDLER = "lootr$dropBannedItems";

  private static volatile boolean missing = false;

  /**
   * @return true once it is known that loot rolls do not pass through the item ban filter. Lootr's own container
   * fills do not depend on the hook.
   */
  public static boolean isMissing() {
    return missing;
  }

  @Override
  public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    if (!MIXIN.equals(mixinClassName) || missing || callsHandler(targetClass)) {
      return;
    }
    missing = true;
    LOG.warn("Lootr could not hook the loot roll of {}, most likely because another mod changes it: banned items are NOT removed from loot rolls outside Lootr containers, whatever 'item_bans.filter_all_loot' says. Lootr containers are still filtered, and loot is rolled as usual.", targetClassName);
  }

  private static boolean callsHandler(ClassNode targetClass) {
    for (MethodNode method : targetClass.methods) {
      for (AbstractInsnNode instruction : method.instructions) {
        if (instruction instanceof MethodInsnNode call && call.owner.equals(targetClass.name) && call.name.endsWith(HANDLER)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public void onLoad(String mixinPackage) {
  }

  @Override
  public String getRefMapperConfig() {
    return null;
  }

  @Override
  public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
    return true;
  }

  @Override
  public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
  }

  @Override
  public List<String> getMixins() {
    return null;
  }

  @Override
  public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
  }
}
