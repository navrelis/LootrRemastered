#!/usr/bin/env python3
"""Lootr Remastered -- showcase images for the CurseForge page.

    python art/showcase.py                 build every image into docs/curseforge/img/
    python art/showcase.py --out <dir>     build into another folder
    python art/showcase.py --check         additionally print a sha256 per written file
    python art/showcase.py --only <name>   build one image (file name without .png)

The mod's art is read from the shipped PNGs and trophy.json, so the pictures show exactly what
is in the jar.  Vanilla blocks and the vanilla font are read at run time from the Minecraft
1.21.1 client jar Loom cached (or the one LOOTR_MC_JAR names); nothing of it is stored here.
Running the script again reproduces byte-identical files.  Python standard library only.
"""
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import showcase_lib as lib                              # noqa: E402
from showcase_lib import Canvas, BRASS, IRON, GEM, INK  # noqa: E402

NOT_LOOTED, LOOTED = "Not looted yet", "Looted"
TAGLINE = "Per-player loot, extra structure loot, item bans and a loot check. One jar."

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------


def framed(img, edge=4, shadow=6):
    """img inside a brass frame with a hard drop shadow, on a transparent canvas."""
    c = Canvas(img.w + 2 * edge + 4 + shadow, img.h + 2 * edge + 4 + shadow)
    c.rect(shadow, shadow, c.w - shadow, c.h - shadow, lib.RIM)
    lib.paste(c, lib.panel(img.w + 2 * edge + 4, img.h + 2 * edge + 4, lib.RIM, lib.BORDER, edge), 0, 0)
    c.hline(0, 0, img.w + 2 * edge + 4, lib.BORDER_LIGHT)
    c.vline(0, 0, img.h + 2 * edge + 4, lib.BORDER_LIGHT)
    lib.paste(c, img, edge + 2, edge + 2)
    return c


def banner():
    w, h = 1000, 420
    c = lib.card(w, h)
    tile = framed(lib.scale_nn(lib.g.logo(), 7))
    lib.paste(c, tile, 42, 34)
    # title: two lines of big letters, pale brass over a dark brass copy and a hard shadow
    tx = 42 + tile.w + 26
    for i, word in enumerate(("Lootr", "Remastered")):
        y = 44 + i * 104
        lib.font_text(c, tx + 12, y + 12, word, lib.RIM, 11, shadow=False)
        lib.paste(c, lib.heading(word, 11, BRASS[4], BRASS[1]), tx, y)
    # tagline strip
    lines = lib.wrap(TAGLINE, w - 2 * 42 - 40, 2)
    strip = lib.panel(w - 2 * 42, 26 + 20 * len(lines), lib.PANEL_DARK, lib.BORDER, 2, ornaments=True)
    for i, line in enumerate(lines):
        lib.text_centred(strip, strip.w // 2, 15 + 20 * i, line, lib.TEXT, 2)
    sy = 34 + tile.h + 8
    c.rect(42 + 6, sy + 6, strip.w, strip.h, lib.RIM)
    lib.paste(c, strip, 42, sy)
    # chips
    row = lib.chips_row([lib.chip("FABRIC 1.21.1"), lib.chip("REPLACES 11 JARS"),
                         lib.chip("ORIGINAL PIXEL ART", BRASS[3], BRASS[5], INK)])
    cy = sy + strip.h + (h - 14 - sy - strip.h - row.h) // 2 + 2
    lib.paste_centred(c, row, w // 2, cy)
    # teal glints, as on the logo
    for x, y, big in ((930, 60, True), (694, 44, False), (884, 128, False), (958, 250, True), (300, 28, False),
                      (640, 240, False), (120, cy + 12, True), (872, cy + 16, True), (206, cy + 22, False),
                      (790, cy + 4, False), (560, 30, False)):
        lib.sparkle(c, x, y, 4, big)
    return c


# ---------------------------------------------------------------------------
# Isometric scenes
# ---------------------------------------------------------------------------

STONE_FLOOR = ["stone_bricks"] * 6 + ["mossy_stone_bricks", "cracked_stone_bricks"]
DEEPSLATE = ["deepslate_bricks"] * 4 + ["cracked_deepslate_bricks", "deepslate_tiles"]
SANDSTONE = ("sandstone_top", "sandstone", "sandstone_bottom")


def hero_dungeon():
    sc = lib.scene()
    nx, nz, slab = 9, 6, 8
    sand = {(0, 4), (0, 5), (1, 4), (1, 5), (2, 4), (2, 5), (3, 5), (3, 3)}
    gravel = {(5, 2), (6, 3), (7, 2), (7, 3), (5, 1)}
    special = {(1, 3): ("sand", False), (2, 3): ("sand", True), (6, 2): ("gravel", False), (5, 3): ("gravel", True)}
    rnd = lib._noise(11)
    for z in range(-1, nz):
        for x in range(-1, nx):
            name = STONE_FLOOR[rnd(len(STONE_FLOOR))]
            if (x, z) in special:
                lib.add_suspicious(sc, x, -slab / 16.0, z, *special[(x, z)], height=slab)
            else:
                name = "sand" if (x, z) in sand else "gravel" if (x, z) in gravel else name
                lib.add_cube(sc, x, -slab / 16.0, z, name, height=slab)
    for y in range(3):                                  # two back walls, cut open towards the viewer
        for x in range(-1, nx):
            lib.add_cube(sc, x, y, -1, DEEPSLATE[rnd(len(DEEPSLATE))])
        for z in range(nz):
            lib.add_cube(sc, -1, y, z, DEEPSLATE[rnd(len(DEEPSLATE))])
    # along the north wall: chests, trapped chests, the two item frames, barrels
    lib.add_chest(sc, 1, 0, 0)
    lib.add_chest(sc, 2, 0, 0, looted=True)
    lib.add_chest(sc, 3, 0, 0, trapped=True)
    lib.add_chest(sc, 4, 0, 0, looted=True, trapped=True)
    lib.add_frame(sc, 5, 1, 0)
    lib.add_frame(sc, 6, 1, 0, looted=True)
    lib.add_barrel(sc, 7, 0, 0)
    lib.add_barrel(sc, 8, 0, 0, looted=True)
    # along the west wall, with gaps so that no block hides its neighbour's front: shulkers, a pot on the sand
    lib.add_shulker(sc, 0, 0, 1)
    lib.add_shulker(sc, 0, 0, 3, looted=True)
    lib.add_pot(sc, 0, 0, 5)
    lib.add_pot(sc, 1, 0, 5, looted=True)
    # in the room: the trophy on a plinth, an opened barrel, the minecart on its rail
    lib.add_cube(sc, 4, 0, 3, "polished_blackstone")
    lib.add_trophy(sc, 4, 1, 3)
    lib.add_barrel(sc, 8, 0, 3, open_top=True)
    for x in range(4, nx):
        lib.add_rail(sc, x, 0, 5)
    lib.add_minecart(sc, 6, 0, 5)
    img = lib.render_scene(sc, 3)
    out = Canvas(1000, img.h + 24, lib.BACKDROP)
    lib.paste(out, img, (out.w - img.w) // 2, (out.h - img.h) // 2)
    return out


def block_image(label, sc, marks, k=5, size=(560, 360)):
    """One container on its plate: the scene, the name box top left, a caption under each marked block column."""
    w, h = size
    img = lib.render_scene(sc, k)
    out = Canvas(w, h, lib.BACKDROP)
    x = (w - img.w) // 2
    y = h - 38 - img.h if marks else 44 + (h - 44 - img.h) // 2 - 6
    lib.paste(out, img, x, y)
    lib.paste(out, lib.label_box(label), 8, 8)
    for bx, bz, text in marks:
        px = x + img.project(bx, 0, bz)[0]
        lib.text_centred(out, px, h - 30, text, lib.ACCENT_LIGHT if text == NOT_LOOTED else lib.TEXT_DIM, 2)
    return out


def pair(label, ground, add, seed=5):
    """The usual block image: a 2 x 2 plate, the sealed variant on the left corner, the looted one on the right."""
    sc = lib.scene()
    lib.add_floor(sc, 2, 2, ground, seed=seed, height=6)
    add(sc, 0, 0, 1, False)
    add(sc, 1, 0, 0, True)
    return block_image(label, sc, [(0.5, 1.5, NOT_LOOTED), (1.5, 0.5, LOOTED)])


def block_chest():
    return pair("Loot Chest", STONE_FLOOR, lambda sc, x, y, z, lo: lib.add_chest(sc, x, y, z, looted=lo))


def block_trapped_chest():
    return pair("Trapped Loot Chest", DEEPSLATE, lambda sc, x, y, z, lo: lib.add_chest(sc, x, y, z, looted=lo, trapped=True), seed=9)


def block_barrel():
    return pair("Loot Barrel", ["cobblestone", "cobblestone", "mossy_cobblestone"],
                lambda sc, x, y, z, lo: lib.add_barrel(sc, x, y, z, looted=lo), seed=4)


def block_shulker():
    return pair("Loot Shulker", ["end_stone_bricks"] * 3 + ["purpur_block"],
                lambda sc, x, y, z, lo: lib.add_shulker(sc, x, y, z, looted=lo), seed=2)


def block_pot():
    return pair("Decorated Loot Pot", [SANDSTONE, SANDSTONE, "sand"],
                lambda sc, x, y, z, lo: lib.add_pot(sc, x, y, z, looted=lo), seed=6)


def block_suspicious_sand():
    return pair("Suspicious Sand", [SANDSTONE, ("cut_sandstone", "cut_sandstone", "sandstone_bottom")],
                lambda sc, x, y, z, lo: lib.add_suspicious(sc, x, y, z, "sand", lo), seed=8)


def block_suspicious_gravel():
    return pair("Suspicious Gravel", ["stone", "stone", "andesite", "cobblestone"],
                lambda sc, x, y, z, lo: lib.add_suspicious(sc, x, y, z, "gravel", lo), seed=3)


def block_item_frame():
    # a wall piece instead of a plate; the captions stand beside the wall at the height of their frame
    sc = lib.scene()
    for x in range(3):
        for y in range(2):
            lib.add_cube(sc, x, y, 0, "purpur_pillar" if x == 1 else "purpur_block", bottom="purpur_pillar_top" if x == 1 else None)
    lib.add_frame(sc, 0, 0.5, 1)
    lib.add_frame(sc, 2, 0.5, 1, looted=True)
    w, h = 560, 360
    img = lib.render_scene(sc, 4)
    out = Canvas(w, h, lib.BACKDROP)
    x, y = (w - img.w) // 2 + 30, 44 + (h - 44 - img.h) // 2
    lib.paste(out, img, x, y)
    lib.paste(out, lib.label_box("Item Frame with Loot"), 8, 8)
    lx = x + img.project(0, 1, 1)[0] - 14
    lib.font_text(out, lx - lib.text_width(NOT_LOOTED, 2), y + img.project(0, 1, 1)[1] - 8, NOT_LOOTED, lib.ACCENT_LIGHT, 2)
    lib.font_text(out, x + img.project(3, 1, 0)[0] + 14, y + img.project(3, 1, 0.5)[1] - 8, LOOTED, lib.TEXT_DIM, 2)
    return out


def block_trophy():
    sc = lib.scene()
    lib.add_floor(sc, 2, 2, ["polished_blackstone_bricks"] * 3 + ["polished_blackstone", "cracked_polished_blackstone_bricks"], seed=5, height=6)
    lib.add_trophy(sc, 0.5, 0, 0.5)
    return block_image("Centennial Trophy", sc, [], k=6)


def block_minecart():
    sc = lib.scene()
    lib.add_floor(sc, 3, 3, ["stone"] * 3 + ["gravel", "cobblestone"], seed=7)
    for x in range(3):
        lib.add_rail(sc, x, 0, 0)
        lib.add_rail(sc, x, 0, 2)
    lib.add_minecart(sc, 0.3, 0, 2)
    lib.add_minecart(sc, 1.7, 0, 0, looted=True)
    return block_image("Minecart with Loot Chest", sc, [(0.8, 2.5, NOT_LOOTED), (2.2, 0.5, LOOTED)], k=4)


# ---------------------------------------------------------------------------
# Grid of every block
# ---------------------------------------------------------------------------


def thumb(add, looted, k=4):
    sc = lib.scene()
    add(sc, looted)
    return lib.render_scene(sc, k)


def frame_thumb(sc, looted):
    lib.add_cube(sc, 0, 0, -1, "purpur_block")
    lib.add_frame(sc, 0, 0, 0, looted=looted)


def cart_thumb(sc, looted):
    lib.add_rail(sc, 0, 0, 0)
    lib.add_minecart(sc, 0, 0, 0, looted=looted)


GRID = [
    ("Loot Chest", lambda sc, lo: lib.add_chest(sc, 0, 0, 0, looted=lo)),
    ("Trapped Loot Chest", lambda sc, lo: lib.add_chest(sc, 0, 0, 0, looted=lo, trapped=True)),
    ("Loot Barrel", lambda sc, lo: lib.add_barrel(sc, 0, 0, 0, looted=lo)),
    ("Loot Shulker", lambda sc, lo: lib.add_shulker(sc, 0, 0, 0, looted=lo)),
    ("Decorated Loot Pot", lambda sc, lo: lib.add_pot(sc, 0, 0, 0, looted=lo)),
    ("Suspicious Sand", lambda sc, lo: lib.add_suspicious(sc, 0, 0, 0, "sand", lo)),
    ("Suspicious Gravel", lambda sc, lo: lib.add_suspicious(sc, 0, 0, 0, "gravel", lo)),
    ("Item Frame with Loot", frame_thumb),
    ("Minecart with Loot Chest", cart_thumb),
]


def sheet_header(c, title, note=None):
    """The heading of a card sheet with its brass rule; returns the y below it."""
    lib.paste(c, lib.heading(title, 4), 40, 34)
    if note:
        lib.font_text(c, c.w - 40 - lib.text_width(note, 2), 50, note, lib.TEXT_DIM, 2)
    c.rect(40, 78, c.w - 80, 2, lib.PANEL_MID)
    c.rect(40, 78, 160, 2, lib.BORDER)
    return 98


def tile(w, h, img, spent=False):
    """A recessed tile with img centred: brass rim for sealed things, iron for looted ones."""
    t = lib.panel(w, h, lib.PANEL_DARK, IRON[4] if spent else BRASS[2], 2)
    t.hline(2, 2, w - 4, lib.RIM)                       # recessed: shadow along the top and left
    t.vline(2, 2, h - 4, lib.RIM)
    lib.paste(t, img, (w - img.w) // 2, (h - img.h) // 2)
    return t


def assets_grid():
    # one column per container: its name, the sealed tile, the looted tile; five columns to a band
    tw, th, gap, cols = 168, 166, 20, 5
    cap = 34                                            # caption line and space under a tile
    band = 46 + 2 * (th + cap) + 14
    columns = GRID + [("Centennial Trophy", None)]
    w, h = 1000, 98 + (len(columns) + cols - 1) // cols * band + 8
    c = lib.card(w, h, dither=False)
    y0 = sheet_header(c, "Every block of the mod")
    x0 = (w - cols * tw - (cols - 1) * gap) // 2
    for n, (name, add) in enumerate(columns):
        x, y = x0 + n % cols * (tw + gap), y0 + n // cols * band
        lines = lib.wrap(name, tw, 2)
        for i, line in enumerate(lines):                # names stand on the tile, however many lines they take
            lib.text_centred(c, x + tw // 2, y + 40 - 20 * (len(lines) - i), line, lib.TEXT, 2)
        y += 46
        if add is None:                                 # the trophy has one state: one tall tile
            img = thumb(lambda sc, lo: lib.add_trophy(sc, 0, 0, 0), False, 8)
            lib.paste(c, tile(tw, 2 * th + cap, img), x, y)
            continue
        for looted in (False, True):
            lib.paste(c, tile(tw, th, thumb(add, looted), looted), x, y)
            lib.text_centred(c, x + tw // 2, y + th + 8, LOOTED if looted else NOT_LOOTED,
                             lib.SPENT if looted else lib.ACCENT_LIGHT, 2)
            y += th + cap
    return c


# ---------------------------------------------------------------------------
# Texture sheet
# ---------------------------------------------------------------------------

SHEET = [
    [("Chests", 3, ["chest", "chest_opened", "chest_trapped", "chest_trapped_opened"])],
    [("Shulker box", 3, ["shulker", "shulker_opened"]),
     ("Decorated pot", 3, ["entity/loot_pot", "entity/loot_pot_open", "entity/decorated_pot/loot_pottery_pattern"])],
    [("Barrel", 6, ["block/barrel_top", "block/barrel_top_open", "block/barrel_side", "block/barrel_bottom",
                    "block/opened_barrel_top", "block/opened_barrel_top_open", "block/opened_barrel_side", "block/opened_barrel_bottom"])],
    [("Planks", 6, ["block/gold_planks", "block/silver_planks"]),
     ("Brushable", 6, ["block/suspicious_sand_open", "block/suspicious_gravel_open"]),
     ("Trophy", 3, ["block/trophy"])],
    [("Toasts", 4, ["gui/sprites/toast/container_alert", "gui/sprites/toast/decay", "gui/sprites/toast/refresh"])],
]


def texture_sheet():
    listed = sorted(n for row in SHEET for _, _, names in row for n in names)
    assert listed == lib.lootr_names(), "the sheet must list exactly the shipped textures"
    gap, group_gap, min_cell = 20, 44, 92

    def cells(names, k):
        out = []
        for n in names:
            tex = lib.scale_nn(lootr_tex(n), k)
            cw = max(tex.w, min_cell)
            out.append((tex, lib.wrap(n.split("/")[-1] + ".png", cw, 1, breaks="_"), cw))
        return out

    def lootr_tex(n):
        return lib.lootr(n)

    laid = [[(title, k, cells(names, k)) for title, k, names in row] for row in SHEET]
    heights = [max(t.h for _, _, cs in row for t, _, _ in cs) + 24 + 8 + 10 * max(len(ls) for _, _, cs in row for _, ls, _ in cs) + 26
               for row in laid]
    w, h = 1000, 98 + sum(heights) + 6
    c = lib.card(w, h, dither=False)
    y = sheet_header(c, "Every texture, pixel for pixel", "%d files" % len(listed))
    for row, rh in zip(laid, heights):
        x = 40
        for title, k, cs in row:
            end = lib.font_text(c, x, y, title.upper(), BRASS[4], 2)
            lib.font_text(c, end + 12, y + 7, "x%d" % k, lib.TEXT_DIM, 1)
            for tex, lines, cw in cs:
                lib.paste(c, lib.checker(tex.w, tex.h, 8), x, y + 24)
                lib.paste(c, tex, x, y + 24)
                c.frame(x - 1, y + 23, tex.w + 2, tex.h + 2, lib.PANEL_MID)
                for i, line in enumerate(lines):
                    lib.font_text(c, x, y + 24 + tex.h + 8 + 10 * i, line, lib.TEXT_DIM, 1)
                x += cw + gap
            x += group_gap - gap
        assert x - group_gap <= w - 30, "row too wide: %d" % x
        y += rh
    return c


# ---------------------------------------------------------------------------
# Toasts
# ---------------------------------------------------------------------------
# LootrToast.render: background sprite at 0,0 (160 x 32), the 20 x 20 icon at 6,6, the title at 30,7
# in 0x500050 and the message at 30,18 in black, both without shadow.

TOASTS = [("decay", "Container decaying!", "%s seconds remaining." % 30),
          ("refresh", "Container refreshed!", "New items available!")]


def toast(icon, title, message):
    t = lib.lootr("gui/sprites/toast/container_alert").copy()
    lib.paste(t, lib.lootr("gui/sprites/toast/" + icon), 6, 6)
    lib.font_text(t, 30, 7, title, lib.rgb("500050"), 1, shadow=False)
    lib.font_text(t, 30, 18, message, lib.rgb("000000"), 1, shadow=False)
    return t


def toasts():
    k = 4
    out = Canvas(160 * k + 80, 62 + len(TOASTS) * (32 * k + 22) + 10, lib.BACKDROP)
    lib.paste(out, lib.label_box("Decay and refresh notices"), 8, 8)
    for i, args in enumerate(TOASTS):
        lib.paste(out, lib.scale_nn(toast(*args), k), 40, 62 + i * (32 * k + 22))
    return out


# ---------------------------------------------------------------------------
# Sealed and spent
# ---------------------------------------------------------------------------


def sealed_vs_spent():
    w, h = 1000, 420
    c = lib.card(w, h, seed=3)
    lib.paste(c, lib.heading("Sealed until you open it", 5), 44, 38)
    c.rect(44, 92, w - 88, 2, lib.PANEL_MID)
    c.rect(44, 92, 200, 2, lib.BORDER)
    chests = []
    for looted in (False, True):
        sc = lib.scene()
        lib.add_chest(sc, 0, 0, 0, looted=looted)
        chests.append(lib.render_scene(sc, 5))
    arr = lib.arrow(72, 4)
    top = 122
    bay = lib.panel(476, 256, lib.PANEL_DARK, lib.BORDER_DARK, 2)
    bay.hline(2, 2, bay.w - 4, lib.RIM)
    bay.vline(2, 2, bay.h - 4, lib.RIM)
    lib.paste(c, bay, 44, top - 10)
    xs = (44 + 30, 44 + bay.w - 30 - chests[1].w)
    for x, img, text, col in ((xs[0], chests[0], NOT_LOOTED, lib.ACCENT_LIGHT), (xs[1], chests[1], LOOTED, lib.SPENT)):
        lib.paste(c, img, x, top + 12)
        lib.text_centred(c, x + img.w // 2, top + 12 + img.h + 14, text, col, 2)
    lib.paste_centred(c, arr, 44 + bay.w // 2, top + 12 + (chests[0].h - arr.h) // 2)
    for x, y, big in ((xs[0] - 8, top + 22, True), (xs[0] + chests[0].w + 2, top + 150, False), (xs[0] + 22, top + 8, False)):
        lib.sparkle(c, x, y, 4, big)
    tx, tw = 44 + bay.w + 34, w - 44 - (44 + bay.w + 34)
    y = lib.paragraph(c, tx, top + 40, "Every player gets their own loot from the same container.", tw, lib.TEXT, 2, leading=4)
    c.rect(tx, y + 8, 48, 2, lib.ACCENT)
    lib.paragraph(c, tx, y + 26, "Once you have looted it, the gem is gone and the brass turns to iron: "
                  "yours is spent, theirs is still sealed.", tw, lib.TEXT_DIM, 2, leading=4)
    return c


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

IMAGES = [
    ("banner", banner),
    ("hero_dungeon", hero_dungeon),
    ("block_chest", block_chest),
    ("block_trapped_chest", block_trapped_chest),
    ("block_barrel", block_barrel),
    ("block_shulker", block_shulker),
    ("block_pot", block_pot),
    ("block_suspicious_sand", block_suspicious_sand),
    ("block_suspicious_gravel", block_suspicious_gravel),
    ("block_item_frame", block_item_frame),
    ("block_minecart", block_minecart),
    ("block_trophy", block_trophy),
    ("assets_grid", assets_grid),
    ("texture_sheet", texture_sheet),
    ("toasts", toasts),
    ("sealed_vs_spent", sealed_vs_spent),
]


def main():
    args = sys.argv[1:]

    def value(flag):
        if flag in args:
            if args.index(flag) + 1 >= len(args):
                sys.exit("%s needs a value" % flag)
            return args[args.index(flag) + 1]
        return None

    out, only = value("--out"), value("--only")
    if out:
        lib.set_out(os.path.abspath(out))
    todo = [(n, f) for n, f in IMAGES if only in (None, n)]
    if not todo:
        sys.exit("no image named %r; known: %s" % (only, ", ".join(n for n, _ in IMAGES)))
    for name, build in todo:
        img = build()
        path, data = lib.save(img, name)
        line = "%4d x %-4d %7d bytes  %s" % (img.w, img.h, len(data), os.path.basename(path))
        if "--check" in args:
            line = hashlib.sha256(data).hexdigest() + "  " + line
        print(line)
    print("wrote %d files" % len(todo))


if __name__ == "__main__":
    main()
