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
| T06 | Verification: `/lootr verify` (async, report file + summary): load failures, legacy folder, missing table references (nested, structure templates, processor lists), banned-item entries, tables that cannot produce anything, structure containers without integrated loot; optional sample rolls; start-up switch for unattended runs | Opus | T04 | Game tests with fixture data for each finding class; report lists responsible mod per finding | done (pack-scale run in T13) |
| T07 | Bug fixes, core: saved data dirtying on every access, legacy ticking files rewritten, refresh/decay shortcut checks, conversion queue cost and cleanup on server stop, static flags without try/finally, refresh while GUI open (upstream #912); each confirmed first, with test or measurement | Opus | T02 | Each item either fixed with proof or documented as not a bug | done |
| T08 | Bug fixes, small: duplicate command registration, command table cache after reload, sand sound, ticking files in file walk, modulo guard, access widener gap for `GameProfileCache$GameProfileInfo`, dead options, per-call config lookup | Sonnet | T07 | Each item fixed or documented; build and tests green | done (T08a and T08b) |
| T09 | Original pixel art: all block, entity and GUI textures, trophy model, icon and logo (native canvas + 512 px nearest neighbour), authored by a script kept in the repository | Opus | T01 | No upstream texture, model art or logo left in repository head; native files kept; in-game screenshots | in review (art accepted from previews; in-game check in T13) |
| T10 | Remove "old textures" pack and its options and renderer paths | Sonnet | T09 | No reference to old textures; build and tests green | done (client look checked in T13) |
| T11 | Language files: keep English, new German, remove the other translations (default pending owner) | Sonnet | T04, T06 | All keys used in code exist in both files | done (in-game look on the manual checklist) |
| T12 | Performance measurement: old 11 jars against new jar on a copy of the server pack (loot roll benchmark, tick time, start-up) | Opus | T05, T07 | Figures in report.md with method | done for the server side (figures in the server-run report); stack-count comparison needs a run without either jar set |
| T13 | Pack run on a throwaway server copy and in the client: swap jars under the game lock, run `/lootr verify`, fix mod-side findings, fix pack data where right, list the rest per mod | Opus + lead | T05, T06, T08 | Verify report archived; pack launches; findings triaged | in progress: first server run done, blocker found (T16), second run and client run owed |
| T14 | Release material: README, CurseForge description and summary, credits, changelog, licence audit document | Sonnet | T09-T13 | Audit passes: no ARR content in head or jar, MIT notice kept | done (two owner decisions remain: history, `.knowledge` in the public tree) |
| T15 | Final acceptance: full build and tests, client proof with screenshots, manual checklist, report.md, shared log line, push | lead | all | Report delivered | open |

Parallel: T09 (art, resources only) can run beside T03-T08. T05 runs beside T06. T07 runs beside T04 only if file ownership is disjoint (T07 owns saved data, ticking and queue classes; T04 owns new packages plus loot mixins).

Manual test checklist is collected in report.md during T13 and T15.


Session handed over on 2026-10-08: see handoff.md (state, next steps in order, T06 specification, pack run notes).

Added 2026-10-09: T16 (Opus, in progress): all loot rolls empty in the pack with the default config (conflict of the all-loot ban filter with CraftTweaker); fix the hook, reproduce in a game test, verification self-diagnosis, dispenser exclusions. Then T13 second server run (loot flows with default config, ban check on a path Item Obliterator does not cover, start-up verify mode, run without either jar set) and the client run.

Session handed over a second time on 2026-10-09 01:15: see handoff.md. Open: T13 second server run (interrupted) and client run, documentation pass for T16, pack change decision, T15 final acceptance with report.md.

## Third lead session (2026-10-12): remaining tasks
| # | Task | Model | Size | Depends on | Definition of done | Status |
|---|------|-------|------|------------|--------------------|--------|
| T13a | Second server run on the throwaway copy, steps a to h of handoff Part 3 | Opus | long | - | Every step answered with numbers or a quoted reason; report files in `results\round2\`; copy left stopped in the new-jar default state | done |
| T17 | Documentation pass 1: CHANGELOG entry for T16, all-loot filter wording in README and CurseForge description, `art\README.md` | Sonnet | short | - | Texts match the code and the known limitations; no other file touched | done |
| T13b | Client run preparation: pack copy with the new jar, launch script from the instance audit, scene world with data pack, screenshot tool; no game start | Sonnet | long | - | Folder `client-run\` complete; launch command printed; nothing in the real pack changed | done |
| T13c | Client run: start the copy, collect log evidence and screenshots | Opus | long | T13a (lock), T13b | Game reaches the world with 0 Lootr errors; screenshot list; rest on the manual checklist | open |
| T18 | Fixes from T13a and T13c, if any | by finding | - | T13a, T13c | Each finding fixed with test or listed | done (Sonnet) |
| T19 | Documentation pass 2: measured figures with method in CHANGELOG, final jar check | Sonnet | short | T13a, T13c, T18 | Figures equal the run results | open |
| T15 | Final acceptance, clean-up, `report.md`, shared log line, push | lead | - | all | Report delivered | open |

Model usage tally, this session (long = 2, short = 1): assigned so far Opus 4 (T13a, T20), Sonnet 5 (T17, T13b, T18); planned Opus 6 with T13c, Sonnet 6 plus T21 by proposal.
| T20 | Performance audit (read-only in the repository): profile the roll path on the server copy with spark and with config variants, review tick, chunk-load, sync and client paths; ranked list of concrete changes with expected gain and risk | Opus | long | T13a | Each proposal has a measurement or a code-path argument, an exact change and a way to prove it | in progress |
| T21 | Implement the accepted optimisations, with before and after figures | by proposal | - | T18, T20 | Behaviour unchanged (all tests green), gain measured on the server copy | open |
