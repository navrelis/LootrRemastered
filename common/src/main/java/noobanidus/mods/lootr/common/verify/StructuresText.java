package noobanidus.mods.lootr.common.verify;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import noobanidus.mods.lootr.common.verify.VerifyResult.StructureProblem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The text of `/lootr verify structures`: the structure section of the last verification result. Everything a
 * player reads is a translation key of the mod's language files (`lootr.commands.verify.structures.*`).
 */
public final class StructuresText {
  /**
   * How many structures the answer lists.
   */
  public static final int LIMIT = 15;

  private StructuresText() {
  }

  /**
   * @param result the result of the last verification; empty if there is none yet.
   * @return the lines to print. A structure that Minecraft knows is clickable and runs `/lootr locate structure`.
   */
  public static List<Component> lines(Optional<VerifyResult> result) {
    List<Component> lines = new ArrayList<>();
    if (result.isEmpty()) {
      lines.add(Component.translatable("lootr.commands.verify.structures.none").withStyle(ChatFormatting.RED));
      return lines;
    }
    VerifyResult.Structures structures = result.get().structures();
    List<StructureProblem> problems = structures.problems();
    lines.add(Component.translatable("lootr.commands.verify.structures.header", problems.size(), structures.missingTables(), structures.emptyTables()).withStyle(ChatFormatting.GOLD));
    if (problems.isEmpty()) {
      lines.add(Component.translatable("lootr.commands.verify.structures.clean"));
    } else {
      // Most problem containers first; the id breaks ties so that the answer is the same every time.
      List<StructureProblem> sorted = new ArrayList<>(problems);
      sorted.sort(Comparator.comparingInt(StructureProblem::total).reversed().thenComparing(StructureProblem::structure).thenComparing(problem -> !problem.attributed()));
      for (StructureProblem problem : sorted.subList(0, Math.min(LIMIT, sorted.size()))) {
        lines.add(entry(problem));
      }
      if (sorted.size() > LIMIT) {
        lines.add(Component.translatable("lootr.commands.verify.structures.more", sorted.size() - LIMIT).withStyle(ChatFormatting.GRAY));
      }
    }
    if (result.get().files() != null) {
      lines.add(Component.translatable("lootr.commands.verify.structures.report", result.get().files().markdown().toString()).withStyle(ChatFormatting.GRAY));
    }
    return lines;
  }

  private static MutableComponent entry(StructureProblem problem) {
    if (!problem.attributed()) {
      return Component.translatable("lootr.commands.verify.structures.entry_unattributed", Component.literal(problem.structure()).withStyle(ChatFormatting.YELLOW), problem.missing(), problem.empty());
    }
    MutableComponent id = Component.literal(problem.structure()).withStyle(Style.EMPTY
        .withColor(ChatFormatting.AQUA)
        .withUnderlined(true)
        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lootr locate structure " + problem.structure()))
        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("lootr.commands.verify.structures.locate", problem.structure()))));
    return Component.translatable("lootr.commands.verify.structures.entry", id, problem.missing(), problem.empty());
  }
}
