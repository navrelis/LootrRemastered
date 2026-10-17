package noobanidus.mods.lootr.common.verify;

import noobanidus.mods.lootr.common.verify.VerifyResult.ContainerProblem;
import noobanidus.mods.lootr.common.verify.VerifyResult.StructureProblem;
import noobanidus.mods.lootr.common.verify.VerifyResult.Structures;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Sorts the containers of structure templates whose loot table is missing, cannot produce or was empty in every
 * sample roll under the structures that place the templates. Pure.
 */
final class StructureProblems {
  /**
   * One kind of container in one template: the block entity or entity that holds the reference, and the loot table
   * as written.
   */
  record TemplateContainer(String template, String holder, String table) {
  }

  private record GroupKey(String id, boolean attributed) {
    private static final Comparator<GroupKey> ORDER = Comparator.comparing(GroupKey::id).thenComparing(key -> !key.attributed);
  }

  private StructureProblems() {
  }

  /**
   * The group of a template that no jigsaw structure reaches: its namespace and the first segment of its path, e.g.
   * `minecraft:woodland_mansion` for `minecraft:woodland_mansion/entrance`. A template without a folder is a group
   * of its own.
   */
  static String unattributedGroup(String template) {
    int colon = template.indexOf(':');
    String namespace = colon == -1 ? "minecraft" : template.substring(0, colon);
    String path = template.substring(colon + 1);
    int slash = path.indexOf('/');
    return namespace + ":" + (slash == -1 ? path : path.substring(0, slash));
  }

  /**
   * @param structureTemplates the templates of each jigsaw structure, by structure id.
   * @param containers         every container reference found in a template, with how often it occurs.
   * @param problemOf          the problem class of a loot table (`missing_reference`, `cannot_produce` or
   *                           `always_empty_in_rolls`), or null if the table is fine.
   */
  static Structures build(int jigsawStructures, int attributedTemplates, Map<String, ? extends Collection<String>> structureTemplates, Map<TemplateContainer, Integer> containers, Function<String, String> problemOf) {
    Map<String, List<String>> structuresOfTemplate = new HashMap<>();
    structureTemplates.forEach((structure, templates) -> templates.forEach(template -> structuresOfTemplate.computeIfAbsent(template, ignored -> new ArrayList<>()).add(structure)));

    Map<GroupKey, Map<ContainerProblem, Integer>> groups = new TreeMap<>(GroupKey.ORDER);
    for (Map.Entry<TemplateContainer, Integer> entry : containers.entrySet()) {
      TemplateContainer container = entry.getKey();
      String problem = problemOf.apply(container.table());
      if (problem == null) {
        continue;
      }
      List<String> structures = structuresOfTemplate.get(container.template());
      List<GroupKey> keys = new ArrayList<>();
      if (structures == null) {
        keys.add(new GroupKey(unattributedGroup(container.template()), false));
      } else {
        structures.forEach(structure -> keys.add(new GroupKey(structure, true)));
      }
      for (GroupKey key : keys) {
        groups.computeIfAbsent(key, ignored -> new TreeMap<>(CONTAINER_ORDER)).merge(new ContainerProblem(container.template(), container.holder(), container.table(), problem, 0), entry.getValue(), Integer::sum);
      }
    }

    List<StructureProblem> result = new ArrayList<>();
    groups.forEach((key, found) -> {
      List<ContainerProblem> list = new ArrayList<>();
      int missing = 0;
      int empty = 0;
      for (Map.Entry<ContainerProblem, Integer> entry : found.entrySet()) {
        ContainerProblem problem = entry.getKey();
        list.add(new ContainerProblem(problem.template(), problem.holder(), problem.table(), problem.problem(), entry.getValue()));
        if (problem.problem().equals(VerifyResult.MISSING_REFERENCE)) {
          missing += entry.getValue();
        } else {
          empty += entry.getValue();
        }
      }
      result.add(new StructureProblem(key.id(), key.attributed(), missing, empty, List.copyOf(list)));
    });
    return new Structures(jigsawStructures, attributedTemplates, List.copyOf(result));
  }

  // The count is not part of the order: the same container found again adds to it.
  private static final Comparator<ContainerProblem> CONTAINER_ORDER = Comparator.comparing(ContainerProblem::template).thenComparing(ContainerProblem::holder).thenComparing(ContainerProblem::table).thenComparing(ContainerProblem::problem);
}
