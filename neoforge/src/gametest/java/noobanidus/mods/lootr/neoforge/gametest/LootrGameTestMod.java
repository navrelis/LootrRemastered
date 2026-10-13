package noobanidus.mods.lootr.neoforge.gametest;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import noobanidus.mods.lootr.gametest.platform.TestPlatforms;

/**
 * The test mod of the shared game tests on NeoForge. It installs the platform and registers the tests with the game
 * test system; the game test server loads it next to Lootr.
 */
@Mod(LootrGameTestMod.MODID)
public class LootrGameTestMod {
  public static final String MODID = "lootr_gametest";

  public LootrGameTestMod(IEventBus modBus) {
    TestPlatforms.install(new NeoForgeTestPlatform());
    modBus.addListener(RegisterGameTestsEvent.class, event -> {
      event.register(NeoForgeGameTests.class);
      NeoForgeGameTests.registerBatchFunctions(event);
    });
  }
}
