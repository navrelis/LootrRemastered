package noobanidus.mods.lootr.common.verify;

import net.minecraft.server.packs.resources.Resource;
import noobanidus.mods.lootr.common.api.PlatformAPI;
import noobanidus.mods.lootr.common.verify.VerifyResult.OriginInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Maps resources to the mod or pack that is responsible for them and remembers every origin it handed out.
 * Thread-safe.
 */
final class Origins {
  private final Map<String, String> keyByPack = new ConcurrentHashMap<>();
  private final Map<String, String> keyByNamespace = new ConcurrentHashMap<>();
  private final Map<String, PackOrigin> origins = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> packs = new ConcurrentHashMap<>();

  /**
   * @return the key of the origin of the pack the resource comes from.
   */
  String of(Resource resource) {
    String packId = resource.sourcePackId();
    String key = keyByPack.get(packId);
    if (key == null) {
      PackOrigin origin = null;
      try {
        origin = PlatformAPI.describePack(packId, resource.source());
      } catch (RuntimeException ignored) {
        // A platform that cannot tell leaves the pack id as the origin.
      }
      key = remember(origin == null ? PackOrigin.pack(packId) : origin);
      keyByPack.put(packId, key);
      packs.get(key).add(packId);
    }
    return key;
  }

  /**
   * For a loot table without a file: the mod of that id if there is one, otherwise the namespace itself.
   */
  String ofNamespace(String namespace) {
    return keyByNamespace.computeIfAbsent(namespace, name -> {
      PackOrigin origin = null;
      try {
        origin = PlatformAPI.describeMod(name);
      } catch (RuntimeException ignored) {
        // As above.
      }
      return remember(origin == null ? PackOrigin.namespace(name) : origin);
    });
  }

  private String remember(PackOrigin origin) {
    String key = origin.key();
    origins.putIfAbsent(key, origin);
    packs.computeIfAbsent(key, ignored -> new ConcurrentSkipListSet<>());
    return key;
  }

  /**
   * @return every origin handed out so far by key, ordered by key.
   */
  Map<String, OriginInfo> all() {
    Map<String, OriginInfo> result = new TreeMap<>();
    origins.forEach((key, origin) -> result.put(key, new OriginInfo(origin, List.copyOf(packs.get(key)))));
    return result;
  }
}
