package noobanidus.mods.lootr.common.mixin.accessor;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CommandSourceStack.class)
public interface AccessorMixinCommandSourceStack {
  @Accessor("source")
  CommandSource lootr$getSource();
}
