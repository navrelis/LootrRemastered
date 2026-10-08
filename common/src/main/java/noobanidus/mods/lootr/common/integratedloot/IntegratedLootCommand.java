package noobanidus.mods.lootr.common.integratedloot;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceKeyArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.Decision;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.IneligibleMatch;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.Pool;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * `/lootr integrated &lt;loot_table&gt;` and `/lootr integrated stats`.
 */
public final class IntegratedLootCommand {
  private static final String TABLE_ARGUMENT = "loot_table";

  private IntegratedLootCommand() {
  }

  public static LiteralArgumentBuilder<CommandSourceStack> build() {
    return Commands.literal("integrated")
        .then(Commands.literal("stats").executes(c -> stats(c.getSource())))
        .then(Commands.argument(TABLE_ARGUMENT, ResourceKeyArgument.key(Registries.LOOT_TABLE))
            .suggests((c, builder) -> SharedSuggestionProvider.suggestResource(c.getSource().getServer().reloadableRegistries().getKeys(Registries.LOOT_TABLE), builder))
            .executes(c -> {
              ResourceKey<?> table = c.getArgument(TABLE_ARGUMENT, ResourceKey.class);
              if (!table.isFor(Registries.LOOT_TABLE)) {
                throw new IllegalStateException("table '" + table + "' is not a ResourceKey<LootTable>!");
              }
              //noinspection unchecked
              return describe(c.getSource(), (ResourceKey<LootTable>) table);
            }));
  }

  private static int describe(CommandSourceStack source, ResourceKey<LootTable> table) {
    String id = table.location().toString();
    if (!source.getServer().reloadableRegistries().getKeys(Registries.LOOT_TABLE).contains(table.location())) {
      source.sendFailure(Component.literal("Loot table " + id + " is not loaded."));
      return 0;
    }
    IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
    Optional<Decision> found = snapshot.decisionFor(table);
    if (found.isEmpty()) {
      Optional<IneligibleMatch> ineligible = snapshot.ineligibleMatches().stream().filter(match -> match.table().equals(table)).findFirst();
      if (ineligible.isPresent()) {
        IneligibleMatch match = ineligible.get();
        send(source, id + ": not extended. " + origin(match.ruleFile().toString(), match.entryIndex(), match.pattern()) + " matches, but the table has the type " + match.type() + " and only chest, vault and untyped tables are extended.");
      } else {
        send(source, id + ": no rule.");
      }
      return 1;
    }
    Decision decision = found.get();
    String origin = origin(decision.ruleFile().toString(), decision.entryIndex(), decision.pattern());
    if (decision.exclusion()) {
      send(source, id + ": excluded by " + origin + ".");
    } else if (decision.pool().isEmpty()) {
      send(source, id + ": not extended. " + origin + " matches, but " + decision.problem().orElse("its pool is not usable") + ".");
    } else {
      Pool pool = decision.pool().get();
      String sources = pool.sources().stream().map(entry -> entry.table().location() + " (weight " + entry.weight() + ")").collect(Collectors.joining(", "));
      send(source, id + ": extended by " + origin + ".");
      send(source, "  Pool " + pool.id() + ", rolls " + decision.rolls() + ", at most " + IntegratedLoot.getSettings().maxExtraStacks() + " stacks by configuration.");
      send(source, "  Sources: " + sources);
      if (!IntegratedLoot.getSettings().enabled()) {
        send(source, "  Integrated loot is switched off in the configuration.");
      }
    }
    return 1;
  }

  private static String origin(String ruleFile, int entryIndex, String pattern) {
    return "rule file " + ruleFile + ", entry " + entryIndex + " (pattern " + pattern + ")";
  }

  private static int stats(CommandSourceStack source) {
    IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
    IntegratedLoot.Stats stats = IntegratedLoot.stats();
    long withoutPool = snapshot.decisions().values().stream().filter(decision -> !decision.exclusion() && !decision.active()).count();
    send(source, "Integrated loot is " + (IntegratedLoot.getSettings().enabled() ? "enabled" : "disabled") + ".");
    send(source, "  Pools: " + snapshot.pools().size() + " usable, " + snapshot.unusablePools().size() + " without a source, " + snapshot.droppedSources().size() + " dropped sources.");
    send(source, "  Rule files: " + snapshot.activeRuleFiles().size() + " active, " + snapshot.inactiveRuleFiles().size() + " inactive (missing mods), " + snapshot.malformedFiles().size() + " malformed files skipped.");
    send(source, "  Loot tables: " + snapshot.extensionCount() + " extended, " + snapshot.exclusionCount() + " excluded, " + withoutPool + " without a usable pool, " + snapshot.ineligibleMatches().size() + " of another type, " + snapshot.tableCount() + " loaded.");
    send(source, "  Patterns matching no loot table: " + snapshot.unmatchedPatterns().size() + ".");
    send(source, "  Since server start: " + stats.extensions() + " extensions, " + stats.sourceRolls() + " source rolls, " + stats.stacksAdded() + " stacks added, " + stats.failures() + " failures.");
    return 1;
  }

  private static void send(CommandSourceStack source, String text) {
    source.sendSuccess(() -> Component.literal(text), false);
  }
}
