package noobanidus.mods.lootr.common.locate;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * `/lootr check [&lt;pos&gt;]`: says what is the matter with the container the player looks at, or the one at the
 * position (see {@link ContainerCheck}).
 */
public final class CheckCommand {
  private static final String POSITION_ARGUMENT = "pos";

  private CheckCommand() {
  }

  public static LiteralArgumentBuilder<CommandSourceStack> build() {
    return Commands.literal("check")
        .requires(source -> source.hasPermission(2))
        .executes(c -> check(c.getSource(), null))
        .then(Commands.argument(POSITION_ARGUMENT, BlockPosArgument.blockPos())
            .executes(c -> check(c.getSource(), BlockPosArgument.getLoadedBlockPos(c, POSITION_ARGUMENT))));
  }

  private static int check(CommandSourceStack source, @Nullable BlockPos pos) {
    ServerLevel level = source.getLevel();
    ServerPlayer player = source.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
    ContainerInfo container;
    if (pos != null) {
      container = ContainerLocator.at(level, pos);
      if (container == null) {
        source.sendFailure(Component.translatable("lootr.commands.check.not_a_container", position(pos)));
        return 0;
      }
    } else {
      if (player == null) {
        source.sendFailure(Component.translatable("lootr.commands.check.needs_player"));
        return 0;
      }
      container = ContainerCheck.lookingAt(player, ContainerCheck.REACH);
      if (container == null) {
        source.sendFailure(Component.translatable("lootr.commands.check.no_target"));
        return 0;
      }
    }
    ContainerCheck.Report report = ContainerCheck.run(level, container, player);
    for (Component line : lines(report)) {
      source.sendSuccess(() -> line, false);
    }
    return 1;
  }

  private static MutableComponent position(BlockPos pos) {
    return Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
  }

  /**
   * The lines of the answer.
   */
  static List<Component> lines(ContainerCheck.Report report) {
    ContainerInfo container = report.container();
    List<Component> lines = new ArrayList<>();
    lines.add(Component.translatable("lootr.commands.check.header").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    lines.add(container.isLootr()
        ? Component.translatable("lootr.commands.check.kind.lootr", container.typeId())
        : Component.translatable("lootr.commands.check.kind.vanilla", container.typeId()));
    lines.add(Component.translatable("lootr.commands.check.position", LocateText.position(container.pos(), container.entity()), container.dimension().location().toString()));
    if (report.uuid() != null) {
      lines.add(Component.translatable("lootr.commands.check.uuid", LocateText.copyable(report.uuid().toString())));
    }
    if (container.table() == null) {
      lines.add(Component.translatable("lootr.commands.check.table.none"));
    } else {
      lines.add(Component.translatable("lootr.commands.check.table", container.table().location().toString(), container.seed()));
    }
    lines.add(statusLine(report.status()));
    LootTableStatus.Sample sample = report.sample();
    if (sample != null) {
      if (sample.error() != null) {
        lines.add(Component.translatable("lootr.commands.check.sample.failed", sample.rolls(), sample.error()));
      } else {
        lines.add(Component.translatable("lootr.commands.check.sample", sample.emptyRolls(), sample.rolls(), String.format(Locale.ROOT, "%.1f", sample.averageStacks())));
      }
    }
    lines.add(integratedLine(report.integrated()));
    if (report.structures().isEmpty()) {
      lines.add(Component.translatable("lootr.commands.check.structure.none"));
    } else {
      lines.add(Component.translatable("lootr.commands.check.structure", report.structures().stream().map(ResourceLocation::toString).collect(Collectors.joining(", "))));
    }
    if (container.isLootr()) {
      lines.add(Component.translatable("lootr.commands.check.openers", report.openers()));
      if (report.playerHasInventory() != null) {
        lines.add(Component.translatable(report.playerHasInventory() ? "lootr.commands.check.inventory.yes" : "lootr.commands.check.inventory.no"));
      }
    }
    return lines;
  }

  private static Component statusLine(LootTableStatus.Result status) {
    return switch (status.kind()) {
      case OK -> Component.translatable("lootr.commands.check.status.ok", LocateText.status(status));
      case MISSING -> Component.translatable("lootr.commands.check.status.missing", LocateText.status(status));
      case EMPTY -> Component.translatable("lootr.commands.check.status.empty", LocateText.status(status), LocateText.reason(status.reason()));
      case NONE -> Component.translatable("lootr.commands.check.status.none", LocateText.status(status));
    };
  }

  private static Component integratedLine(LootTableStatus.Integrated integrated) {
    return switch (integrated.state()) {
      case EXTENDED -> Component.translatable("lootr.commands.check.integrated.extended", integrated.detail());
      case SWITCHED_OFF -> Component.translatable("lootr.commands.check.integrated.switched_off", integrated.detail());
      case EXCLUDED -> Component.translatable("lootr.commands.check.integrated.excluded");
      case RULE_PROBLEM -> Component.translatable("lootr.commands.check.integrated.problem", integrated.detail());
      case OTHER_TYPE -> Component.translatable("lootr.commands.check.integrated.other_type", integrated.detail());
      case NONE -> Component.translatable("lootr.commands.check.integrated.none");
    };
  }
}
