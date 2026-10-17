package noobanidus.mods.lootr.common.locate;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLoot;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.Decision;
import noobanidus.mods.lootr.common.verify.TableCheck;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What can be said about the loot table of a container without opening it: whether it is loaded, whether it can
 * produce anything (the static analysis of `/lootr verify`), what a few sample rolls give and whether integrated
 * loot extends it. Plain logic for the commands `/lootr check` and `/lootr locate`.
 */
public final class LootTableStatus {
  /**
   * How many sample rolls {@link #sample} does.
   */
  public static final int SAMPLE_ROLLS = 16;

  private LootTableStatus() {
  }

  public enum Kind {
    /**
     * The table is loaded and can produce items.
     */
    OK,
    /**
     * The container refers to a table that is not loaded.
     */
    MISSING,
    /**
     * The table is loaded but has no pools or can never produce an item.
     */
    EMPTY,
    /**
     * The container has no loot table (a custom inventory).
     */
    NONE
  }

  /**
   * @param reason for {@link Kind#EMPTY} why the table cannot produce (a verification reason such as `no_pools`),
   *               else an empty string.
   */
  public record Result(Kind kind, String reason) {
    public static final Result OK = new Result(Kind.OK, "");
    public static final Result MISSING = new Result(Kind.MISSING, "");
    public static final Result NONE = new Result(Kind.NONE, "");
  }

  /**
   * @param error the message of the roll that threw, which ended the sampling; null if none did.
   */
  public record Sample(int rolls, int emptyRolls, int stacks, @Nullable String error) {
    public double averageStacks() {
      return rolls == 0 ? 0.0 : (double) stacks / rolls;
    }
  }

  /**
   * Remembers the status of every table for the duration of one command, so that a table that many containers use
   * is judged once.
   */
  public static final class Cache {
    private final MinecraftServer server;
    private final Map<ResourceKey<LootTable>, Result> results = new HashMap<>();

    public Cache(MinecraftServer server) {
      this.server = server;
    }

    public Result get(@Nullable ResourceKey<LootTable> table) {
      if (table == null) {
        return Result.NONE;
      }
      return results.computeIfAbsent(table, key -> LootTableStatus.of(server, key));
    }
  }

  /**
   * Must be called on the server thread.
   *
   * @param table the table of the container; null if it has none.
   */
  public static Result of(MinecraftServer server, @Nullable ResourceKey<LootTable> table) {
    if (table == null) {
      return Result.NONE;
    }
    if (!server.reloadableRegistries().getKeys(Registries.LOOT_TABLE).contains(table.location())) {
      return Result.MISSING;
    }
    // The built-in empty table is what the game uses for a table it does not know.
    if (server.reloadableRegistries().getLootTable(table) == LootTable.EMPTY) {
      return Result.MISSING;
    }
    Optional<String> reason = TableCheck.cannotProduceReason(server.reloadableRegistries().get(), table.location());
    return reason.map(text -> new Result(Kind.EMPTY, text)).orElse(Result.OK);
  }

  /**
   * Rolls the table {@link #SAMPLE_ROLLS} times with the container's position as the origin and without a player,
   * so without luck. Explorer maps are not created. The seeds are derived from the table id: the same data gives
   * the same sample.
   */
  public static Sample sample(ServerLevel level, ResourceKey<LootTable> table, Vec3 origin) {
    LootTable lootTable = level.getServer().reloadableRegistries().getLootTable(table);
    LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, origin).create(LootContextParamSets.CHEST);
    int rolls = 0;
    int empty = 0;
    int stacks = 0;
    for (int i = 0; i < SAMPLE_ROLLS; i++) {
      // A seed of 0 would make the game roll with the level's shared random.
      long seed = table.location().toString().hashCode() * 31L + i + 1;
      int[] count = new int[1];
      try {
        IntegratedLoot.withoutMapCreation(() -> lootTable.getRandomItems(params, seed == 0L ? 1L : seed, stack -> {
          if (!stack.isEmpty()) {
            count[0]++;
          }
        }));
      } catch (Exception | LinkageError | StackOverflowError e) {
        return new Sample(rolls, empty, stacks, e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage());
      }
      rolls++;
      stacks += count[0];
      if (count[0] == 0) {
        empty++;
      }
    }
    return new Sample(rolls, empty, stacks, null);
  }

  public enum IntegratedState {
    /**
     * Rolls of the table are extended by a pool.
     */
    EXTENDED,
    /**
     * A rule extends the table, but integrated loot is switched off in the configuration.
     */
    SWITCHED_OFF,
    /**
     * A rule excludes the table.
     */
    EXCLUDED,
    /**
     * A rule matches but its pool is not usable.
     */
    RULE_PROBLEM,
    /**
     * A rule matches, but tables of this type are not extended.
     */
    OTHER_TYPE,
    /**
     * No rule matches.
     */
    NONE
  }

  /**
   * @param detail the pool for {@link IntegratedState#EXTENDED} and {@link IntegratedState#SWITCHED_OFF}, the
   *               problem for {@link IntegratedState#RULE_PROBLEM}, the table type for {@link IntegratedState#OTHER_TYPE},
   *               else an empty string.
   */
  public record Integrated(IntegratedState state, String detail) {
    public boolean extended() {
      return state == IntegratedState.EXTENDED;
    }
  }

  /**
   * Says whether integrated loot extends the table, as `/lootr integrated &lt;loot_table&gt;` does.
   */
  public static Integrated integrated(@Nullable ResourceKey<LootTable> table) {
    if (table == null) {
      return new Integrated(IntegratedState.NONE, "");
    }
    IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
    Optional<Decision> found = snapshot.decisionFor(table);
    if (found.isEmpty()) {
      return snapshot.ineligibleMatches().stream().filter(match -> match.table().equals(table)).findFirst()
          .map(match -> new Integrated(IntegratedState.OTHER_TYPE, match.type()))
          .orElse(new Integrated(IntegratedState.NONE, ""));
    }
    Decision decision = found.get();
    if (decision.exclusion()) {
      return new Integrated(IntegratedState.EXCLUDED, "");
    }
    if (decision.pool().isEmpty()) {
      return new Integrated(IntegratedState.RULE_PROBLEM, decision.problem().orElse(""));
    }
    String pool = decision.poolId().map(Object::toString).orElse("");
    return new Integrated(IntegratedLoot.getSettings().enabled() ? IntegratedState.EXTENDED : IntegratedState.SWITCHED_OFF, pool);
  }
}
