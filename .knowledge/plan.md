# Plan: Lootr Remastered

Status values: open / in progress / in review / done. Model = planned subagent model. Each task is committed separately after review.

| # | Task | Model | Depends on | Definition of done | Status |
|---|------|-------|------------|--------------------|--------|
| T00 | Understand: codebase map, Loot Integrations study, Item Obliterator study, pack inventory, baseline build, licences | Sonnet + Opus | - | Reports in scratch folder, facts in requirements.md | done |
| T01 | Fabric-only build: drop NeoForge, version 2.0.0, new metadata, no upstream publishing, upstream root art and CI files removed, generated resources moved to main resources | Sonnet | T00 | `:fabric:build` green, jar `lootr-remastered-fabric-1.21.1-2.0.0.jar`, no upstream project ids, no Java change | done |
| T02 | Game test infrastructure on Fabric (separate source set, `runGametest`, one smoke test: place loot chest, it converts, two players get different inventories) | Sonnet | T01 | `:fabric:runGametest` green, test classes not in the release jar | done |
| T03 | Item bans: ban service with Item Obliterator source (method handle), own extra list, global loot roll filter, scrub of saved inventories on open, config section, game tests | Opus | T02 | Banned item never in roll results or opened inventories (tests with a test ban source and a pre-filled saved inventory); no error when Item Obliterator is absent | done |
| T04 | Integrated loot engine: pool and rule data loading, resolution per reload, roll hook with capacity limit, config section, Loot Integrations config takeover, `/lootr integrated` inspection command, game tests | Opus | T03 | Tests: rule adds stacks, respects free slots, never removes original loot, no recursion, banned items never added, `required_mods` gating, bad file does not break reload | done |
| T05 | Built-in pools and rules for vanilla and the pack's structure mods (data only), written from installed loot table ids | Sonnet | T04 | Every rule file loads without warnings in the pack; coverage figures per mod | done (in-pack check of all rule files in T13) |
| T06 | Verification: `/lootr verify` (async, report file + summary): load failures, legacy folder, missing table references (nested, structure templates, processor lists), banned-item entries, tables that cannot produce anything, structure containers without integrated loot; optional sample rolls; start-up switch for unattended runs | Opus | T04 | Game tests with fixture data for each finding class; report lists responsible mod per finding | in progress (delegated again, worktree `s7-t06-verify`) |
| T07 | Bug fixes, core: saved data dirtying on every access, legacy ticking files rewritten, refresh/decay shortcut checks, conversion queue cost and cleanup on server stop, static flags without try/finally, refresh while GUI open (upstream #912); each confirmed first, with test or measurement | Opus | T02 | Each item either fixed with proof or documented as not a bug | done |
| T08 | Bug fixes, small: duplicate command registration, command table cache after reload, sand sound, ticking files in file walk, modulo guard, access widener gap for `GameProfileCache$GameProfileInfo`, dead options, per-call config lookup | Sonnet | T07 | Each item fixed or documented; build and tests green | T08a done; T08b (core defects from the T07 review, Opus) open |
| T09 | Original pixel art: all block, entity and GUI textures, trophy model, icon and logo (native canvas + 512 px nearest neighbour), authored by a script kept in the repository | Opus | T01 | No upstream texture, model art or logo left in repository head; native files kept; in-game screenshots | in review (art accepted from previews; in-game check in T13) |
| T10 | Remove "old textures" pack and its options and renderer paths | Sonnet | T09 | No reference to old textures; build and tests green | done (client look checked in T13) |
| T11 | Language files: keep English, new German, remove the other translations (default pending owner) | Sonnet | T04, T06 | All keys used in code exist in both files | open |
| T12 | Performance measurement: old 11 jars against new jar on a copy of the server pack (loot roll benchmark, tick time, start-up) | Opus | T05, T07 | Figures in report.md with method | open |
| T13 | Pack run on a throwaway server copy and in the client: swap jars under the game lock, run `/lootr verify`, fix mod-side findings, fix pack data where right, list the rest per mod | Opus + lead | T05, T06, T08 | Verify report archived; pack launches; findings triaged | open |
| T14 | Release material: README, CurseForge description and summary, credits, changelog, licence audit document | Sonnet | T09-T13 | Audit passes: no ARR content in head or jar, MIT notice kept | open |
| T15 | Final acceptance: full build and tests, client proof with screenshots, manual checklist, report.md, shared log line, push | lead | all | Report delivered | open |

Parallel: T09 (art, resources only) can run beside T03-T08. T05 runs beside T06. T07 runs beside T04 only if file ownership is disjoint (T07 owns saved data, ticking and queue classes; T04 owns new packages plus loot mixins).

Manual test checklist is collected in report.md during T13 and T15.


Session handed over on 2026-10-08: see handoff.md (state, next steps in order, T06 specification, pack run notes).
