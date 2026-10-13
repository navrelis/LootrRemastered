package noobanidus.mods.lootr.gametest.platform;

/**
 * Holds the {@link TestPlatform} of the loader the tests run on. A plain static field is used instead of a service
 * lookup: the loader layer installs the platform in the constructor or initialiser of its test mod, which runs before
 * any test, and a field behaves the same under Fabric's and NeoForge's class loading.
 */
public final class TestPlatforms {
  private static volatile TestPlatform current;

  private TestPlatforms() {
  }

  public static void install(TestPlatform platform) {
    current = platform;
  }

  public static TestPlatform get() {
    TestPlatform platform = current;
    if (platform == null) {
      throw new IllegalStateException("No test platform installed; the test mod of the loader did not initialise");
    }
    return platform;
  }
}
