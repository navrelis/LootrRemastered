# Lootr Remastered

Lootr Remastered is a Fabric mod for Minecraft 1.21.1. It gives every player their own copy of the loot in a
loot container, adds a few extra stacks from other loot tables to the containers of vanilla and modded structures,
keeps banned items out of loot rolls and Lootr containers, and can check a whole pack's loot tables with one command.

It is a fork of [Lootr](https://github.com/LootrMinecraft/Lootr) (code under the MIT licence) and takes over the job of
the mod Loot Integrations with its add-ons, in one jar. Mod id: `lootr`. Version 2.0.0. Fabric only.

What the mod does not do: it does not change structures or loot tables on disk, it does not remove loot that a
table yields (extra stacks are only added), and it does not show the extra stacks in loot viewers (see
[Known limitations](#known-limitations)).

## Requirements

| | Needed | Notes |
|---|---|---|
| Minecraft | 1.21.1 | |
| Java | 21 or newer | |
| Fabric Loader | 0.17.0 or newer | `fabric.mod.json` |
| Fabric API | 0.100.3 or newer | built against 0.116.7+1.21.1 |
| Cloth Config API | 15.0.127 or newer | required; provides the config screen and file |
| Item Obliterator | optional | its disabled items are kept out of loot, see [Item bans](#item-bans) |
| Mod Menu, Jade | optional | config screen entry, container tooltips |

The jar also contains compatibility code for Sherds API and Create; neither is required. Install it on the server and on every client.

## Installation

Put the jar `lootr-remastered-fabric-1.21.1-2.2.1.jar` into `mods/`. Default settings work without changes.

### Switching from Lootr and Loot Integrations

This mod replaces the original Lootr and Loot Integrations with all its add-ons (`lootintegrations_*` and similar).

1. Remove from `mods/`: the Lootr jar, the Loot Integrations jar and every Loot Integrations add-on jar. Do not run the old and new mod side by side.
2. Keep Cloth Config. `cupboard`: Lootr Remastered does not need it (it is not in the mod's dependency list), but other mods may; check what depends on it before you remove it.
3. Existing worlds keep working: the mod id is `lootr`, saved per-player inventories under `data/lootr` are read as before, and the Java packages and API of Lootr are unchanged, so mods that link against Lootr keep running.
4. `config/lootr.json` keeps working; new sections are added with defaults on the first start.
5. `config/lootintegrations.json` is read once, on the first start with this mod: `moddedItemWeight` n becomes `modded_item_weight` n + 1, and `skipExistingItems`, `skipMapItems` and `debugOutput` are taken over as `skip_existing_items`, `skip_map_items` and `debug_log`. The file itself is not changed or deleted. The flag `integrated_loot.imported_loot_integrations_config` in `config/lootr.json` records that this happened; set it to `false` to take the values over again.
6. The rules of the Loot Integrations add-ons are not read. The built-in rules of this mod take their place, and each one is active only when the mod it targets is present.

## Features

### Per-player loot (from Lootr)

- A container with a loot table is turned into a Lootr container when it is loaded: chests, trapped chests, barrels, shulker boxes, minecarts with chests, decorated pots, suspicious sand and gravel, and item frames that hold an elytra. Each player who opens one gets their own inventory, rolled from the loot table; what one player takes does not affect another.
- Optional decay (a container crumbles away some time after it has been opened) and refresh (a container yields new loot after a delay), by loot table, mod id, dimension or structure tag, or for everything.
- Optional shared inventories for teams. Break protection with several switches. Advancements and a statistic for looted containers.
- Block, entity and structure tags decide what is converted and what is excluded (`data/lootr/tags/...`). Two optional data packs are built in: "Disable Lootr Advancements" and "Disable Lootr Converting Suspicious Blocks".

### Personal cycle

Lootr's refresh and decay work on a container for everybody at once. The personal cycle gives each player (or team) a schedule of their own, counted from their first opening of a container in real time: their loot is re-rolled at fixed intervals a set number of times, and afterwards the container decays for that player only. Other players keep their loot.

- On by default: every 72 real hours, 3 re-rolls, then decay at 288 hours after the first opening. Server downtime counts. The schedule is worked out when the player opens the container, so changed settings apply to cycles that already run. Nothing runs in the background except a cheap visual update of the containers near players.
- Lootr's own refresh and decay (sections `refresh` and `decay`) keep precedence: a container they handle is not subject to the personal cycle. Custom inventories, fake players, container types that cannot refresh, listed loot tables and dimensions, and containers in structures of the tag `lootr:personal_cycle_exempt` are not subject to it either.
- To switch it off, set `personal_cycle.enabled` to false.
- Settings in the `personal_cycle` section of `config/lootr.json`: `enabled` (true), `refresh_interval_hours` (72; at least 0.0167, one minute), `refresh_count` (3; 0 never re-rolls), `decay_after_last_refresh` (true; the container decays for the player one interval after the last re-roll), `excluded_loot_tables` (loot table ids or `namespace:*`), `excluded_dimensions` (dimension ids or `namespace:*`) and `overrides` (entries `<loot table id or namespace:*>=<hours>,<count>,<true|false>`; an exact id beats a namespace).
- Commands: `/lootr cycle info`, `reset` and `expire`, see [Commands](#commands).

### Integrated loot (replaces Loot Integrations)

Rules in data packs select loot tables, usually chests of structures; when such a table is rolled, one or two source tables from a pool are rolled as well and a few of their stacks are added to the result.

- The loot table files are not rewritten. The original loot is never removed or merged. Extra stacks are limited by the free slots of the container being filled and by `max_extra_stacks`.
- Source tables are rolled with the random of the roll itself, so a container with a fixed seed stays reproducible. Source rolls never trigger integrated loot themselves. Explorer maps are not created for source rolls (setting `skip_map_items`), so opening a chest does not start a structure search.
- A table gets at most one rule: the first match by priority, file id and entry order.
- Tables are extended only if they are of type chest, vault or untyped. Items in the item tag `lootr:integrated_loot/excluded` (command blocks, barriers, spawners and similar) and banned items are never added.
- 57 built-in data files ship in the jar: 6 pools of vanilla chest loot and 51 rule files for vanilla and for the structure mods named in them. A rule file is active only when all mods in its `required_mods` are loaded and, if it lists `required_namespaces`, a loot table of each of those namespaces exists. Mods that change dimensions or progression (Aether, Twilight Forest, Cataclysm, Eternal Starlight, Deeper and Darker, Astral Dimension, Bosses of Mass Destruction) have exclude-only rule files, so the loot tables they name get no extras. Packs can override or add rule files.

### Item bans

- If Item Obliterator is installed, every item it disables is removed from what a loot table itself rolls (chests, minecart chests, pots, archaeology, mob and block drops, integrated loot) and from per-player Lootr inventories. Stacks that another mod adds after the table's own roll has returned (for example loot modifiers of CraftTweaker or Porting Lib, such as the Aether's) do not pass through this filter; Lootr containers and stored per-player inventories are scrubbed separately in every case. If the hook on the loot roll cannot be applied because another mod changed the method, Lootr logs one warning at start-up and switches the all-loot filter off. The mod calls only the public check method of Item Obliterator through a method handle.
- Stacks that were generated before an item was banned are removed from stored per-player inventories when they are opened. This deletes the stack permanently (it is the same as Item Obliterator's own behaviour). Switch it off with `scrub_saved_inventories`.
- The list `extra_banned_items` bans more items without Item Obliterator: item ids (`namespace:item`) and item tags (`#namespace:tag`).

### Loot table verification

`/lootr verify` checks the loot tables of all data packs and the integrated loot data and writes a report, including the structures whose containers have a missing or empty loot table; see [Verification report](#verification-report). `/lootr verify structures` prints that part again.

### Fixes and optimisation of the Lootr core

See [CHANGELOG.md](CHANGELOG.md) for the list. In short: saved data is no longer rewritten after every restart, unopened containers no longer create data objects, the conversion queue costs less, several static flags became thread-safe, and a refresh while a container screen is open closes that screen with a message.

### Art

All textures, the trophy model, the mod icon and the logo are new pixel art, written by `art/generate.py` (standard library only). No artwork of the original Lootr is included. See [LICENSE_ASSETS.txt](LICENSE_ASSETS.txt).

## Commands

Everything starts with `/lootr` and needs permission level 2 (operator). "Stand on" means: the container is at your feet or in the block under you.

| Command | What it does |
|---|---|
| `/lootr` | Shows the usage line. |
| `/lootr <chest\|trapped_chest\|barrel\|shulker_box\|pot\|sand\|gravel> [<loot_table>]` | Places a Lootr container of that type at your position with the loot table (a random one if left out). |
| `/lootr cart [<loot_table>]`, `/lootr item_frame [<loot_table>]` | Summons a Lootr minecart with chest or item frame. |
| `/lootr integrated <loot_table>` | Says whether integrated loot extends that table, which rule file, entry and pattern matched, the pool and its sources, and the rolls. |
| `/lootr integrated stats` | Counts of usable pools, active and inactive rule files, extended and excluded tables, and totals since server start (extensions, source rolls, stacks added, failures). |
| `/lootr verify` | Starts a loot table verification. One run at a time. |
| `/lootr verify rolls <count>` | The same, and rolls every structure and `chests/` loot table `count` times (0 to 100). |
| `/lootr verify structures` | Prints the structures with problem containers (loot table missing, empty) of the latest verification: the 15 with the most, each with its counts and a clickable structure id that runs `/lootr locate structure <id>`. Says to run `/lootr verify` first if there is no result yet. |
| `/lootr check [<pos>]` | Looks at the container you aim at (block within 8 blocks, Lootr minecart or item frame) or at the block at `pos`, also an unconverted vanilla container with a loot table. Shows its kind, position, dimension, info UUID, loot table and seed, the status of the table (OK, MISSING, EMPTY or NONE, with 16 sample rolls for OK), whether integrated loot extends the table, the structures around it, how many players opened it and whether you have an inventory in it. |
| `/lootr locate table <loot_table> [<radius>]` | Finds the containers (Lootr and unconverted vanilla ones) with that loot table within `radius` chunks (default 8, at most 32) around you: the 10 nearest, with a clickable position that teleports you, distance, kind and table status, then a total. Chunks that are not loaded are only read if they are already fully generated; none is generated. The work is spread over several ticks. |
| `/lootr locate structure <structure or #tag> [<radius>]` | Finds the nearest structure like the vanilla `/locate` (default and largest radius 100 chunks), then, if its chunks are already generated (at most 64 are scanned), lists every container inside its bounding box with kind, loot table and status, and counts the ones with a missing or an empty table. |
| `/lootr id` | Shows the UUID of the container you stand on. |
| `/lootr openers <x> <y> <z>` | Lists the players that have opened the container at that position. |
| `/lootr open_as <player>`, `/lootr open_as_uuid <uuid>` | Opens the stored inventory of that player in the container you stand on. |
| `/lootr clear <player>` | Deletes the stored inventories of a player (by name from the profile cache). |
| `/lootr cclear <entities>` | The same for the players the selector matches; with team loot, for their team. |
| `/lootr refresh`, `/lootr decay` | Sets the container you stand on to refresh or decay, with the configured delay. |
| `/lootr cycle info [<player>]` | Shows the personal cycle of a player (default: yourself) in the container you look at or stand on: when it started, re-rolls used of the configured count, whether it has decayed, and when the next re-roll or the decay is due; or why the container is not subject to the cycle. |
| `/lootr cycle reset <player>` | Removes the player's inventory and cycle in that container; the next opening rolls fresh loot and starts a new cycle. |
| `/lootr cycle expire <player>` | Moves the start of the player's cycle back by one interval, so that the next re-roll or the decay is due at the next opening. |
| `/lootr cull` | Deletes, in the background, saved data files of containers that have no stored inventory, no opener and were never opened. Prints how many. |
| `/lootr custom-chest <pos>` | Turns a container that holds items but has no loot table into a Lootr container with those items as the reference. |
| `/lootr custom-area <from> <to>` | The same for every container in the box. |
| `/lootr custom-map <dimension>` | The same for all generated chunks of a dimension, as a background job with progress messages. |
| `/lootr custom-chest-old`, `/lootr custom-area-old <from> <to>` | The older variants from Lootr, for the container you stand on and for a box. |
| `/lootr force_chunk`, `/lootr force_radius <radius>`, `/lootr force_all` | Queues the block entities of your chunk, of the chunks within the radius, or of the chunks the server has loaded, for conversion. |

### Start-up mode for the verification

`-Dlootr.verify=true` runs one verification once the server has started; `-Dlootr.verify=exit` also stops a dedicated server afterwards. `-Dlootr.verify.rolls=<n>` sets the sample rolls (0 to 100). Other values are ignored with a warning in the log.

## Configuration

`config/lootr.json` (Cloth Config / AutoConfig), also editable in the Mod Menu config screen. On a dedicated server, changes to the file need a restart. Item ban and integrated loot settings are applied again when the config is loaded or saved.

| Section | Contents |
|---|---|
| `debug` | `report_unresolved_tables` |
| `seed` | `randomize_seed` |
| `conversion` | `disable`, elytra and structure item frame conversion, `world_border`, `perform_piecewise_check`, `bypass_spawn_protection`, `skip_logging_no_loot_table_at_generation`, `tickDelay` (decay and refresh check interval, default 20 ticks) |
| `breaking` | `disable_break`, `enable_break`, `enable_fake_player_break`, `power_comparators`, `blast_resistant`, `blast_immune`, `trapped_custom`, `should_drop_player_loot`, `break_to_drop_loot`, `brushables_self_support`, `item_frames_self_support` |
| `lists` | dimension and mod id whitelists and blacklists, `loot_table_blacklist`, `loot_modid_blacklist`, `loot_table_forced_whitelist` |
| `decay` | `decay_value` (default 6000), `decay_all`, `decay_modids`, `decay_loot_tables`, `decay_dimensions`, `perform_tick_decay`, `start_tick_decay`, `replace_when_decayed` |
| `refresh` | `refresh_value` (default 24000), `refresh_all`, `refresh_modids`, `refresh_loot_tables`, `refresh_dimensions`, `perform_tick_refresh`, `start_tick_refresh` |
| `notifications` | `disable_message_styles` |
| `client` | `vanilla_textures`, particles (`unopened_particles`, `refresh_particles`, `decay_particles`), `display_toasts`, `notification_delay`, `disable_notifications` |
| `team` | `team_loot`, `pinned_team_resolver` |
| `item_bans` | `respect_item_obliterator` (true), `filter_all_loot` (true; false = only Lootr containers), `scrub_saved_inventories` (true), `extra_banned_items` (empty list) |
| `personal_cycle` | `enabled` (true), `refresh_interval_hours` (72), `refresh_count` (3), `decay_after_last_refresh` (true), `excluded_loot_tables`, `excluded_dimensions`, `overrides`; see [Personal cycle](#personal-cycle) |
| `integrated_loot` | `enabled` (true), `modded_item_weight` (2; 1 to 1000), `skip_existing_items` (true), `skip_map_items` (true), `max_extra_stacks` (4; 0 to 9), `debug_log` (false), `imported_loot_integrations_config` |

`modded_item_weight` matters when a source roll yields more candidate stacks than the rule asks for: candidates from other mods are drawn with this weight, vanilla items with weight 1.

## Integrated loot data

Pools and rules are JSON files in any data pack, also in your own:

- Pool: `data/<namespace>/lootr/integrated_loot/pool/<path>.json`, id `<namespace>:<path>`.
- Rule: `data/<namespace>/lootr/integrated_loot/rule/<path>.json`.

The data is read on every data reload (`/reload`). A file that cannot be parsed is skipped with a warning in the log and listed by `/lootr verify`; it never affects other files.

Pool `data/lootr/lootr/integrated_loot/pool/village.json` (shortened): a weighted list of source loot tables. `weight` is optional (default 1).

```json
{
  "sources": [
    { "table": "minecraft:chests/village/village_armorer", "weight": 1 },
    { "table": "minecraft:chests/village/village_butcher" }
  ]
}
```

Rule file (`data/example/lootr/integrated_loot/rule/my_dungeons.json`):

```json
{
  "required_mods": ["my_dungeon_mod"],
  "required_namespaces": [],
  "priority": 0,
  "entries": [
    { "tables": ["my_dungeon_mod:chests/boss_room"], "exclude": true },
    { "tables": ["my_dungeon_mod:chests/treasure"], "pool": "lootr:overworld_rare", "rolls": 2 },
    { "tables": ["my_dungeon_mod:chests/*"], "pool": "lootr:overworld_common", "rolls": { "min": 0, "max": 1 } }
  ]
}
```

Fields:

| Field | Meaning |
|---|---|
| `required_mods` | Optional list of mod ids. The file is active only if all of them are loaded. |
| `required_namespaces` | Optional list of namespaces. The file is active only if at least one loaded loot table exists in each (for mods without a Fabric mod id, and for plain data packs). |
| `priority` | Optional integer, default 0. Files with a higher priority are consulted first; ties by file id. |
| `entries` | Required list; the first entry that matches a table decides. |
| `tables` | Required, non-empty list of patterns `namespace:path`. `*` matches any characters except `/`, `**` matches anything including `/`. Without a wildcard it is an exact id. |
| `pool` | Pool id for an extending entry. |
| `exclude` | `true` makes the matching tables never extended; an entry has either `pool` or `"exclude": true`, not both. |
| `rolls` | Optional, default 1. A number, or `{ "min": a, "max": b }`; how many stacks the entry asks for. The settings cap it. |

Built-in pools: `lootr:overworld_common`, `lootr:overworld_rare`, `lootr:village`, `lootr:ocean`, `lootr:nether`, `lootr:end`. Test one table with `/lootr integrated <table>`.

## Verification report

`/lootr verify` runs in the background (files are read on other threads; sample rolls run on the server thread in slices of at most 8 ms per tick) and changes nothing in the game. It writes to `logs/lootr/` in the game directory:

- `verify-<yyyyMMdd-HHmmss>.md` (for reading: grouped by the mod or pack responsible, at most 25 findings per group) and `.json` (everything),
- `verify-latest.md` and `verify-latest.json` (copies of the newest run).

The report has a header (versions, duration, number of scanned files, item ban status, integrated loot status, sample rolls) and a summary table, then:

| Section | Finding class | Meaning |
|---|---|---|
| A | `load_failure` | Loot table files that did not load, with the game's error. Sub-kinds: unknown items (the ids are listed), invalid JSON, other. `skipped_by_condition` (info): files whose load conditions are not met. |
| B | `legacy_folder` | Files in `loot_tables/` (the folder before 1.21); the game ignores them. Says if the same id exists in `loot_table/`. |
| C | `missing_reference` | References to loot tables that do not exist: nested tables, `set_loot_table`, container loot components, structure templates (`.nbt`), worldgen files. |
| D | `banned_item` (info) | Loot table entries that name a banned item or a tag with banned members, and tables that reach one through nested loot table entries (`via` in the JSON report). Only when a ban source is active. |
| E | `cannot_produce` | Tables that cannot yield anything judged from their entries (no pools, only empty entries, only banned items, only references to such tables). Warning if a structure container uses the table. With rolls: `roll_error` (a roll threw an exception) and `always_empty_in_rolls` (no roll produced an item; warning if a structure container uses the table, info otherwise). |
| F | `no_integrated_loot` | For each loot table that a structure container uses: `extended`, `excluded`, `rule_problem` (a rule matches but has no effect), `not_applicable` (only pots or brushable blocks, no room for extra stacks), `no_rule`. A coverage table per origin. |
| G | `integrated_loot_data` | Pool and rule file problems: malformed files, inactive rule files (missing mod or namespace), patterns that match no table, dropped sources, pools without usable source, matches of tables of another type. |
| H | `structure_containers` | Structures with problem containers: per structure, every container of a structure template whose loot table is missing, cannot produce or was empty in every sample roll, with template, holder and table (the files list them all). Not a finding class of its own: the counts of the classes above are unchanged. Templates are attributed to jigsaw structures through the start pool, pool elements (single, legacy, list; features are ignored) and the jigsaw blocks of the templates; templates no jigsaw structure reaches (mansion, stronghold, structures of mods that place templates in code) are listed as unattributed, grouped by namespace and the first part of the template path. Containers in worldgen files (processor lists) are not part of it. |
| - | `internal_error` | Something the verification itself could not do; the rest is unaffected. |

Findings have the severity error, warning or info. Rolls use the world spawn as the position, no integrated loot and no explorer maps; tables whose type needs more than a position are not rolled and are counted in the report; a table whose roll takes very long is cut short and listed. If at least 20 tables were sampled and at least 90 % of them were empty in every roll, the report and the summary say so at the top: the tables are not at fault then, something empties the loot rolls (usually mods that change loot rolls and get in each other's way).

## Building and testing

Java 21 and the Gradle wrapper are enough; Gradle downloads the rest. Each loader folder is a standalone Gradle build with its own wrapper, so run the commands inside it (`fabric/`):

```
cd fabric
.\gradlew.bat build          # jar: fabric/build/libs/lootr-remastered-fabric-1.21.1-2.2.1.jar
.\gradlew.bat runGametest    # Fabric game tests on a headless server
```

(`./gradlew` on Linux and macOS.) The game tests are in `fabric/src/gametest`; they are not part of the release jar. `runGametest` exits with a non-zero code if a test fails; the JUnit report is written to `fabric/build/gametest/junit.xml`. `build` also checks that `common/` imports no loader API (`checkCommonIsLoaderNeutral`). `pwsh build-all.ps1` builds every loader folder in turn; `pwsh release.ps1` copies the built jars into `<loader>/release/`. To check a built jar for the licence points of `docs/licence-audit.md`: `python docs/tools/check_jar.py <jar>`.

Layout: one branch per Minecraft version (`mc/<version>`), one standalone Gradle build per loader.

- `gradle/shared.properties`: mod id, name, version, Minecraft version and library versions, read by every loader build.
- `common/`: loader-neutral sources and resources (no build file of its own); each loader build compiles it by path.
- `fabric/`: the Fabric build (`build.gradle`, `settings.gradle`, `gradle.properties`, wrapper) and the Fabric-only sources.
- `datapacks/`: the optional data packs that are copied into the jar under `datapacks/`.

The art and the logo are generated by `art/generate.py`.

## Known limitations

- Loot viewers such as EMI Loot and Advanced Loot Info read loot tables statically and do not show the extra stacks of integrated loot. They keep working and show the tables as they are in the data.
- The all-loot ban filter covers what a loot table itself rolls. Stacks that another mod adds after that roll (loot modifiers of CraftTweaker, Porting Lib and similar) are not filtered; Lootr containers and stored per-player inventories are scrubbed in every case.
- Only the top-level stack is checked against bans; the contents of bundles and shulker box items are not. A banned stack that a player put into their own Lootr inventory is deleted as well. An item frame whose reference item is banned still shows it.
- `neoforge:conditions` in loot tables are judged by the verification against Fabric mod ids, so mods that run through a compatibility layer count as absent there. The verification's list of one-slot containers is fixed. Bans that a loot function sets while rolling are not seen by the static checks (D, E). Ban sources are called from the verification's scan threads.
- With loot seed 0, vanilla uses the level's random for the roll, so extra stacks draw from it in that case; the slot layout of an extended container differs from vanilla for the same seed.
- Changes to `config/lootr.json` on a dedicated server need a restart.
- Open points found while fixing the core, not changed: a block entity sync request still creates one in-memory data object per asked container; `/lootr clear` skips saved data from before the opened flag existed; there is no message when a container is locked by another player.

## Credits

Lootr was written by Noobanidus; ZestyBlaze and embeddedt are listed as contributors. The idea of adding loot from other tables to structure containers is that of the mod Loot Integrations by someaddons; the implementation and all data here are our own. See [CREDITS.md](CREDITS.md).

## Licence

Code, data and art: MIT, Copyright (c) 2026 navrelis for the changes, and the original MIT notice of Lootr; see [LICENSE](LICENSE). The build puts `LICENSE` and `LICENSE_ASSETS.txt` at the root of the jar. For artwork and resource files see [LICENSE_ASSETS.txt](LICENSE_ASSETS.txt); the licence audit is in [docs/licence-audit.md](docs/licence-audit.md).
