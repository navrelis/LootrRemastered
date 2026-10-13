package noobanidus.mods.lootr.fabric.gametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.datafixers.util.Pair;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import noobanidus.mods.lootr.fabric.config.ConfigManager;
import noobanidus.mods.lootr.fabric.event.LootrEventsInit;
import noobanidus.mods.lootr.fabric.impl.PlatformAPIImpl;
import noobanidus.mods.lootr.gametest.platform.TestPlatform;
import noobanidus.mods.lootr.gametest.platform.TestPlatforms;

import java.util.List;

/**
 * The Fabric side of the shared game tests. It is the initialiser of the test mod, which installs it before any test runs.
 */
public class FabricTestPlatform implements TestPlatform, ModInitializer {
  @Override
  public void onInitialize() {
    TestPlatforms.install(this);
  }

  @Override
  public void fireTagsLoaded(MinecraftServer server) {
    CommonLifecycleEvents.TAGS_LOADED.invoker().onTagsLoaded(server.reloadableRegistries().get(), false);
  }

  @Override
  public void runWithoutServer(Runnable action) {
    MinecraftServer server = LootrEventsInit.serverInstance;
    LootrEventsInit.serverInstance = null;
    try {
      action.run();
    } finally {
      LootrEventsInit.serverInstance = server;
    }
  }

  @Override
  public void prepareMockPlayer(ServerPlayer player) {
    // Fabric API sends payloads to any connection; nothing to prepare.
  }

  @Override
  public void awaitSavedDataWrites() {
    // Saved data is written on the calling thread.
  }

  @Override
  public String dataPackId(String modId) {
    return modId;
  }

  @Override
  public void setDecayAll(boolean value) {
    ConfigManager.get().decay.decay_all = value;
  }

  @Override
  public void setRefreshAll(boolean value) {
    ConfigManager.get().refresh.refresh_all = value;
  }

  @Override
  public void setTickDelay(int value) {
    ConfigManager.get().conversion.tickDelay = value;
  }

  @Override
  public Pair<List<Integer>, List<Integer>> syncData(ServerPlayer player) {
    return new PlatformAPIImpl() {
      Pair<List<Integer>, List<Integer>> build() {
        return getSyncData(player);
      }
    }.build();
  }

  // GsonConfigSerializer reads the file with this Gson and `fromJson(reader, ConfigManager.class)`.
  @Override
  public void assertConfigWithRemovedKeysLoads(GameTestHelper helper) {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String json = "{\"conversion\": {\"save_mode\": \"ALWAYS\", \"tickDelay\": 7, \"disable\": true}, \"decay\": {\"decay_value\": 99}, \"removed_section\": {\"x\": [1, 2]}}";
    ConfigManager loaded;
    try {
      loaded = gson.fromJson(json, ConfigManager.class);
    } catch (RuntimeException e) {
      throw new GameTestAssertException("A configuration with removed keys does not load: " + e);
    }
    helper.assertTrue(loaded.conversion.tickDelay == 7 && loaded.conversion.disable, "Values next to the removed key were reset");
    helper.assertTrue(loaded.decay.decay_value == 99, "Values of another section were reset");
    helper.assertTrue(loaded.refresh.refresh_value == new ConfigManager().refresh.refresh_value, "A section that is not in the file lost its defaults");
  }

  @Override
  public boolean skipsFilesByFabricConditions() {
    return true;
  }
}
