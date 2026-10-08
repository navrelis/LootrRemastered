package noobanidus.mods.lootr.common.integratedloot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * A loot table id pattern of the form `namespace:path`. Both parts may contain wildcards: `*` matches any run of
 * characters except `/`, `**` matches any run of characters including `/`. Without a wildcard it is an exact id.
 */
public final class TablePattern {
  public static final Codec<TablePattern> CODEC = Codec.STRING.comapFlatMap(TablePattern::parse, TablePattern::toString);

  private final String source;
  private final String namespace;
  private final String path;
  // null where the part has no wildcard and is compared as it is.
  @Nullable
  private final Pattern namespacePattern;
  @Nullable
  private final Pattern pathPattern;
  // The literal start of a path with wildcards; rejects most tables without running the regular expression.
  private final String pathPrefix;

  private TablePattern(String source, String namespace, String path) {
    this.source = source;
    this.namespace = namespace;
    this.path = path;
    this.namespacePattern = compile(namespace);
    this.pathPattern = compile(path);
    this.pathPrefix = pathPattern == null ? path : path.substring(0, path.indexOf('*'));
  }

  public static DataResult<TablePattern> parse(String text) {
    if (text == null) {
      return DataResult.error(() -> "Table pattern is missing");
    }
    int colon = text.indexOf(':');
    if (colon <= 0 || colon == text.length() - 1 || text.indexOf(':', colon + 1) != -1) {
      return DataResult.error(() -> "Table pattern '" + text + "' is not of the form namespace:path");
    }
    String namespace = text.substring(0, colon);
    String path = text.substring(colon + 1);
    for (int i = 0; i < namespace.length(); i++) {
      char c = namespace.charAt(i);
      if (c != '*' && !validNamespaceChar(c)) {
        return DataResult.error(() -> "Table pattern '" + text + "' has the invalid character '" + c + "' in its namespace");
      }
    }
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c != '*' && c != '/' && !validNamespaceChar(c)) {
        return DataResult.error(() -> "Table pattern '" + text + "' has the invalid character '" + c + "' in its path");
      }
    }
    return DataResult.success(new TablePattern(text, namespace, path));
  }

  private static boolean validNamespaceChar(char c) {
    return c == '_' || c == '-' || c == '.' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
  }

  @Nullable
  private static Pattern compile(String glob) {
    if (glob.indexOf('*') == -1) {
      return null;
    }
    StringBuilder regex = new StringBuilder();
    int literalStart = 0;
    int i = 0;
    while (i < glob.length()) {
      if (glob.charAt(i) != '*') {
        i++;
        continue;
      }
      if (i > literalStart) {
        regex.append(Pattern.quote(glob.substring(literalStart, i)));
      }
      int stars = 0;
      while (i < glob.length() && glob.charAt(i) == '*') {
        stars++;
        i++;
      }
      regex.append(stars == 1 ? "[^/]*" : ".*");
      literalStart = i;
    }
    if (literalStart < glob.length()) {
      regex.append(Pattern.quote(glob.substring(literalStart)));
    }
    return Pattern.compile(regex.toString());
  }

  /**
   * @return true if the pattern has no wildcard and therefore names exactly one loot table.
   */
  public boolean isExact() {
    return namespacePattern == null && pathPattern == null;
  }

  public boolean matches(ResourceLocation id) {
    if (namespacePattern == null ? !namespace.equals(id.getNamespace()) : !namespacePattern.matcher(id.getNamespace()).matches()) {
      return false;
    }
    if (pathPattern == null) {
      return path.equals(id.getPath());
    }
    return id.getPath().startsWith(pathPrefix) && pathPattern.matcher(id.getPath()).matches();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof TablePattern pattern && pattern.source.equals(source);
  }

  @Override
  public int hashCode() {
    return source.hashCode();
  }

  /**
   * @return the pattern exactly as it was written in the rule file.
   */
  @Override
  public String toString() {
    return source;
  }
}
