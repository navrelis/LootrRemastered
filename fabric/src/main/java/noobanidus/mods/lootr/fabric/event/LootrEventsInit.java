package noobanidus.mods.lootr.fabric.event;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.fabric.api.resource.SimpleResourceReloadListener;
import net.fabricmc.fabric.impl.resource.loader.ResourceManagerHelperImpl;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.block.entity.BlockEntityTicker;
import noobanidus.mods.lootr.common.chunk.LoadedChunks;
import noobanidus.mods.lootr.common.command.CommandLootr;
import noobanidus.mods.lootr.common.data.DataStorage;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLoot;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions;
import noobanidus.mods.lootr.common.itemban.ItemBans;
import noobanidus.mods.lootr.common.verify.LootVerifier;
import noobanidus.mods.lootr.fabric.config.ConfigManager;
import noobanidus.mods.lootr.fabric.network.to_client.PacketSyncConfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class LootrEventsInit {
  public static MinecraftServer serverInstance;

  public static void registerEvents() {
    ServerLifecycleEvents.SERVER_STARTING.register(server -> {
      serverInstance = server;
      LoadedChunks.clear();
      BlockEntityTicker.clear();
      IntegratedLoot.onServerStarting(server);
    });

    ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
      serverInstance = null;
      LoadedChunks.clear();
      BlockEntityTicker.clear();
      IntegratedLoot.onServerStopped();
    });

    // Loot table verification: the run requested by -Dlootr.verify, and no run outlives its server.
    ServerLifecycleEvents.SERVER_STARTED.register(LootVerifier::onServerStarted);
    ServerLifecycleEvents.SERVER_STOPPING.register(LootVerifier::onServerStopping);

    ServerTickEvents.END_SERVER_TICK.register(BlockEntityTicker::onServerTick);

    ServerChunkEvents.CHUNK_LOAD.register(LoadedChunks::onChunkLoad);
    ServerChunkEvents.CHUNK_UNLOAD.register(LoadedChunks::onChunkUnload);

    PlayerBlockBreakEvents.BEFORE.register(HandleBreak::beforeBlockBreak);

    CommandRegistrationCallback.EVENT.register((dispatcher, reg, env) -> {
      CommandLootr.register(dispatcher);
    });

    ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
      if (server.isDedicatedServer()) {
        ServerPlayNetworking.send(handler.player, new PacketSyncConfig(ConfigManager.getConfigForSync()));
      }
    });

    // Fires whenever data packs are (re)loaded, including `/reload`: forget which ban sources were reported
    // as failing and drop the parsed `extra_banned_items` (tags may have changed).
    CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> ItemBans.onDataReload());

    // Integrated loot: the listener only reads the pool and rule files. They are resolved against the loot tables
    // when the server announces the registries of the same data load, which is right after it put them in place
    // (on a reload in the same server thread task that swaps them in).
    ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new SimpleResourceReloadListener<IntegratedLootDefinitions>() {
      @Override
      public ResourceLocation getFabricId() {
        return LootrAPI.rl("integrated_loot");
      }

      @Override
      public CompletableFuture<IntegratedLootDefinitions> load(ResourceManager manager, ProfilerFiller profiler, Executor executor) {
        return CompletableFuture.supplyAsync(() -> IntegratedLootDefinitions.load(manager), executor);
      }

      @Override
      public CompletableFuture<Void> apply(IntegratedLootDefinitions data, ResourceManager manager, ProfilerFiller profiler, Executor executor) {
        return CompletableFuture.runAsync(() -> IntegratedLoot.setDefinitions(data), executor);
      }
    });
    CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
      if (!client) {
        IntegratedLoot.onDataLoaded(registries);
      }
    });

    ModContainer container = FabricLoader.getInstance().getModContainer(LootrAPI.MODID).orElseThrow();

    registerPack(container, "lootr_no_advancements", Component.literal("Disable Lootr Advancements"));
    registerPack(container, "lootr_no_suspicious_blocks", Component.literal("Disable Lootr Converting Suspicious Blocks"));
  }

  private static void registerPack (ModContainer container, String name, Component desc) {
    ResourceManagerHelperImpl.registerBuiltinResourcePack(
        ResourceLocation.fromNamespaceAndPath(container.getMetadata().getId(), name),
        "datapacks/" + name,
        container,
        desc,
        ResourcePackActivationType.NORMAL);
  }
}
