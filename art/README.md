# Lootr Remastered art

Original artwork for Lootr Remastered; no pixels derived from Lootr or Minecraft textures.

This folder is the source of every image the mod ships and of the trophy block model. Nothing is
painted by hand in an editor: `generate.py` holds the palettes, the hand-authored pixel grids and
the drawing rules, and writes each file to its place: the game resources into a version branch, the
logo and the previews into this folder.

## Where the game resources are

The game code and its resources live on the version branches `mc/<minecraft version>`, not on `main`.
The scripts in this folder (`generate.py`, `verify.py`, and through them `showcase*.py`) find them in
the checkout of one branch: the folder named by the environment variable `LOOTR_VERSION_DIR`, as an
absolute path or relative to the repository root. Without the variable it is `versions\1.21.1`, the
worktree of `mc/1.21.1` (see `scripts\setup-worktrees.ps1`). If the folder does not exist, or does not
contain `common\src\main\resources`, the script stops with an error message that says so.

```
$env:LOOTR_VERSION_DIR = 'versions\1.21.1'     # PowerShell; this is the default
```

The paths in the table below are paths inside that checkout, except `art/…`, which is in this repository.
After a run, the changed files of the version branch are committed on that branch, the changed files
of `art/` on `main`.

## Running

```
python art/generate.py                 # textures, trophy model, logo, icons, previews
python art/generate.py --check         # same, and print a sha256 per written file
python art/generate.py --no-previews   # skip art/preview/
python art/verify.py [git-revision]    # dimensions, validity, "is it what the generator makes",
                                       # and pixel difference against a revision (default HEAD of the
                                       # version branch)
```

Python 3 standard library only. Running the generator again reproduces byte-identical files.

| Written to | What |
| --- | --- |
| `common/src/main/resources/assets/lootr/textures/…` | barrels, planks, brushable blocks, chests, shulker boxes, pot, toasts, `block/trophy.png` |
| `common/src/main/resources/assets/lootr/models/block/trophy.json` | the trophy model (its UVs come from the same table as its texture) |
| `fabric/src/main/resources/icon.png` | mod icon, 256 × 256 (logo × 8) |
| `datapacks/*/pack.png` | 64 × 64 (logo × 2) |
| `art/logo/logo_native.png`, `art/logo/logo_512.png` | the 32 × 32 logo and its × 16 upscale for CurseForge |
| `art/preview/` | × 8 contact sheets and software-rendered 3D previews; not shipped |

The 3D previews are drawn by a small renderer inside the script that unwraps boxes exactly like
`ModelPart.Cube` and poses them like the mod's renderers, so a face that is upside down or on the
wrong side of a box shows up here before it shows up in game.

## Concept: sealed and spent

A loot container you have not opened yet is **sealed**: dark walnut timber bound in warm brass,
closed with a glowing teal gem. Once you have looted it, it is **spent**: the gem is gone and
leaves an empty dark socket, the brass has turned to cold dark iron and the timber has weathered
to a greyer, lighter brown.

The two states differ in value and shape, not only in hue: sealed is dark wood with bright metal
and a bright gem; spent is lighter wood with dark metal and a black hole where the gem was. The
`GREY` column of the 3D previews is the check for that.

The same motif, the **seal** (a gem in a metal setting, or its empty socket), is on every container:

- **Chest**: two straps over the lid, a lock that is the gem itself (the spent lock is only the
  upper half of the hasp; the lower half is cut out), a seal on each flank and on the lid.
  Trapped chests carry red settings: a ruby rim round the lock plate and round each seal, dull
  rust-red once spent.
- **Barrel**: two hoops, a seal on every side and a large one on the lid; looking into a sealed
  barrel shows teal glints.
- **Shulker box**: a dark plum shell with a brass belt where lid and base meet (faded mauve with an
  iron belt once spent).
- **Decorated pot**: a fired-clay neck with a metal collar, capped with brass and the gem. A looted
  pot is drawn by the mod as a broken pot, so its sheet is bare clay and an open mouth.
- **Item frame**: `gold_planks` / `silver_planks` are timber panels with a metal inlay exactly
  where the frame model takes its visible rim from: brass rim = not looted, iron rim = looted.
- **Toasts**: a brass-bound parchment slip with a timber bay for the icon; an hourglass whose teal
  sand turns to ash (decay) and two arrows circling the seal (refresh).
- **Trophy**: a brass goblet on a timber plinth holding an oversized gem.
- **Logo**: the sealed chest, no text.

## Palette

Ramps run dark to light; shadows lean cool, highlights warm. Light comes from the top left.

| Ramp | Used for | Colours |
| --- | --- | --- |
| `TIMBER` | sealed wood | `#1b1319 #2a1c20 #3c2925 #523728 #6c4a2f #88623b` |
| `ASH` | spent wood | `#231c20 #342a2b #493c39 #625146 #7d6957 #99836b` |
| `BRASS` | sealed metal | `#46291a #754b1d #ab7d25 #d8ad38 #f3d975 #fff5c2` |
| `IRON` | spent metal | `#121117 #1e1d26 #2d2c37 #403f4a #5a5962 #7d7b82` |
| `GEM` | the seal gem | `#0a3340 #0e6f76 #1cbfab #6df1d4 #e3fff4` |
| `VOID` | empty socket | `#0c0a10 #1c1b22 #3a3a42` |
| `RUBY` / `RUST` | trapped, sealed / spent | `#440d16 #8a1a1f #cf3a2a #ff8a5c` / `#2e1519 #512426 #733831 #8f5346` |
| `SHELL` / `HUSK` | shulker, sealed / spent | `#181322 #281f36 #3a2d4b #513e62 #6f567c #97799a` / `#2a2430 #3f3646 #594c60 #75667b #958699 #b4a7b4` |
| `CLAY` | pot | `#4a221f #6a3228 #8a4733 #a65d3e #c27a4f #dc9a68` |
| `PAPER` | toast | `#6f5334 #b3925f #d9c08c #efdfb4 #faf2d6` |
| `SAND`, `GRAVEL` | brushed-out blocks | `#b89f70 #cdb987 #dacca0 #e5dbb4 #f0eaca`, `#4a474d #656165 #827e7e #9c9692 #b5aea7` |

## Sheet layouts

Where a face sits on a sheet is dictated by the game's models and is noted next to each builder
in `generate.py` (chest: `ChestRenderer.createSingleBodyLayer`; shulker: `ShulkerModel.createBodyLayer`;
pot: `DecoratedPotRenderer.createBaseLayer` and the mod's `LootrDecoratedPotRenderer.createBodyLayer`;
box unwrap: `ModelPart.Cube`; item frame: `minecraft:block/template_item_frame`).
