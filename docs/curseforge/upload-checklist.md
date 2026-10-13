# CurseForge upload checklist: Lootr Remastered 2.0.0

Nothing here has been uploaded. Field names follow the CurseForge project and file forms as I know them; check each one
against the form you see, they change from time to time. Items marked (unverified) are suggestions I could not check.

## Before anything goes public

| Done | Item | Where |
|---|---|---|
| [ ] | The licence audit has passed: every row of the table in `docs/licence-audit.md` is "pass" or has an owner decision. Open points at the time of writing: history option (5) and `.knowledge/` in the public tree (3). The check of the built jar (6) is done: `LICENSE` and `LICENSE_ASSETS.txt` are at the jar root of the checked build. The 32 block state and model files are rewritten; 6 of them are necessarily identical to upstream (1b), which `check_jar.py` reports as `EXPECTED`. | `docs/licence-audit.md` |
| [ ] | The built jar passes `python docs\tools\check_jar.py fabric\build\libs\lootr-remastered-fabric-1.21.1-2.0.0.jar b62e382a` (no FAIL, no unexplained CHECK). The final jar was checked this way with the result "no FAIL"; run the check again on the exact file that gets uploaded. | |
| [ ] | The repository is public (the owner's step), because the description and `fabric.mod.json` link to `https://github.com/navrelis/LootrRemastered`. If it stays private, remove the last line of the description and the three contact links from `fabric/src/main/resources/fabric.mod.json` first. | |
| [ ] | The in-pack results are in: the figures are in `CHANGELOG.md` (section "Measured on a server") and in the short "Cost" paragraph at the end of "Why one jar" in `docs/curseforge/description.md`. They describe one pack (about 314 server-side jars) on one shared PC and are a rough guide, not a promise; keep that wording if the text is edited. | |
| [ ] | Do not publish with the project ids of the original Lootr (`361276`, `615106` on CurseForge, `EltpO5cN` on Modrinth). They were removed from the build; nothing in this repository can publish to them. | |

## The file

| Field | Value |
|---|---|
| File to upload | `fabric\build\libs\lootr-remastered-fabric-1.21.1-2.0.0.jar` (not the `-sources` jar and not a `-dev` jar if one is in the folder) |
| SHA-256 of the checked build | `61664C8299A6B86EED4A5B3198B93E5E87B2CA58403B4D9A4A8F4DDA37DE6FA0` (re-check after any rebuild) |
| Display name | `Lootr Remastered 2.0.0` |
| Version | 2.0.0 (the file name carries 1.21.1-2.0.0) |
| Release type | owner decides: Release, or Beta for the first public build |
| Game version | 1.21.1 only (`fabric.mod.json` requires exactly Minecraft 1.21.1) |
| Mod loader | Fabric (the jar has no NeoForge or Forge metadata) |
| Java | 21 |
| Environment | Client and Server: needed on both sides (it adds blocks, textures and a config) |
| Changelog | paste the section `2.0.0` of `CHANGELOG.md` |

### Relations

| Type | Project |
|---|---|
| Required dependency | Fabric API |
| Required dependency | Cloth Config API |
| Optional dependency | Item Obliterator (item bans) |
| Optional dependency | Mod Menu, Jade (the jar has integrations; both are optional) |
| Incompatible | Lootr (the Fabric project), because this mod replaces it and uses the same mod id `lootr` |
| Incompatible | Loot Integrations and each of its add-on projects, because this mod replaces them |

`fabric.mod.json` now declares `"breaks": { "lootintegrations": "*" }`, so Fabric Loader refuses to start with Loot Integrations; the add-ons depend on that mod id, so one
entry covers them (the id is the lead's information; the audit did not look it up). It does not cover the original Lootr, which has the same mod id `lootr`
as this mod. The CurseForge relations above are still to be set by hand.

## The project page

| Field | Value |
|---|---|
| Name | Lootr Remastered (check that the name is allowed next to the original Lootr project) |
| Slug | `lootr-remastered` (if free) |
| Summary | `docs/curseforge/summary.txt` (232 characters; the limit is 250) |
| Description | `docs/curseforge/description.md`. It is Markdown. If the editor does not render Markdown, paste it into the rich text editor in the HTML view after converting it, or format the headings and lists by hand. It contains 22 image markers (`>>> PASTE IMAGE: ... <<<`); see `docs/curseforge/README.md` for the upload steps and open `docs/curseforge/preview.html` to see the page with the images. |
| Logo | `art\logo\logo_512.png` (512 x 512, nearest-neighbour upscale of the 32 x 32 `art\logo\logo_native.png`; check the size limits of the form) |
| Licence | MIT License |
| Categories | suggestion (unverified, pick from the current list): Adventure and RPG, Server Utility, Utility and QoL, Miscellaneous. The original Lootr's categories were not looked up. |
| Source link | `https://github.com/navrelis/LootrRemastered` |
| Issues link | `https://github.com/navrelis/LootrRemastered/issues` |
| Wiki, donation links | none |
| Showcase images | 22 PNGs in `docs/curseforge/img/` (banner, dungeon scene, one image per container, block grid, texture sheet, notices, six feature cards), built by `art/showcase.py` and `art/showcase_cards.py`. They are software renderings of the shipped art on vanilla ground textures, not game screenshots. Check with `python art\showcase_preview.py --check`. |
| Screenshots | game screenshots of the final jar exist outside the repository: nine 1280 x 720 captures of a test scene in the pack (chests, trapped chest, shulker box, decorated pot, barrels in six facings, item frames, chest minecart, trophies). They are test captures: they show the HUD of other mods in the pack (a minimap, Jade tooltips) and a debug action bar label, so for the project page the owner may prefer to take clean ones. `art/preview/` holds software renderings, not game screenshots. |
| Third-party distribution (for example in modpacks) | owner decides; the MIT licence allows it |

## What the owner has to decide or do by hand

1. Publish with history or from a fresh history (`docs/licence-audit.md`, section 5).
2. The six block state and model files that are necessarily identical to upstream (`docs/licence-audit.md`, 1b): accept the position in `LICENSE_ASSETS.txt` (a one-line statement
   with no other way to write it), or ask the original author. The other 26 files were rewritten and need no decision.
3. Whether `.knowledge/` stays in the public repository.
4. Whether to contact the author of Lootr before release (courtesy; the MIT licence does not require it). The translations of Lootr's community are not
   included; asking for permission to reuse them is the only way to bring them back.
5. After the build: run `python docs\tools\check_jar.py <jar> b62e382a` and confirm that `LICENSE` and `LICENSE_ASSETS.txt` are at the jar root (the build file puts them there; the checked build had both), then
   create the CurseForge project, fill in the form from this list, upload the file, and wait for the project and file approval.
6. After the project page exists: add its address to `fabric.mod.json` (`contact.homepage`) in the next build, and to the README and `CREDITS.md` if wanted.
7. Release type and the final wording of the page.
8. Give the old pack's `mods/` list to the pack maintainer: remove `lootr-fabric-1.21.1-1.11.38.127.jar`, `lootintegrations-fabric-1.21-4.7.jar` and the nine add-on jars, add the new jar; keep `cupboard`, `necronomicon` and Cloth Config (other mods need them).
