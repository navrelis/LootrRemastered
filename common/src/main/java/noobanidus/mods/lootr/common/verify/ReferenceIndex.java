package noobanidus.mods.lootr.common.verify;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every reference to a loot table that the scan saw, existing or not, aggregated while the files are read: the
 * files themselves are not kept. Thread-safe. What it holds does not depend on the order the references arrive in.
 */
final class ReferenceIndex {
  static final String SOURCE_LOOT_TABLE = "loot_table";
  static final String SOURCE_STRUCTURE = "structure";
  static final String SOURCE_WORLDGEN = "worldgen";

  // Kinds found inside loot tables.
  static final String NESTED_TABLE = "nested_table";
  static final String SET_LOOT_TABLE = "set_loot_table";
  static final String COMPONENT = "container_loot_component";
  // Kinds found inside structure templates.
  static final String CONTAINER = "container";
  static final String ENTITY_CONTAINER = "entity_container";
  static final String CONTAINER_ITEM = "container_item";
  static final String VAULT = "vault";
  static final String VAULT_DISPLAY = "vault_display";
  static final String TRIAL_SPAWNER = "trial_spawner";
  static final String ENTITY_DEATH = "entity_death";
  static final String EQUIPMENT = "equipment";
  static final String OTHER = "other";

  static final int MAX_EXAMPLES = 5;
  private static final int MAX_HOLDERS = 20;

  /**
   * @param table  the referenced id as written; not necessarily a valid id.
   * @param origin the key of the origin of the referencing file.
   */
  record Key(String table, String source, String kind, String origin) implements Comparable<Key> {
    private static final Comparator<Key> ORDER = Comparator.comparing(Key::table).thenComparing(Key::source).thenComparing(Key::kind).thenComparing(Key::origin);

    @Override
    public int compareTo(Key other) {
      return ORDER.compare(this, other);
    }
  }

  static final class Uses {
    private int count;
    // The smallest ids, so that the choice does not depend on the order of arrival.
    private final TreeSet<String> examples = new TreeSet<>();
    private final TreeSet<String> holders = new TreeSet<>();

    private synchronized void add(String file, String holder) {
      count++;
      examples.add(file);
      if (examples.size() > MAX_EXAMPLES) {
        examples.pollLast();
      }
      if (!holder.isEmpty()) {
        holders.add(holder);
        if (holders.size() > MAX_HOLDERS) {
          holders.pollLast();
        }
      }
    }

    synchronized int count() {
      return count;
    }

    synchronized TreeSet<String> examples() {
      return new TreeSet<>(examples);
    }

    synchronized TreeSet<String> holders() {
      return new TreeSet<>(holders);
    }
  }

  private final Map<Key, Uses> uses = new ConcurrentHashMap<>();

  /**
   * @param file   the id of the referencing loot table, template or worldgen file.
   * @param holder the block entity, entity or block that carries the reference; empty if there is none.
   */
  void add(String source, String kind, String table, String origin, String file, String holder) {
    uses.computeIfAbsent(new Key(table, source, kind, origin), key -> new Uses()).add(file, holder);
  }

  /**
   * @return everything, ordered by referenced table, source, kind and origin.
   */
  TreeMap<Key, Uses> sorted() {
    return new TreeMap<>(uses);
  }

  /**
   * @return true if a reference of this kind makes the table the loot of a container in a structure: every
   * reference from worldgen files, and the references of block entities, container entities (chest minecarts),
   * container items and vaults in templates.
   */
  static boolean isContainerUse(Key key) {
    if (SOURCE_WORLDGEN.equals(key.source())) {
      return true;
    }
    return SOURCE_STRUCTURE.equals(key.source()) && (CONTAINER.equals(key.kind()) || ENTITY_CONTAINER.equals(key.kind()) || CONTAINER_ITEM.equals(key.kind()) || VAULT.equals(key.kind()));
  }
}
