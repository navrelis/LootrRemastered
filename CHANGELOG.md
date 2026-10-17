# Changelog

## 2.2.0

- New commands `/lootr check [<pos>]`, `/lootr locate table <loot_table> [<radius>]` and `/lootr locate structure <structure> [<radius>]`: say what is the matter with a container (loot table missing, empty or fine, integrated loot, structure, openers) and find containers by loot table or inside a structure. Chunks that are not loaded are read only if they are already generated; none is generated.
- The verification report has a new section H, "Structures with problem containers" (`structure_containers` in the JSON report). For each structure it lists every container of a structure template whose loot table is missing (`missing_reference`), cannot produce an item (`cannot_produce`) or produced nothing in the sample rolls (`always_empty_in_rolls`), with the template, the holder (block entity or entity id) and the loot table; the files have the full lists, without the cap of five examples that the other sections have. Templates are attributed to structures through the start pool of every jigsaw structure, its pool elements (single, legacy and list elements; features are ignored) and the jigsaw blocks of the templates, with the fallback pools. Templates that no jigsaw structure reaches (the woodland mansion, the stronghold, structures of mods that place templates from code) are grouped by namespace and the first part of the template path and marked as unattributed. The chat summary gets the line "Structures with problem containers: N (M container tables missing, K empty)". The existing finding classes and their counts are unchanged.
- New command `/lootr verify structures`: prints the structure section of the latest result (the 15 structures with most problem containers; the structure id is clickable and runs `/lootr locate structure <id>`), or says to run `/lootr verify` first.
- New: the personal cycle. Every player (or team) gets their own loot in a Lootr container refreshed at fixed real-time intervals after the first opening, a configured number of times, and afterwards the container decays for that player only; other players are not affected. It is on by default: every 72 real hours, 3 re-rolls, then decay at 288 hours after the first opening (one interval after the last re-roll). Server downtime counts, and everything is worked out when the player opens the container, from the stored start and the settings in force then, so changed settings apply to running cycles. Settings in the new `personal_cycle` section of `config/lootr.json`: `enabled` (true), `refresh_interval_hours` (72; at least 0.0167), `refresh_count` (3; 0 never re-rolls), `decay_after_last_refresh` (true), `excluded_loot_tables` and `excluded_dimensions` (ids or `namespace:*`) and `overrides` (`<loot table id or namespace:*>=<hours>,<count>,<true|false>`; an exact id beats a namespace). Containers in structures of the tag `lootr:personal_cycle_exempt` keep their loot for good. Lootr's own refresh and decay keep precedence: a container that they handle is not subject to the personal cycle. Custom inventories, fake players and container types that cannot refresh are not subject to it either. New command `/lootr cycle info [<player>]`, `/lootr cycle reset <player>` and `/lootr cycle expire <player>` (moves the start back by one interval) for the container you look at or stand on. A server owner who does not want the personal cycle sets `personal_cycle.enabled` to false.

## 2.1.0

- Build restructured: one branch per Minecraft version, one standalone Gradle build per loader.

## 2.0.0 (Lootr Remastered, Fabric 1.21.1)

Relative to Lootr 1.11.38 (the base is 1.11.38.128, commit `b62e382a`). The mod id `lootr`, the Java packages and the saved
data format are unchanged, so worlds and mods that link against Lootr keep working. The jar is
`lootr-remastered-fabric-1.21.1-2.0.0.jar`.

Figures in the lists below come from the project's own test runs (Fabric game tests on a headless server with vanilla content
and small scenarios), not from a modpack. They show how much unnecessary work was removed in that scenario, not how much
faster your server runs. Figures from a server copy of a large pack are in the section "Measured on a server" at the end.

### Added

- Integrated loot, built in: data pack rules add one to a few stacks from pools of vanilla chest loot tables to the roll
  results of chest-type loot tables, including those of structure mods. 6 pools and 51 rule files ship in the jar; each rule file is
  active only when the mod it targets is installed. Replaces Loot Integrations and its add-ons. Settings in the new
  `integrated_loot` section of `config/lootr.json`; format and example in the README. The vanilla table
  `minecraft:chests/trial_chambers/intersection_barrel` is not a source of the pool `overworld_common`: its compass entry
  with `set_damage` logged "Couldn't set damage of loot item" warnings when the table was rolled as a source.
- The settings of an existing `config/lootintegrations.json` (`moddedItemWeight`, `skipExistingItems`, `skipMapItems`,
  `debugOutput`) are taken over once; the file is not changed.
- Item bans: items disabled in Item Obliterator (if installed) and items on the new `extra_banned_items` list are removed
  from what a loot table itself rolls and from stored per-player inventories when these are opened. New `item_bans` section.
  The filter wraps the consumer on its way into the item functions and leaves the arguments of the roll alone, so it works
  beside mods that capture the roll output of `LootTable.getRandomItemsRaw` (tested with CraftTweaker 21.0.38). In a
  second server run (a copy of a pack with about 314 server-side jars, CraftTweaker among them, default config) loot rolled
  normally, and an item that Item Obliterator disables appeared in 0 of 24,000 rolls of `minecraft:chests/simple_dungeon`
  with the filter on, against 11,970 of 24,000 with `filter_all_loot` off (counted in the inventory of a chest minecart).
  The start-up line about Item Obliterator says whether other loot rolls are filtered (`filter_all_loot`) or only Lootr containers.
- `/lootr verify [rolls <n>]`: checks all loot tables, structure templates and worldgen files of the loaded packs and writes
  a Markdown and a JSON report to `logs/lootr/`. Start-up mode with `-Dlootr.verify=true|exit` and `-Dlootr.verify.rolls=<n>`.
  Its `banned_item` check also follows nested loot table entries: a table that reaches a banned item through the tables it
  refers to is listed, with the tables it goes through (`via` in the JSON report).
- `/lootr integrated <loot_table>` and `/lootr integrated stats` to see what integrated loot does.
- The loot table verification states at the top of its report (and in its summary) when nearly all sampled tables roll empty
  (at least 90 percent of at least 20 sampled tables), because that points to a conflict between mods that change loot rolls
  and not to bad tables.
- If the hook on the loot roll cannot be applied (another mod changed the method), Lootr logs one warning at start-up and
  switches the all-loot filter off instead of failing to start. Lootr containers and stored inventories are still scrubbed.
- Item tag `lootr:integrated_loot/excluded` (command blocks, barriers, spawners and similar): never added by integrated loot.
- A German language file, written new.
- Message "This container's loot has refreshed." for a player whose container screen is closed by a refresh.
- New pixel art for every texture, the trophy model, the mod icon and the pack icons, and a logo.
- `ILootrAPI` has two new methods, `getLoadedData` and `getExistingData`; they are default methods, so existing implementations still compile.
- Fabric game tests (`:fabric:runGametest`) for the core, item bans, integrated loot and the verification; they are not part of the jar.

### Changed

- Fabric only. The NeoForge build of Lootr is not part of this project.
- Name Lootr Remastered, version 2.0.0, author navrelis; the original authors are credited in `fabric.mod.json` and `CREDITS.md`.
- Textures and the trophy model are new. The built-in "old textures" resource pack and the `new_textures` option are gone; the API
  methods `isOldTextures`, `isNewTextures` and `ClientTextureType.OLD` still exist but are deprecated.
- English language file cleaned up (76 unused entries removed).
- A container that is waiting for its surroundings to load is checked every 5 ticks instead of every tick, so it can be converted
  up to 5 ticks later than before.
- Licence files: `LICENSE_ASSETS.txt` now describes this repository (see there and `docs/licence-audit.md`). The build puts `LICENSE` and `LICENSE_ASSETS.txt` at the root of the jar.
- The block state and model JSON files (32) were written anew from a functional specification; the item models of the chests, shulker box, trapped chest, inventory chest and
  decorated pot now inherit from vanilla's chest item model instead of carrying their own display transforms. Six one-line files are still identical to Lootr's, because they cannot differ.
- In the verification, loot tables that roll empty in every sample roll and that no container uses are listed as notes
  instead of warnings (`always_empty_in_rolls`). Dispenser loot tables (`minecraft:dispensers/**` and
  `repurposed_structures:dispensers/**`) are excluded from integrated loot explicitly: they are ammunition, not treasure.
- `fabric.mod.json` declares that this mod breaks with `lootintegrations` (Loot Integrations and its add-ons): running both would extend loot twice.

### Fixed

Saved data and performance of the core (found by reading the code; each was confirmed before it was fixed, most with a test that failed before the fix):

- Saved data of opened containers was rewritten to disk on every access after a server restart. Now only changed data is saved.
  Test: 20 opened chests accessed over 5 rounds after a restart rewrote 100 data files before, 0 now.
- A container nobody had opened created an empty saved-data object and a file lookup whenever its data was sent or updated.
  Test: 50 unopened chests created 50 objects before, 0 now. Another test: an update of an unopened container created a data file, 20 times in the test before, 0 now.
  The same for entities that are sent to a player: 10 objects before, 0 now.
- Old-format "ticking" data files were created and rewritten on every access (2 files in the test before, 0 now).
- The server treated "some container may be refreshing or decaying" as true whenever the shipped empty tags existed, so it did the
  extra work for every container. It is now false until something is configured.
- Containers waiting to be converted were re-checked every tick. Now every 5 ticks (100 checks in 100 ticks before, 20 now in the test);
  the queue is also emptied when the server starts and stops.
- Caches keyed by player UUID were never emptied. Removed.
- Several on/off flags used during loot filling, container closing and saving were shared by all threads and not reset after an
  error. After one failed structure save, `LootrId` was no longer written in later saves. They are now per thread and always reset.
- The service registry was not thread-safe. It is now.
- The routine that walks Lootr's data files (used by admin commands) took timer files for containers and dropped timers that were not yet saved.
- `/lootr cull` could remove saved data of a container that still had recorded openers.

Found in a performance review of the finished mod (by reading the code):

- The server's handler for the client's container update request (`request_update`) looked up the block entity at any
  position the client named. For a chunk that is not loaded this can load or generate the chunk on the server thread. The handler
  now ignores positions outside the requesting player's dimension, outside the view distance, or in chunks that are not loaded,
  so a modified client can no longer make the server load or generate chunks through it. The stall itself was not reproduced
  in a test; the mod's own client only asks about blocks it has loaded, so normal play is not affected.
- The hopper hook looked up a block entity for every container lookup, also for blocks that have none (the air above a hopper).
  It no longer does; vanilla asks only blocks that have one. Saves one lookup per hopper container lookup (estimated
  50 to 100 nanoseconds). No effect was measurable in the test pack, where Lithium keeps idle hoppers asleep; not measured without Lithium.

Behaviour that players and admins notice:

- Refresh while a player has the container open (Lootr issue #912): the screen now closes with a message instead of showing stale contents.
- Clearing a player's stored inventory (for example `/lootr clear`) while that player had it open left the screen on a deleted inventory. Fixed.
- Closing containers from a thread other than the server thread is now handed to the server thread.
- A player was recorded as opener of a container even when the screen failed to open. Fixed.
- Looking up the stored inventory of an offline player threw a `NullPointerException`. It now returns nothing.
- A decay or refresh check with a missing or client-side level threw an exception. Fixed.
- `/lootr custom-area` did not exist because the old variant was registered under the same name. `custom-area` is the current command and `custom-area-old` the old one.
- The loot table suggestions of the commands were cached forever and missed tables after `/reload`.
- Finishing suspicious sand played the gravel sound.
- With `tickDelay` set to 0 the decay and refresh check divided by zero. Guarded.
- The shape of the trophy block did not match its model (13 x 14.5 x 8 against 8 x 14 x 8). They match now.
- A call that refreshes the sections could load a client class on a dedicated server when no server was registered yet. Fixed.
- Client: the model of an opened barrel overrode the vanilla barrel mode because of a missing `else` in `CustomBarrelModel`. Fixed.
- The configuration is looked up once instead of on every call.

### Removed

- The NeoForge module, the data generators (generated resources are maintained by hand now), the Jenkins and Crowdin files, and the publishing
  configuration of the original Lootr (project ids, Maven and release tasks).
- The 30 community translations of Lootr. Only `en_us` and `de_de` ship until the translators and the original author agree to their reuse.
- The built-in "old textures" resource pack, its 14 textures, 4 models and atlas entries, and the option `new_textures`.
- The options `save_mode` (it had no effect) and `doesServerNeedLessFiles` (dead code). Unknown keys in an existing `config/lootr.json` are ignored.
- The original artwork, its source files and its logo (replaced, see above).

### Measured on a server

Server runs and a profile of the finished jar, all on a dedicated server (Java 21) with a copy of one large pack (about 314
server-side jars; among them C2ME, Lithium, VMP, ServerCore and Item Obliterator, which disabled 2 items). The PC was shared
with other programs. These figures describe that pack and that machine; they are not a promise for yours.

Cost of one loot roll, in microseconds, for the chest tables that integrated loot extends. A roll is
`loot replace block ... loot <table>`, repeated 20,000 times in one function and timed from the server log, so it includes about
2 microseconds of command dispatch. Each configuration ran in two separate server starts (the mod has no runtime config reload),
10 timed passes each; each value is the mean of the two run medians, rounded.

| Table | Default | Item ban filter off | Integrated loot off | Both off |
|---|---|---|---|---|
| `minecraft:chests/simple_dungeon` | 33 | 34 | 17 | 18 |
| `dungeons_arise:chests/illager_fort/illager_fort_treasure` | 37 | 36 | 25 | 22 |
| `nova_structures:chests/illager_hideout_tresure` | 20 | 19 | 5 | 6 |
| `minecraft:chests/ruined_portal` | 40 | 39 | 25 | 24 |

- Integrated loot adds 12 to 16 microseconds to a roll of an extended table. In the CPU profile about 10 to 14 of those are the
  one extra source table that the feature rolls. A roll happens once per player per container, not per tick. For a table without
  a rule and for entity tables the difference was within the noise.
- The item ban filter could not be resolved by this timing (the differences are below the spread and change sign). From the
  profile it costs about 0.1 microseconds per checked stack (an estimate, plus or minus half), about 1.4 to 4.9 microseconds
  per chest roll, and nearly all of that is spent inside Item Obliterator's own check, not in Lootr.
- "Both off" is not "mod absent": the hooks stay installed (one volatile read each) and the jar was not removed for this
  series. In an earlier run of the same pack copy with none of the loot mods installed, the same four tables took 3.6 to 14
  microseconds per roll and 19 to 84 with this mod (median of five batches of 5,000 rolls, one run each).
- The old set (Lootr and Loot Integrations with add-ons, 11 jars) measured 0.30 to 0.45 seconds per roll of an extended table on
  the server thread in the first run of the same pack copy; the same rolls with this mod took 20 to 60 microseconds in that run.
  That run used batches of 20 rolls for the old jars and is not a like-for-like comparison.

Outside loot rolls the mod's code was not visible in the profile (sampled server thread, default config; the profiler delivered
about one sample per 20 milliseconds, so "not visible" means below about 1 percent, not zero):

- idle, 40 seconds: 0 of 237 samples in Lootr code;
- 4,096 Lootr barrels loaded, 60 seconds: 1 of 405 samples in block entity ticking at all, 0 in Lootr code;
- 432 newly generated chunks: 5 of 691 samples in Lootr code;
- converting 4,096 vanilla barrels that have a loot table: 2 of 113 samples, about 40 milliseconds in all (estimated from the 2 samples);
- start-up including loading the integrated loot data: about 12 of 6,125 samples, about 0.25 seconds (estimated);
- 4,096 hoppers, 40 seconds: no hopper code among the samples.

`/lootr verify rolls 3` on the same pack took 14.2 seconds (1,203 tables, 3,609 rolls); the server tick during the run averaged
1.3 to 2.6 milliseconds, the 99th percentile was at most 15.0 milliseconds.

Noise: the PC also ran Gradle daemons, and a Minecraft client was started during two of the runs. Batches within one run differed
by up to a factor of 2.7, and the medians of two starts of one configuration by up to 1.5; differences between configurations
below about 5 microseconds are not findings. Not measured: the client side (renderers and models were only read), `/reload`, a
server without Lithium.
