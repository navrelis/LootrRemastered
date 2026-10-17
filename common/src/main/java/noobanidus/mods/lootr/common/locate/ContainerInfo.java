package noobanidus.mods.lootr.common.locate;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import org.jetbrains.annotations.Nullable;

/**
 * A container with a loot table as `/lootr check` and `/lootr locate` see it: a Lootr container or a vanilla one
 * that Lootr has not converted yet.
 *
 * @param typeId   the Lootr type (`lootr:chest`) or the id of the block entity or entity type for a vanilla container.
 * @param table    the loot table; null if the container has none (a custom inventory).
 * @param provider the Lootr container; null for a vanilla one.
 */
public record ContainerInfo(
    Kind kind,
    String typeId,
    ResourceKey<Level> dimension,
    BlockPos pos,
    boolean entity,
    @Nullable ResourceKey<LootTable> table,
    long seed,
    @Nullable ILootrInfoProvider provider) {

  public enum Kind {
    LOOTR,
    VANILLA
  }

  public boolean isLootr() {
    return kind == Kind.LOOTR;
  }

  public Vec3 center() {
    return Vec3.atCenterOf(pos);
  }

  /**
   * @return the squared distance of the centre of the container to the point.
   */
  public double distanceSqr(Vec3 from) {
    return center().distanceToSqr(from);
  }
}
