# Testing

What is checked on a version branch and how. Status today (`mc/1.21.1`): the build, the Fabric game tests and the
server start test are in use; the server start test is done by hand; everything for NeoForge and Forge is planned; the
client checks are manual.

## Rules for every Gradle call

- Always `--no-daemon`. A daemon that is left behind keeps running after the command.
- Never `gradlew --stop`: it also stops the daemons of other builds on the machine.
- No Gradle call from `main`: it has no build. Work in the worktree of the branch (`versions/<minecraft version>/`) or
  use `scripts/build-all-versions.ps1`.

## Build

Per branch, either in each loader folder:

```powershell
cd versions/1.21.1/fabric
./gradlew build --no-daemon        # gradlew.bat on Windows cmd
```

or for all loader folders of the branch (this is `build-all.ps1`, same on every branch):

```powershell
cd versions/1.21.1
./build-all.ps1                    # default task: build; stops at the first failing loader
```

and for every worktree: `pwsh scripts/build-all-versions.ps1` from `main`.

`build` includes `check`, which includes `checkCommonIsLoaderNeutral` (no loader imports in `common/`) and compiles the
game tests. It does **not run** the game tests. The jar ends up in `<loader>/build/libs/`.

## Fabric game tests

```powershell
cd versions/1.21.1/fabric
./gradlew runGametest --no-daemon
```

97 tests on `mc/1.21.1` (sources: `fabric/src/gametest`). They run on a headless server with vanilla content; the
process ends with a non-zero code if a test fails, and the JUnit report is written to `fabric/build/gametest/junit.xml`.
The tests are not part of the release jar. The groups in the folder: core behaviour of Lootr containers, fixes of
defects of the original, performance fixes, integrated loot, item bans, the verification, and a smoke group.

NeoForge game tests and Forge game tests: **planned**. Until they exist a NeoForge or Forge jar is tested only by the
server start test and the client checks below.

## Dedicated server start test

Start the built jar on a dedicated server with the loader and the mods it needs, and check that the log shows the mod
loading and the server reaching "Done", with no error from `lootr`. The test server uses the port **25611** and
`online-mode=false` (so that no account is needed), with the EULA accepted in that test folder. Done **by hand** so
far; a script for it is **planned**.

## Client checks (manual)

There is no automated client test. Before a release, with the jar in a client and on a server:

- open each container type: chest, trapped chest, barrel, shulker box, minecart with chest, decorated pot (break it),
  suspicious sand and gravel (brush), item frame with an elytra;
- per-player contents: a second player opening the same container gets their own, full contents;
- toasts: the decay and refresh toasts appear and use the mod's own art;
- config screen (on Fabric: Mod Menu with Cloth Config): opens, saves, values are applied;
- built-in data packs: "Disable Lootr Advancements" and "Disable Lootr Converting Suspicious Blocks" are listed
  and can be enabled;
- `/lootr verify` runs and writes `logs/lootr/verify-latest.md`.

## Jar check

```powershell
python docs/tools/check_jar.py versions/1.21.1/fabric/build/libs/lootr-remastered-fabric-1.21.1-2.1.0.jar
```

Run from `main`. It reads the jar and the git history only. It lists entries that must not be in the jar, entries
identical to files of the upstream revision, text that points at third-party projects, and whether `LICENSE` and
`LICENSE_ASSETS.txt` are at the jar root and equal to the repository files. The result line must be `no FAIL`
([licence-audit.md](licence-audit.md) explains each point).

## Art and showcase checks (on `main`)

`python art/verify.py [git-revision]` checks the generated art in the branch named by `LOOTR_VERSION_DIR`;
`python art/showcase_preview.py --check` checks the CurseForge material. See [art/README.md](../art/README.md) and
[curseforge/README.md](curseforge/README.md).
