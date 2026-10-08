package noobanidus.mods.lootr.fabric.config;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.minecraft.world.InteractionResult;
import noobanidus.mods.lootr.common.itemban.ItemBans;

public class LootrConfigInit {
  public static void registerConfig() {
    AutoConfig.register(ConfigManager.class, GsonConfigSerializer::new);
    ConfigHolder<ConfigManager> config = AutoConfig.getConfigHolder(ConfigManager.class);
    config.registerLoadListener((manager, configData) -> {
      configData.reset();
      ItemBans.applySettings(configData.itemBanSettings());
      return InteractionResult.PASS;
    });
    config.registerSaveListener((manager, configData) -> {
      configData.reset();
      ItemBans.applySettings(configData.itemBanSettings());
      return InteractionResult.PASS;
    });
    // The first load happens inside `register`, before the listeners exist. This registers the item ban
    // sources that the configuration asks for; the listeners above re-evaluate them on every load and save.
    ItemBans.applySettings(config.getConfig().itemBanSettings());
  }
}
