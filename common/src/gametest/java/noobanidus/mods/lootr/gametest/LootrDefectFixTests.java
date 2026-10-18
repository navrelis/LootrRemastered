package noobanidus.mods.lootr.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.registry.LootrRegistry;
import noobanidus.mods.lootr.common.command.CommandLootr;
import noobanidus.mods.lootr.common.impl.LootrServiceRegistry;
import noobanidus.mods.lootr.gametest.platform.TestPlatforms;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static noobanidus.mods.lootr.gametest.LootrTestSupport.EMPTY_STRUCTURE;

/**
 * Tests for the small defects fixed in T08a: the command tree, the suggestion cache, the sounds of the suspicious
 * blocks, the trophy shape, the removed options and the refresh of the sections without a server.
 */
public class LootrDefectFixTests {
  // The reload replaces the data of the whole server, so that test runs in a batch of its own.
  private static final String RELOAD_BATCH = "lootr_t08a_reload";
  private static final String KNOWN_TABLE = "minecraft:chests/simple_dungeon";

  // ----- 1. The area commands -----

  @GameTest(template = EMPTY_STRUCTURE)
  public void areaCommandsAreRegisteredOnce(GameTestHelper helper) {
    CommandDispatcher<CommandSourceStack> dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
    CommandNode<CommandSourceStack> lootr = dispatcher.getRoot().getChild("lootr");
    helper.assertTrue(lootr != null, "The lootr command is not registered");
    for (String name : List.of("custom-area", "custom-area-old")) {
      CommandNode<CommandSourceStack> area = lootr.getChild(name);
      helper.assertTrue(area != null, "The sub-command " + name + " is not registered");
      CommandNode<CommandSourceStack> to = area.getChild("from") == null ? null : area.getChild("from").getChild("to");
      helper.assertTrue(to != null && to.getCommand() != null, "The sub-command " + name + " cannot be executed with two positions");
    }
    helper.succeed();
  }

  // ----- 2. The loot table suggestions follow a data reload -----

  @GameTest(template = EMPTY_STRUCTURE, batch = RELOAD_BATCH, timeoutTicks = 600)
  public void lootTableSuggestionsFollowDataReload(GameTestHelper helper) {
    MinecraftServer server = helper.getLevel().getServer();
    helper.assertTrue(suggestedTables(server).contains(KNOWN_TABLE), "The loot table suggestions do not contain " + KNOWN_TABLE);
    ReloadableServerRegistries.Holder before = server.reloadableRegistries();
    List<?> tablesBefore = cachedTables();
    helper.assertTrue(tablesBefore.contains(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(KNOWN_TABLE))), "The cached tables do not contain " + KNOWN_TABLE);

    // The same as /reload.
    CompletableFuture<Void> reload = server.reloadResources(server.getPackRepository().getSelectedIds());
    helper.startSequence()
        .thenWaitUntil(() -> helper.assertTrue(reload.isDone(), "The data reload has not finished"))
        .thenExecute(() -> {
          helper.assertTrue(!reload.isCompletedExceptionally(), "The data reload failed");
          ReloadableServerRegistries.Holder after = server.reloadableRegistries();
          helper.assertTrue(after != before, "The data reload did not replace the registries");
          helper.assertTrue(cachedSource() == before, "The test needs the cache to still belong to the old registries");
          helper.assertTrue(suggestedTables(server).contains(KNOWN_TABLE), "The loot table suggestions lost " + KNOWN_TABLE + " after the reload");
          helper.assertTrue(cachedSource() == after, "The cached tables still belong to the registries from before the reload");
          helper.assertTrue(cachedTables() != tablesBefore, "The loot table suggestions were not read again after the reload");
        })
        .thenSucceed();
  }

  private static List<String> suggestedTables(MinecraftServer server) {
    CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
    String id = LootrServiceRegistry.getCommandBlockExtensions().get(0).getId();
    try {
      return dispatcher.getCompletionSuggestions(dispatcher.parse("lootr " + id + " ", server.createCommandSourceStack()))
          .get().getList().stream().map(Suggestion::getText).toList();
    } catch (Exception e) {
      throw new GameTestAssertException("Could not complete the lootr command: " + e);
    }
  }

  private static Object cachedSource() {
    try {
      Field field = CommandLootr.class.getDeclaredField("tablesSource");
      field.setAccessible(true);
      return ((WeakReference<?>) field.get(null)).get();
    } catch (ReflectiveOperationException e) {
      throw new GameTestAssertException("The loot table cache of CommandLootr changed: " + e);
    }
  }

  private static List<?> cachedTables() {
    try {
      Field field = CommandLootr.class.getDeclaredField("tables");
      field.setAccessible(true);
      return (List<?>) field.get(null);
    } catch (ReflectiveOperationException e) {
      throw new GameTestAssertException("The loot table cache of CommandLootr changed: " + e);
    }
  }

  // ----- 3. The sounds of the suspicious blocks -----

  @GameTest(template = EMPTY_STRUCTURE)
  public void suspiciousBlocksUseTheirVanillaSounds(GameTestHelper helper) {
    BrushableBlock sand = (BrushableBlock) LootrRegistry.getSuspiciousSandBlock();
    BrushableBlock gravel = (BrushableBlock) LootrRegistry.getSuspiciousGravelBlock();
    helper.assertTrue(sand.getBrushSound() == SoundEvents.BRUSH_SAND, "The suspicious sand does not brush like sand");
    helper.assertTrue(sand.getBrushCompletedSound() == SoundEvents.BRUSH_SAND_COMPLETED, "The suspicious sand does not finish like sand");
    helper.assertTrue(gravel.getBrushSound() == SoundEvents.BRUSH_GRAVEL, "The suspicious gravel does not brush like gravel");
    helper.assertTrue(gravel.getBrushCompletedSound() == SoundEvents.BRUSH_GRAVEL_COMPLETED, "The suspicious gravel does not finish like gravel");
    helper.succeed();
  }

  // ----- 6. The removed options -----

  // The configuration formats differ (Gson into the config object on Fabric, a corrected TOML tree on NeoForge), so each loader checks its own.
  @GameTest(template = EMPTY_STRUCTURE)
  public void configWithRemovedKeysStillLoads(GameTestHelper helper) {
    TestPlatforms.get().assertConfigWithRemovedKeysLoads(helper);
    helper.succeed();
  }

  // The Aether's chest mimic renderer calls `ConfigManager.isNewTextures()`, which an earlier Lootr had; without it the game crashes with a NoSuchMethodError.
  @GameTest(template = EMPTY_STRUCTURE)
  public void removedNewTexturesOptionStillAnswersTrue(GameTestHelper helper) {
    helper.assertTrue(TestPlatforms.get().legacyIsNewTextures(), "ConfigManager.isNewTextures() does not report the new textures");
    helper.succeed();
  }

  // ----- 8. Refreshing the sections without a server -----

  @GameTest(template = EMPTY_STRUCTURE)
  public void refreshSectionsWithoutServerStaysOffTheClient(GameTestHelper helper) {
    TestPlatforms.get().runWithoutServer(() -> {
      try {
        LootrAPI.refreshSections();
      } catch (Throwable t) {
        throw new GameTestAssertException("Refreshing the sections without a server reaches client code on a dedicated server: " + t);
      }
    });
    helper.succeed();
  }

  // ----- 9. The shape of the trophy -----

  @GameTest(template = EMPTY_STRUCTURE)
  public void trophyShapeMatchesTheModel(GameTestHelper helper) {
    AABB model = modelBounds("/assets/lootr/models/block/trophy.json");
    Block trophy = LootrRegistry.getTrophyBlock();
    BlockPos pos = helper.absolutePos(BlockPos.ZERO);
    for (Direction facing : Direction.Plane.HORIZONTAL) {
      BlockState state = trophy.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
      AABB selection = state.getShape(helper.getLevel(), pos).bounds();
      AABB collision = state.getCollisionShape(helper.getLevel(), pos).bounds();
      helper.assertTrue(sameBox(selection, rotate(model, facing)), "The selection box facing " + facing + " is " + selection + " but the model is " + rotate(model, facing));
      helper.assertTrue(sameBox(collision, rotate(model, facing)), "The collision box facing " + facing + " is " + collision + " but the model is " + rotate(model, facing));
    }
    helper.succeed();
  }

  // The outer bounds of all elements of a block model, in blocks.
  private static AABB modelBounds(String resource) {
    try (InputStream stream = LootrAPI.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new GameTestAssertException("The model " + resource + " is missing");
      }
      JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
      double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
      double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
      for (JsonElement element : root.getAsJsonArray("elements")) {
        JsonArray from = element.getAsJsonObject().getAsJsonArray("from");
        JsonArray to = element.getAsJsonObject().getAsJsonArray("to");
        for (int axis = 0; axis < 3; axis++) {
          min[axis] = Math.min(min[axis], from.get(axis).getAsDouble() / 16.0);
          max[axis] = Math.max(max[axis], to.get(axis).getAsDouble() / 16.0);
        }
      }
      return new AABB(min[0], min[1], min[2], max[0], max[1], max[2]);
    } catch (IOException e) {
      throw new GameTestAssertException("Could not read the model " + resource + ": " + e);
    }
  }

  // The blockstate turns the model around the vertical axis of the block, north being the unrotated model.
  private static AABB rotate(AABB box, Direction facing) {
    return switch (facing) {
      case SOUTH -> new AABB(1 - box.maxX, box.minY, 1 - box.maxZ, 1 - box.minX, box.maxY, 1 - box.minZ);
      case EAST -> new AABB(1 - box.maxZ, box.minY, box.minX, 1 - box.minZ, box.maxY, box.maxX);
      case WEST -> new AABB(box.minZ, box.minY, 1 - box.maxX, box.maxZ, box.maxY, 1 - box.minX);
      default -> box;
    };
  }

  private static boolean sameBox(AABB a, AABB b) {
    return Math.abs(a.minX - b.minX) < 1.0E-6 && Math.abs(a.minY - b.minY) < 1.0E-6 && Math.abs(a.minZ - b.minZ) < 1.0E-6
        && Math.abs(a.maxX - b.maxX) < 1.0E-6 && Math.abs(a.maxY - b.maxY) < 1.0E-6 && Math.abs(a.maxZ - b.maxZ) < 1.0E-6;
  }
}
