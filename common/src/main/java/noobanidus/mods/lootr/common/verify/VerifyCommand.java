package noobanidus.mods.lootr.common.verify;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.mixin.accessor.AccessorMixinCommandSourceStack;

/**
 * `/lootr verify` and `/lootr verify rolls &lt;n&gt;`: starts a loot table verification (see {@link LootVerifier})
 * and tells the command source how it went.
 */
public final class VerifyCommand {
  private static final String ROLLS_ARGUMENT = "count";

  private VerifyCommand() {
  }

  public static LiteralArgumentBuilder<CommandSourceStack> build() {
    return Commands.literal("verify")
        .requires(source -> source.hasPermission(2))
        .executes(c -> start(c.getSource(), 0))
        .then(Commands.literal("rolls")
            .then(Commands.argument(ROLLS_ARGUMENT, IntegerArgumentType.integer(0, VerifyOptions.MAX_ROLLS))
                .executes(c -> start(c.getSource(), IntegerArgumentType.getInteger(c, ROLLS_ARGUMENT)))));
  }

  private static int start(CommandSourceStack source, int rolls) {
    boolean started = LootVerifier.start(source.getServer(), VerifyOptions.of(rolls), new LootVerifier.Listener() {
      @Override
      public void progress(String message) {
        source.sendSystemMessage(Component.literal("Loot table verification: " + message));
      }

      @Override
      public void finished(VerifyResult result) {
        // The summary belongs in the log once. The server console writes there itself whatever it is sent.
        boolean console = ((AccessorMixinCommandSourceStack) source).lootr$getSource() == source.getServer();
        for (String line : VerifyReport.summary(result)) {
          if (!console) {
            LootrAPI.LOG.info(line);
          }
          source.sendSystemMessage(Component.literal(line));
        }
      }

      @Override
      public void failed(String reason) {
        source.sendFailure(Component.literal("Loot table verification failed, see the log: " + reason));
      }
    });
    if (!started) {
      source.sendFailure(Component.literal("A loot table verification is already running. Wait until it has finished."));
      return 0;
    }
    source.sendSuccess(() -> Component.literal("Loot table verification started" + (rolls > 0 ? " with " + rolls + " sample rolls per loot table" : "") + ". The report goes to " + VerifyOptions.defaultOutputDirectory() + "."), true);
    return 1;
  }
}
