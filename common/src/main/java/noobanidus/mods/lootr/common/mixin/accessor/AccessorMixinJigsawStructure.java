package noobanidus.mods.lootr.common.mixin.accessor;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(JigsawStructure.class)
public interface AccessorMixinJigsawStructure {
  @Accessor("startPool")
  Holder<StructureTemplatePool> lootr$getStartPool();
}
