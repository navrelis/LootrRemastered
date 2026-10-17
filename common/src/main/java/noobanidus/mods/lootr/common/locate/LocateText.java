package noobanidus.mods.lootr.common.locate;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;

/**
 * The pieces of text that `/lootr check` and `/lootr locate` are made of. Everything a player reads is a translation
 * key of the mod's language files (`lootr.commands.check.*`, `lootr.commands.locate.*`).
 */
final class LocateText {
  private LocateText() {
  }

  /**
   * The coordinates of a position as a text that teleports the player there when it is clicked.
   *
   * @param pos the position of the container.
   */
  static MutableComponent position(BlockPos pos, boolean entity) {
    // A block container is full or nearly: stand on top of it.
    int y = entity ? pos.getY() : pos.getY() + 1;
    String coordinates = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    String command = "/tp @s " + pos.getX() + " " + y + " " + pos.getZ();
    return Component.literal(coordinates).withStyle(Style.EMPTY
        .withColor(ChatFormatting.AQUA)
        .withUnderlined(true)
        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("lootr.commands.locate.teleport", coordinates))));
  }

  /**
   * A text that puts its value on the clipboard when it is clicked.
   */
  static MutableComponent copyable(String text) {
    return Component.literal(text).withStyle(Style.EMPTY
        .withColor(ChatFormatting.YELLOW)
        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text))
        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("lootr.commands.check.copy"))));
  }

  /**
   * The short word for the status of a loot table: OK, MISSING, EMPTY or NONE.
   */
  static MutableComponent status(LootTableStatus.Result result) {
    return switch (result.kind()) {
      case OK -> Component.translatable("lootr.commands.status.ok").withStyle(ChatFormatting.GREEN);
      case MISSING -> Component.translatable("lootr.commands.status.missing").withStyle(ChatFormatting.RED);
      case EMPTY -> Component.translatable("lootr.commands.status.empty").withStyle(ChatFormatting.GOLD);
      case NONE -> Component.translatable("lootr.commands.status.none").withStyle(ChatFormatting.GRAY);
    };
  }

  /**
   * Why a table cannot produce, in words.
   */
  static MutableComponent reason(String reason) {
    return switch (reason) {
      case "no_pools", "only_empty_entries", "only_banned_items", "only_unproductive_references" ->
          Component.translatable("lootr.commands.status.reason." + reason);
      default -> Component.translatable("lootr.commands.status.reason.no_producing_entry");
    };
  }

  /**
   * What kind of container it is, short: the Lootr type, or the vanilla type with a mark.
   */
  static MutableComponent kind(ContainerInfo info) {
    return info.isLootr()
        ? Component.translatable("lootr.commands.kind.lootr", info.typeId())
        : Component.translatable("lootr.commands.kind.vanilla", info.typeId());
  }

  static MutableComponent table(@Nullable ResourceKey<LootTable> table) {
    return table == null ? Component.translatable("lootr.commands.status.none") : Component.literal(table.location().toString());
  }
}
