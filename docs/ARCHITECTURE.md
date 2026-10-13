# Architecture

How the repository is organised and how one version branch is built. Mod id: `lootr`; package root:
`noobanidus.mods.lootr`. Everything below describes what exists on `mc/1.21.1` today (Fabric jar); the NeoForge build
on that branch is in progress, and every other branch and loader is planned.

## Hub and version branches

- **`main` is the hub.** It has no game code. It holds the documentation, `scripts/`, `templates/`, the art generator
  (`art/`), the CurseForge material (`docs/curseforge/`), the licences, the changelog and the credits.
- **`mc/<minecraft version>` is a version branch.** It holds everything needed to build the mod for that Minecraft
  version and nothing about other versions. At the moment the only branch is `mc/1.21.1`.
- Locally each version branch is a git worktree at `versions/<minecraft version>/` of the main checkout
  (`scripts/setup-worktrees.ps1`). `versions/` is git-ignored on `main`.
- The infra files that must be the same on all branches (`build-all.ps1`, `release.ps1`, `.gitattributes`,
  `.github/workflows/build.yml`) are kept in `templates/branch/` and copied onto the worktrees by
  `scripts/sync-branch-infra.ps1`.

## Layout of a version branch

```
gradle/shared.properties   mod id, name, version, licence, authors, Minecraft version, Java version and the library
                           versions that every loader uses
common/
  src/main/java            the mod's code, loader-neutral
  src/main/resources       assets, data, mixin config of the common code
datapacks/                 two optional data packs, packed into the jar
fabric/                    standalone Gradle project: build.gradle, settings.gradle, gradle.properties, gradlew(.bat),
                           gradle/wrapper, src/main (entry points, implementations, mixins), src/gametest
neoforge/, forge/          the same shape for the other loaders (neoforge/ is in progress on mc/1.21.1; forge/ is planned)
build-all.ps1              builds every loader folder in turn
release.ps1                builds, checks and publishes the jars of every loader folder
<loader>/release/          the jars of a release and SHA256SUMS.txt (written by release.ps1)
```

**Shared properties.** `gradle/shared.properties` is loaded by the `settings.gradle` of each loader folder (it reads
`../gradle/shared.properties` and sets every key as a project property). Mod id, version and Minecraft version are
therefore defined once per branch. Properties that only one loader needs (loader version, loader API version, mod
menu version, ...) are in that loader's own `gradle.properties`.

**`common/` is a source tree, not a Gradle project.** There is no `build.gradle` in it and no published artifact. Each
loader build adds it to its own source sets:

```groovy
java.srcDir "${commonDir}/src/main/java"
resources.srcDir "${commonDir}/src/main/resources"
```

so the code of `common/` is compiled into every loader jar together with the loader's own sources.

**`checkCommonIsLoaderNeutral`.** Every loader build has a task of this name; it is part of `check` and so of `build`. It
reads every `.java` file under `common/src/main/java` and fails the build if a line imports `net.fabricmc`,
`net.neoforged` or `net.minecraftforge`. Code in `common/` can therefore use Minecraft's own classes but must reach
anything loader-specific through the interfaces described below.

**Standalone loader projects.** `fabric/`, `neoforge/` and `forge/` each have their own Gradle wrapper and can be built
alone (`gradlew build --no-daemon` inside the folder). They share no Gradle state; the only thing they share is
`common/` and `gradle/shared.properties`. `build-all.ps1` finds the loader folders by looking for a `gradlew.bat` in
the top-level folders and builds them one after the other; `release.ps1` uses the same rule. Each loader's jar is
`<loader>/build/libs/lootr-remastered-<loader>-<minecraft>-<mod version>.jar`; `release.ps1` copies it to
`<loader>/release/` (see [RELEASING.md](RELEASING.md)).

## How common code reaches the loader

The common code needs to ask things that only the running loader can answer (which mod is loaded, what is the config
directory, how is a packet sent) and to use the blocks, items and block entity types that the loader registers. It
does this through three interfaces and static facades in
`common/src/main/java/noobanidus/mods/lootr/common/api/`. They are not found through Java's `ServiceLoader`: each loader's
entry point creates its implementations and assigns them to the `INSTANCE` field of the facade when the mod starts (on
Fabric, `noobanidus.mods.lootr.fabric.Lootr`).

- **`ILootrAPI`** (facade `LootrAPI`, `INSTANCE`): the central interface. It gives access to the server and the
  players, to the per-player inventories and the saved data of a container (`getInventory`, `getData`), to the loot
  seed, and to all configuration values the common code reads (decay and refresh settings, break protection, dimension
  and loot table lists, conversion switches, notification and texture settings). It also hands out the message styles
  and components, and records advancements and statistics (`award`, `isAwarded`). The implementation in the loader
  builds on `DefaultLootrAPIImpl` in `common/impl`, which implements what does not depend on the loader.
- **`IPlatformAPI`** (facade `PlatformAPI`, `INSTANCE`): the parts that differ between loaders. It handles opening and
  closing of block and entity containers (`performBlockOpen`, `performEntityOpen`, ...), copying block entity data when
  a container is replaced by a Lootr container, sending container status and sync packets to players, the pot break
  hook, and a few questions about the environment: `isModLoaded`, `getConfigDirectory`, `getGameDirectory` and the
  mod that owns a data pack (`describePack`, `describeMod`). The last four have defaults, so an implementation that
  does not know the answer returns nothing. `DefaultPlatformAPIImpl` in `common/impl` is the base for the loader
  implementations.
- **`ILootrRegistry`** (facade `LootrRegistry` in `common/api/registry`, `INSTANCE`): the objects the loader registers
  with the game. It returns the Lootr blocks (chest, trapped chest, barrel, shulker box, suspicious sand and gravel,
  decorated pot, trophy, inventory block), their items, the minecart and item frame entity types, the block entity
  types, the advancement and container triggers, the looted statistic, the creative tab, the loot condition type and the
  particle types. Registration itself is done by the loader, because it is done differently on each one.

`LootrAPI` also holds constants (mod id `lootr`, network version, the built-in loot table keys) and static helpers
that call through `INSTANCE`.

## Main feature packages in `common`

Under `noobanidus/mods/lootr/common/`:

- **Per-player loot, from Lootr**: `block`, `entity`, `data` (saved data and the per-player inventory), `chunk`
  (the loaded chunks per dimension), `loot`, `advancement`, `particle`, `client`, `command`, `config`, `mixin`
  (twenty groups of mixins, for example for locking, hoppers, redstone, structure saving and teams), `api`
  (the interfaces above and the public API of the original mod) and `integration` (compatibility code for Create,
  Jade, Sherds API and Digsite Workshop).
- **`itemban`**: item bans. `ItemBans` answers whether an item is banned; the sources of bans are `ObliteratorBanSource`
  (the items Item Obliterator has disabled, read through its public check method) and `ConfigBanSource` (the config
  list `extra_banned_items`). `LootRollHook` is a mixin config plugin that applies the loot table mixin of `mixin/itemban`
  to the loot roll (if another mod has changed the hooked method it logs one warning and the all-loot filter stays off);
  stored per-player inventories are cleaned when they are opened. Settings: `ItemBanSettings`.
- **`integratedloot`**: extra stacks from other loot tables. `IntegratedLootDefinitions` reads the pool and rule files
  from data packs (`data/<namespace>/lootr/integrated_loot/pool|rule/`) on every data reload, `TablePattern` matches loot table ids against
  the patterns of a rule, and `IntegratedLoot` adds stacks from the source tables to the roll result of a matching
  table. `IntegratedLootSettings` holds the config, `IntegratedLootSnapshot` the loaded pools and rules,
  `IntegratedLootCommand` is `/lootr integrated`, and `LootIntegrationsImport` takes over the values of the old
  `config/lootintegrations.json` once.
- **`verify`**: the loot table verification behind `/lootr verify` (`VerifyCommand`). `LootVerifier` and `VerifyRun`
  run a verification in the background, `VerifyScan`, `ReferenceScanner`/`ReferenceIndex`, `TableAnalyzer` and
  `LoadConditions` read and judge the loot table files, `RollSampler` rolls tables, `Origins`/`PackOrigin` find the mod or
  pack responsible for a file, and `VerifyReport`/`VerifyResult` write the Markdown and JSON reports.

The user-visible behaviour of these features is described in the [user guide](USER_GUIDE.md).
