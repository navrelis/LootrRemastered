# Porting

How a version branch (`mc/<minecraft version>`) is made, which upstream branch each one starts from, and which toolchains
are planned. Read [ARCHITECTURE.md](ARCHITECTURE.md) first for the shape of a branch.

Status today: `mc/1.21.1` exists with a Fabric build (available) and a NeoForge build (in progress). Everything else in
this document is **planned**.

## Rules

1. **The base of a version is the upstream Lootr branch for that Minecraft version**, plus the Remastered features
   (per-player loot is upstream's; integrated loot, item bans, the verification command, the fixes listed in
   `CHANGELOG.md`). Upstream is [LootrMinecraft/Lootr](https://github.com/LootrMinecraft/Lootr). Only its code is used,
   because only the code is MIT.
2. **Upstream's assets are never copied.** Textures, models, sounds and the logo of Lootr are All Rights Reserved
   (see [LICENSE_ASSETS.txt](../LICENSE_ASSETS.txt)). The pixel art comes from the generator in `art/`
   (`python art/generate.py`, with `LOOTR_VERSION_DIR` pointing at the branch; see [art/README.md](../art/README.md)).
   How each resource file of `mc/1.21.1` was handled is in [licence-audit.md](licence-audit.md); a built jar is checked with
   `python docs/tools/check_jar.py <jar>`, which must end without FAIL.
3. **Loader-specific model JSON is written from the loader's own code**, not copied from upstream's asset files.
4. **`common/` stays loader-neutral** (`checkCommonIsLoaderNeutral`); everything else goes through the interfaces in
   `common/.../api/` (see ARCHITECTURE.md).

## Upstream bases

Upstream repository: `LootrMinecraft/Lootr`. Remote branches (`git remote add upstream`, then `git fetch upstream`):

| Minecraft | Upstream branch(es) | Notes |
|---|---|---|
| 1.16.5 | `1.16` | Forge only. Upstream has no Fabric branch for this version, so a Fabric port has no upstream base. |
| 1.18.2 | `1.18.2` + `fabric-1.18` | |
| 1.19.2 | `1.19` + `fabric-1.19` | |
| 1.20.1 | `1.20.1` + `fabric-1.20` | |
| 1.21.1 | `mdg-1.21.1` | `mc/1.21.1` exists; Fabric is built from it. The project's starting commit is `b62e382a` (Lootr 1.11.38.128). |
| 1.21.11 | `mdg-1.21.11` | |
| 26.3 | `mdg-26.3` | needs Java 25 |

## Planned toolchains

Everything here is **planned, not yet proven by a build**, except Fabric 1.21.1, which is proven.

| Loader / version | Plugin | Gradle | Status |
|---|---|---|---|
| Fabric 1.21.1 | Fabric Loom 1.14.7 | 9.2.1 | proven (the build of `mc/1.21.1`) |
| NeoForge | ModDevGradle (`net.neoforged.moddev`) 2.0.147 | 9.2.1 | planned, not yet proven by a build |
| Forge 1.21.1 and later | ForgeGradle 7.0.40 | 9.3.1 | planned, not yet proven by a build |
| Forge 1.18.2 to 1.20.1 | ModDevGradle Legacy 2.0.147 | 8.14.5 | planned, not yet proven by a build |
| Forge 1.16.5 | Architectury Loom | to be chosen | planned, not yet proven by a build |

Java: 26.3 needs Java 25. The Java version of every branch is set by `java_version` in `gradle/shared.properties`.
Versions of the loaders and of the other Fabric versions (1.16.5 to 1.20.1, 1.21.11, 26.3) are not decided yet.

## Order of the ports

1.21.1 (exists), 1.21.11, 26.3, 1.20.1, 1.19.2, 1.18.2, 1.16.5.

## Steps to add a branch

1. **Create the branch** from the nearest finished branch (not from `main`, which has no game code), named
   `mc/<minecraft version>`, for example `git switch -c mc/1.21.11 mc/1.21.1`. Where the Minecraft version has a
   different upstream base, start from the upstream branch of the table above, take over the Remastered code, and drop
   upstream's assets (rules 1 and 2).
2. **Copy the infra files** from `templates/branch/` (`build-all.ps1`, `release.ps1`, `.gitattributes`,
   `.github/workflows/build.yml`), or run `pwsh scripts/sync-branch-infra.ps1` once the worktree exists (step 6).
3. **Write `gradle/shared.properties`**: `mod_id`, `mod_version`, `minecraft_version`, `minecraft_version_range`,
   `java_version`, `ci_gradle_jdk`, mappings and library versions. Use the same `mod_version` as the other branches
   of the same release.
4. **Add the loader folders** (`fabric/`, `neoforge/`, `forge/` as the version allows). Each one is a standalone Gradle
   project with its own wrapper at the Gradle version of the toolchain table; its `settings.gradle` loads
   `../gradle/shared.properties`; its `build.gradle` adds `common/src/main/{java,resources}` with `srcDir` and
   registers `checkCommonIsLoaderNeutral`. Put the loader's entry point and its implementations of `ILootrAPI`,
   `IPlatformAPI` and `ILootrRegistry` in the loader folder.
5. **Port `common/`** to the Minecraft version until every loader folder builds with `gradlew build`.
6. **Add the worktree**: push the branch, then `pwsh scripts/setup-worktrees.ps1 -Fetch`. The branch appears at
   `versions/<minecraft version>/`.
7. **Check** as in [TESTING.md](TESTING.md), then build the jars with `release.ps1` ([RELEASING.md](RELEASING.md)).
8. **Update the support matrix** in `README.md` on `main`: a loader is "available" only when its jar is built from the
   branch, "in progress" while the work is open.
