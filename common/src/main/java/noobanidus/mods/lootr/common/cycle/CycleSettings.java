package noobanidus.mods.lootr.common.cycle;

import net.minecraft.resources.ResourceLocation;
import noobanidus.mods.lootr.common.api.LootrAPI;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, parsed snapshot of the `personal_cycle` configuration section. The loaders build it once per
 * configuration load (see {@link #parse}) and hand it out through `LootrAPI.getPersonalCycleSettings()`; malformed
 * entries are logged when it is built, so once per load.
 */
public final class CycleSettings {
  public static final boolean DEFAULT_ENABLED = true;
  public static final double DEFAULT_INTERVAL_HOURS = 72.0;
  public static final double MIN_INTERVAL_HOURS = 0.0167;
  public static final double MAX_INTERVAL_HOURS = 1_000_000.0;
  public static final int DEFAULT_COUNT = 3;
  public static final int MIN_COUNT = 0;
  public static final int MAX_COUNT = 10_000;
  public static final boolean DEFAULT_DECAY = true;

  public static final CycleSettings DISABLED = new CycleSettings(false, new Schedule(DEFAULT_INTERVAL_HOURS, DEFAULT_COUNT, DEFAULT_DECAY), Matcher.EMPTY, Matcher.EMPTY, Map.of(), Map.of());

  /**
   * The re-roll interval, the number of re-rolls and whether the container decays for the player afterwards.
   */
  public record Schedule(long intervalMillis, int count, boolean decay) {
    public Schedule(double intervalHours, int count, boolean decay) {
      this(toMillis(intervalHours), count, decay);
    }

    public Schedule {
      intervalMillis = Math.max(toMillis(MIN_INTERVAL_HOURS), Math.min(intervalMillis, toMillis(MAX_INTERVAL_HOURS)));
      count = Math.max(MIN_COUNT, Math.min(count, MAX_COUNT));
    }

    private static long toMillis(double hours) {
      if (Double.isNaN(hours)) {
        hours = DEFAULT_INTERVAL_HOURS;
      }
      return Math.round(Math.max(MIN_INTERVAL_HOURS, Math.min(hours, MAX_INTERVAL_HOURS)) * 3_600_000.0);
    }
  }

  private final boolean enabled;
  private final Schedule schedule;
  private final Matcher excludedTables;
  private final Matcher excludedDimensions;
  private final Map<ResourceLocation, Schedule> tableOverrides;
  private final Map<String, Schedule> namespaceOverrides;

  private CycleSettings(boolean enabled, Schedule schedule, Matcher excludedTables, Matcher excludedDimensions, Map<ResourceLocation, Schedule> tableOverrides, Map<String, Schedule> namespaceOverrides) {
    this.enabled = enabled;
    this.schedule = schedule;
    this.excludedTables = excludedTables;
    this.excludedDimensions = excludedDimensions;
    this.tableOverrides = tableOverrides;
    this.namespaceOverrides = namespaceOverrides;
  }

  public static CycleSettings parse(boolean enabled, double intervalHours, int count, boolean decay, @Nullable Collection<? extends String> excludedTables, @Nullable Collection<? extends String> excludedDimensions, @Nullable Collection<? extends String> overrides) {
    Map<ResourceLocation, Schedule> tableOverrides = new HashMap<>();
    Map<String, Schedule> namespaceOverrides = new HashMap<>();
    if (overrides != null) {
      for (String entry : overrides) {
        parseOverride(entry, tableOverrides, namespaceOverrides);
      }
    }
    return new CycleSettings(enabled, new Schedule(intervalHours, count, decay), Matcher.parse(excludedTables, "excluded_loot_tables"), Matcher.parse(excludedDimensions, "excluded_dimensions"), Map.copyOf(tableOverrides), Map.copyOf(namespaceOverrides));
  }

  // `<loot table id or namespace:*>=<hours>,<count>,<decay true|false>`
  private static void parseOverride(@Nullable String entry, Map<ResourceLocation, Schedule> tables, Map<String, Schedule> namespaces) {
    if (entry == null || entry.isBlank()) {
      return;
    }
    int equals = entry.indexOf('=');
    String[] values = equals < 0 ? new String[0] : entry.substring(equals + 1).split(",", -1);
    if (equals <= 0 || values.length != 3) {
      LootrAPI.LOG.warn("Personal cycle: ignoring the malformed override '{}'; expected '<loot table or namespace:*>=<hours>,<count>,<true|false>'.", entry);
      return;
    }
    String target = entry.substring(0, equals).trim();
    double hours;
    int count;
    try {
      hours = Double.parseDouble(values[0].trim());
      count = Integer.parseInt(values[1].trim());
    } catch (NumberFormatException e) {
      LootrAPI.LOG.warn("Personal cycle: ignoring the override '{}': the hours or the count is not a number.", entry);
      return;
    }
    String decayValue = values[2].trim().toLowerCase(Locale.ROOT);
    if (!decayValue.equals("true") && !decayValue.equals("false")) {
      LootrAPI.LOG.warn("Personal cycle: ignoring the override '{}': the decay value must be true or false.", entry);
      return;
    }
    if (Double.isNaN(hours) || hours < MIN_INTERVAL_HOURS || count < MIN_COUNT) {
      LootrAPI.LOG.warn("Personal cycle: ignoring the override '{}': the hours must be at least {} and the count at least {}.", entry, MIN_INTERVAL_HOURS, MIN_COUNT);
      return;
    }
    Schedule schedule = new Schedule(hours, count, decayValue.equals("true"));
    String namespace = Matcher.wildcardNamespace(target);
    if (namespace != null) {
      namespaces.put(namespace, schedule);
      return;
    }
    ResourceLocation id = ResourceLocation.tryParse(target);
    if (id == null) {
      LootrAPI.LOG.warn("Personal cycle: ignoring the override '{}': '{}' is not a loot table id.", entry, target);
      return;
    }
    tables.put(id, schedule);
  }

  public boolean enabled() {
    return enabled;
  }

  public Schedule defaultSchedule() {
    return schedule;
  }

  // An exact loot table id beats a namespace wildcard, which beats the section's values.
  public Schedule scheduleFor(@Nullable ResourceLocation lootTable) {
    if (lootTable == null) {
      return schedule;
    }
    Schedule exact = tableOverrides.get(lootTable);
    if (exact != null) {
      return exact;
    }
    return namespaceOverrides.getOrDefault(lootTable.getNamespace(), schedule);
  }

  public boolean isTableExcluded(ResourceLocation lootTable) {
    return excludedTables.matches(lootTable);
  }

  public boolean isDimensionExcluded(ResourceLocation dimension) {
    return excludedDimensions.matches(dimension);
  }

  /**
   * Exact ids and `namespace:*` wildcards.
   */
  private record Matcher(Set<ResourceLocation> ids, Set<String> namespaces) {
    static final Matcher EMPTY = new Matcher(Set.of(), Set.of());

    static Matcher parse(@Nullable Collection<? extends String> entries, String key) {
      if (entries == null || entries.isEmpty()) {
        return EMPTY;
      }
      Set<ResourceLocation> ids = new HashSet<>();
      Set<String> namespaces = new HashSet<>();
      for (String entry : entries) {
        if (entry == null || entry.isBlank()) {
          continue;
        }
        String trimmed = entry.trim();
        String namespace = wildcardNamespace(trimmed);
        if (namespace != null) {
          namespaces.add(namespace);
          continue;
        }
        ResourceLocation id = ResourceLocation.tryParse(trimmed);
        if (id == null) {
          LootrAPI.LOG.warn("Personal cycle: ignoring the entry '{}' of '{}'; it is neither an id nor 'namespace:*'.", entry, key);
          continue;
        }
        ids.add(id);
      }
      return new Matcher(Set.copyOf(ids), Set.copyOf(namespaces));
    }

    @Nullable
    static String wildcardNamespace(String value) {
      if (value.endsWith(":*") && value.length() > 2) {
        String namespace = value.substring(0, value.length() - 2);
        return ResourceLocation.isValidNamespace(namespace) ? namespace : null;
      }
      return null;
    }

    boolean matches(ResourceLocation id) {
      return (!ids.isEmpty() && ids.contains(id)) || (!namespaces.isEmpty() && namespaces.contains(id.getNamespace()));
    }
  }
}
