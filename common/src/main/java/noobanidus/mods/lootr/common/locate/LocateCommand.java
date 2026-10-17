package noobanidus.mods.lootr.common.locate;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.datafixers.util.Either;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceKeyArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * `/lootr locate table &lt;loot_table&gt; [&lt;radius&gt;]` and `/lootr locate structure &lt;structure or #tag&gt;
 * [&lt;radius&gt;]`. The searches are in {@link LocateSearch}; this class reads the arguments and writes the answer.
 * <br />
 * The answer of a search that has to load chunks comes a few ticks after the command ran, as a message to the
 * command source.
 */
public final class LocateCommand {
  private static final String TABLE_ARGUMENT = "loot_table";
  private static final String STRUCTURE_ARGUMENT = "structure";
  private static final String RADIUS_ARGUMENT = "radius";
  // How many containers `locate structure` lists before it only counts.
  private static final int STRUCTURE_LIST_LIMIT = 40;
  private static final DynamicCommandExceptionType ERROR_INVALID_STRUCTURE = new DynamicCommandExceptionType(id -> Component.translatable("lootr.commands.locate.structure.invalid", id));

  private LocateCommand() {
  }

  public static LiteralArgumentBuilder<CommandSourceStack> build() {
    return Commands.literal("locate")
        .requires(source -> source.hasPermission(2))
        .then(Commands.literal("table")
            .then(Commands.argument(TABLE_ARGUMENT, ResourceKeyArgument.key(Registries.LOOT_TABLE))
                .suggests((c, builder) -> SharedSuggestionProvider.suggestResource(c.getSource().getServer().reloadableRegistries().getKeys(Registries.LOOT_TABLE), builder))
                .executes(c -> table(c.getSource(), tableArgument(c), LocateSearch.DEFAULT_TABLE_RADIUS))
                .then(Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(1, LocateSearch.MAX_TABLE_RADIUS))
                    .executes(c -> table(c.getSource(), tableArgument(c), IntegerArgumentType.getInteger(c, RADIUS_ARGUMENT))))))
        .then(Commands.literal("structure")
            .then(Commands.argument(STRUCTURE_ARGUMENT, ResourceOrTagKeyArgument.resourceOrTagKey(Registries.STRUCTURE))
                .executes(c -> structure(c, LocateSearch.DEFAULT_STRUCTURE_RADIUS))
                .then(Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(1, LocateSearch.MAX_STRUCTURE_RADIUS))
                    .executes(c -> structure(c, IntegerArgumentType.getInteger(c, RADIUS_ARGUMENT))))));
  }

  @SuppressWarnings("unchecked")
  private static ResourceKey<LootTable> tableArgument(CommandContext<CommandSourceStack> c) {
    // Read without the check that the table exists: a table that is missing is exactly what one may look for.
    ResourceKey<?> table = c.getArgument(TABLE_ARGUMENT, ResourceKey.class);
    if (!table.isFor(Registries.LOOT_TABLE)) {
      throw new IllegalStateException("table '" + table + "' is not a ResourceKey<LootTable>!");
    }
    return (ResourceKey<LootTable>) table;
  }

  // ----- locate table -----

  private static int table(CommandSourceStack source, ResourceKey<LootTable> table, int radius) {
    ServerLevel level = source.getLevel();
    Vec3 origin = source.getPosition();
    int chunks = (2 * radius + 1) * (2 * radius + 1);
    source.sendSuccess(() -> Component.translatable("lootr.commands.locate.table.start", table.location().toString(), radius, chunks), false);
    LocateSearch.table(level, origin, table, radius, result -> {
      source.sendSuccess(() -> Component.translatable("lootr.commands.locate.table.header", table.location().toString(), LocateText.status(result.status())).withStyle(ChatFormatting.GOLD), false);
      for (ContainerInfo info : result.nearest()) {
        source.sendSuccess(() -> Component.translatable("lootr.commands.locate.entry",
            LocateText.position(info.pos(), info.entity()),
            distance(info, origin),
            LocateText.kind(info),
            LocateText.status(result.status())), false);
      }
      if (result.total() > result.nearest().size()) {
        source.sendSuccess(() -> Component.translatable("lootr.commands.locate.more", result.total() - result.nearest().size()), false);
      }
      source.sendSuccess(() -> Component.translatable("lootr.commands.locate.total", result.total(), result.chunksScanned(), result.chunksSkipped()), false);
      if (result.entitiesMissed() > 0) {
        source.sendSuccess(() -> Component.translatable("lootr.commands.locate.entities_missed", result.entitiesMissed()).withStyle(ChatFormatting.GRAY), false);
      }
    });
    return 1;
  }

  private static int distance(ContainerInfo info, Vec3 origin) {
    return (int) Math.round(Math.sqrt(info.distanceSqr(origin)));
  }

  // ----- locate structure -----

  private static int structure(CommandContext<CommandSourceStack> c, int radius) throws CommandSyntaxException {
    CommandSourceStack source = c.getSource();
    ResourceOrTagKeyArgument.Result<Structure> argument = ResourceOrTagKeyArgument.getResourceOrTagKey(c, STRUCTURE_ARGUMENT, Registries.STRUCTURE, ERROR_INVALID_STRUCTURE);
    ServerLevel level = source.getLevel();
    Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
    HolderSet<Structure> structures = holders(argument, registry);
    if (structures == null) {
      throw ERROR_INVALID_STRUCTURE.create(argument.asPrintable());
    }
    Vec3 origin = source.getPosition();
    LocateSearch.StructureHit hit = LocateSearch.findStructure(level, structures, BlockPos.containing(origin), radius);
    if (hit == null) {
      source.sendFailure(Component.translatable("lootr.commands.locate.structure.not_found", argument.asPrintable(), radius));
      return 0;
    }
    BlockPos pos = hit.pos();
    // The locate position has no height of its own: the middle of the structure is the place to go to.
    BlockPos target = hit.bounds() != null ? hit.bounds().getCenter() : new BlockPos(pos.getX(), level.getSeaLevel(), pos.getZ());
    MutableComponent position = LocateText.position(target, true);
    int distance = (int) Math.round(Math.sqrt(Vec3.atCenterOf(pos).multiply(1, 0, 1).distanceToSqr(origin.multiply(1, 0, 1))));
    source.sendSuccess(() -> Component.translatable("lootr.commands.locate.structure.found", hit.id().toString(), position, distance).withStyle(ChatFormatting.GOLD), false);
    if (LocateSearch.structureContents(level, hit, origin, result -> report(source, origin, result)) == null) {
      source.sendSuccess(() -> Component.translatable("lootr.commands.locate.structure.no_bounds"), false);
    }
    return 1;
  }

  @Nullable
  private static HolderSet<Structure> holders(ResourceOrTagKeyArgument.Result<Structure> argument, Registry<Structure> registry) {
    Either<ResourceKey<Structure>, TagKey<Structure>> either = argument.unwrap();
    if (either.left().isPresent()) {
      Optional<Holder.Reference<Structure>> holder = registry.getHolder(either.left().get());
      return holder.<HolderSet<Structure>>map(HolderSet::direct).orElse(null);
    }
    return registry.getTag(either.right().orElseThrow()).<HolderSet<Structure>>map(tag -> tag).orElse(null);
  }

  private static void report(CommandSourceStack source, Vec3 origin, LocateSearch.StructureResult result) {
    if (!result.generated()) {
      source.sendSuccess(() -> Component.translatable("lootr.commands.locate.structure.not_generated").withStyle(ChatFormatting.YELLOW), false);
      return;
    }
    if (result.chunksSkipped() > 0) {
      source.sendSuccess(() -> Component.translatable("lootr.commands.locate.structure.partial", result.chunksSkipped(), result.chunksCapped()).withStyle(ChatFormatting.YELLOW), false);
    }
    if (result.chunksTotal() > result.chunksCapped()) {
      source.sendSuccess(() -> Component.translatable("lootr.commands.locate.structure.capped", result.chunksTotal(), result.chunksCapped()).withStyle(ChatFormatting.GRAY), false);
    }
    int shown = 0;
    for (LocateSearch.StructureContainer entry : result.containers()) {
      if (shown++ >= STRUCTURE_LIST_LIMIT) {
        break;
      }
      ContainerInfo info = entry.container();
      source.sendSuccess(() -> Component.translatable("lootr.commands.locate.structure.entry",
          LocateText.position(info.pos(), info.entity()),
          LocateText.kind(info),
          distance(info, origin),
          LocateText.table(info.table()),
          LocateText.status(entry.status())), false);
    }
    if (result.containers().size() > STRUCTURE_LIST_LIMIT) {
      source.sendSuccess(() -> Component.translatable("lootr.commands.locate.more", result.containers().size() - STRUCTURE_LIST_LIMIT), false);
    }
    source.sendSuccess(() -> Component.translatable("lootr.commands.locate.structure.summary", result.containers().size(), result.missing(), result.empty()).withStyle(ChatFormatting.GOLD), false);
    if (result.entitiesMissed() > 0) {
      source.sendSuccess(() -> Component.translatable("lootr.commands.locate.entities_missed", result.entitiesMissed()).withStyle(ChatFormatting.GRAY), false);
    }
  }
}
