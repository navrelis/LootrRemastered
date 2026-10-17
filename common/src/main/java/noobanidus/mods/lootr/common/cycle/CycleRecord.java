package noobanidus.mods.lootr.common.cycle;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The personal cycle of one player (or team) in one container, stored in the container's saved data as an entry of
 * the `cycles` list: `{id: UUID, start: long, refreshes: int, decayed: boolean}`, plus `exempt: true` when the cycle
 * started inside a structure of the tag `lootr:personal_cycle_exempt`.
 * <br />
 * The schedule is not stored: it follows from `start` and the configuration in force when the player opens.
 */
public final class CycleRecord {
  private long start;
  private int refreshes;
  private boolean decayed;
  private final boolean exempt;

  public CycleRecord(long start, int refreshes, boolean decayed, boolean exempt) {
    this.start = start;
    this.refreshes = Math.max(0, refreshes);
    this.decayed = decayed;
    this.exempt = exempt;
  }

  public long start() {
    return start;
  }

  public int refreshes() {
    return refreshes;
  }

  public boolean decayed() {
    return decayed;
  }

  // Inside an exempt structure: the record only remembers that decision.
  public boolean exempt() {
    return exempt;
  }

  void setStart(long start) {
    this.start = start;
  }

  void setRefreshes(int refreshes) {
    this.refreshes = Math.max(0, refreshes);
  }

  void setDecayed(boolean decayed) {
    this.decayed = decayed;
  }

  public CompoundTag save(UUID id) {
    CompoundTag tag = new CompoundTag();
    tag.putUUID("id", id);
    tag.putLong("start", start);
    tag.putInt("refreshes", refreshes);
    tag.putBoolean("decayed", decayed);
    if (exempt) {
      tag.putBoolean("exempt", true);
    }
    return tag;
  }

  @Nullable
  public static CycleRecord load(CompoundTag tag) {
    if (!tag.hasUUID("id") || !tag.contains("start", Tag.TAG_LONG)) {
      return null;
    }
    return new CycleRecord(tag.getLong("start"), tag.getInt("refreshes"), tag.getBoolean("decayed"), tag.getBoolean("exempt"));
  }
}
