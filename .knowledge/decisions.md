# Decisions: Lootr Remastered

## Process and licences
- Working branch is the worktree branch `claude/intelligent-gauss-f0435e`: it sits on `mdg-1.21.1` at b62e382a, identical to upstream and origin on 2026-10-08.
- Loot Integrations is rewritten clean-room, not reused: jar metadata and the CurseForge page say All Rights Reserved, the repository has no licence.
- Clean-room procedure: one study subagent decompiled the jars in the session scratch folder outside the repository and wrote a prose behaviour report without source excerpts; implementers receive only a specification written by the lead; no decompiled file, no add-on data file and no study report is ever copied into the repository; implementers of the built-in rules see only loot table ids from the installed structure mods, never the add-ons' target lists.
- Item Obliterator is honoured through interoperability only (one method-handle call to its public check, no code from it): the AGNYA licence forbids reuse of its source without permission.
- Skill workflow "brainstorming" was not used: the owner's process (ask once, then work alone) takes precedence.
- Tests are Fabric game tests, no JUnit: the game test API is part of Fabric API which the project already uses; JUnit would be a new library.

## Build and scope
- NeoForge module removed from repository and build: the deliverable is the Fabric jar, nobody would test a second loader, and every change to shared code would need an untested NeoForge counterpart. Consequence: no data generators; the former generated resources are maintained by hand.
- Version 2.0.0, display name "Lootr Remastered", jar `lootr-remastered-fabric-1.21.1-2.0.0.jar`, mod id `lootr` and Java packages `noobanidus.mods.lootr.*` unchanged: five pack mods link against Lootr classes.
- All upstream publishing configuration removed (CurseForge and Modrinth project ids of the original Lootr): a release task must never be able to publish to the original author's projects.
- Cloth Config stays a required dependency: the pack ships it and 15 other mods need it.
- `cupboard` and `necronomicon` stay in the pack: other mods need them.

## Integrated loot (replaces Loot Integrations)
- Results are extended after a roll, loot tables are not rewritten: same reach as the original (containers, vaults), no unknown entry types for loot viewers to choke on.
- Own data format: pools (weighted lists of source loot tables) and rules (target tables by id or glob pattern, pool, number of extra stacks, `required_mods`). Rules are resolved against the loaded tables once per reload, so a roll costs one field read when no rule applies.
- One table gets at most one rule (highest priority, then order): predictable, unlike the original's stacking of several modifier files.
- One or two source rolls per extended roll instead of the original's 14 to 34; randomness comes from the roll's own seeded random, so a seeded container stays reproducible and the world random is not touched.
- Original loot is never removed or merged; extra stacks are limited to the free slots of the container being filled.
- Source rolls never trigger integration themselves (per-thread flag): no recursion, no shared mutable state, safe with parallel chunk generation (the pack runs C2ME).
- Explorer-map functions are skipped inside source rolls: avoids structure searches during chest opening.
- `required_mods` on each rule file makes built-in content active only when its target mod is loaded (R6).
- Built-in rules cover the mods the nine add-ons targeted plus the pack's other overworld, nether and end structure mods; dimension and boss progression mods (Aether, Twilight Forest, Cataclysm, Eternal Starlight, Deeper and Darker, Astral Dimension) are excluded on purpose and listed as such by the verification report. Owner may change this.
- Existing `config/lootintegrations.json` is read once and its values taken over (`moddedItemWeight` n becomes multiplier n+1, `skipExistingItems`, `skipMapItems`, `debugOutput`); the old file is left in place because pack files belong to S2.

## Item bans (Item Obliterator)
- Banned items are removed from every loot table roll in the game, not only from Lootr containers (switchable): one hook covers chests, minecarts, pots, archaeology, integrated loot and mob drops.
- Saved per-player inventories are scrubbed on every open: the ban list can change on `/reload`, only a check at open time covers inventories generated earlier.
- Scrubbing deletes the stack permanently (default; Item Obliterator itself deletes). Owner may change this.

## Assets and release
- Own original art for every shipped texture, model and logo (default); the "old textures" resource pack and its options are removed instead of being redrawn.
- English language file kept (its strings come from the MIT-licensed generator code), a new German file is written, the other 30 community translations are removed until the Lootr author permits their use: they sit in the assets folder that upstream declares All Rights Reserved. Owner may change this.
