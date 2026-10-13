# CurseForge page material for Lootr Remastered

| File | What |
|---|---|
| `description.md` | The project description (Markdown), with one paste marker line for each image |
| `summary.txt` | The short summary for the project form |
| `upload-checklist.md` | The fields of the CurseForge forms and what to enter |
| `preview.html` | The description with the images inline, for a browser; works offline when opened from this folder |
| `img/` | The 22 images the description refers to (PNG) |

## Images

| File | What it shows |
|---|---|
| `banner.png` | Title banner: logo, name, tagline and three chips |
| `hero_dungeon.png` | Every loot container of the mod in one dungeon room |
| `card_per_player.png` | Feature card: everyone gets their own loot |
| `sealed_vs_spent.png` | A sealed chest next to a looted one, with a short explanation |
| `card_extra_loot.png` | Feature card: extra stacks in structure chests |
| `card_item_bans.png` | Feature card: banned items stay out of loot |
| `card_verify.png` | Feature card: the loot table check |
| `assets_grid.png` | Every block of the mod, not looted yet and looted |
| `block_chest.png` | Loot Chest, not looted yet and looted |
| `block_trapped_chest.png` | Trapped Loot Chest, both states |
| `block_barrel.png` | Loot Barrel, both states |
| `block_shulker.png` | Loot Shulker, both states |
| `block_pot.png` | Decorated Loot Pot, both states |
| `block_suspicious_sand.png` | Suspicious Sand, both states |
| `block_suspicious_gravel.png` | Suspicious Gravel, both states |
| `block_item_frame.png` | Item Frame with Loot, both states |
| `block_minecart.png` | Minecart with Loot Chest, both states |
| `block_trophy.png` | Centennial Trophy |
| `toasts.png` | The decay and refresh notices |
| `texture_sheet.png` | Every texture the mod ships, pixel for pixel |
| `card_one_jar.png` | Feature card: one jar instead of eleven |
| `card_install.png` | Feature card: what you need |

## Putting the description on CurseForge

1. Open the project's description editor, switch to the Markdown editor and paste the content of `description.md`.
2. At every line that reads `>>> PASTE IMAGE: file_name.png | Alt text <<<`, upload `img/file_name.png` with the editor's image button.
3. Use the text after the `|` as the alt text of the image.
4. Delete the marker line.
5. Check the page preview before saving. `preview.html` shows where each image goes.
6. Upload the images as PNG and do not let the site rescale them. The logo is `art\logo\logo_512.png`.

## Rebuilding

```
python art\showcase.py
python art\showcase_cards.py
python art\showcase_preview.py
python art\showcase_preview.py --check
```

The first two build the images into `img/`, the third writes `preview.html` from `description.md`, and `--check` verifies that every marker names an existing file, every image is used by exactly one marker, the marker lines have the exact form, every PNG is at most 1000 px wide and 400,000 bytes, and `preview.html` is up to date.

The generators need a Minecraft 1.21.1 client jar. They find the one in the Gradle Loom cache (`%USERPROFILE%\.gradle\caches\fabric-loom\1.21.1\`) automatically; set the environment variable `LOOTR_MC_JAR` to use another jar. The output is byte-identical on every run.

## What the images contain

The mod's own art is read from the shipped textures and from `trophy.json`. The ground blocks, the rails, the minecart, the side of the pot, the elytra, the item icons, the player faces and the font are vanilla Minecraft textures. The generator reads them from the client jar when it runs; they are not stored in this repository.

The images are page material and are not packed into the mod jar. The statement in `art\README.md` that the art has no pixels derived from Minecraft textures is about the shipped textures, not about these images.
