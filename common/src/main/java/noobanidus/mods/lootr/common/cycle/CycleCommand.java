package noobanidus.mods.lootr.common.cycle;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import noobanidus.mods.lootr.common.data.DataStorage;
import noobanidus.mods.lootr.common.data.LootrSavedData;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * `/lootr cycle info [<player>]`, `/lootr cycle reset <player>` and `/lootr cycle expire <player>`: the personal cycle
 * of a player in the container the executing player looks at, or else stands on.
 */
public final class CycleCommand {
  private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

  private CycleCommand() {
  }

  public static LiteralArgumentBuilder<CommandSourceStack> build() {
    return Commands.literal("cycle").requires(source -> source.hasPermission(2))
        .then(Commands.literal("info")
            .executes(c -> info(c.getSource(), self(c.getSource())))
            .then(Commands.argument("player", GameProfileArgument.gameProfile())
                .executes(c -> info(c.getSource(), GameProfileArgument.getGameProfiles(c, "player")))))
        .then(Commands.literal("reset")
            .then(Commands.argument("player", GameProfileArgument.gameProfile())
                .executes(c -> reset(c.getSource(), GameProfileArgument.getGameProfiles(c, "player")))))
        .then(Commands.literal("expire")
            .then(Commands.argument("player", GameProfileArgument.gameProfile())
                .executes(c -> expire(c.getSource(), GameProfileArgument.getGameProfiles(c, "player")))));
  }

  private static Collection<GameProfile> self(CommandSourceStack source) throws CommandSyntaxException {
    return List.of(source.getPlayerOrException().getGameProfile());
  }

  private static int info(CommandSourceStack source, Collection<GameProfile> profiles) {
    ILootrInfoProvider provider = target(source);
    if (provider == null) {
      source.sendFailure(Component.translatable("lootr.commands.cycle.no_container"));
      return 0;
    }
    CycleSettings settings = LootrAPI.getPersonalCycleSettings();
    PersonalCycle.Exclusion exclusion = PersonalCycle.exclusion(provider, settings);
    LootrSavedData data = DataStorage.getExistingData(provider.getInfoKey());
    CycleSettings.Schedule schedule = PersonalCycle.scheduleFor(provider, settings);
    boolean decays = PersonalCycle.decays(provider, schedule);
    long now = WallClock.now();
    for (GameProfile profile : profiles) {
      UUID key = key(source, profile);
      CycleRecord record = data == null ? null : data.getCycle(key);
      source.sendSuccess(() -> Component.translatable("lootr.commands.cycle.header", profile.getName(), provider.getInfoUUID().toString()), false);
      PersonalCycle.Exclusion reason = exclusion == null && record != null && record.exempt() ? PersonalCycle.Exclusion.EXEMPT_STRUCTURE : exclusion;
      if (reason != null) {
        source.sendSuccess(() -> Component.translatable("lootr.commands.cycle.not_subject", reason.describe()), false);
        continue;
      }
      if (record == null) {
        source.sendSuccess(() -> Component.translatable("lootr.commands.cycle.no_record"), false);
        continue;
      }
      source.sendSuccess(() -> Component.translatable("lootr.commands.cycle.started", DATE_TIME.format(Instant.ofEpochMilli(record.start())), PersonalCycle.formatDuration(now - record.start())), false);
      source.sendSuccess(() -> Component.translatable("lootr.commands.cycle.refreshes", record.refreshes(), schedule.count()), false);
      source.sendSuccess(() -> Component.translatable("lootr.commands.cycle.decayed", Component.translatable(record.decayed() ? "lootr.commands.cycle.yes" : "lootr.commands.cycle.no")), false);
      if (record.decayed()) {
        continue;
      }
      PersonalCycle.Evaluation evaluation = PersonalCycle.evaluate(record.start(), record.refreshes(), now, schedule, decays);
      if (evaluation.decayDue()) {
        source.sendSuccess(() -> Component.translatable("lootr.commands.cycle.pending_decay"), false);
      } else if (evaluation.due() > record.refreshes()) {
        source.sendSuccess(() -> Component.translatable("lootr.commands.cycle.pending_refresh", evaluation.due()), false);
      } else if (evaluation.nextEventAt() < 0) {
        source.sendSuccess(() -> Component.translatable("lootr.commands.cycle.next_none"), false);
      } else {
        String at = DATE_TIME.format(Instant.ofEpochMilli(evaluation.nextEventAt()));
        Component in = PersonalCycle.formatDuration(evaluation.nextEventAt() - now);
        source.sendSuccess(() -> Component.translatable(evaluation.nextIsDecay() ? "lootr.commands.cycle.next_decay" : "lootr.commands.cycle.next_refresh", at, in), false);
      }
    }
    return profiles.size();
  }

  private static int reset(CommandSourceStack source, Collection<GameProfile> profiles) {
    ILootrInfoProvider provider = target(source);
    if (provider == null) {
      source.sendFailure(Component.translatable("lootr.commands.cycle.no_container"));
      return 0;
    }
    LootrSavedData data = DataStorage.getExistingData(provider.getInfoKey());
    int count = 0;
    for (GameProfile profile : profiles) {
      // Removes the inventory, the cycle and the visual opener: the next opening rolls fresh loot and starts anew.
      if (data != null && data.clearInventories(key(source, profile))) {
        count++;
        source.sendSuccess(() -> Component.translatable("lootr.commands.cycle.reset.done", profile.getName()), true);
      } else {
        source.sendFailure(Component.translatable("lootr.commands.cycle.reset.nothing", profile.getName()));
      }
    }
    if (count > 0) {
      provider.performClose();
      provider.performUpdate();
    }
    return count;
  }

  private static int expire(CommandSourceStack source, Collection<GameProfile> profiles) {
    ILootrInfoProvider provider = target(source);
    if (provider == null) {
      source.sendFailure(Component.translatable("lootr.commands.cycle.no_container"));
      return 0;
    }
    LootrSavedData data = DataStorage.getExistingData(provider.getInfoKey());
    CycleSettings.Schedule schedule = PersonalCycle.scheduleFor(provider, LootrAPI.getPersonalCycleSettings());
    int count = 0;
    for (GameProfile profile : profiles) {
      CycleRecord record = data == null ? null : data.getCycle(key(source, profile));
      if (record == null) {
        source.sendFailure(Component.translatable("lootr.commands.cycle.expire.no_record", profile.getName()));
        continue;
      }
      record.setStart(record.start() - schedule.intervalMillis());
      data.markChanged();
      count++;
      source.sendSuccess(() -> Component.translatable("lootr.commands.cycle.expire.done", profile.getName(), PersonalCycle.formatDuration(schedule.intervalMillis())), true);
    }
    return count;
  }

  // The key of the player's loot: their team's when team loot is on and the player is online, else their own id.
  private static UUID key(CommandSourceStack source, GameProfile profile) {
    ServerPlayer player = source.getServer().getPlayerList().getPlayer(profile.getId());
    return player != null ? LootrAPI.resolveServerPlayerTeam(player) : profile.getId();
  }

  // The container (block or entity) the executing player looks at, else the one the source stands on or in.
  @Nullable
  private static ILootrInfoProvider target(CommandSourceStack source) {
    if (source.getEntity() instanceof ServerPlayer player) {
      double reach = Math.max(player.blockInteractionRange(), player.entityInteractionRange());
      Vec3 eye = player.getEyePosition();
      Vec3 view = player.getViewVector(1.0f);
      Vec3 end = eye.add(view.scale(reach));
      HitResult block = player.pick(reach, 1.0f, false);
      double blockDistance = block.getType() == HitResult.Type.MISS ? reach * reach : block.getLocation().distanceToSqr(eye);
      EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, eye, end, player.getBoundingBox().expandTowards(view.scale(reach)).inflate(1.0), e -> !e.isSpectator() && LootrAPI.resolveEntity(e) != null, blockDistance);
      if (entity != null && LootrAPI.resolveEntity(entity.getEntity()) instanceof ILootrInfoProvider provider) {
        return provider;
      }
      if (block instanceof BlockHitResult blockHit && block.getType() == HitResult.Type.BLOCK) {
        ILootrInfoProvider provider = ILootrInfoProvider.of(blockHit.getBlockPos(), source.getLevel());
        if (provider != null) {
          return provider;
        }
      }
    }
    BlockPos pos = BlockPos.containing(source.getPosition());
    ILootrInfoProvider provider = ILootrInfoProvider.of(pos, source.getLevel());
    return provider != null ? provider : ILootrInfoProvider.of(pos.below(), source.getLevel());
  }
}
