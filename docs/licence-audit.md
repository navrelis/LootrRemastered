# Licence audit of Lootr Remastered 2.0.0

Audited tree: branch `claude/intelligent-gauss-f0435e`, HEAD `ad106f67` plus the uncommitted working tree (the edits of this
task, and the lead's rewrite of the block state and model JSON files, `fabric/build.gradle` and `fabric.mod.json`; the assets were
classified again after that rewrite, from the working tree). Upstream reference:
commit `b62e382a` (Lootr 1.11.38.128, branch `mdg-1.21.1`). Date of the audit: 2026-10-08, updated the same day.

Since the repository was split into a hub (`main`) and version branches, the game code, resources and build files named below (`common/`, `fabric/`, `datapacks/`, `gradle.properties`, `build.gradle`) are on the branch `mc/1.21.1`, checked out at `versions\1.21.1`; the audited tree is the one described above. The branch now builds version 2.1.0, so the 2.0.0 jars named below are built from the tag `fabric-1.21.1-2.0.0`.

This is a technical comparison of files, not legal advice. Where a verdict depends on how a licence sentence is
read, the audit says so and leaves the decision to the owner.

## Summary

| # | Point | Status | What to do |
|---|-------|--------|------------|
| 1a | Images (25 textures, icon, pack icons, logo) | pass | Nothing. No pixel of an original image is left. |
| 1b | 32 block state and model JSON files that were inherited from upstream's `assets` folder | pass, 6 files necessarily identical | 26 were rewritten from a functional specification and differ from upstream; 6 are byte-identical because there is nothing else to write (section 1b). Nothing is left that depends on a reading of the original notice, except the question whether those 6 one-line statements are protected at all. |
| 1c | Language files | pass, with a note | `en_us.json` text comes from MIT generator code (decision recorded). `de_de.json` is new. |
| 1d | Assets that upstream's data generator wrote (3 identical, 3 changed) | pass | Nothing. |
| 2 | Data (tags, loot tables, advancements, optional data packs) | pass | Nothing. All inherited files were written by upstream's MIT-licensed generator code; none was hand-written. |
| 3 | Third-party material (Loot Integrations, Item Obliterator, decompiled code) | pass | Nothing in code or data. `.knowledge/` is committed: owner decides whether it is published (section 3). |
| 4 | Metadata and notices | pass; jar entries to check after the build | `LICENSE` has our copyright line, `LICENSE_ASSETS.txt` replaced, README no longer points at upstream's Maven, `fabric/build.gradle` puts `LICENSE` and `LICENSE_ASSETS.txt` into the jar root, `fabric.mod.json` declares `breaks` for `lootintegrations`. Check after the build that both licence files are in the jar (section 6). |
| 5 | Git history | owner decision | Publish with history or from a fresh history (section 5). |
| 6 | The jar | open until the jar is built | Run `docs/tools/check_jar.py` (section 6). |

Items that must be settled before the repository is made public: 5 (history) and the `.knowledge/` question in 3. Point 1b is no longer
an owner decision in its old form; the owner can still choose to have the original author confirm it, which would only matter for the 6 identical files.

## Method

All comparisons use git object hashes, so "identical" means byte-identical (line endings as stored in git).

```
git ls-tree -r HEAD            # files of our tree with blob hashes
git ls-tree -r b62e382a        # files of upstream with blob hashes
git diff -M --stat b62e382a HEAD
git show b62e382a:<path>       # content of an upstream file
```

For every non-Java file under `common/src/main/resources/`, `fabric/src/main/resources/` and `datapacks/` the blob hash
was looked up among the upstream blobs, first at the upstream path with the same tail (upstream had the resources
in two places: `common/src/main/resources/` for hand-written files and `common/src/generated/resources/` for files
written by the data generator), then anywhere. Images were additionally compared pixel by pixel with an own PNG
decoder (RGBA, drawn pixel = alpha above 0 in either image) and with `art/verify.py b62e382a`. The scripts are
outside the repository; the jar check at the end is repeatable from `docs/tools/check_jar.py`.

Upstream's data generator is part of upstream's MIT-licensed code: `neoforge/src/main/java/noobanidus/mods/lootr/neoforge/gen/*`
(tags, atlases, loot tables, particles, advancements, `LootrLangProvider` for `en_us.json`) and `gen/optionals/*`
(the two optional data packs). `crowdin.yml` of upstream names `common/src/generated/resources/assets/lootr/lang/en_us.json` as the translation source.

## 1. Resources under `assets/` and `fabric/src/main/resources/`

Scope: every non-Java file in the jar's resource roots. The jar takes `common/src/main/resources/` and
`fabric/src/main/resources/` (`fabric/build.gradle`, `processResources`), the two data packs
`datapacks/lootr_no_advancements` and `datapacks/lootr_no_suspicious_blocks` (into `datapacks/` in the jar), and excludes
`META-INF/accesstransformer.cfg`. `fabric/build.gradle` also copies `LICENSE` and `LICENSE_ASSETS.txt` into the jar root (to be confirmed by a build). Nothing is added by `include` or shadowing: no third-party library is inside the jar.
`art/`, `docs/` and `.knowledge/` are not packaged.

### Counts

| Group | Files | (a) identical to upstream | (b) modified | (c) new |
|---|---:|---:|---:|---:|
| `common/.../assets/` | 65 | 9 (6 hand-written folder, 3 generated folder) | 55 (52 + 3) | 1 |
| `common/.../data/` | 137 | 79 (all generated folder) | 0 | 58 (57 integrated loot files + 1 tag) |
| `common/.../lootr-common.mixins.json`, `META-INF/accesstransformer.cfg` | 2 | 1 (the access transformer, not packaged) | 1 | 0 |
| `fabric/src/main/resources/` | 4 | 2 | 2 | 0 |
| `datapacks/` (packaged) | 20 | 18 | 2 (`pack.png`) | 0 |
| Total | 228 | 109 | 60 | 59 |

227 of the 228 are packaged (all but the access transformer).

### 1a. Images: all new or fully redrawn

45 PNG files are in the tree. None is an upstream file.

| Group | Files | Relation to upstream |
|---|---:|---|
| `assets/lootr/textures/**` | 25 | 24 exist at the same path upstream: every one differs; 0 identical drawn pixels in each (checked by an own decoder over all 24, and by `art/verify.py`: 100 % of drawn pixels differ). 1 is new (`block/trophy.png`). |
| `fabric/src/main/resources/icon.png` | 1 | upstream 400 x 400 (a different file from upstream's `logo.png`), ours 256 x 256 from `art/logo/logo_native.png` |
| `datapacks/*/pack.png` | 2 | upstream: byte-identical copies of its 400 x 400 `logo.png` (its generator copies `logo.png`); ours 64 x 64 |
| `art/logo/*.png`, `art/preview/*.png` | 2 + 15 | new; not packaged |

`art/generate.py` writes all of them; it contains a PNG reader only for its checks and reads no upstream file.
Layout (which region of a chest sheet is the lid) is dictated by Minecraft's own models and is not a Lootr expression.

Upstream's source art (`*.psd` x 4, `*.bbmodel` x 2, `logo.png`, the 15 images of the `old_textures` resource pack,
30 translations) is not in HEAD.

### 1b. The 32 block state and model JSON files

The first run of this audit found 32 JSON files in `common/src/main/resources/assets/lootr/` that were byte-identical to upstream's
hand-written assets folder, a folder that the original notice (`All Lootr Assets are ARR (All Rights Reserved).`) can be read to
cover. They had no image data and no model geometry. The lead then had them deleted and written again by an implementer who never opened the old
files, from a prose specification of what each file must contain (variants, parents, texture ids). The assets were classified
again from the working tree after that:

| Result against `b62e382a` | Files |
|---|---:|
| Rewritten: bytes differ from upstream | 26 |
| Still byte-identical | 6 |
| Total (the 32 of the first run) | 32 |

(The task message expected 27 and 5. The count of 27 changed files in `blockstates/` and `models/` includes our own `models/block/trophy.json`, which
was already new before; and `models/item/trophy.json`, a single `parent` line, is the sixth identical file.)

Rewritten (26), all under `common/src/main/resources/assets/lootr/`:
- `blockstates/` (4): `lootr_barrel`, `suspicious_gravel`, `suspicious_sand`, `trophy`
- `models/block/` (13): `decorated_pot`, `item_frame`, `item_frame_open`, `lootr_barrel_unopened`, `lootr_barrel_unopened_open`,
  `lootr_opened_barrel`, `lootr_opened_barrel_open`, `lootr_chest`, `lootr_inventory`, `lootr_shulker`, `lootr_trapped_chest`,
  `suspicious_gravel_open`, `suspicious_sand_open`
- `models/item/` (9): `decorated_pot`, `lootr_barrel`, `lootr_chest`, `lootr_inventory`, `lootr_opened_barrel`, `lootr_shulker`,
  `lootr_trapped_chest`, `suspicious_gravel`, `suspicious_sand`

What differs, measured by comparing the parsed JSON of old and new (namespace `minecraft:` ignored, key order ignored):
- 21 of the 26 differ only in formatting, key order, written-out namespaces or similar. They still say the same thing, because the
  specification says what the game needs: for example `{"parent": "minecraft:block/cube_all", "textures": {"all": "lootr:block/suspicious_sand_open"}}`
  has one correct form. Their bytes are ours; their meaning is what the engine requires.
- 5 differ in substance: the item models `decorated_pot`, `lootr_chest`, `lootr_inventory`, `lootr_shulker`, `lootr_trapped_chest`
  (1,530 bytes each upstream, with a `display` block of transform numbers) now inherit from vanilla (`{"parent": "minecraft:item/chest"}`,
  the pot adds a particle texture). The copied transform numbers are gone.
- None of the 32 contains `"elements"`; there is no model geometry in any of them.

Still byte-identical (6):

| File | Content | Why it cannot differ |
|---|---|---|
| `blockstates/decorated_pot.json` | one variant `""` pointing at one model | the only way to write it in the game's format |
| `blockstates/lootr_chest.json` | the same | the same |
| `blockstates/lootr_inventory.json` | the same | the same |
| `blockstates/lootr_shulker.json` | the same | the same |
| `blockstates/lootr_trapped_chest.json` | the same | the same |
| `models/item/trophy.json` | `{"parent": "lootr:block/trophy"}` | one parent line, fixed by the name of our own trophy model |

These six are under 100 bytes each and hold one model id; they leave no room for a different expression. They are listed in
`LICENSE_ASSETS.txt` and tolerated by `docs/tools/check_jar.py` as `EXPECTED`. Whether such statements are protected by
copyright at all is a legal question that this audit does not answer; the position taken is that they are not, and that nothing else is.

Readings of "Lootr Assets" and their effect now:

| Reading | Effect |
|---|---|
| Narrow: artwork | nothing in the tree is covered; pass |
| Folder: every file in upstream's hand-written `assets` folder | covers the 6 identical files above and `en_us.json` as a file (see 1c); all other files differ in content |
| Wide: every resource that is not code | the 6 files, `lootr.accesswidener`, `lootr-fabric.mixins.json` (see 1d) and the generated data (see 2) |

The result no longer depends on the narrow reading except for these six files. If the owner wants certainty about them, the only route left is the
original author's confirmation.

Modified or new files in `assets/` (no concern): `models/block/trophy.json` (replaced by a new model with its own
geometry, 72 lines added, 1,477 removed, the only shared line is a parent key), 24 textures (1a), `textures/block/trophy.png`
(new), `atlases/chests.json` and `atlases/shulker_boxes.json` (upstream generator output with the `old_*` entries removed).

### 1c. Language files

| File | Status |
|---|---|
| `assets/lootr/lang/en_us.json` | 192 keys, 170 with the same value as upstream's generated file. Upstream wrote it from `LootrLangProvider` (MIT code), not by hand. Decision recorded in `.knowledge/decisions.md`: kept. Under the "folder" reading it sits in the covered tree; the text itself is MIT code output. Pass, note. |
| `assets/lootr/lang/de_de.json` | rewritten (192 keys; 4 values equal upstream's German file: `Sand`, `Client`, `Seed`, `%s,%s,%s`). Pass. |
| 30 other languages | removed from HEAD (they are community translations in the covered folder). Remain in history. |

### 1d. Other inherited files

- `assets/lootr/particles/refresh_particle.json`, `unopened_particle.json`, `assets/minecraft/atlases/decorated_pot.json`: identical, written by the generator (`LootrParticleProvider`, `LootrAtlasGenerator`). Pass.
- `fabric/src/main/resources/lootr.accesswidener`, `lootr-fabric.mixins.json`: identical, hand-written upstream, outside `assets/`; lists of class names and fields the code needs. Treated as MIT code configuration. Pass under the narrow and folder readings.
- `common/.../lootr-common.mixins.json`: modified (our mixins added). `fabric.mod.json`: modified (see 4).

## 2. Resources under `data/`

Upstream had no hand-written data. Every `data/` file and every file under `datapacks/` of upstream lived in
`common/src/generated/resources/` or was written by `gen/optionals/LootrNoAdvancementGenerator` and `LootrNoSuspiciousGenerator`,
i.e. was produced by MIT-licensed code.

| Group | Files | Origin |
|---|---:|---|
| `data/lootr/advancement/` | 16 | identical, generated |
| `data/lootr/loot_table/` | 9 | identical, generated |
| `data/lootr/tags/**`, `data/c/tags/**`, `data/minecraft/tags/**`, `data/ftbchunks/tags/**` | 54 | identical, generated |
| `datapacks/lootr_no_advancements/` | 16 (14 advancements, `pack.mcmeta`, `pack.png`) | 15 identical, generated; `pack.png` new (ours) |
| `datapacks/lootr_no_suspicious_blocks/` | 4 (2 tags, `pack.mcmeta`, `pack.png`) | 3 identical, generated; `pack.png` new (ours) |
| `data/lootr/lootr/integrated_loot/pool/` (6) and `rule/` (51) | 57 | new, ours |
| `data/lootr/tags/item/integrated_loot/excluded.json` | 1 | new, ours (15 vanilla technical items such as `minecraft:barrier`) |

`LICENSE_ASSETS.txt` ("All Lootr Assets are ARR") names no folder. Readings:

- Narrow (artwork): data files are not artwork. Pass.
- Wide (everything that is not code): the data files are covered in principle. They are the output of MIT-licensed
  generator code and consist of identifiers (tag members, advancement ids, loot table structure), so the content came
  into existence under MIT. Pass; residual risk is low and exists only if the author claimed the generated output separately.

The 57 integrated loot files and the tag are ours; see 3 for how their content was checked.

## 3. Third-party material

Searched in HEAD: `lootintegration`, `loot_integration`, `Loot Integrations`, `someaddons`, `obliterator`, `elocindev`,
`decompil`, decompiler names (Fernflower, Vineflower, CFR, Procyon, Recaf, JD-Core), `$FF`, obfuscated or intermediary
names (`class_123`, `method_123`, `field_123`, `f_123`, `m_123_`), `var1`-style local names, upstream project ids,
URLs and funding links.

| Search | Result | Verdict |
|---|---|---|
| Loot Integrations (code, data, namespace, add-on file names) | No file, namespace or data copy. Mentions: `LootIntegrationsImport.java` (reads the key names `moddedItemWeight`, `skipExistingItems`, `skipMapItems`, `debugOutput` from the user's own `config/lootintegrations.json`; never writes or deletes it), its call in `LootrConfigInit`, the option `imported_loot_integrations_config`, two language entries, tests, comments, and documentation | pass (expected) |
| Item Obliterator | `ObliteratorBanSource.java`: the mod id `item_obliterator`, the class name string `elocindev.item_obliterator.fabric_quilt.util.Utils` and the method name `isDisabled`, resolved with `MethodHandles.publicLookup().findStatic`. No code from the mod. | pass |
| Decompiler markers | None in files that are ours. `var6`, `var7`, `var5` in `LootrBrushableBlock.java` and `LootrDecoratedPotBlock.java` (8 occurrences): present unchanged in upstream `b62e382a`, so they come from upstream. The `method_5914`, `method_38542` strings are intermediary names in `@Inject`/`@WrapOperation` targets (needed for Fabric), also upstream. | pass |
| Integrated loot data | 57 files: 6 pools (48 sources, all `minecraft:chests/...` ids, weights) and 51 rule files (242 entries: 214 with `pool`, 28 with `exclude`; table patterns of the form `namespace:path` with `*` and `**`; 48 `required_mods` ids and 2 `required_namespaces`: `astral_dimension`, `cataclysm`). Keys used: `sources`, `table`, `weight`, `required_mods`, `required_namespaces`, `priority`, `entries`, `tables`, `pool`, `rolls`, `exclude`. Only loot table ids and patterns, mod ids, numbers. The files were not compared with the add-ons of Loot Integrations (those are not to be opened, per the project's clean-room rule); that no file is a copy rests on the recorded procedure | pass |
| Upstream project ids, URLs, update checkers, funding links | `git grep` for `curseforge`, `modrinth`, `patreon`, `ko-fi`, `discord`, `LootrMinecraft`, `github.com/noobanidus`, ids `615106`, `361276`, `EltpO5cN`: only the build's Maven repositories for dependencies (`api.modrinth.com/maven`, for Jade) and the old `README.md` text (BlameJared's Maven for upstream artifacts, rewritten in this task). No update checker, no publishing task, no funding entry. | pass |

Open point: `.knowledge/*.md` (5 files) is committed in HEAD. It contains the project's working notes, local
Windows paths, a prose summary of what was found about Loot Integrations and Item Obliterator
(including that the study involved decompiling the jars, in `decisions.md`, `handoff.md`, `requirements.md`),
and the names of folders outside the repository where study material lives. It contains no code or data of those mods.
Owner decision: publish it, or leave it out of the public tree (my suggestion: leave it out; it is internal and gives
no benefit to users). The clean-room procedure itself (`decisions.md`) can only be recorded, not verified from the files.

## 4. Metadata and notices

| File | Finding |
|---|---|
| `fabric/src/main/resources/fabric.mod.json` | Correct as committed: id `lootr`, name `Lootr Remastered`, `authors: ["navrelis"]`, `contributors` credit Noobanidus, ZestyBlaze, embeddedt as original Lootr, `contact` points at `github.com/navrelis/LootrRemastered` (homepage, sources, issues), `license: "MIT"`, `icon: "icon.png"`, description from `gradle.properties` (`Per-player loot containers with built-in structure loot integration.`). No upstream URL, no CurseForge link (none exists yet; add it after the project page exists). No change by this audit. The lead has since added `"breaks": { "lootintegrations": "*" }`: running both mods would extend loot twice, and the add-ons depend on that mod id, so one entry covers them. It is only a mod id in a metadata field; it contains nothing of that mod. (The id is the lead's information; this audit did not look it up.) |
| `gradle.properties` | `mod_name`, `mod_description` and the four URLs are ours. The upstream publishing ids (`curseforge_id_*`, `modrinth_id_*`) and `git_previous_commit` are gone. The comment about `maven_group` is upstream's; the group stays because other mods link `noobanidus.mods.lootr`. |
| `build.gradle`, `fabric/build.gradle`, `common/build.gradle` | No publishing configuration. No NeoForge module. |
| `LICENSE` | MIT text of upstream, unchanged, including its notice line `Copyright (c) 2018 ` (upstream left the holder's name empty). This task added the line `Copyright (c) 2026 navrelis`. |
| `LICENSE_ASSETS.txt` | Was one sentence: `All Lootr Assets are ARR (All Rights Reserved).` (47 bytes, no trailing newline; identical in upstream `b62e382a`, where the file exists since the initial commit of 2020). Replaced; the new text quotes it and states what is true for this tree (section 1). |
| `README.md` | Was upstream's text about depending on Lootr through BlameJared's Maven. Our project publishes no artifact there. Rewritten. |
| `CREDITS.md`, `CHANGELOG.md` | New. |
| Jar | The first run found no `from("LICENSE")` in `fabric/build.gradle`. The lead has since added to the `jar` task `from(rootProject.file("LICENSE"))` and `from(rootProject.file("LICENSE_ASSETS.txt"))`, with the remark that `remapJar` remaps the output of that task. Not confirmed until the jar is built: `check_jar.py` (point 4) FAILs if either file is missing at the jar root or differs from the repository file. |

## 5. Git history

Facts:
- The history reaches back to 2020. `b62e382a` has 1,388 commits (authors by count: Jon 920, Jon McManus 328,
  noobanidus-translations 111, mezz 20, embeddedt 5); this tree adds 18.
- It contains every file removed in this tree: 60 PNG files of upstream (39 textures under `assets`, 15 images in the `old_textures` pack,
  `logo.png`, the mod icon and 4 pack icons), 4 `.psd` and 2 `.bbmodel` source files, and 32 language files, under the original notice.
- Upstream is a public GitHub repository (`LootrMinecraft/Lootr`), so that material is already public there.
- The local repository has `origin` = `navrelis/LootrRemastered` (private), with `mdg-1.21.1` and `claude/intelligent-gauss-f0435e` pushed,
  39 local tags from upstream, and local branches `wip/s7-t06-verify`, `wip/s7-t06-verify-partial`, `wip/s7-t07-core-fixes`,
  `worktree-agent-a779a652ac1d89f14`.

Option A, publish with the full history
- The repository shows the real derivation: MIT code stays attributed to its authors by commit, blame and bisect work.
- The old artwork, source art and translations stay downloadable from our repository's history, under the original notice
  ("All Rights Reserved"), next to a `LICENSE_ASSETS.txt` that says the current tree is different. Anyone can check out an old commit; our licence statement does not cover those files.
- A request from the original author to remove the art would have to be answered by rewriting history later, after the repository has been cloned.
- Branches and tags to publish must be chosen (the `wip/*` branches and 39 upstream tags are not needed).

Option B, publish from a fresh history
- One initial commit (or a short new series) of the current tree. The repository then contains only files that are covered by `LICENSE` and `LICENSE_ASSETS.txt`, apart from the six necessarily identical files of 1b.
- Authorship of the inherited MIT code is no longer visible in git; the notice in `LICENSE`, `CREDITS.md` and `fabric.mod.json` carries the attribution, which is all that the MIT licence requires.
- The existing private repository (and its pushed branches) must be replaced, not just overwritten on one branch: either a new repository, or delete the old branches and tags, force-push and ask GitHub to garbage-collect. Commit hashes cited in `.knowledge/` change.
- A variant between the two is to rewrite the history with the art and translations removed from every commit (`git filter-repo`); authors and code history stay, hashes all change, and the effort and the risk of a mistake are higher.

## 6. The jar: repeatable check

The jar is built by the lead (`gradlew.bat build` in `versions\1.21.1\fabric`; result `versions\1.21.1\fabric\build\libs\lootr-remastered-fabric-1.21.1-2.0.0.jar`).

Listing (PowerShell, from the repository root):

```powershell
$jar = (Resolve-Path versions\1.21.1\fabric\build\libs\lootr-remastered-fabric-1.21.1-2.0.0.jar).Path
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jar)
$zip.Entries | Where-Object { -not $_.FullName.EndsWith('/') } | ForEach-Object FullName | Sort-Object | Set-Content jar-entries.txt
$zip.Dispose()
(Get-Content jar-entries.txt | Measure-Object).Count
```

(`jar tf <jar>` gives the same list if a JDK is on the path.)

Patterns that must not match an entry name (`Select-String -Path jar-entries.txt -Pattern ...`):

| Pattern | Why |
|---|---|
| `old_` below `assets/lootr/textures/`, `^resourcepacks/`, `atlases/blocks\.json` | the old-textures pack and its atlas |
| `\.psd$`, `\.bbmodel$`, `(^|/)logo\.png$`, `\.ogg$` | source art, original logo, sounds |
| `assets/lootr/lang/` entries other than `en_us.json` and `de_de.json` | community translations |
| `neoforge`, `accesstransformer` | second loader |
| `gametest` | test material |
| `lootintegrations` as a path | namespace of the other mod |
| `^art/`, `^\.knowledge/`, `\.cache/` | repository folders and generator cache |

Text that must not be found in any entry: `someaddons`, `noobanidus.com`, `curseforge.com/minecraft/mc-mods/lootr`, `615106`, `361276`, `EltpO5cN`, `LootrMinecraft`.
Allowed: `lootintegrations` only in `LootIntegrationsImport.class`, `LootrConfigInit.class`, `lang/de_de.json` and `fabric.mod.json` (the `breaks` entry); `elocindev` only in `ObliteratorBanSource.class`; `LootrMinecraft` only in `LICENSE_ASSETS.txt`.

Everything above, plus the comparison with upstream's blobs, is done by one script (needs Python 3 and git; it only reads):

```powershell
python docs\tools\check_jar.py versions\1.21.1\fabric\build\libs\lootr-remastered-fabric-1.21.1-2.0.0.jar b62e382a
```

Expected output (checked on a stand-in zip built from the resource folders and the two licence files of the working tree, not on a real jar; the same zip without the licence files gave two FAIL lines in point 4):
- point 1: `none`;
- point 2: no `FAIL`; 6 lines `EXPECTED` (the six necessarily identical files of 1b; any other JSON file identical to a hand-written upstream asset is a `FAIL`) and about 100 lines `GENERATED` (79 data files, 3 assets, 18 data pack files); `OTHER` for `lootr.accesswidener` and `lootr-fabric.mixins.json`;
- point 3: `expected` lines only (a `CHECK` line means a class or file that mentions the other mods without being listed: open it);
- point 4: `ok` for `LICENSE` and `LICENSE_ASSETS.txt` (at the jar root, equal to the repository files) and `breaks: {"lootintegrations": "*"}`;
- last line `result: no FAIL`, exit code 0.

Further checks on the built jar by hand: `fabric.mod.json` inside the jar has no `${...}` left; `META-INF/jars` is absent or empty (nothing is bundled).

## Other findings for the lead (outside the files this task may change)

- `art/verify.py` stopped with `FileNotFoundError` for `assets/minecraft/atlases/blocks.json`; the lead has repaired it and it was run again for this update.
- `common/build.gradle` still carries the NeoForge ModDev plugin and `neoForge {}` block (build tooling to compile common code against vanilla; no NeoForge file reaches the jar). Not a licence matter.

## Statements this audit could not verify

- Whether the clean-room procedure for the integrated loot code and data was followed: only the record in `.knowledge/decisions.md` exists.
- How the original author reads `All Lootr Assets are ARR`; this matters only for the six identical one-statement files of 1b.
- That the rewritten files were written without sight of the old ones: that is the lead's account, and what the audit can show is only that 26 files differ in bytes, 5 in substance.
- The contents of the built jar, including whether the two licence files land at its root (no jar exists in the worktree; section 6 gives the check).
- The output path of upstream's optional data pack generators beyond their source (`datapacks/` is a "hack" path in the source; the files are identical to what they generate by content and structure).
