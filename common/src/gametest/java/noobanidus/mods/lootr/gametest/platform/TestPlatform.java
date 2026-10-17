package noobanidus.mods.lootr.gametest.platform;

import com.mojang.datafixers.util.Pair;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Everything the shared game tests need from the loader they run on. Each loader's test layer implements it once and
 * installs it with {@link TestPlatforms#install} while the test mod initialises.
 */
public interface TestPlatform {
  /**
   * Announces freshly loaded tags the way the loader does after a data load, so that everything Lootr does on a data
   * reload runs: Fabric API's `CommonLifecycleEvents.TAGS_LOADED` on Fabric, `TagsUpdatedEvent` on NeoForge.
   */
  void fireTagsLoaded(MinecraftServer server);

  /**
   * Runs the action while Lootr has no server registered, as it is the case before a server started.
   */
  void runWithoutServer(Runnable action);

  /**
   * Called for every player the tests create, right after the player joined. The loader's layer makes the player's
   * connection accept what a client would: the mock player has no client behind it, which NeoForge does not accept
   * for the custom payloads Lootr sends.
   */
  void prepareMockPlayer(ServerPlayer player);

  /**
   * Returns when the files of the saved data that `DimensionDataStorage#save` wrote are on disk. A loader that writes
   * them on another thread waits for that thread.
   */
  void awaitSavedDataWrites();

  /**
   * The id the loader gives the data pack of a mod, as the verification report lists it (`lootr_gametest` on Fabric,
   * `mod/lootr_gametest` on NeoForge).
   */
  String dataPackId(String modId);

  /**
   * Sets the configuration option that makes every container decay (the value is read back through `LootrAPI`).
   */
  void setDecayAll(boolean value);

  /**
   * Sets the configuration option that makes every container refresh (the value is read back through `LootrAPI`).
   */
  void setRefreshAll(boolean value);

  /**
   * Sets the delay between the ticks of a container (the value is read back through `LootrAPI`).
   */
  void setTickDelay(int value);

  /**
   * Sets the options of the `personal_cycle` section (read back through `LootrAPI.getPersonalCycleSettings`) and drops
   * the parsed copy, so that the next read sees them.
   */
  void setPersonalCycle(boolean enabled, double refreshIntervalHours, int refreshCount, boolean decayAfterLastRefresh, List<String> excludedLootTables, List<String> excludedDimensions, List<String> overrides);

  /**
   * The sync data that is sent to a player after a team change: the ids of the opened and of the closed entities.
   * The loader's platform implementation builds it with a protected method.
   */
  Pair<List<Integer>, List<Integer>> syncData(ServerPlayer player);

  /**
   * Checks that a configuration which still holds options that were removed (a section and a key) loads, keeps the
   * values next to them and fills the rest with the defaults. The configuration formats of the loaders differ, so
   * each one checks its own.
   */
  void assertConfigWithRemovedKeysLoads(GameTestHelper helper);

  /**
   * Whether the loader skips data files that carry a `fabric:load_conditions` entry with an unmet condition. A loader
   * that does not reads the entry as unknown data. (Every loader skips files by the condition form of its own API only.)
   */
  boolean skipsFilesByFabricConditions();
}
