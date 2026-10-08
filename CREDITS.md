# Credits

## Lootr (the original mod)

Lootr Remastered is a fork of [Lootr](https://github.com/LootrMinecraft/Lootr), version 1.11.38.128 (commit `b62e382a`).
The idea, the design and most of the core code come from there:

- **Noobanidus**, the author of Lootr.
- **ZestyBlaze** and **embeddedt**, contributors to Lootr (named as such in the original `fabric.mod.json`).
- Other people appear in the commit history of the original repository, among them **mezz** and the community
  translators who worked through the account `noobanidus-translations`. Their translations are not part of this release
  (see below).

The code of Lootr is MIT licensed; its notice is kept in [LICENSE](LICENSE). The artwork of the original Lootr is not included.

## Lootr Remastered

- **navrelis**: everything added or changed in this fork: integrated loot (rule and pool data, engine, built-in rule files), item bans, the loot table verification and its reports, the fixes and optimisations of the core, the game tests, the German language file, the pixel art and the logo, and the documentation.

## The idea of integrated loot

Adding a few stacks from other loot tables to the results of structure loot tables is the idea of the mod **Loot Integrations**
by **someaddons**, which also has add-on packs for particular mods. Loot Integrations is licensed All Rights Reserved. Lootr Remastered
contains no code and no data file of it or of its add-ons: the engine, the data format, the pools and the rule files were written
for this mod, and the mod only reads the key names of an existing `config/lootintegrations.json` once to take over its four settings.
Loot Integrations is not affiliated with this project and has not endorsed it.

## Art

All images were drawn for this mod by navrelis, using the script `art/generate.py`: textures of the containers, the trophy and its
model, the toasts, the mod icon, the data pack icons and the logo. They are MIT licensed. The textures of the original Lootr were
replaced. Minecraft textures are not included. Details: [LICENSE_ASSETS.txt](LICENSE_ASSETS.txt) and [docs/licence-audit.md](docs/licence-audit.md).

## Language files

`en_us` has the English text of Lootr, which Lootr's code generated; entries were added and changed here. `de_de` was written for this
mod. The other 30 translations of the original Lootr, made by its community, are not included until the original author and the
translators permit it.

## Mods this mod works with

The mod calls into, or runs next to, these projects. None of their code is included in the jar.

- **Fabric Loader** and **Fabric API** (FabricMC): required. **Fabric Loom** builds the mod.
- **Cloth Config API** (shedaniel): required; the config screen and the config file.
- **Item Obliterator** (AGNYA licence): optional. Lootr Remastered calls its public check method for a stack through a method handle and
  uses no code of it. Items it disables are kept out of loot.
- **Mod Menu** (TerraformersMC) and **Jade**: optional integrations (config screen entry, container tooltips).
- **Sherds API** and **Create**: the jar contains compatibility code; neither is required.
- **Parchment** mappings and Mojang's official mappings are used when building.
- Loot viewers such as **EMI Loot** and **Advanced Loot Info** keep working but show loot tables without the extras of integrated loot.
- The built-in integrated loot rules address the loot tables of vanilla Minecraft and of these mods, named by mod id (a rule
  file is active only when its mod is installed): abridged, adorabuild_structures, adventuredungeons, aether, aether_villages, artifacts, beautify,
  betterdeserttemples, betterdungeons, betterend, betterfortresses, betterjungletemples, betternether, betteroceanmonuments, betterstrongholds,
  betterwitchhuts, biomeswevegone, bosses_of_mass_destruction, deeperdarker, dungeons_arise, dungeons_arise_seven_seas, eternal_starlight,
  eternalnether, explorify, friendsandfoes, galosphere, illagerinvasion, incendium, medieval_buildings, medieval_nether, medievalend, mes,
  mns, mr_dungeons_andtaverns, mr_dungeons_andtavernsancientcityoverhaul, mss, mvs, repurposed_structures, sawmill, structory,
  structory_towers, supplementaries, t_and_t, takesapillage, terralith, twilightforest, villagesandpillages, yungsextras; and, by namespace
  (mods that run through a compatibility layer), astral_dimension and cataclysm. The authors of these mods did not take part in this project.

Minecraft is a trademark of Mojang AB. This mod is not affiliated with Mojang or Microsoft.
