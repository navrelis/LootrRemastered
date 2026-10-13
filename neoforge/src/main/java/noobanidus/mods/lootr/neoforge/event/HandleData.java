package noobanidus.mods.lootr.neoforge.event;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLoot;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions;
import noobanidus.mods.lootr.common.itemban.ItemBans;

@EventBusSubscriber(modid = LootrAPI.MODID)
public class HandleData {
  // Integrated loot: the listener only reads the pool and rule files. They are resolved against the loot tables
  // when the server announces the registries of the same data load, which is right after it put them in place
  // (on a reload in the same server thread task that swaps them in).
  @SubscribeEvent
  public static void onAddReloadListeners(AddReloadListenerEvent event) {
    event.addListener(new SimplePreparableReloadListener<IntegratedLootDefinitions>() {
      @Override
      protected IntegratedLootDefinitions prepare(ResourceManager manager, ProfilerFiller profiler) {
        return IntegratedLootDefinitions.load(manager);
      }

      @Override
      protected void apply(IntegratedLootDefinitions data, ResourceManager manager, ProfilerFiller profiler) {
        IntegratedLoot.setDefinitions(data);
      }
    });
  }

  @SubscribeEvent
  public static void onTagsUpdated(TagsUpdatedEvent event) {
    // Fires whenever data packs are (re)loaded, including `/reload`: forget which ban sources were reported
    // as failing and drop the parsed `extra_banned_items` (tags may have changed).
    ItemBans.onDataReload();
    if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
      IntegratedLoot.onDataLoaded(event.getRegistryAccess());
    }
  }
}
