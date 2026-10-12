# Requirements: Lootr Remastered (session S7)

Lead: Opus 5.5. Subagents implement; the lead plans, reviews, builds, commits. Status board for this workstream.

## Owner task
One new mod "Lootr Remastered" that combines Lootr and Loot Integrations (with its add-on packs), fixes bugs, is optimised, works with Item Obliterator, and proves that all loot tables of the Nytheria pack work.

## Requirements
- R1 One Fabric 1.21.1 jar that does what Lootr does (per-player loot containers) and what Loot Integrations plus add-ons do (modded loot added to structure loot tables). Replaces 11 jars in the pack.
- R2 Bugs fixed and both parts optimised; report what was fixed and what was measured.
- R3 Item Obliterator 3.1.1: a removed item never appears in a Lootr container or in integrated loot, also not in containers generated before the item was removed; no errors in loot generation because of it.
- R4 Repeatable loot table check for the whole pack (command or automated test in the running pack): tables that fail to load, references to missing tables, entries for missing or obliterated items, tables that can never produce anything, structures whose containers get no integrated loot although they should. Run it, fix what the mod can fix, fix pack data where right, list the rest with the responsible mod.
- R5 Existing worlds keep working: mod id `lootr`, saved data under `data\lootr` still read, existing Loot Integrations config taken over where sensible.
- R6 Add-on content built in; each part active only when its target mod is installed.
- R7 Public CurseForge release: description, short summary, credits, true pixel-art logo (small native canvas, nearest-neighbour to 512 px PNG, native file kept), licence audit passed before anything goes public.
- R8 Built jar proven in the pack in game.

## Constraints
- Minecraft 1.21.1, Fabric (pack: Fabric Loader 0.19.5, 413 jars), Java 21, Windows 11 / PowerShell.
- Repository `navrelis/LootrRemastered` is private until the licence audit passes; making it public is the owner's step.
- Base branch `mdg-1.21.1` (upstream Lootr 1.11.38.128 at b62e382a). Working branch: `claude/intelligent-gauss-f0435e` (worktree).
- No new libraries beyond what the project and the pack already ship.
- No decompiled or copied third-party code or data files in the repository, ever. Study material lives in the session scratch folder outside the repository.
- Game lock `F:\Coding\.knowledge\game.lock` before any game launch; throwaway worlds only; sound muted.
- Pack: only S2 commits in the pack repository. Jar swaps and loot-related pack data changes only while holding the game lock, with backups (`mods_backup/`, `.dev/backups/`); every pack change is listed in the report.
- No CurseForge upload without the owner's OK.
- No Graphify.

## Jars to replace in the pack
`lootr-fabric-1.21.1-1.11.38.127.jar`, `lootintegrations-fabric-1.21-4.7.jar`, `lootintegration_wda-1.8.jar`, `lootintegrations_dnt-2.7.jar`, `lootintegrations_dungeonsenhanced-1.8.jar`, `lootintegrations_lootintegrations_bygonepillage-1.4.jar`, `lootintegrations_medievalbuildings-1.5.jar`, `lootintegrations_moog-2.2.jar`, `lootintegrations_structory-1.5.jar`, `lootintegrations_vanilla-1.8.jar`, `lootintegrations_yungs-1.6.jar`.

## Must keep working
Item Obliterator 3.1.1, Advanced Loot Info 2.3.0, EMI Loot 0.7.9, dragonloot 1.1.9.

## Owner answers
- Use Loot Integrations if its licence allows; otherwise decompile all its jars from the pack, understand them, write a better version in own code.
- Public CurseForge release.
- Keep mod id `lootr`; add-on packs built in.
- Really check all loot tables of Nytheria.
- The PC may be taken over for game runs.

- 2026-10-08, asked during the second lead session: when the mod is finished, tell the owner (1) whether the original Lootr and all Loot Integrations jars can be removed from the pack, with the exact list, and (2) exactly what to upload to CurseForge: description, summary and the pixel-art logo (file paths and ready-to-paste text). Both go into `report.md` and the final message.

## Licence facts (checked 2026-10-08)
- Lootr code: MIT (`LICENSE`). Lootr assets: all rights reserved (`LICENSE_ASSETS.txt`).
- Loot Integrations 4.7 and all nine add-ons: `"license": "ARR"` in each jar's `fabric.mod.json`; CurseForge page states "All Rights Reserved"; GitHub repository has no licence; not on Modrinth. Reuse is not allowed.
- Item Obliterator 3.1.1: AGNYA License rev 1.4 (all rights reserved unless stated; using its source in another project needs the author's permission). Interoperate only, no code reuse.

## Open owner decisions
- Lootr assets: own original textures and models (default, planned) or the Lootr author's written permission.

## Owner answers, third lead session (2026-10-12)
- Game runs: both allowed now (headless server copy and a client window on a copy of the pack, sound muted).
- Pack swap: the owner does it. The jar is proven on copies only; the real pack is not touched. Report gives the exact list and one command.
- Graphify: skipped (earlier rule stays; no graph exists).
- Git: push to `origin/claude/intelligent-gauss-f0435e`; `mdg-1.21.1` untouched; the four leftover local branches and two worktrees are deleted in the final clean-up.
- 2026-10-12, owner during the session: "if you can optimise this mod somehow because it seems very unoptimised then also do that". Taken as R9: find the mod's real costs by measurement (loot roll, tick, chunk load, client) and remove what can be removed without changing behaviour; state before and after figures.
