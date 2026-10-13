# Lootr Remastered

Lootr Remastered is a Minecraft mod (mod id `lootr`) that gives every player their own copy of the loot in a
loot container, adds a few extra stacks from other loot tables to the containers of vanilla and modded structures,
keeps banned items out of loot rolls and Lootr containers, and can check a whole pack's loot tables with one command.
It is a fork of [Lootr](https://github.com/LootrMinecraft/Lootr) (code under the MIT licence) and takes over the job of
the mod Loot Integrations with its add-ons, in one jar.

This branch, `main`, is the **hub**: it has no game code. The mod itself lives on one branch per Minecraft version,
`mc/<minecraft version>`. `main` holds what ties the branches together: documentation, scripts, the templates for
the files every branch shares, the art generator, the CurseForge material, the licences, the changelog and the credits.

What the mod does is described in the [user guide](docs/USER_GUIDE.md) (requirements, installation, features, commands,
configuration, integrated loot data, verification report, known limitations).

## Support matrix

"available" means that the branch builds a jar for this loader; "in progress" that the work has started and no jar is
ready; "planned" that nothing exists yet. Only 2.0.0 for Fabric 1.21.1 has been released.

| Minecraft | Fabric | NeoForge | Forge | Branch |
|---|---|---|---|---|
| 1.21.1 | available (2.1.0 built from the branch; 2.0.0 released, tag `fabric-1.21.1-2.0.0`) | in progress | planned | `mc/1.21.1` |
| 1.21.11 | planned | planned | planned | `mc/1.21.11` (planned) |
| 26.3 | planned | planned | planned | `mc/26.3` (planned) |
| 1.20.1 | planned | use the Forge jar | planned | planned |
| 1.19.2 | planned | no NeoForge for this version | planned | planned |
| 1.18.2 | planned | no NeoForge | planned | planned |
| 1.16.5 | planned | no NeoForge | planned | planned |

Order of the ports and the upstream branch each one starts from: [docs/PORTING.md](docs/PORTING.md).

## Where the code is

Each version branch is self-contained and has this shape (details in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)):

```
common/               code and resources shared by all loaders of the branch: a source tree without a build file
fabric/               standalone Gradle build (own wrapper) for the Fabric jar
neoforge/, forge/     the same for the other loaders, where the branch has them
gradle/shared.properties   mod id, version, Minecraft version, library versions; read by every loader build
datapacks/            the two optional data packs that are built into the jar
build-all.ps1         builds every loader folder of the branch
release.ps1           builds, checks and copies the jars to <loader>/release/
```

The loader folders do not depend on each other: each one compiles `common/` into its own jar through a `srcDir`, so
`gradlew build` in one loader folder is enough to get that loader's jar. `build-all.ps1` and `release.ps1`, the CI
workflow and `.gitattributes` are the same on every branch; their master copies are in [templates/branch/](templates/branch/).

Jar names: `lootr-remastered-<loader>-<minecraft>-<mod version>.jar`, for example
`lootr-remastered-fabric-1.21.1-2.1.0.jar`. The version declared inside the jar is `<minecraft>-<mod version>`.
See [docs/RELEASING.md](docs/RELEASING.md).

## Getting the branches

Locally every version branch is checked out as a git worktree under `versions/<minecraft version>/` of the main
checkout (`versions/` is git-ignored). With PowerShell 7:

```powershell
git clone <repository> "Lootr Remastered"
cd "Lootr Remastered"
pwsh scripts/setup-worktrees.ps1 -Fetch     # one worktree per origin/mc/* branch; safe to run again
pwsh scripts/build-all-versions.ps1         # build-all.ps1 in every worktree (needs Java and network for Gradle)
cd versions/1.21.1/fabric
./gradlew build --no-daemon                 # or just one loader
```

| Path | Contents |
|---|---|
| `docs/USER_GUIDE.md` | the user documentation of the mod |
| `docs/ARCHITECTURE.md` | hub and version branches, layout of a branch, how `common/` reaches the loader |
| `docs/PORTING.md` | how a version branch is made, upstream bases, toolchains, order of the ports |
| `docs/RELEASING.md` | version, jar names, `release.ps1`, CurseForge material |
| `docs/TESTING.md` | builds, game tests, server start test, manual client checks |
| `docs/curseforge/` | CurseForge description, images, upload checklist (its `README.md` explains the generators) |
| `docs/licence-audit.md`, `docs/tools/check_jar.py` | the licence audit and the script that checks a built jar |
| `art/` | generator for all pixel art and the showcase images, see [art/README.md](art/README.md) |
| `scripts/` | `setup-worktrees.ps1`, `build-all-versions.ps1`, `sync-branch-infra.ps1` |
| `templates/branch/` | the files every version branch shares, see [templates/README.md](templates/README.md) |
| `CHANGELOG.md`, `CREDITS.md` | changes per version; who the mod builds on |
| `LICENSE`, `LICENSE_ASSETS.txt` | code, data and art licence; licence of the artwork and resource files |

The art generator and the checks in `art/` read the game resources from the worktree named by the environment
variable `LOOTR_VERSION_DIR` (default `versions/1.21.1`).

## Documents

- [User guide](docs/USER_GUIDE.md)
- [Architecture](docs/ARCHITECTURE.md), [Porting](docs/PORTING.md), [Releasing](docs/RELEASING.md), [Testing](docs/TESTING.md)
- [Changelog](CHANGELOG.md), [Credits](CREDITS.md)
- [Licence](LICENSE), [asset licence](LICENSE_ASSETS.txt), [licence audit](docs/licence-audit.md)
- [CurseForge material](docs/curseforge/)

## Licence

Code, data and art: MIT, Copyright (c) 2026 navrelis for the changes, and the original MIT notice of Lootr; see
[LICENSE](LICENSE). Artwork and resource files: [LICENSE_ASSETS.txt](LICENSE_ASSETS.txt). Lootr was written by
Noobanidus; see [CREDITS.md](CREDITS.md).
