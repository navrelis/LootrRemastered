# Report: Lootr Remastered 2.0.0 (session S7)

Written 2026-10-13 by the lead of the fourth session. Branch `claude/intelligent-gauss-f0435e`, pushed to `origin` (`navrelis/LootrRemastered`, private). Final jar: `fabric\build\libs\lootr-remastered-fabric-1.21.1-2.0.0.jar`, 917,747 bytes, SHA-256 `61664C8299A6B86EED4A5B3198B93E5E87B2CA58403B4D9A4A8F4DDA37DE6FA0`.

## 1. The owner's two questions

### 1a. Can the original Lootr and all Loot Integrations jars be removed from the pack? YES.

Proven with the final jar on a server copy of the pack (314 jars) and on a client copy of the pack (404 jars, the 11 old jars removed, the new jar added). The real pack was not changed; the swap is the owner's step.

Remove these 11 jars from `mods`:

1. `lootr-fabric-1.21.1-1.11.38.127.jar`
2. `lootintegrations-fabric-1.21-4.7.jar`
3. `lootintegration_wda-1.8.jar`
4. `lootintegrations_dnt-2.7.jar`
5. `lootintegrations_dungeonsenhanced-1.8.jar`
6. `lootintegrations_lootintegrations_bygonepillage-1.4.jar`
7. `lootintegrations_medievalbuildings-1.5.jar`
8. `lootintegrations_moog-2.2.jar`
9. `lootintegrations_structory-1.5.jar`
10. `lootintegrations_vanilla-1.8.jar`
11. `lootintegrations_yungs-1.6.jar`

Add: `lootr-remastered-fabric-1.21.1-2.0.0.jar`.

Must stay: `cupboard`, `necronomicon`, Cloth Config (other mods need them), Fabric API, Item Obliterator, CraftTweaker, Advanced Loot Info, EMI Loot, dragonloot. `config\lootintegrations.json` may stay; its settings are taken over once and the file is not changed. `config\lootr.json` is kept (unknown old keys are ignored).

One command (PowerShell; moves the old jars to `mods_backup`, copies the new jar; close the game first; the same applies to the server pack's `mods` folder):

```powershell
$p='C:\Users\nikol\curseforge\minecraft\Instances\NytheriaDevelopment'; New-Item -ItemType Directory -Force "$p\mods_backup" | Out-Null; Get-ChildItem "$p\mods" -Filter *.jar | Where-Object { $_.Name -like 'lootr-fabric-*' -or $_.Name -like 'lootintegration*' } | Move-Item -Destination "$p\mods_backup"; Copy-Item 'F:\Coding\Lootr Remastered\.claude\worktrees\intelligent-gauss-f0435e\fabric\build\libs\lootr-remastered-fabric-1.21.1-2.0.0.jar' "$p\mods"
```

Fabric Loader refuses to start if Loot Integrations is still present (`breaks` entry), so a forgotten jar is noticed at once. The pack repository belongs to S2: the swap is a change for S2 or the owner to commit.

### 1b. What to upload to CurseForge

| Item | File |
|---|---|
| Jar | `fabric\build\libs\lootr-remastered-fabric-1.21.1-2.0.0.jar` (re-run the jar check after any rebuild) |
| Description (Markdown, ready to paste) | `docs\curseforge\description.md` |
| Summary (232 characters, limit 250) | `docs\curseforge\summary.txt` |
| Logo | `art\logo\logo_512.png` (512 x 512, nearest-neighbour upscale); native 32 x 32 file `art\logo\logo_native.png` |
| Changelog | section `2.0.0` of `CHANGELOG.md` |
| Field values, relations, steps | `docs\curseforge\upload-checklist.md` |

Summary text: "Per-player loot containers (Lootr) with built-in extra loot for vanilla and modded structure chests, item bans through Item Obliterator and a loot table check command. One Fabric 1.21.1 jar that replaces Lootr and Loot Integrations."

Nothing was uploaded. Before the upload the owner decides the points in section 9.

## 2. What was implemented, per requirement

| Req. | Result | Proof |
|---|---|---|
| R1 one jar | Fabric-only build, mod id `lootr`, version 2.0.0. Lootr's per-player containers plus an own integrated loot engine (pools and rules as data, results extended after the roll). Replaces 11 jars. | Server and client copies run with the one jar; 904 loot tables extended, 51 of 51 rule files active. |
| R2 bugs and optimisation | 12 core defects (saved data, ticking files, conversion queue), 10 small ones, 7 further core defects from review, 2 hardening changes from the performance audit; list in `CHANGELOG.md` "Fixed". | 97 game tests; figures in section 4. |
| R3 Item Obliterator | Banned items are removed from every loot table roll, from Lootr fills and from stored per-player inventories on open. Interoperation by one method-handle call, no code of Item Obliterator. | 0 of 24,000 + 4,000 + 4,000 + 4,000 rolls with the banned `clutter:coin_pouch` on a path Item Obliterator does not cover, against 11,970 of 24,000 with the filter off. |
| R4 verification | `/lootr verify [rolls <n>]` and `-Dlootr.verify=true|exit`; Markdown and JSON report under `logs\lootr\`. | Run in the pack: 13.3 s, 16,186 tables; triage in section 5. |
| R5 existing worlds | Mod id, packages and saved data format unchanged; `lootintegrations.json` taken over once. | First server run: copy of an existing world started clean, its 5 `data\lootr` files byte-identical afterwards. Client log shows the take-over line. |
| R6 add-on content built in | 6 pools, 51 rule files, each gated by `required_mods` or `required_namespaces`. | 51 of 51 active in the pack, 8 of 62 in the vanilla test environment. |
| R7 release material | Description, summary, credits, changelog, own pixel art for every texture and the logo, licence audit. | `docs\licence-audit.md`: pass, owner decisions in section 9; `check_jar.py`: no FAIL. |
| R8 proven in the pack | Final jar on a server copy and a client copy. | Section 3. |
| R9 optimise (2026-10-12) | Measured first. The mod is cheap; one robustness defect found and fixed, one small guard added; six other proposals rejected with reasons. | Section 4; `server-run\results\perf\AUDIT.md`. |

Total change against upstream `b62e382a`: 34 commits, 619 files (18,191 insertions, 16,539 deletions). Main areas: `common\...\itemban` (bans), `common\...\integratedloot` and `data\lootr\lootr\integrated_loot` (engine and built-in rules), `common\...\verify` (verification), core fixes in `LootrSavedData`, `DataStorage`, `BlockEntityTicker`, `SimpleLootrInstance`; all textures, models and block states replaced by own work; NeoForge module, old-textures pack and 30 translations removed; game tests under `fabric\src\gametest`; documentation under `docs\`.

## 3. Proof of the final jar (`61664C82...`)

- Build `:fabric:build` green; `:fabric:runGametest` 97 test cases, 0 failures (lead's own run); `docs\tools\check_jar.py` "no FAIL".
- Server copy, default config (`server-run\results\final\FINAL.md`): hook warning absent; "Item Obliterator found" once; "904 loot tables extended, 239 excluded (6 pools, 51 of 51 rule files active, 0 malformed files)"; loot flows for chest, entity and block tables; stacks per roll 12.16 / 14.03 / 7.00 / 8.37 on the four test tables; 0 coin pouches in 4,000 + 4,000 minecart rolls; 0 compass warnings in 21,003 + 22,000 rolls (previous build: about 3 per 1,000); `lootr verify rolls 3` in 13.3 s; 0 ERROR lines from the mod; clean stop, exit code 0.
- Client copy (`client-run\results\CLIENT.md`, captures in `client-run\shots\view1.png` to `view9.png`): the game reached the world on the first launch, 2 min 53 s after start, "Loading 643 mods" with `lootr 1.21.1-2.0.0`; 0 ERROR or WARN lines, exceptions, mixin failures or missing model, texture or translation messages from the mod; no line from the five mods that link against Lootr classes mentions Lootr. Command-placed vanilla chests, trapped chest, shulker box, decorated pot, barrels in six facings and the chest minecart show as Lootr containers with the new art; directly placed Lootr blocks, three Lootr item frames and four trophies render without missing textures. The lead looked at views 1, 2 and 8 himself; the others are described by the run agent.
- Not shown by the client run (no input was sent because the owner was using the PC): see the manual checklist, section 7.

## 4. Measurements (method and noise in `CHANGELOG.md`, "Measured on a server")

- Cost of one roll of an extended chest table, default config: 20 to 40 microseconds (four tables, two server starts per configuration, 10 batches of 20,000 rolls). Integrated loot adds 12 to 16 microseconds, which is the one extra source table roll the feature consists of. A roll happens once per player per container.
- Ban filter: about 0.1 microseconds per stack (estimate from the CPU profile), spent inside Item Obliterator's own check; not resolvable by timing.
- Outside loot rolls (CPU profile, server thread): idle 0 of 237 samples in the mod's code; 4,096 loaded Lootr barrels 0 of 405; generation of 432 chunks 5 of 691; start-up about 12 of 6,125.
- Old 11 jars, first run of the same pack copy: 0.30 to 0.45 s per roll of an extended table (thread dumps showed an explorer map created on every such roll); the new jar 20 to 60 microseconds in that run. Not a like-for-like benchmark, but the reason chest opening stalled with the old set.
- The pack without either jar set gives 12.1 / 13.0 / 5.0 / 8.4 stacks per roll on the four tables; the old 11 jars gave 8.3 / 7.0 / 7.0 / 8.3, the new jar gives 12.2 / 14.0 / 7.0 / 8.4. That the old set removed stacks of other mods is an inference from these sums.
- Timings were taken on a shared PC; batches within one run differ by up to a factor 2.7. Differences below about 5 microseconds are not findings.

Audit proposals not implemented, with reasons: per-item cache of the ban answer (wrong as soon as Item Obliterator bans by component data, which Lootr cannot see); skipping the second ban check of candidates (about 1 microsecond, loses a safety net); no ticker while nothing decays (1 of 405 samples, behaviour risk); cheaper source roll (would change which items appear); two more below the sampling resolution.

## 5. Verification triage (`server-run\results\final\verify-20261013-003919.md`)

Totals: load_failure 0, internal_error 0, no_integrated_loot 0, missing_reference 39, legacy_folder 37 files, always_empty_in_rolls 2 warnings and 36 notes, cannot_produce 238 notes, banned_item 65 (16 direct, 49 through nested tables), skipped_by_condition 96. Coverage: 848 of 990 structure container loot tables are extended, 0 have no rule.

Fixed on the mod side: the emptied-rolls conflict with CraftTweaker (all loot rolls were empty with the first build); dispenser tables excluded explicitly; `banned_item` follows nested tables; the vanilla trial chamber barrel table removed as a pool source (compass warnings).

Pack data, for S2 or the owner (not changed by this session): nothing has to change for the mod to work. Optional: the two pot tables that always roll empty (`nova_structures:pots/pot_badland_miner_outpost`, `mss:pots/tower`).

Upstream mod defects (structures refer to loot tables that do not exist; those containers are empty with or without this mod):

| Mod | Missing table ids |
|---|---|
| Dungeons and Taverns (15) | `nova_structures:chests/illager_hideout_lesser_meat`, `..._raw_trash`, `..._raw_vegitarian`, `..._raw_weaponry`, `nova_structures:chests/ruin_loot_1` to `_7`, `nova_structures:mob/elder_guardian_big`, `nova_structures:pots/pot_piglins`, `nova_structures:spawner_projectile/shrine/projectile_shrine`, `nova_structures:spawner_projectile/toxic_lair/items_to_drop_when_ominous` |
| When Dungeons Arise (14) | `dungeons_arise:chests/desert_city_big`, `_medium`, `_small`, `giant_mushroom/red_giant_mushroom`, `giant_mushroom/twin_giant_mushroom`, `house_tower_treasure`, `illager_fortress_small`, `mushroom_house/mushroom_house_barrel`, `mushroom_village/mushroom_village_normal`, `mushroom_village/mushroom_village_weaponry`, `pillager_palace_supply`, `pillager_palace_treasure`, `orion_structures:chests/greenwood_towers/greenwood_towers_barrels`, `..._treasure` |
| Towns and Towers (6) | `kaisyn:village/village_birch_forest_house`, `kaisyn:village/village_forest_ruins/forest_ruins_butcher`, `..._library`, `..._temple`, `minecraft:chests/village/village_leatherworker`, `wythers:chests/village/forest_ruins_big_house` |
| Cataclysm (1) | `minecraft:chests/village_desert_house` |
| Adventure Dungeons (1) | `adventuredungeons:chests/murkydungeon/murky_chest_brick` |
| Incendium (1) | `incendium:castle/tower_floor_low` |
| Terralith (1) | `terralith:village/desert/leatherworker` |

Loot tables in the pre-1.21 folder `loot_tables` (not loaded by the game): Porting Lib Item Abilities 27 files, Variants and Ventures 7, Explorify 3.

## 6. Key decisions

Full list with reasons in `decisions.md`. The ones that shape the result: clean-room rewrite of Loot Integrations (All Rights Reserved; nothing from it is in the repository or the jar); Fabric only; results are extended after the roll, tables are not rewritten; one or two source rolls per extension; banned items are filtered from every loot roll (switchable) and stored inventories are scrubbed on open; own art for everything shipped; English and a new German language file, the other translations removed until the Lootr author permits their use; the real pack was not touched; optimisation by measurement first, which showed that only two small changes were worth making.

## 7. Manual test checklist (not executed; needs a person at the keyboard)

- Open a Lootr chest, barrel, shulker box, pot and minecart: per-player inventory, lid animation, opened ("spent") texture, toast and advancement.
- Vanilla suspicious sand and gravel placed with a loot table: converted and brushable (the captures cannot tell them from plain blocks; Jade hides their name).
- `clutter:coin_pouch` never in a new Lootr chest; a chest opened before a ban loses the item on reopen; `/reload` after changing Item Obliterator's config applies without restart.
- A structure chest of a covered mod shows extras for two different players; `/lootr integrated <table>`, `/lootr integrated stats`.
- `/lootr verify` in the real pack lists `dungeons_arise:chests/illager_fort/illager_fort_treasure` under `banned_item` "(through `minecraft:chests/woodland_mansion`)".
- Start-up log with `filter_all_loot=false` shows "... other loot rolls are not filtered (filter_all_loot is off)."
- Advanced Loot Info and EMI Loot open and show loot tables (without integrated extras: known limitation).
- An existing world with `data\lootr` opens its containers with the old contents (use a copy).
- Config screen in German and English; `vanilla_textures` switches chest, shulker and barrel to vanilla art and back.
- Art details: chest strap alignment over lid and body, lid seam and shulker belt without flicker, open barrel, broken pot, item models in hand and inventory, break particles, mod icon in Mod Menu; with shaders on (Iris was off in the test copy).
- A refresh while a container is open closes the menu with the action-bar message; `/lootr clear <player>` closes that player's open menu.
- Two observations from the client run to look at: a white puff on the floor next to the chest minecart in view 3 (which mod draws it is unknown), and a FancyMenu log line about a `RadialMenu` screen although no input was sent.

## 8. Known limitations

- Loot viewers read tables statically and do not show integrated extras.
- The all-loot filter does not see stacks that other mods add after a table's own roll returned (CraftTweaker and Porting Lib loot modifiers, Aether); Lootr containers and stored inventories are scrubbed in every case.
- Bans check the top-level stack only; an item frame whose reference item is banned still displays it.
- Config changes on a dedicated server need a restart.
- 44 trial chamber dispensers per 300 generated chunks stay vanilla block entities (dispensers are not a Lootr container type).
- `lootr verify` prints two start lines; `always_empty_in_rolls` warnings depend on the roll count (2 with 3 rolls, 5 with 1); 40 "Couldn't set damage" warnings for air and book appear during the verify rolls with or without this build, their source table was not determined.
- Verification judges `neoforge:conditions` against Fabric mod ids (mods loaded through Sporran count as absent); bans set through item functions are not seen statically.
- Command feedback texts are English only.
- Not measured: client frame cost, `/reload` time, a server without Lithium.
- The stall through the `request_update` packet was found by reading and closed by a check; it was not reproduced with a modified client.

## 9. Open owner decisions

1. Publish the repository with its history (which contains upstream's removed art and translations) or from a fresh history.
2. Whether `.knowledge\` goes into the public repository (it mentions the decompile-based study and local paths).
3. Release type (Release or Beta), CurseForge relations (incompatible: Lootr, Loot Integrations and add-ons), whether to contact the Lootr author as a courtesy and to ask for the translations.
4. Screenshots for the project page: the nine test captures show other mods' HUD and a debug label; clean ones are better.

## 10. Housekeeping the owner has to do

- The main checkout `F:\Coding\Lootr Remastered` (branch `mdg-1.21.1`) has two uncommitted file changes made there by mistake by a subagent (`MixinHopperBlockEntity.java`, `LootrNetworkingInit.java`). The permission system blocked the restore. Undo: `git -C "F:\Coding\Lootr Remastered" checkout -- common/src/main/java/noobanidus/mods/lootr/common/mixin/hopper/MixinHopperBlockEntity.java fabric/src/main/java/noobanidus/mods/lootr/fabric/network/LootrNetworkingInit.java`
- The leftover local worktrees and branches were not deleted (blocked by the permission system). Everything in them is on the working branch. Remove: `git worktree remove` for `...\.claude\worktrees\s7-t06-verify` and `...\agent-a779a652ac1d89f14`, then `git branch -D wip/s7-t06-verify wip/s7-t06-verify-partial wip/s7-t07-core-fixes worktree-agent-a779a652ac1d89f14`.
- Throwaway material outside the repository, about several GB, can be deleted when no longer wanted: `F:\Coding\.knowledge\s7-lootr-remastered\server-run\` and `client-run\`.

## 11. Model usage

Third session: Opus 4 (second server run, the stopped audit), Sonnet 5. Fourth session (long = 2, short = 1): Opus 5 (performance audit 2, final server re-check 1, client run 2), Sonnet 4 (T21 1, documentation pass 1, checklist update 1, one-sentence fix 1). Precision overrides to Opus: the server runs (their numbers carry the yes on removing 11 jars), the client run (unattended start of a 400-mod pack), the audit (profiling judgment in hot paths shared with 20 other loot mixins).
