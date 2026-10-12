# Changelog

## 2.0.0 (Lootr Remastered, Fabric 1.21.1)

Relative to Lootr 1.11.38 (the base is 1.11.38.128, commit `b62e382a`). The mod id `lootr`, the Java packages and the saved
data format are unchanged, so worlds and mods that link against Lootr keep working. The jar is
`lootr-remastered-fabric-1.21.1-2.0.0.jar`.

Figures in this file come from the project's own test runs (Fabric game tests on a headless server with vanilla content
and small scenarios), not from a modpack. They show how much unnecessary work was removed in that scenario, not how much
faster your server runs.

### Added

- Integrated loot, built in: data pack rules add one to a few stacks from pools of vanilla chest loot tables to the roll
  results of chest-type loot tables, including those of structure mods. 6 pools and 51 rule files ship in the jar; each rule file is
  active only when the mod it targets is installed. Replaces Loot Integrations and its add-ons. Settings in the new
  `integrated_loot` section of `config/lootr.json`; format and example in the README.
- The settings of an existing `config/lootintegrations.json` (`moddedItemWeight`, `skipExistingItems`, `skipMapItems`,
  `debugOutput`) are taken over once; the file is not changed.
- Item bans: items disabled in Item Obliterator (if installed) and items on the new `extra_banned_items` list are removed
  from what a loot table itself rolls and from stored per-player inventories when these are opened. New `item_bans` section.
  The filter wraps the consumer on its way into the item functions and leaves the arguments of the roll alone, so it works
  beside mods that capture the roll output of `LootTable.getRandomItemsRaw` (tested with CraftTweaker 21.0.38).
- `/lootr verify [rolls <n>]`: checks all loot tables, structure templates and worldgen files of the loaded packs and writes
  a Markdown and a JSON report to `logs/lootr/`. Start-up mode with `-Dlootr.verify=true|exit` and `-Dlootr.verify.rolls=<n>`.
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
