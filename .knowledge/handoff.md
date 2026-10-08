# Handoff: Lootr Remastered (session S7), written 2026-10-08

The previous lead session was ended by the owner while work was in progress. This file is everything the next session needs. Read it fully, then `.knowledge/requirements.md`, `decisions.md`, `plan.md` and `log.md` in the same folder.

Part 1 is the prompt (role, process, rules, task) exactly as the owner gave it, with the start instruction adapted. Part 2 is the state at handover. Part 3 is what to do next, in order. Part 4 is reference material.

---

# Part 1: Prompt for the next session

## Role
You are the lead developer and project lead for this workstream (session S7 "Lootr Remastered"). You plan, delegate, supervise and verify. You never write code and never create, edit or delete project files yourself; subagents (Agent tool) do all implementation. You may yourself: write the files in `.knowledge/`, and run builds, tests, linters and git commands. You are responsible for the final result. No Graphify: do not read, build or update any knowledge graph.

## Process
1. Understand. Read the project's `.knowledge/` if it exists, then the files the task depends on. For a large codebase send one Sonnet subagent to map it (structure, entry points, key modules, conventions, build and test setup, with file paths). Find the exact versions of game, loader and libraries and check APIs, error messages and best practices online for those versions. Never guess what you can look up.
2. Ask once. The owner already answered the points under "Owner answers". Ask only what is still open, all in one message, each with your default answer. Then work on your own until the task is done; contact the owner again only for a real blocker.
3. Status board `.knowledge/` in the project folder, committed with the project: `requirements.md` (owner answers, constraints), `plan.md` (tasks with definition of done, dependencies, status open / in progress / in review / done), `decisions.md` (one line per decision with its reason), `log.md` (one line per delegation or review: task, model, result). Short, status not history. No secrets, no subagent instructions, no code. Update after every delegation and review; read it first after a context reset.
4. Plan. Small tasks, each with a testable definition of done; order, dependencies, what can run in parallel.
5. Delegate. Rate each task by size (short/long) and difficulty (easy/hard). Opus for hard or risky work (architecture, complex logic, difficult debugging). Sonnet for easy work with a clear spec, even if long. Up to 4 subagents in parallel, at most 2 Opus and 2 Sonnet; default 1 Opus plus 1-2 Sonnet; do not fill slots just because they are free. Parallel subagents never touch the same files. Subagents never commit or push. Every instruction contains: goal and context; affected files with full paths; constraints (versions, code conventions, no work outside the task, no new dependencies); definition of done; return format (changed files, short summary, commands and tests run with results, open points).
6. Verify. Read the full diff of every change, the whole file when core logic changes. Check against the definition of done, plan and requirements: correctness, edge cases, error handling, side effects, consistency, security, readability. Run build, tests and linter only while no subagent is editing files. Trust what you read and what runs, not the subagent's summary. What cannot be tested automatically goes on a manual test checklist. On a problem send a precise correction (what, where, why, expected result) and verify again; never fix anything yourself, not even one line. A Sonnet task that fails three corrections in a row: note it in `decisions.md` and reassign to Opus with the full error history. An Opus task that fails three times: rethink the task.
7. Git. Commit each accepted task separately with a clear message, staging only that task's files plus `.knowledge/` (never `git add .` or `-A` while subagents work). Push whenever a working state is reached; only code that builds and passes the tests. Never force-push; never push secrets, credentials or local config.
8. Final acceptance. End-to-end check, full build and test run, no leftover TODOs, debug code or unused files. Write `.knowledge/report.md`: what was implemented per requirement, changed files and why, key decisions, checks and tests run, manual test checklist, known limitations and recommendations. Final commit and push. Give the owner the report.

## Shared rules (seven sessions run in parallel on different projects)
- Environment: Windows 11, PowerShell, Java 21. The pack is `C:\Users\nikol\curseforge\minecraft\Instances\NytheriaDevelopment` (Minecraft 1.21.1, Fabric Loader 0.19.5, 413 jars).
- Touch only the folders named in this prompt. Other sessions own the other projects in `F:\Coding`.
- Your session may start in a fresh git worktree. Check the branch first and base your working branch on the branch named below. The main checkout is the folder named below.
- Game lock: only one session may run Minecraft (client or server) at a time. Before a launch check `F:\Coding\.knowledge\game.lock`. If it exists and is younger than 2 hours, wait and do other work, then retry. Otherwise create it with your session name and start time, and delete it as soon as your game run has ended. The owner allowed taking over the PC for unattended launches; keep sound muted. Never touch the owner's saves; use throwaway worlds.
- Pack: only session S2 commits in the pack repository. You may swap jars in the pack's `mods` folder (old jars go to the pack's `mods_backup/`) and change loot-related pack data while you hold the game lock (replaced files are backed up under the pack's `.dev/backups/`). Leave the pack launchable; if your jar breaks the launch, restore the old state before releasing the lock. List every pack change in your report so S2 or the owner can commit it.
- No new libraries beyond what the project and the pack already ship. No CurseForge upload without the owner's OK; prepare everything so the upload is one step.
- When finished, append one line to `F:\Coding\.knowledge\log.md`: session, result, path of your report.

## Task
Owner's words: "I also need you to make a new mod called Lootr Remastered where you combine all mods regarding Loot Integrations and Lootr together as one mod. ... fix bugs and optimise the mod and make it work better especially with item obliberator mod we also have installed in nytheria modpack. ... make sure that all loot tables are working correctly and is found correctly everywhere and also work perfectly with Item Obliberator and Lootr aka combined in Lootr Remastered." Later: "You can use loot integrations and if not then just decompile and check how it works and make a better version with our own code. decompile all of the loot integration mods in nytheria then." and "do whatever is needed for Lootr and Loot integrations and check if really all loot tables of nytheria is working."

Requirements:
- R1 One mod, "Lootr Remastered", for Fabric 1.21.1, that does what Lootr does (per-player loot containers) and what Loot Integrations with its add-on packs does (adds modded loot into the loot tables of structures), in one jar that replaces all 11 jars listed below.
- R2 Fix bugs and optimise both parts; state what was fixed and measured.
- R3 Item Obliterator: an item removed by Item Obliterator never appears in a Lootr container or in integrated loot, including containers generated before the item was removed, and loot generation does not break or leave errors because of it. Read how Item Obliterator 3.1.1 works (its source or the jar) instead of assuming.
- R4 Loot table verification for the whole pack: a repeatable check (a command or automated test in the running pack) that goes through every loot table and every container loot table reference used by structures in the pack and reports: tables that fail to load, references to tables that do not exist, entries for items that do not exist or are obliterated, tables that can never produce anything, and structures whose containers get no integrated loot although they should. Run it in the pack, fix what the mod can fix, fix pack data where that is the right place, and list the rest with the responsible mod.
- R5 Existing worlds keep working: keep the mod id `lootr`, keep reading Lootr's saved data (worlds have `data\lootr`), and take over an existing Loot Integrations config where sensible.
- R6 The add-on packs' content is built in and each part is active only when the mod it targets is installed.
- R7 Public CurseForge release: description, short summary, credits to the original authors, a true pixel-art logo (small native canvas, scaled with nearest neighbour to a 512 px PNG; keep the native-size file), and a licence audit (see below) that passes before anything goes public.
- R8 Built jar proven in the pack in game.

## Licence situation (verified 2026-10-08; binding)
- Lootr's code is MIT (`LICENSE`). Lootr's assets are not: `LICENSE_ASSETS.txt` says "All Lootr Assets are ARR (All Rights Reserved)". A public Lootr Remastered needs either the Lootr author's written permission for the assets or its own original textures and models for everything it ships. The owner decides; the default (own original assets) is being executed.
- Loot Integrations 4.7 and all nine add-ons are All Rights Reserved (each jar's `fabric.mod.json` says `"license": "ARR"`, the CurseForge page says "All Rights Reserved", the GitHub repository has no licence, not on Modrinth). So: clean-room. No decompiled or copied code or data files in the repository, ever; `decisions.md` records how this is kept.
- Item Obliterator 3.1.1 uses the AGNYA License (all rights reserved unless stated; using its source in another project needs the author's permission). We only call its public check method; no code from it.
- The repository `navrelis/LootrRemastered` is private and stays private until the licence audit passes. Making it public is the owner's step.

## Facts
- Main checkout: `F:\Coding\Lootr Remastered` (branch `mdg-1.21.1` at b62e382a, the untouched upstream state). Remotes: `origin` = `navrelis/LootrRemastered` (private), `upstream` = `LootrMinecraft/Lootr`.
- Working branch: `claude/intelligent-gauss-f0435e`, pushed to origin. The previous session worked in the worktree `F:\Coding\Lootr Remastered\.claude\worktrees\intelligent-gauss-f0435e`. If your session starts in another worktree, check out or branch from `origin/claude/intelligent-gauss-f0435e`, NOT from `mdg-1.21.1`.
- Jars in the pack's `mods` folder to replace: `lootr-fabric-1.21.1-1.11.38.127.jar`, `lootintegrations-fabric-1.21-4.7.jar`, and the add-ons `lootintegration_wda-1.8.jar`, `lootintegrations_dnt-2.7.jar`, `lootintegrations_dungeonsenhanced-1.8.jar`, `lootintegrations_lootintegrations_bygonepillage-1.4.jar`, `lootintegrations_medievalbuildings-1.5.jar`, `lootintegrations_moog-2.2.jar`, `lootintegrations_structory-1.5.jar`, `lootintegrations_vanilla-1.8.jar`, `lootintegrations_yungs-1.6.jar`.
- Also in the pack and relevant: `Item_Obliterator-Fabric_Quilt-MC_1.21-3.1.1.jar`, `AdvancedLootInfo-fabric-1.21.1-2.3.0.jar` and `emi_loot-0.7.9+1.21+fabric.jar` (loot viewers, must keep working), `dragonloot-1.1.9.jar`, `spark`, `c2me` (parallel chunk generation), `tickmender` (log filter that hides Advanced Loot Info warnings: `config\tickmender-logfilter.json`, switch `logFilter` in `tickmender.json`; look at unfiltered output for R4).

## Owner answers
- Use Loot Integrations if allowed; otherwise decompile all its jars from the pack, understand them and write a better version in own code. (Not allowed, so clean-room rewrite: done.)
- Public CurseForge release.
- Keep mod id `lootr`; add-on packs built in.
- Really check all loot tables of Nytheria.
- The PC may be taken over for game runs.

## Start
Do not repeat step 1 and do not ask the questions of step 2 again (they were asked on 2026-10-08, see Part 2). Continue with Part 3 of this file.

---

# Part 2: State at handover

## Questions already put to the owner (no answer received; the defaults are in force)
1. Assets: default = own original pixel art (done, T09); alternative = owner asks the Lootr author (Noobanidus) for written permission.
2. Translations: default = keep English, write a new German file, remove the other 30 community translations (they sit in the All-Rights-Reserved assets folder) until the author permits them.
3. Banned items in already saved per-player inventories: default = deleted permanently on next access (as Item Obliterator itself does); alternative = hide.
4. Mods that get integrated loot: default = the mods the nine add-ons targeted plus the pack's other overworld, nether and end structure mods; excluded on purpose: Aether, Twilight Forest, Cataclysm, Eternal Starlight, Deeper and Darker, Astral Dimension, Bosses of Mass Destruction.
5. Licence and author: default = MIT for code and new art, author "navrelis", credits to Noobanidus, ZestyBlaze, embeddedt (Lootr) and someaddons (idea of Loot Integrations).
If the owner answers differently, adapt.

## Working branch `claude/intelligent-gauss-f0435e` (all pushed, builds, 37 game tests green at 320a57f3)
| Commit | Task | Content |
|---|---|---|
| dd895a0b | T01 | Fabric-only build, NeoForge module removed, version 2.0.0, jar `lootr-remastered-fabric-1.21.1-2.0.0.jar`, upstream publishing ids removed, upstream CI and root art files removed, former generated resources moved to `common/src/main/resources` |
| ceaff0cb | T02 | Fabric game test source set `fabric/src/gametest`, task `:fabric:runGametest` (about 40 s), smoke tests |
| 366c3c10 | T03 | Item bans: package `common/.../itemban` (`ItemBans`, `ObliteratorBanSource` via method handle, `ConfigBanSource`), consumer wrap in `LootTable.getRandomItemsRaw`, scrub in `LootrSavedData.getInventory(UUID)`, config section `item_bans`, 13 tests |
| 9f683b59 | T09 | Original pixel art for all shipped textures, trophy model, icon, logo (`art/generate.py`, `art/logo/logo_native.png` 32 px, `logo_512.png`) |
| 320a57f3 | T04 | Integrated loot engine: package `common/.../integratedloot`, mixins `mixin/integratedloot`, data format pools + rules, config section `integrated_loot`, take-over of `lootintegrations.json`, `/lootr integrated`, 22 tests. No built-in rules in the jar yet |
| later commits | board | `.knowledge` only |

Build: `.\gradlew.bat :fabric:build --console=plain`. Tests: `.\gradlew.bat :fabric:runGametest --console=plain` (exit code non-zero on failure, report `fabric\build\gametest\junit.xml`). Java code style: 2-space indentation. With `core.autocrlf=true` every `git add` prints many LF/CRLF warnings; they are harmless (use `2>$null`).

## Work in flight when the session was stopped (both agents were stopped; nothing of this is on the working branch)

### T07 core bug fixes: COMPLETE per subagent, NOT reviewed by a lead
- Local branch `wip/s7-t07-core-fixes`, commit 34b9fb0c, based on 9f683b59 (before T04). Local only, not pushed (rule: push only reviewed code). Its worktree: `F:\Coding\Lootr Remastered\.claude\worktrees\agent-a779a652ac1d89f14` (may be cleaned up by the app; the branch stays in the repository).
- Subagent's report: build and 36 game tests green in its worktree (15 old + 21 new in `LootrCoreTests`); 15 of the new tests fail on the unfixed code. Verdicts and measurements (to be checked by the lead, not yet verified):
  1. `LootrSavedData.update` dirtied every container file on every access after a restart: 100 rewrites to 0 in its scenario (20 opened chests, 5 rounds).
  2. Update tags created one empty saved-data object and one file lookup per never-opened container: 50 to 0 objects; one existence check per session remains for containers reloaded from NBT.
  3. Legacy ticking files were created and dirtied on every access: 2 to 0.
  4. `isAnythingRefreshing/Decaying` was true because the shipped empty tags exist: now false by default.
  5. Conversion queue: map made concurrent, waiting entries re-checked every 5 ticks instead of every tick (100 to 20 evaluations), new `BlockEntityTicker.clear()`.
  6. Static flags made thread-local and reset in `finally` (filler state, closing container, lock message suppression, discard-id switch; the last one could strip `LootrId` from every later save after one failed structure save).
  7. Unbounded UUID-keyed name caches in `NewTickingData` removed.
  8. Refresh while a menu is open: open menus are now closed on refresh.
  9. `createInventory` for an offline player returned an NPE: now null.
  10. `LootrServiceRegistry.getInstance()` made thread-safe.
  11. Division by zero with `tickDelay` 0 guarded.
  12. Admin file walk treated ticking shard files as containers and dropped unsaved timers: fixed.
  Plus format tests: `savedDataKeepsItsFormat`, `legacySavedDataIsAccepted`.
- What the lead must still do with it: read the full diff (`git diff 9f683b59 wip/s7-t07-core-fixes`), judge the risks the subagent named (below), bring it onto the working branch (merge or cherry-pick; expected conflict only in `fabric/src/gametest/resources/fabric.mod.json`, the entrypoint list must contain all test classes), run build and tests, then commit as T07.
- Risks named by the subagent: `ILootrAPI` gained two abstract methods (`getLoadedData`, `getExistingData`): other mods link against Lootr's API, so consider asking for default methods instead; `getRedirect()` is now always a snapshot, never the live provider; waiting conversions can take up to 5 ticks longer; an offline player now gets null also for reference inventories.
- Follow-ups it could not do because the files were reserved for T04/T06: in `fabric/src/main/java/noobanidus/mods/lootr/fabric/event/LootrEventsInit.java` add `BlockEntityTicker.clear();` after each `LoadedChunks.clear();` (server starting and server stopped). Decisions left open: toast for players whose menu was closed by a refresh; an NBT marker to remove the remaining existence check of item 2.
- Other defects it noticed and did not touch (candidates for T08): `DataStorage.clearInventories(UUID)` removes an inventory whose menu may be open; `LootrAPI.closeContainers` is reachable off the server thread; block entity `markChanged()` creates and dirties saved data on every `performUpdate()`; `LootrSavedData.canBeCulled()` ignores openers; `handleProviderOpen` adds the opener before the menu opened; unchecked `ServerLevel` cast in `ConfigManager.isDecaying/isRefreshing`; `DefaultPlatformAPIImpl.getSyncData` does the item-2 pattern for entities.

### T06 loot table verification: PARTIAL, stopped early, NOT reviewed, may not compile
- Local branch `wip/s7-t06-verify-partial`, commit 05b227b2, based on 9a25d79e. Local only.
- Contains: platform seams in `IPlatformAPI`/`PlatformAPI`/`PlatformAPIImpl` (pack origin, game directory), small public helpers in `IntegratedLoot`/`IntegratedLootSnapshot`, and in `common/.../verify/`: `LoadConditions` (302 lines), `PackOrigin`, `ReferenceIndex`, `ReferenceScanner`, `TableAnalyzer` (292), `VerifyOptions`, `VerifyResult` (245). Missing: the run orchestration, report rendering (Markdown and JSON), the command, the start-up mode, all tests.
- Recommendation: delegate T06 again to Opus with the specification in Part 4, telling it that the branch exists and may be used as a starting point after reading it critically, or discarded.

### T05 built-in rule data: DRAFT COMPLETE in a folder outside the repository, not installed
- Durable copy: `F:\Coding\.knowledge\s7-lootr-remastered\t05-rules\` (`data\lootr\lootr\integrated_loot\pool\*.json` 6 pools, `rule\*.json` 51 files, `tools\validate.py`, `tools\gen_data.py`, `NOTES.md`, `coverage.md`).
- Validator result against the pack inventory: 1,125 container candidate tables, 893 extended, 145 excluded, 54 not containers, 33 without rule (each with a reason); every structure-referenced table is extended or has a stated reason.
- It was written from the loot table ids of the installed structure mods only; the subagent was forbidden to read the Loot Integrations study or jars (clean-room).
- Before installing: the engine needs a second gate `required_namespaces` (decision recorded), because Cataclysm and Astral Dimension are NeoForge jars loaded through the mod Sporran and are not visible as Fabric mod ids (the pack log lists only `sporran`); their two exclude-only rule files currently have no gate and would show up as "pattern matches nothing" in every pack without those mods.

## Stopped agents
All subagents are stopped. No Gradle or game process was started by this session that still needs to run; check with the process list if in doubt. The game lock `F:\Coding\.knowledge\game.lock` was never taken by this session.

---

# Part 3: Next steps, in order

1. Set up: make sure you are on `claude/intelligent-gauss-f0435e` (clean, last commit is the handoff commit), run build and game tests once to confirm the baseline (37 tests).
2. T07: review the WIP branch as described above, request corrections from a subagent where needed (API default methods), bring it onto the working branch, add the `BlockEntityTicker.clear()` hook lines through a subagent, build, test, commit, push.
3. T05 install + namespace gate (one Sonnet task, engine part small): add optional `required_namespaces` (list of strings; rule file is active only if every listed namespace has at least one loaded loot table; reported in diagnostics like missing mods) to `IntegratedLootDefinitions`/`IntegratedLootSnapshot` with a game test; copy the 6 pool files and 51 rule files from the durable folder into `common/src/main/resources/data/lootr/lootr/integrated_loot/{pool,rule}/`; give the two NeoForge-format exclude files `required_namespaces` (`cataclysm`, `astral_dimension`); add a game test that all built-in files load without malformed-file diagnostics and that the vanilla rule file is active in the dev environment; check that existing tests still pass with vanilla tables now being extended. Do not copy `tools\` or the coverage files into the repository unless you decide the validator is worth keeping (it depends on the pack inventory files).
4. T06 verification command (Opus), specification in Part 4.
5. T08 small bug fixes (Sonnet): duplicate registration of `/lootr custom-area-old` in `CommandLootr` (two registrations, the first is dead); static loot table suggestion cache in `CommandLootr` never refreshed after `/reload`; Fabric `ModBlocks`: the suspicious sand variant uses the gravel completed-sound; `GameProfileCache$GameProfileInfo` is made public only by the access transformer (used for compiling `common`) and not by the Fabric access widener (check whether Fabric code touches it at runtime); `buildSrc` is unused now and can be removed; dead options (`save_mode`, `doesServerNeedLessFiles`); `ConfigManager.get()` is a holder lookup on every call; `DefaultLootrAPIImpl.refreshSections()` may reach client code on a dedicated server before server start (not verified); trophy block selection box is deeper than the new model (`TrophyBlock`); plus the untouched items from the T07 list above. Each confirmed before fixing.
6. T10 remove the "old textures" built-in resource pack (`common/src/main/resources/resourcepacks/old_textures`), all `old_*` textures, the options `vanilla_textures`/`new_textures` and the renderer paths that use them, the pack registration in `LootrEventsInit`, and delete the now empty `common/src/main/resources/assets/minecraft/atlases/blocks.json`; check `chests.json`/`shulker_boxes.json` atlas files for old-texture entries. These files are still upstream art and must be gone before anything is public.
7. T11 language files: en_us has stale keys (`client.old_textures`, `conversion.max_entry_age`, `conversion.rename_container_block_entities`, `debug.report_invalid_tables`) and misses names for `lists.loot_table_forced_whitelist` and `conversion.tickDelay`; write `de_de.json` new; remove the other 30 translation files (default answer 2).
8. T12 measurement and T13 pack run (Opus plus lead), details in Part 4 "Running the pack".
9. T14 release material (Sonnet): README rewrite (still upstream text plus a Tests section), CurseForge description and short summary, credits, changelog, replace `LICENSE_ASSETS.txt` (it still says all assets are All Rights Reserved; new art is ours), licence audit document. Audit points already known: git history of the repository still contains upstream's reserved assets (it is a fork of the public upstream repository; decide with the owner whether to publish with history or from a fresh history); repository head must contain no upstream art (after T10 and T11) and no decompiled or add-on data.
10. T15 final acceptance: full build and tests, client proof with screenshots, manual checklist, `.knowledge/report.md`, one line in `F:\Coding\.knowledge\log.md`, push.

---

# Part 4: Reference

## Design in force (details in `decisions.md`)
- Integrated loot extends roll results, it does not rewrite tables. Hooks: wrap of `shuffleAndSplitItems` inside `LootTable.fill` (gives the empty-slot count) and of the private list roll inside the three public `getRandomItems` list overloads. Decisions live in an identity map keyed by the loot table objects of the current data load; resolution happens on Fabric's tags-loaded event and on server starting. One or two source rolls per extension, random from the roll's own context, never removes original loot, never re-entrant (thread-local flag), explorer maps skipped inside source rolls.
- Data format (own): pools `data/<ns>/lootr/integrated_loot/pool/<path>.json` = `{ "sources": [ { "table": id, "weight": n } ] }`; rules `data/<ns>/lootr/integrated_loot/rule/<path>.json` = `{ "required_mods": [...], "priority": n, "entries": [ { "tables": [patterns], "pool": id, "rolls": n | {min,max} } | { "tables": [...], "exclude": true } ] }`; pattern `namespace:path` with `*` (no `/`) and `**`; first match wins by priority descending, file id, entry order. Item tag `lootr:integrated_loot/excluded`.
- Read-only API for the verification: `IntegratedLoot.snapshot()` returning `IntegratedLootSnapshot` (`decisionFor(key)`, `decisions()`, `ruleFiles()`, `inactiveRuleFiles()`, `unmatchedPatterns()`, `droppedSources()`, `unusablePools()`, `malformedFiles()`, `ineligibleMatches()`, `pools()`), `IntegratedLoot.stats()`.
- Item bans: `ItemBans.isBanned(stack)`, sources registered from the pushed `ItemBanSettings`; Item Obliterator entry point `elocindev.item_obliterator.fabric_quilt.util.Utils.isDisabled(ItemStack)` resolved with `MethodHandles.publicLookup().findStatic` (reflection with `getMethod` fails when EMI is absent).
- Config (`config/lootr.json`, Cloth AutoConfig): new sections `item_bans` (`respect_item_obliterator`, `filter_all_loot`, `scrub_saved_inventories`, `extra_banned_items`) and `integrated_loot` (`enabled`, `modded_item_weight` default 2, `skip_existing_items`, `skip_map_items`, `max_extra_stacks` default 4, `debug_log`, `imported_loot_integrations_config`). Settings are pushed to common code as immutable records from `fabric/.../config/LootrConfigInit.java`.
- Tests are Fabric game tests only (no JUnit: it would be a new library).

## Specification for T06 (verification), to be delegated again
Command `/lootr verify [rolls <n>]` (permission level 2, one run at a time, scan off the server thread where safe, sample rolls on the server thread with a per-tick budget, at most one progress message every few seconds). Start-up mode through JVM property `lootr.verify` = `true` (run once after server start; `lootr.verify.rolls` for the roll count) or `exit` (also stop a dedicated server afterwards). Reports `logs/lootr/verify-<yyyyMMdd-HHmmss>.md` and `.json` plus `verify-latest.*`; Markdown grouped by responsible mod or pack (from `Resource.sourcePackId()` mapped to mod id, name, version through a platform seam) and capped per group; JSON complete; sorted, diffable output; no finding class may abort the run. New package `noobanidus.mods.lootr.common.verify`.
Check categories:
- A `load_failure`: loot table resources not present in the loaded registry, re-parsed with the game's codec for the error text; sub-classes `unknown_item` (list the ids), `invalid_json`, `other`; resources with unmet `fabric:load_conditions` / `neoforge:conditions` are `skipped_by_condition` (info).
- B `legacy_folder`: resources under `loot_tables/` (ignored by 1.21.1), per pack, with a note whether the same id exists in the right folder.
- C `missing_reference`: (C1) nested table entries and set-loot-table functions inside LOADED tables (encode with the codec and walk the JSON, so entries added in code by other mods are seen; fall back to the resource JSON when encoding fails); (C2) structure templates `structure/**/*.nbt` walked generically for `LootTable`, `DeathLootTable`, `minecraft:container_loot`, trial spawner and vault keys, recording kind and block entity or entity id; (C3) worldgen JSON (`worldgen/**`) values under `loot_table`. The union of container references is "tables used by structure containers".
- D `banned_item`: item and tag entries of loaded tables that are banned (info, only when a ban source is active).
- E `cannot_produce`: static judgement (no pools; only empty entries; only banned items; nested references to tables that cannot produce; unknown entry types count as "can"); warning when used by a structure container, otherwise info as a count per origin. With `rolls` > 0: sample rolls of structure-used and `chests/` tables through a path not extended by integrated loot and with explorer maps suppressed; record `roll_error` and `always_empty_in_rolls`.
- F `no_integrated_loot`: for each structure-container table of an extendable type: `extended`, `excluded` (by which rule file), `rule_problem`, `not_applicable` (only one-slot or brushable containers use it), `no_rule`; coverage per origin.
- G `integrated_loot_data`: the snapshot's own diagnostics.
Game tests with fixtures for each finding class (including a reproducible `.nbt` fixture), report files written and JSON parseable, second concurrent run refused, start-up decision function tested without stopping the test server. Allowed files: the new package; `CommandLootr` (registration line); `LootrEventsInit` (start-up hook); the three platform API files; small public helpers in `IntegratedLoot`/`IntegratedLootSnapshot`; game test sources and resources.

## Facts found in step 1 (also in `log.md`)
- Loot Integrations owns no item list: it samples vanilla chest tables through six pool tables into 922 exact target ids; 14 to 34 full table rolls per extended roll, per player with Lootr. Defects seen: its map-skip option flags the wrong context, its cap step deletes original loot, global unsynchronised guard, one malformed file fails the whole reload. Its config in the pack (`config\lootintegrations.json`): `skipExistingItems` true, `moddedItemWeight` 1, `skipMapItems` true, `debugOutput` false.
- Item Obliterator: blacklist in `config\item_obliterator.json5` (exact ids or `!` + full-match regex), reloaded on `/reload`; its only loot hook is `RandomizableContainerBlockEntity.setItem`, so Lootr inventories, minecart chests, pots and hoppers were not covered. Pack blacklist: `kibe:chunk_loader`, `clutter:coin_pouch` (Clutter adds the pouch to vanilla chest and entity tables in code through Fabric's loot table modify event: this is the real test case for R3).
- Static pack scan: 19,814 loot table files, all valid JSON, no missing nested references; 74 structure-NBT `LootTable` references (36 ids) point to tables that exist nowhere (Dungeons and Taverns 32, When Dungeons Arise 30, Towns and Towers 7, Cataclysm 2, Adventure Dungeons 1, Incendium 1, Terralith 1); 223 non-vanilla empty tables; 91 files on the legacy `loot_tables/` path; stale `dynamic-data-pack-cache\everycomp-dynamic_resources` (Every Compat is not installed); `global_packs\required_data\dungeons_arise_fixes.zip` overrides 11 Dungeons Arise chest tables.
- `cupboard` (needed by 7 other mods) and `necronomicon` (needed by `bossultimatum`) must stay in the pack when the 11 jars go. Cloth Config stays (Lootr requires it).
- Mods that link against Lootr classes: JadeAddons, SubtleEffects, aether, artifacts-remastered, simplyswords. Keep packages and public API stable.
- Four NeoForge-format jars run through Sporran (`L_Ender's Cataclysm`, `astral_dimension`, `curios`, `lionfishapi`).
- Upstream open bug reports relevant to 1.21.1: #912 refresh while GUI open (addressed by T07 item 8), #886 double chests split (not planned), #788 brush compatibility (not planned).

## Material outside the repository
- Durable copy made at handover: `F:\Coding\.knowledge\s7-lootr-remastered\` with `study-facts.md` (condensed behaviour facts of Loot Integrations and Item Obliterator, lead's own words; do not copy into the repository), `t05-rules\` (see above), `pack-inventory\out\` (CSV inventories: `loot_tables_all.txt`, `loot_tables_vanilla.txt`, `mods.csv`, `structure_nbt_loot_refs.csv`, `chest_tables_by_source.csv`, `log_findings.md`, config copies) and `pack-inventory\tools\` (the Python scan scripts), `pack-blacklist.txt`.
- Session scratch folder of the previous session (may be gone): `C:\Users\nikol\AppData\Local\Temp\claude\F--Coding-Lootr-Remastered--claude-worktrees-intelligent-gauss-f0435e\23e8465a-784f-4fde-8d2e-6fff389c8237\scratchpad\` with `li-study\` (decompiled Loot Integrations material and its complete target lists: All Rights Reserved, never copy into the repository; not needed any more) and `io-study\`.
- Python 3.12: `C:\Users\nikol\AppData\Local\Programs\Python\Python312\python.exe` (the `python` on PATH is a store stub). Vineflower and yarn mappings are in the Gradle cache if a jar must be inspected again.

## Running the pack (for T12, T13, T15)
- No unattended launch script exists; the pack is normally started by the CurseForge launcher. `mods_backup\` and `.dev\backups\` do not exist yet in the pack; create them when you swap jars.
- Server side: `C:\Users\nikol\curseforge\minecraft\Instances\NytheriaDevelopment\.dev\ServerPack` is a complete built Fabric server (324 mods, `fabric-server-launch.jar`, `start.bat`, `variables.txt` with RAM=10G, EULA already accepted by the owner, `online-mode=true`, RCON off). Plan: copy it (without the 1.1 GB zip and the `world` folder) to a throwaway folder, swap the 11 jars for the new jar there, start it with `-Dlootr.verify=exit -Dlootr.verify.rolls=<n>` once T06 exists, read `logs\lootr\verify-latest.*`. For the baseline measurement (T12) run the same copy with the old 11 jars. This still counts as running Minecraft: take the game lock.
- Client side: `logs\instance_audit.txt` in the pack records the full JVM arguments and classpath of each launcher start (79 classpath entries, all present; natives under `C:\Users\nikol\curseforge\minecraft\Install\natives\fabric-0.19.5-1.21.1`, 10 GB heap). The program arguments are not recorded; the plan was to start `net.fabricmc.loader.impl.launch.knot.KnotClient` with that classpath and `--gameDir <pack> --assetsDir C:\Users\nikol\curseforge\minecraft\Install\assets --assetIndex <index for 1.21.1> --version fabric-0.19.5-1.21.1 --username <name> --accessToken 0` (offline, single player) and a throwaway world. Not tried yet; verify the asset index name in `Install\assets\indexes` first.
- Manual checklist items collected so far (to be executed in the pack and written into `report.md`):
  - Start-up log: "Item Obliterator found: ..." once, no mixin errors for `LootTable` or `ExplorationMapFunction` next to the roughly 29 other mods that mix into `LootTable`, one line "Integrated loot: N loot tables extended ...", one line about taking over `lootintegrations.json` on first start only.
  - `clutter:coin_pouch` never in a new Lootr chest or `/loot` result; a chest opened before a ban loses the item on reopen and after restart; `/reload` after changing Item Obliterator's config applies without restart; minecart chest, decorated pot, suspicious sand and gravel, item frame, `/lootr open_as`.
  - A structure chest of a covered mod shows extras for two different players; `/lootr integrated <table>` and `/lootr integrated stats`; no lag spike on ocean-type chests (explorer maps skipped).
  - Advanced Loot Info and EMI Loot still open and show loot tables (they will not show integrated extras: known limitation).
  - Existing world with `data\lootr` (the pack has two test worlds with Lootr data: `New World (3)`, `PerfTest-create-20261003`; use COPIES, never the owner's saves) opens its containers with the old contents.
  - Art in game: chest strap alignment over lid and body, lock position, no flicker at the lid seam and at the shulker belt, barrels in all facings, pot cap and broken pot, toast text placement, item frame rim, trophy in four facings and as item, break particles, advancement tab background, mod icon in Mod Menu.
  - With C2ME: generate new chunks with structures, watch the log for "Accessing LegacyRandomSource from multiple threads".
- Pack changes to list for S2 when they happen: removal of the 11 jars, addition of the new jar, any loot-related data fix, the leftover `config\lootintegrations.json` and `config\cupboard.json` (cupboard stays), the stale Every Compat cache folder.

## Known limitations so far
- Loot viewers read tables statically and do not show integrated extras.
- Only the top-level stack is checked against bans (contents of bundles or shulker box items are not inspected); a banned stack a player put into their own Lootr inventory is also deleted; an item frame whose reference item is banned still displays it.
- Config changes on a dedicated server need a restart (existing Lootr behaviour).
- With loot seed 0 vanilla's roll random is the level random, so extras draw from it in that case; the slot layout of an extended fill differs from vanilla for the same seed.
