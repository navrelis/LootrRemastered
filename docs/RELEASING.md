# Releasing

How the jars of a version branch are built and checked, and what is prepared for CurseForge. Releases are done by
the owner; there is no upload script.

## Versions and names

- Mod version on `mc/1.21.1`: **2.1.0** (`mod_version` in `gradle/shared.properties`). Released so far: 2.0.0 for
  Fabric 1.21.1, tag `fabric-1.21.1-2.0.0`.
- Jar: `lootr-remastered-<loader>-<minecraft>-<mod version>.jar`, for example `lootr-remastered-fabric-1.21.1-2.1.0.jar`
  (in `<loader>/build/libs/`; the `-sources` jar next to it is not a release file).
- Declared mod version, inside the jar (`fabric.mod.json`, `META-INF/neoforge.mods.toml`, `META-INF/mods.toml`):
  `<minecraft>-<mod version>`, for example `1.21.1-2.1.0`.
- `LICENSE` and `LICENSE_ASSETS.txt` are packed at the jar root by the loader build.

## Version bump

Change `mod_version` in `gradle/shared.properties` of the branch; every loader folder reads it from there. A change of
`minecraft_version` is a port, not a version bump ([PORTING.md](PORTING.md)). Add the entry to `CHANGELOG.md` on `main`.

## Build and publish a branch: `release.ps1`

Run inside the branch (`versions/1.21.1/` in the main checkout), PowerShell 7. The script is the same on every branch
(`templates/branch/release.ps1`).

```powershell
cd versions/1.21.1
pwsh ./release.ps1              # build, check, publish
pwsh ./release.ps1 -NoBuild     # reuse the jars already in <loader>/build/libs
```

For every loader folder (any top-level folder with a `gradlew.bat`) it:

1. runs `gradlew build --no-daemon --stacktrace` in the folder (not with `-NoBuild`);
2. looks for exactly one `build/libs/lootr-remastered-<loader>-*-<mod version>.jar` (the `-sources` jar does not match);
3. opens the jar and compares the version in its mod metadata with `<minecraft_version>-<mod_version>` from
   `gradle/shared.properties`; a mismatch, or a jar without any of the three metadata files, is an error;
4. deletes the old `*.jar` in `<loader>/release/`, copies the new jar there and writes
   `<loader>/release/SHA256SUMS.txt` (`<sha256>  <jar name>`);
5. prints a table with one row per loader (status, jar, SHA-256).

It exits with 1 if any loader fails to build, has no or several matching jars, or has a version mismatch; the other
loaders are still processed. Nothing is committed or tagged.

Build only, without publishing: `./build-all.ps1` in the branch, or `pwsh scripts/build-all-versions.ps1` on `main` for
every worktree (`-Only 1.21.1` for one). Both build with `--no-daemon`.

## Release checklist

1. Version bump and changelog.
2. `release.ps1` on the branch; read the table.
3. Test the jar as in [TESTING.md](TESTING.md).
4. Licence check of the jar from the `main` checkout:
   `python docs/tools/check_jar.py versions/1.21.1/fabric/release/lootr-remastered-fabric-1.21.1-2.1.0.jar`; it must end
   without FAIL (what it checks: [licence-audit.md](licence-audit.md)).
5. Commit on the branch, tag (`<loader>-<minecraft>-<mod version>`, like `fabric-1.21.1-2.0.0`), push.

## CurseForge

The material for the project page is in [curseforge/](curseforge/): `description.md` (source text with image markers),
`description_published.md` (ready to paste, with the gallery addresses), `preview.html`, `summary.txt`, `img/` and
[upload-checklist.md](curseforge/upload-checklist.md), the list of what to check and enter on the upload forms.
`curseforge/README.md` explains the generators for the images and for the two derived files.

**The uploads are done by the owner by hand**, with the jar from `<loader>/release/` and the checklist. There is no
upload script and no API key in this repository.

## CI

`.github/workflows/build.yml` (from `templates/branch/`) runs on pushes and pull requests to `mc/**`. It discovers the
loader folders and builds each one with the JDK named by `ci_gradle_jdk` in `gradle/shared.properties`.
