package noobanidus.mods.lootr.common.verify;

import com.mojang.datafixers.util.Either;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import noobanidus.mods.lootr.common.mixin.accessor.AccessorMixinJigsawStructure;
import noobanidus.mods.lootr.common.mixin.accessor.AccessorMixinListPoolElement;
import noobanidus.mods.lootr.common.mixin.accessor.AccessorMixinSinglePoolElement;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Finds out which structure templates a structure is built from. Only jigsaw structures can be followed: the walk
 * starts at the structure's start pool and goes through every pool element of a pool (single and legacy elements
 * name a template, list elements hold more elements, everything else, e.g. features, has no template) and on to the
 * pools that the jigsaw blocks of those templates lead to, and to the fallback of each pool. Every pool is visited
 * once per structure, so cycles end.
 * <br />
 * Structures that place templates from code (the mansion, the stronghold, the ocean monuments of other mods) or
 * use pool elements of a mod that are neither single nor list elements are not seen: their templates stay
 * unattributed.
 */
final class StructureAttribution {
  private static final ResourceLocation EMPTY_POOL = ResourceLocation.withDefaultNamespace("empty");
  // Lists of lists are legal; a list that holds itself is not possible, but the depth is limited all the same.
  private static final int MAX_LIST_DEPTH = 16;

  /**
   * @param jigsawStructures  how many jigsaw structures were walked.
   * @param templatesByStructure the ids of the templates (as `namespace:path`) each jigsaw structure can place, by
   *                          structure id; ordered.
   */
  record Result(int jigsawStructures, Map<String, TreeSet<String>> templatesByStructure) {
  }

  private record PoolContent(Set<String> templates, Holder<StructureTemplatePool> fallback) {
  }

  private final Registry<StructureTemplatePool> pools;
  private final Map<String, Set<String>> jigsawPoolsByTemplate;
  private final Map<ResourceLocation, PoolContent> contents = new HashMap<>();

  private StructureAttribution(Registry<StructureTemplatePool> pools, Map<String, Set<String>> jigsawPoolsByTemplate) {
    this.pools = pools;
    this.jigsawPoolsByTemplate = jigsawPoolsByTemplate;
  }

  /**
   * @param registries            the registries of the world, which hold the structures and template pools.
   * @param jigsawPoolsByTemplate for each template that has jigsaw blocks, the pools they point to.
   */
  static Result attribute(RegistryAccess registries, Map<String, Set<String>> jigsawPoolsByTemplate) {
    Registry<Structure> structures = registries.registryOrThrow(Registries.STRUCTURE);
    StructureAttribution walker = new StructureAttribution(registries.registryOrThrow(Registries.TEMPLATE_POOL), jigsawPoolsByTemplate);
    Map<String, TreeSet<String>> result = new TreeMap<>();
    int jigsaw = 0;
    for (Map.Entry<ResourceKey<Structure>, Structure> entry : structures.entrySet()) {
      if (!(entry.getValue() instanceof JigsawStructure structure)) {
        continue;
      }
      jigsaw++;
      result.put(entry.getKey().location().toString(), walker.walk(((AccessorMixinJigsawStructure) (Object) structure).lootr$getStartPool()));
    }
    return new Result(jigsaw, result);
  }

  private TreeSet<String> walk(Holder<StructureTemplatePool> start) {
    TreeSet<String> templates = new TreeSet<>();
    Set<ResourceLocation> visited = new HashSet<>();
    Deque<Holder<StructureTemplatePool>> queue = new ArrayDeque<>();
    queue.add(start);
    while (!queue.isEmpty()) {
      Holder<StructureTemplatePool> pool = queue.poll();
      ResourceLocation id = pool.unwrapKey().map(key -> key.location()).orElse(null);
      if (id != null && (id.equals(EMPTY_POOL) || !visited.add(id))) {
        continue;
      }
      PoolContent content = id == null ? read(pool.value()) : contents.computeIfAbsent(id, ignored -> read(pool.value()));
      for (String template : content.templates()) {
        templates.add(template);
        for (String target : jigsawPoolsByTemplate.getOrDefault(template, Set.of())) {
          ResourceLocation targetId = ResourceLocation.tryParse(target);
          if (targetId != null && !visited.contains(targetId)) {
            pools.getHolder(targetId).ifPresent(holder -> queue.add(holder));
          }
        }
      }
      queue.add(content.fallback());
    }
    return templates;
  }

  private static PoolContent read(StructureTemplatePool pool) {
    Set<String> templates = new TreeSet<>();
    // All elements of the pool, each as often as its weight; the order does not matter here.
    for (StructurePoolElement element : pool.getShuffledTemplates(RandomSource.create(0L))) {
      collect(element, templates, 0);
    }
    return new PoolContent(templates, pool.getFallback());
  }

  private static void collect(StructurePoolElement element, Set<String> templates, int depth) {
    if (element instanceof SinglePoolElement single) {
      Either<ResourceLocation, ?> template = ((AccessorMixinSinglePoolElement) (Object) single).lootr$getTemplate();
      template.left().ifPresent(location -> templates.add(location.toString()));
    } else if (element instanceof ListPoolElement list && depth < MAX_LIST_DEPTH) {
      for (StructurePoolElement child : ((AccessorMixinListPoolElement) (Object) list).lootr$getElements()) {
        collect(child, templates, depth + 1);
      }
    }
  }
}
