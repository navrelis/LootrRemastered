package noobanidus.mods.lootr.fabric.config;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.minecraft.world.InteractionResult;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.PlatformAPI;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLoot;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSettings;
import noobanidus.mods.lootr.common.integratedloot.LootIntegrationsImport;
import noobanidus.mods.lootr.common.itemban.ItemBans;

import java.util.Optional;

public class LootrConfigInit {
  public static void registerConfig() {
    AutoConfig.register(ConfigManager.class, GsonConfigSerializer::new);
    ConfigHolder<ConfigManager> config = AutoConfig.getConfigHolder(ConfigManager.class);
    // Before the listeners are registered: it saves the configuration, and the save listener is not meant to
    // run while the mod is still initialising.
    importLootIntegrationsConfig(config);
    config.registerLoadListener((manager, configData) -> {
      configData.reset();
      ItemBans.applySettings(configData.itemBanSettings());
      IntegratedLoot.applySettings(configData.integratedLootSettings());
      return InteractionResult.PASS;
    });
    config.registerSaveListener((manager, configData) -> {
      configData.reset();
      ItemBans.applySettings(configData.itemBanSettings());
      IntegratedLoot.applySettings(configData.integratedLootSettings());
      return InteractionResult.PASS;
    });
    // The first load happens inside `register`, before the listeners exist. This registers the item ban
    // sources that the configuration asks for; the listeners above re-evaluate them on every load and save.
    ItemBans.applySettings(config.getConfig().itemBanSettings());
    IntegratedLoot.applySettings(config.getConfig().integratedLootSettings());
  }

  // Takes over the settings of `lootintegrations.json` once; the flag in our own configuration remembers it.
  private static void importLootIntegrationsConfig(ConfigHolder<ConfigManager> holder) {
    ConfigManager config = holder.getConfig();
    if (config.integrated_loot == null) {
      config.integrated_loot = new ConfigManager.IntegratedLootOptions();
    }
    ConfigManager.IntegratedLootOptions options = config.integrated_loot;
    if (options.imported_loot_integrations_config) {
      return;
    }
    Optional<LootIntegrationsImport.Result> result = LootIntegrationsImport.read(PlatformAPI.getConfigDirectory(), config.integratedLootSettings());
    if (result.isEmpty()) {
      return;
    }
    IntegratedLootSettings settings = result.get().settings();
    options.modded_item_weight = settings.moddedItemWeight();
    options.skip_existing_items = settings.skipExistingItems();
    options.skip_map_items = settings.skipMapItems();
    options.debug_log = settings.debugLog();
    options.imported_loot_integrations_config = true;
    holder.save();
    LootrAPI.LOG.info("Integrated loot: took over the settings of {} once: {}.", LootIntegrationsImport.FILE_NAME, result.get().takenOver().isEmpty() ? "it sets nothing that applies" : String.join(", ", result.get().takenOver()));
  }
}
