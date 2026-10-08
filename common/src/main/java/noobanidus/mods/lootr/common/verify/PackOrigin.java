package noobanidus.mods.lootr.common.verify;

/**
 * Who is responsible for a resource: the mod that ships it, a data pack that is not a mod's, or (for loot tables
 * that exist only in the registry and whose namespace is no mod id) just the namespace.
 *
 * @param id      the mod id, the pack id (e.g. `file/pack.zip`) or the namespace.
 * @param name    the mod's display name; the id for the other two types.
 * @param version the mod's version; empty for the other two types.
 */
public record PackOrigin(Type type, String id, String name, String version) {
  public enum Type {
    MOD("mod"),
    PACK("pack"),
    NAMESPACE("namespace");

    private final String label;

    Type(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  public static PackOrigin mod(String id, String name, String version) {
    return new PackOrigin(Type.MOD, id, name == null || name.isBlank() ? id : name, version == null ? "" : version);
  }

  public static PackOrigin pack(String packId) {
    return new PackOrigin(Type.PACK, packId, packId, "");
  }

  public static PackOrigin namespace(String namespace) {
    return new PackOrigin(Type.NAMESPACE, namespace, namespace, "");
  }

  /**
   * @return the stable key findings refer to: the mod id, `pack:` plus the pack id, or `namespace:` plus the
   * namespace. A mod id contains no colon, so the three cannot collide.
   */
  public String key() {
    return switch (type) {
      case MOD -> id;
      case PACK -> "pack:" + id;
      case NAMESPACE -> "namespace:" + id;
    };
  }

  /**
   * @return the origin as people read it in the report.
   */
  public String label() {
    return switch (type) {
      case MOD -> name + " (" + id + (version.isEmpty() ? "" : " " + version) + ")";
      case PACK -> "Pack " + id;
      case NAMESPACE -> "Namespace " + id + " (no file found)";
    };
  }
}
