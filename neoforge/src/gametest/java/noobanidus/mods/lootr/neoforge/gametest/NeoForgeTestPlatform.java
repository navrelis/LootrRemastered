package noobanidus.mods.lootr.neoforge.gametest;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.mojang.datafixers.util.Pair;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import noobanidus.mods.lootr.common.api.ILootrAPI;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.gametest.platform.TestPlatform;
import noobanidus.mods.lootr.neoforge.config.ConfigManager;
import noobanidus.mods.lootr.neoforge.event.HandleData;
import noobanidus.mods.lootr.neoforge.impl.LootrAPIImpl;
import noobanidus.mods.lootr.neoforge.impl.PlatformAPIImpl;
import noobanidus.mods.lootr.neoforge.network.toClient.PacketAreaEntitySync;
import noobanidus.mods.lootr.neoforge.network.toClient.PacketCloseCart;
import noobanidus.mods.lootr.neoforge.network.toClient.PacketCloseContainer;
import noobanidus.mods.lootr.neoforge.network.toClient.PacketContainerStatus;
import noobanidus.mods.lootr.neoforge.network.toClient.PacketOpenCart;
import noobanidus.mods.lootr.neoforge.network.toClient.PacketOpenContainer;
import noobanidus.mods.lootr.neoforge.network.toClient.PacketPerformBreakEffect;
import noobanidus.mods.lootr.neoforge.network.toClient.PacketRefreshSection;
import noobanidus.mods.lootr.neoforge.network.toClient.PacketSyncConfig;

import java.util.List;
import java.util.Set;

/**
 * The NeoForge side of the shared game tests.
 */
public class NeoForgeTestPlatform implements TestPlatform {
  @Override
  public void fireTagsLoaded(MinecraftServer server) {
    // NeoForge posts this event after the tags of a data load were bound (ReloadableServerResources#updateRegistryTags).
    // Its other listeners expect a data load in progress (the data map loader fails without one), so Lootr's listener,
    // which the mod registers for the event, is called directly.
    HandleData.onTagsUpdated(new TagsUpdatedEvent(server.reloadableRegistries().get(), false, false));
  }

  @Override
  public void runWithoutServer(Runnable action) {
    ILootrAPI original = LootrAPI.INSTANCE;
    LootrAPI.INSTANCE = new LootrAPIImpl() {
      @Override
      public MinecraftServer getServer() {
        return null;
      }
    };
    try {
      action.run();
    } finally {
      LootrAPI.INSTANCE = original;
    }
  }

  // The payloads the mod sends to a client. NeoForge only sends a payload over a connection that announced the channel.
  @Override
  public void prepareMockPlayer(ServerPlayer player) {
    Set<ResourceLocation> channels = ChannelAttributes.getOrCreateAdHocChannels(player.connection.getConnection());
    for (CustomPacketPayload.Type<?> type : List.of(PacketOpenCart.TYPE, PacketOpenContainer.TYPE, PacketCloseCart.TYPE, PacketCloseContainer.TYPE, PacketRefreshSection.TYPE, PacketPerformBreakEffect.TYPE, PacketAreaEntitySync.TYPE, PacketSyncConfig.TYPE, PacketContainerStatus.TYPE)) {
      channels.add(type.id());
    }
  }

  @Override
  public void awaitSavedDataWrites() {
    IOUtilities.waitUntilIOWorkerComplete();
  }

  @Override
  public String dataPackId(String modId) {
    return "mod/" + modId;
  }

  @Override
  public void setDecayAll(boolean value) {
    ConfigManager.DECAY_ALL.set(value);
  }

  @Override
  public void setRefreshAll(boolean value) {
    ConfigManager.REFRESH_ALL.set(value);
  }

  @Override
  public void setTickDelay(int value) {
    ConfigManager.TICK_DELAY.set(value);
  }

  @Override
  public void setPersonalCycle(boolean enabled, double refreshIntervalHours, int refreshCount, boolean decayAfterLastRefresh, List<String> excludedLootTables, List<String> excludedDimensions, List<String> overrides) {
    ConfigManager.PERSONAL_CYCLE_ENABLED.set(enabled);
    ConfigManager.REFRESH_INTERVAL_HOURS.set(refreshIntervalHours);
    ConfigManager.REFRESH_COUNT.set(refreshCount);
    ConfigManager.DECAY_AFTER_LAST_REFRESH.set(decayAfterLastRefresh);
    ConfigManager.PERSONAL_CYCLE_EXCLUDED_LOOT_TABLES.set(List.copyOf(excludedLootTables));
    ConfigManager.PERSONAL_CYCLE_EXCLUDED_DIMENSIONS.set(List.copyOf(excludedDimensions));
    ConfigManager.PERSONAL_CYCLE_OVERRIDES.set(List.copyOf(overrides));
    ConfigManager.resetPersonalCycleSettings();
  }

  @Override
  public Pair<List<Integer>, List<Integer>> syncData(ServerPlayer player) {
    return new PlatformAPIImpl() {
      Pair<List<Integer>, List<Integer>> build() {
        return getSyncData(player);
      }
    }.build();
  }

  // The configuration is a TOML tree that the spec corrects: unknown keys are dropped, missing ones get their defaults.
  @Override
  public void assertConfigWithRemovedKeysLoads(GameTestHelper helper) {
    CommentedConfig config = CommentedConfig.inMemory();
    config.set("conversion.tick_delay", 7);
    config.set("conversion.disable", true);
    config.set("decay.decay_value", 99);
    config.set("removed_section.x", List.of(1, 2));
    try {
      ConfigManager.COMMON_CONFIG.correct(config);
    } catch (RuntimeException e) {
      throw new GameTestAssertException("A configuration with removed keys does not load: " + e);
    }
    helper.assertTrue(config.<Integer>get("conversion.tick_delay") == 7 && Boolean.TRUE.equals(config.get("conversion.disable")), "Values next to the removed key were reset");
    helper.assertTrue(config.<Integer>get("decay.decay_value") == 99, "Values of another section were reset");
    helper.assertTrue(config.<Integer>get("refresh.refresh_value").equals(ConfigManager.REFRESH_VALUE.getDefault()), "A section that is not in the file lost its defaults");
    helper.assertTrue(!config.contains("removed_section"), "The removed section was kept");
    helper.assertTrue(ConfigManager.COMMON_CONFIG.isCorrect(config), "The corrected configuration is not valid");
  }

  @Override
  public boolean skipsFilesByFabricConditions() {
    return false;
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean legacyIsNewTextures() {
    return ConfigManager.isNewTextures();
  }
}
