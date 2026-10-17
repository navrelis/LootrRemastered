package noobanidus.mods.lootr.neoforge.gametest;

import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import noobanidus.mods.lootr.gametest.LootrCoreTests;
import noobanidus.mods.lootr.gametest.LootrDefectFixTests;
import noobanidus.mods.lootr.gametest.LootrIntegratedLootTests;
import noobanidus.mods.lootr.gametest.LootrItemBanTests;
import noobanidus.mods.lootr.gametest.LootrLocateTests;
import noobanidus.mods.lootr.gametest.LootrPerformanceFixTests;
import noobanidus.mods.lootr.gametest.LootrSmokeTests;
import noobanidus.mods.lootr.gametest.LootrVerifyTests;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Registers the shared game tests with NeoForge. NeoForge builds the structure id of a test from the namespace of the
 * test class (`@GameTestHolder`, which loader-neutral classes cannot carry) and the lower-case name of the class, so a
 * template id such as `lootr_gametest:empty` would turn into `minecraft:lootrcoretests.lootr_gametest:empty`. This
 * generator therefore creates the test functions itself, with the names Fabric API gives them (`class.method`, both in
 * lower case) and the template exactly as written in `@GameTest`. The namespace of that template is also what
 * `neoforge.enabledGameTestNamespaces` filters generated tests by.
 */
public final class NeoForgeGameTests {
  // The same classes as the `fabric-gametest` entrypoints of the Fabric test mod.
  private static final List<Class<?>> TEST_CLASSES = List.of(
      LootrSmokeTests.class,
      LootrDefectFixTests.class,
      LootrItemBanTests.class,
      LootrIntegratedLootTests.class,
      LootrCoreTests.class,
      LootrVerifyTests.class,
      LootrPerformanceFixTests.class,
      LootrLocateTests.class
  );

  private NeoForgeGameTests() {
  }

  // The functions that run before and after a batch are registered per method, as the generator only returns tests.
  static void registerBatchFunctions(RegisterGameTestsEvent event) {
    for (Class<?> testClass : TEST_CLASSES) {
      for (Method method : testClass.getDeclaredMethods()) {
        if (method.isAnnotationPresent(BeforeBatch.class) || method.isAnnotationPresent(AfterBatch.class)) {
          event.register(method);
        }
      }
    }
  }

  @GameTestGenerator
  public static Collection<TestFunction> lootrTests() {
    List<TestFunction> functions = new ArrayList<>();
    for (Class<?> testClass : TEST_CLASSES) {
      Arrays.stream(testClass.getDeclaredMethods())
          .filter(method -> method.isAnnotationPresent(GameTest.class))
          .sorted(Comparator.comparing(Method::getName))
          .forEach(method -> functions.add(toTestFunction(testClass, method)));
    }
    return functions;
  }

  private static TestFunction toTestFunction(Class<?> testClass, Method method) {
    GameTest test = method.getAnnotation(GameTest.class);
    String name = testClass.getSimpleName().toLowerCase(Locale.ROOT) + "." + method.getName().toLowerCase(Locale.ROOT);
    String template = test.template().isEmpty() ? LootrGameTestMod.MODID + ":" + name : test.template();
    return new TestFunction(
        test.batch(),
        name,
        template,
        StructureUtils.getRotationForRotationSteps(test.rotationSteps()),
        test.timeoutTicks(),
        test.setupTicks(),
        test.required(),
        test.manualOnly(),
        test.attempts(),
        test.requiredSuccesses(),
        test.skyAccess(),
        invoker(testClass, method)
    );
  }

  // A new instance of the test class for every test, like the game test system does it.
  private static Consumer<GameTestHelper> invoker(Class<?> testClass, Method method) {
    return helper -> {
      try {
        method.invoke(testClass.getDeclaredConstructor().newInstance(), helper);
      } catch (InvocationTargetException e) {
        if (e.getCause() instanceof RuntimeException runtimeException) {
          throw runtimeException;
        }
        throw new RuntimeException(e.getCause());
      } catch (ReflectiveOperationException e) {
        throw new RuntimeException("Could not run " + testClass.getSimpleName() + "." + method.getName(), e);
      }
    };
  }
}
