#!/usr/bin/env python3
"""Lootr Remastered -- six feature cards for the CurseForge page.

    python art/showcase_cards.py                 build every card into docs/curseforge/img/
    python art/showcase_cards.py --out <dir>     build into another folder
    python art/showcase_cards.py --check         additionally print a sha256 per written file
    python art/showcase_cards.py --only <name>   build one card (file name without .png)

Same card style as showcase.py (sealed_vs_spent, assets_grid): a framed card, a heading with a two-part
rule, a recessed picture bay, text in the vanilla font at a whole scale.  The shared drawing code is
showcase_lib.py; what only these cards need (inventory slots, player faces, finding rows) lives here.
Vanilla item icons and skins are read at run time from the Minecraft 1.21.1 client jar, as everywhere
else.  Running the script again reproduces byte-identical files.  Python standard library only.
"""
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import showcase_lib as lib                                                  # noqa: E402
from showcase_lib import Canvas, BRASS, IRON, GEM, RUBY, INK                # noqa: E402,F401

W, MARGIN = 1000, 40                    # card width, and the content margin the heading also sits on
RULE_Y, TOP = 88, 108                   # the heading rule, and the first y of the content under it
BOTTOM = 36                             # space under the last text line (the corner ornaments need ~34)
LINE = 24                               # one line of scale-2 text with leading 4: (8 + 4) * 2

# ---------------------------------------------------------------------------
# Shared pieces
# ---------------------------------------------------------------------------


def new_card(h, title, seed):
    """The card with its heading and the two-part rule, as sealed_vs_spent() and assets_grid() draw it."""
    c = lib.card(W, h, seed=seed)
    # scale 5 where the title fits the content width, else 4 (two titles are too long for 5); both centred on one band
    scale = 5 if lib.text_width(title, 5) + 5 <= W - 2 * MARGIN else 4
    lib.paste(c, lib.heading(title, scale), MARGIN, 34 + (45 - 9 * scale) // 2)
    c.rect(MARGIN, RULE_Y, W - 2 * MARGIN, 2, lib.PANEL_MID)
    c.rect(MARGIN, RULE_Y, 200, 2, lib.BORDER)
    return c


def bay(w, h):
    """The recessed picture bay (brass rim, shadow along the top and left), a shade lighter than a slot."""
    b = lib.panel(w, h, lib.PANEL, lib.BORDER_DARK, 2)
    b.hline(2, 2, w - 4, lib.RIM)
    b.vline(2, 2, h - 4, lib.RIM)
    return b


def shadowed(c, x, y, img, depth=4):
    """Paste img with a hard RIM drop shadow, like the tagline strip of the banner."""
    c.rect(x + depth, y + depth, img.w, img.h, lib.RIM)
    lib.paste(c, img, x, y)


def row_panel(w, h, edge):
    """A finding / requirement row: recessed panel with a 6 px coloured edge on the left."""
    r = lib.panel(w, h, lib.PANEL_DARK, lib.PANEL_MID, 2)
    r.rect(0, 0, 6, h, edge)
    return r


def block_height(lines, scale=2, leading=3):
    return 7 * scale + (len(lines) - 1) * (8 + leading) * scale


def row_lines(r, x, lines, colour=None, scale=2):
    """Draw already wrapped lines in row r, centred vertically."""
    y = (r.h - block_height(lines, scale)) // 2
    for line in lines:
        lib.font_text(r, x, y, line, colour, scale)
        y += (8 + 3) * scale


def segments(canvas, x, y, parts, scale=2):
    """Text in several colours on one line; parts is a list of (string, colour)."""
    done = ""
    for string, colour in parts:
        lib.font_text(canvas, x + (lib.text_width(done, 1) + 1) * scale if done else x, y, string, colour, scale)
        done += string


def lines_of(text, width):
    """text wrapped to width at scale 2; a last line of a word or two (under a fifth of the width) is avoided
    by narrowing the measure to the smallest one that keeps the same number of lines."""
    lines = lib.wrap(text, width, 2)
    if len(lines) > 1 and lib.text_width(lines[-1], 2) < width // 5:
        lo, hi = 1, width
        while lo < hi:
            mid = (lo + hi) // 2
            if len(lib.wrap(text, mid, 2)) == len(lines):
                hi = mid
            else:
                lo = mid + 1
        lines = lib.wrap(text, lo, 2)
    return lines


def para(c, x, y, text, width, colour=None):
    """Scale-2 paragraph from lines_of(); returns the y below it."""
    for line in lines_of(text, width):
        lib.font_text(c, x, y, line, colour, 2)
        y += LINE
    return y


def bullets(c, x, y, items, width, colour=None, gap=12):
    """Rows with a small teal square as bullet, wrapped to width; returns the y below the last row."""
    for text in items:
        c.rect(x, y + 3, 8, 8, lib.ACCENT)
        c.rect(x, y + 3, 2, 2, lib.ACCENT_LIGHT)
        for line in lines_of(text, width - 24):
            lib.font_text(c, x + 24, y, line, colour or lib.TEXT, 2)
            y += LINE
        y += gap
    return y - gap


def item(name, k=3):
    return lib.scale_nn(lib.vanilla("item/%s.png" % name), k)


def slot(name=None, k=3):
    """One inventory slot drawn at scale 1 (18 x 18) and scaled up, with the item inside it (16 x 16, one texel in)."""
    s = Canvas(18, 18, lib.PANEL_DARK)
    s.hline(0, 0, 18, lib.RIM)
    s.vline(0, 0, 18, lib.RIM)
    s.hline(0, 17, 18, lib.PANEL_MID)
    s.vline(17, 0, 18, lib.PANEL_MID)
    s = lib.scale_nn(s, k)
    if name:
        lib.paste(s, item(name, k), k, k)
    return s


def outlined(s):
    """A slot with a 2 px teal outline round it and a small teal plus on the top right corner."""
    o = Canvas(s.w + 4, s.h + 4)
    o.frame(0, 0, o.w, o.h, lib.ACCENT)
    o.frame(1, 1, o.w - 2, o.h - 2, lib.ACCENT)
    lib.paste(o, s, 2, 2)
    x0 = o.w - 12
    o.rect(x0, 0, 12, 12, lib.ACCENT)
    o.rect(x0 + 2, 5, 8, 2, lib.RIM)
    o.rect(x0 + 5, 2, 2, 8, lib.RIM)
    return o


def slot_strip(names, k=3, gap=8, marks=()):
    """Slots side by side (None is an empty slot); marks lists the indexes that get the teal outline."""
    sw = 18 * k
    c = Canvas(len(names) * sw + (len(names) - 1) * gap, sw)
    for i, name in enumerate(names):
        x = i * (sw + gap)
        if i in marks:
            lib.paste(c, outlined(slot(name, k)), x - 2, -2)
        else:
            lib.paste(c, slot(name, k), x, 0)
    return c


def face(skin, k=6):
    """The 8 x 8 face of a skin with its hat layer over it, scaled up."""
    sheet = lib.vanilla("entity/player/wide/%s.png" % skin)
    f = sheet.crop(8, 8, 8, 8)
    lib.paste(f, sheet.crop(40, 8, 8, 8), 0, 0)
    return lib.scale_nn(f, k)


def face_tile(skin, edge):
    img = face(skin)
    t = lib.panel(img.w + 12, img.h + 12, lib.PANEL_DARK, edge, 2)
    t.hline(2, 2, t.w - 4, lib.RIM)
    t.vline(2, 2, t.h - 4, lib.RIM)
    lib.paste(t, img, 6, 6)
    return t


def arrow_head(c, x, cy, colour, k=4):
    """A small blocky arrowhead pointing right; x is its left edge, cy the centre line."""
    for i, half in enumerate((2, 1, 0)):
        c.rect(x + i * k, cy - k // 2 - half * k, k, (2 * half + 1) * k, colour)


# ---------------------------------------------------------------------------
# 1. Everyone gets their own loot
# ---------------------------------------------------------------------------

PLAYERS = [
    ("Steve", "steve", lib.ACCENT, ["iron_ingot", "bread", None, "saddle", "golden_apple"]),
    ("Alex", "alex", BRASS[3], ["diamond", None, "name_tag", "iron_horse_armor", "bone"]),
    ("Ari", "ari", RUBY[2], ["enchanted_book", None, "redstone", "music_disc_13", "wheat"]),
]


def card_per_player():
    row_w, row_h, row_gap, pad = 520, 76, 12, 16
    bay_h = 3 * row_h + 2 * row_gap + 2 * pad
    p1 = "The first time each player opens a loot container, an inventory is rolled from its loot table just for them."
    p2 = ("Nobody misses out because someone else was there first. Works for chests, barrels, shulker boxes, "
          "chest minecarts, decorated pots, suspicious sand and gravel, and elytra item frames.")
    tw = W - 2 * MARGIN
    n1, n2 = len(lines_of(p1, tw)), len(lines_of(p2, tw))
    ty = TOP + bay_h + 24
    h = ty + (n1 + n2) * LINE + 26 + 16 + BOTTOM - LINE
    c = new_card(h, "Everyone gets their own loot", 11)
    lib.paste(c, bay(tw, bay_h), MARGIN, TOP)
    rows_x = MARGIN + tw - 24 - row_w
    rows_y = TOP + pad
    # the sealed chest on the left, with a few teal glints
    sc = lib.scene()
    lib.add_chest(sc, 0, 0, 0)
    chest = lib.render_scene(sc, 5)
    cx, cy = MARGIN + 36 + 150, TOP + bay_h // 2
    lib.paste(c, chest, cx - chest.w // 2, cy - chest.h // 2)
    for x, y, big in ((cx - 100, cy - 62, True), (cx + 88, cy + 52, False), (cx - 74, cy + 58, False), (cx + 92, cy - 54, True)):
        lib.sparkle(c, x, y, 4, big)
    # connectors: out of the chest to a trunk, from the trunk to the three rows
    line, ys = lib.BORDER, [rows_y + i * (row_h + row_gap) + row_h // 2 for i in range(3)]
    trunk = rows_x - 54
    x0 = cx + chest.w // 2 + 8
    c.rect(x0, cy - 2, trunk - x0 + 4, 4, line)
    c.rect(trunk, ys[0] - 2, 4, ys[2] - ys[0] + 4, line)
    for y in ys:
        c.rect(trunk, y - 2, rows_x - 16 - trunk, 4, line)
        arrow_head(c, rows_x - 16, y, lib.BORDER_LIGHT)
    # one row per player: face, name, five slots with different items
    for i, (name, skin, edge, items) in enumerate(PLAYERS):
        r = row_panel(row_w, row_h, edge)
        r.blit(face_tile(skin, lib.PANEL_MID), 22, (row_h - 60) // 2)
        lib.font_text(r, 98, (row_h - 14) // 2, name, lib.TEXT, 2)
        strip = slot_strip(items, 3, 10)
        lib.paste(r, strip, row_w - 22 - strip.w, (row_h - strip.h) // 2)
        shadowed(c, rows_x, rows_y + i * (row_h + row_gap), r)
    y = para(c, MARGIN, ty, p1, tw, lib.TEXT)
    c.rect(MARGIN, y + 8, 48, 2, lib.ACCENT)
    para(c, MARGIN, y + 26, p2, tw, lib.TEXT_DIM)
    return c


# ---------------------------------------------------------------------------
# 2. A few extra stacks in structure chests
# ---------------------------------------------------------------------------

# (column, row) -> (item, added by integrated loot); the extra stacks do not touch each other
CHEST = {(1, 0): ("bread", False), (4, 0): ("diamond", True), (7, 0): ("bone", False),
         (0, 1): ("arrow", False), (3, 1): ("coal", False), (6, 1): ("enchanted_book", True), (8, 1): ("string", False),
         (2, 2): ("apple", False), (4, 2): ("golden_apple", True)}


def card_extra_loot():
    pitch, grid_pad = 58, 20
    grid_w, grid_h = 9 * pitch - 4, 3 * pitch - 4
    bay_w = grid_w + 2 * grid_pad
    legend_y = 18 + grid_h + 18
    bay_h = legend_y + 2 * 40 + 6 + 8
    chips = lib.chips_row([lib.chip("6 POOLS"), lib.chip("51 RULE FILES"), lib.chip("DATA PACK DRIVEN")])
    chips_y = TOP + bay_h + 18
    tx = MARGIN + bay_w + 34
    tw = W - MARGIN - tx
    p1 = "When a selected loot table is rolled, one or two source tables are rolled too and a few of their stacks are added."
    bl = ["The original loot is never removed.", "Extra stacks only use free slots.",
          "Each rule file is active only when the mod it targets is installed."]
    h = chips_y + chips.h + BOTTOM
    c = new_card(h, "A few extra stacks in structure chests", 23)
    lib.paste(c, bay(bay_w, bay_h), MARGIN, TOP)
    gx, gy = MARGIN + grid_pad, TOP + 18
    for r in range(3):
        for col in range(9):
            name, extra = CHEST.get((col, r), (None, False))
            s = slot(name)
            lib.paste(c, outlined(s) if extra else s, gx + col * pitch - (2 if extra else 0), gy + r * pitch - (2 if extra else 0))
    # legend: a plain slot and an outlined one, at scale 2
    for i, (text, mark) in enumerate((("Original loot", False), ("Added by integrated loot", True))):
        s = slot(None, 2)
        y = TOP + legend_y + i * 40
        lib.paste(c, outlined(s) if mark else s, gx - (2 if mark else 0), y - (2 if mark else 0))
        lib.font_text(c, gx + 36 + 14, y + 11, text, lib.TEXT_DIM, 2)
    lib.paste_centred(c, chips, MARGIN + bay_w // 2, chips_y)
    y = para(c, tx, TOP + 2, p1, tw, lib.TEXT)
    c.rect(tx, y + 8, 48, 2, lib.ACCENT)
    bullets(c, tx, y + 26, bl, tw, lib.TEXT_DIM, 12)
    return c


# ---------------------------------------------------------------------------
# 3. Banned items stay out of loot
# ---------------------------------------------------------------------------

ROLLED = ["bread", "iron_ingot", "diamond", "coal", "arrow"]
BANNED = 2                                  # the index of the item the ban removes


def red_cross(k=3):
    """A blocky X, three cells thick, in RUBY with a dark outline and a lit upper edge."""
    n = 14
    cells = set()
    for i in range(n):
        for j in (i - 1, i, i + 1):
            if 0 <= j < n:
                cells.add((j, i))
                cells.add((j, n - 1 - i))
    ring = {(i + di, j + dj) for (i, j) in cells for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1))} - cells
    c = Canvas((n + 2) * k, (n + 2) * k)
    for (i, j) in ring:
        c.rect((i + 1) * k, (j + 1) * k, k, k, RUBY[0])
    for (i, j) in cells:
        c.rect((i + 1) * k, (j + 1) * k, k, k, RUBY[3] if (i, j - 1) not in cells else RUBY[2])
    return c


def card_item_bans():
    tw = W - 2 * MARGIN
    k, gap = 3, 8
    row_w = 5 * 18 * k + 4 * gap
    arr = lib.arrow(72, 4)
    bay_h = 124
    p1 = ("With Item Obliterator installed, the items it disables are removed from what loot tables roll and from "
          "stored per-player inventories when these are opened.")
    p2 = ("Covers chests, minecart chests, pots, archaeology, mob and block drops and integrated loot. "
          "You can also ban items and item tags in this mod's config.")
    note = "Bans check the top-level stack; the contents of bundles and shulker box items are not inspected."
    ty = TOP + bay_h + 24
    lines = sum(len(lines_of(t, tw)) for t in (p1, p2, note))
    h = ty + lines * LINE + 26 + 12 + 16 + BOTTOM - LINE
    c = new_card(h, "Banned items stay out of loot", 31)
    lib.paste(c, bay(tw, bay_h), MARGIN, TOP)
    total = 2 * row_w + arr.w + 2 * 30
    x0 = MARGIN + (tw - total) // 2
    sy = TOP + 18 + 14 + 14
    left = slot_strip(ROLLED, k, gap)
    right = slot_strip([None if i == BANNED else n for i, n in enumerate(ROLLED)], k, gap)
    lib.paste(c, left, x0, sy)
    lib.paste(c, right, x0 + row_w + arr.w + 60, sy)
    cross = red_cross(k)
    lib.paste(c, cross, x0 + BANNED * (18 * k + gap) + (18 * k - cross.w) // 2, sy + (18 * k - cross.h) // 2)
    lib.text_centred(c, x0 + row_w // 2, TOP + 18, "Rolled", lib.TEXT_DIM, 2)
    lib.text_centred(c, x0 + row_w + arr.w + 60 + row_w // 2, TOP + 18, "In the chest", lib.ACCENT_LIGHT, 2)
    lib.paste_centred(c, arr, x0 + row_w + 30 + arr.w // 2, sy + (18 * k - arr.h) // 2)
    y = para(c, MARGIN, ty, p1, tw, lib.TEXT)
    c.rect(MARGIN, y + 8, 48, 2, lib.ACCENT)
    y = para(c, MARGIN, y + 26, p2, tw, lib.TEXT_DIM)
    para(c, MARGIN, y + 12, note, tw, lib.mix(lib.TEXT_DIM, lib.PANEL, 0.3))
    return c


# ---------------------------------------------------------------------------
# 4. A loot table check for your whole pack
# ---------------------------------------------------------------------------

FINDINGS = [("Loot tables that did not load", RUBY[2]),
            ("References to loot tables that do not exist", RUBY[2]),
            ("Entries for banned items, also through nested tables", BRASS[3]),
            ("Tables that can never produce anything", BRASS[3]),
            ("Structure chests that get no integrated loot", lib.ACCENT)]


def console(w, h):
    """A dark terminal: the prompt and the command, then abstract report lines in the colours of the findings."""
    t = lib.panel(w, h, lib.RIM, lib.BORDER_DARK, 2)
    x = 18
    end = lib.font_text(t, x, 18, ">", lib.TEXT_DIM, 2, shadow=False)
    end = lib.font_text(t, end + 12, 18, "/lootr verify rolls 5", lib.ACCENT_LIGHT, 2, shadow=False)
    t.rect(end + 8, 18, 10, 14, lib.ACCENT_LIGHT)                   # the cursor
    t.rect(x, 50, w - 2 * x, 2, lib.PANEL_DARK)
    for i, ((_, colour), n) in enumerate(zip(FINDINGS, (150, 190, 210, 130, 170))):
        y = 76 + i * 48
        t.rect(x, y, 12, 12, colour)
        t.rect(x + 24, y + 4, n, 4, IRON[4])
        t.rect(x + 24, y + 4, n // 3, 4, IRON[5])
    return t


def card_verify():
    tw = W - 2 * MARGIN
    row_h, row_gap, con_w = 60, 8, 300
    list_h = 5 * row_h + 4 * row_gap
    rows_x = MARGIN + con_w + 24
    rows_w = W - MARGIN - rows_x
    text = ("Reads the loot tables, structure templates and worldgen files of the pack and writes a report to "
            "logs/lootr/ as Markdown and JSON, grouped by the mod responsible.")
    ty = TOP + list_h + 24
    h = ty + len(lines_of(text, tw)) * LINE + 16 + BOTTOM - LINE
    c = new_card(h, "A loot table check for your whole pack", 41)
    shadowed(c, MARGIN, TOP, console(con_w, list_h))
    for i, (label, edge) in enumerate(FINDINGS):
        r = row_panel(rows_w, row_h, edge)
        row_lines(r, 6 + 18, lib.wrap(label, rows_w - 6 - 18 - 18, 2))
        shadowed(c, rows_x, TOP + i * (row_h + row_gap), r)
    para(c, MARGIN, ty, text, tw, lib.TEXT_DIM)
    return c


# ---------------------------------------------------------------------------
# 5. One jar instead of eleven
# ---------------------------------------------------------------------------

JAR_FONT = {"J": ("..#", "..#", "..#", "#.#", ".#."), "A": (".#.", "#.#", "###", "#.#", "#.#"),
            "R": ("##.", "#.#", "##.", "#.#", "#.#")}


def jar_icon(k=3):
    """A small jar file: a page with a folded corner, JAR in a 3 x 5 pixel font, in the iron greys."""
    iw, ih, cut = 13, 14, 4
    body = {(x, y) for y in range(ih) for x in range(iw) if not (x >= iw - cut and y < x - (iw - cut) + 1)}
    ring = {(x + dx, y + dy) for (x, y) in body for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))} - body
    c = Canvas(iw + 2, ih + 2)
    for (x, y) in ring:
        c.set(x + 1, y + 1, IRON[1])
    for (x, y) in body:
        col = IRON[4]
        if x == 0 or y == 0:
            col = IRON[5]
        if x == iw - 1 or y == ih - 1:
            col = IRON[3]
        if x >= iw - cut and y <= cut and y > x - (iw - cut):
            col = IRON[3]                                           # the folded flap
        c.set(x + 1, y + 1, col)
    for n, ch in enumerate("JAR"):
        for j, rowbits in enumerate(JAR_FONT[ch]):
            for i, bit in enumerate(rowbits):
                if bit == "#":
                    c.set(2 + n * 4 + i, 8 + j, IRON[1])
    return lib.scale_nn(c, k)


def logo_tile(k=4):
    """The mod logo in a brass frame with a hard shadow."""
    img = lib.scale_nn(lib.g.logo(), k)
    size = img.w + 12
    t = lib.panel(size, size, lib.RIM, lib.BORDER, 4)
    t.hline(0, 0, size, lib.BORDER_LIGHT)
    t.vline(0, 0, size, lib.BORDER_LIGHT)
    lib.paste(t, img, 6, 6)
    out = Canvas(size + 6, size + 6)
    shadowed(out, 0, 0, t, 6)
    return out


def card_one_jar():
    tw = W - 2 * MARGIN
    arrow_zone, right_w = 100, 320
    left_w = tw - arrow_zone - right_w
    box_h = 224
    bl = ["One config file: config/lootr.json",
          "Existing worlds keep working: the mod id is still lootr and saved inventories are read as before.",
          "Do not run old and new side by side: remove the old jars first."]
    by = TOP + box_h + 26
    n = sum(len(lines_of(t, tw - 24)) for t in bl)
    h = by + n * LINE + 2 * 12 + 16 + BOTTOM - LINE
    c = new_card(h, "One jar instead of eleven", 53)
    lib.paste(c, bay(left_w, box_h), MARGIN, TOP)
    lib.paste(c, bay(right_w, box_h), W - MARGIN - right_w, TOP)
    # eleven jar files, six over five, centred in the left bay under their label
    jar = jar_icon(3)
    label = "Lootr + Loot Integrations + 9 add-ons"
    lib.text_centred(c, MARGIN + left_w // 2, TOP + 20, label, lib.TEXT_DIM, 2)
    gap = 16
    rows = [6, 5]
    top = TOP + 20 + 14 + (box_h - 34 - 2 * jar.h - gap) // 2 + 2
    for r, count in enumerate(rows):
        total = count * jar.w + (count - 1) * gap
        x = MARGIN + (left_w - total) // 2
        for i in range(count):
            lib.paste(c, jar, x + i * (jar.w + gap), top + r * (jar.h + gap))
    # the arrow, then the logo under its name
    arr = lib.arrow(72, 4)
    lib.paste_centred(c, arr, MARGIN + left_w + arrow_zone // 2, TOP + (box_h - arr.h) // 2)
    rx = W - MARGIN - right_w
    lib.text_centred(c, rx + right_w // 2, TOP + 20, "Lootr Remastered", BRASS[4], 2)
    tile = logo_tile(4)
    lib.paste_centred(c, tile, rx + right_w // 2, TOP + 20 + 14 + (box_h - 34 - tile.h) // 2 + 2)
    for x, y, big in ((rx + 24, TOP + 70, True), (rx + right_w - 40, TOP + 160, True), (rx + 40, TOP + 180, False)):
        lib.sparkle(c, x, y, 4, big)
    bullets(c, MARGIN, by, bl, tw, lib.TEXT, 12)
    return c


# ---------------------------------------------------------------------------
# 6. What you need
# ---------------------------------------------------------------------------

REQUIRED = ["Minecraft 1.21.1", "Fabric Loader 0.17.0 or newer", "Java 21", "Fabric API", "Cloth Config API"]
OPTIONAL = [("Item Obliterator", "item bans"), ("Mod Menu", "config screen"), ("Jade", "tooltips")]


def card_install():
    col_w, row_h, row_gap = 440, 52, 10
    right_x = W - MARGIN - col_w
    cap_y = TOP + 2
    rows_y = cap_y + 34
    list_h = 5 * row_h + 4 * row_gap
    closing = "Install on the server and on every client. NeoForge is not supported."
    strip = lib.panel(W - 2 * MARGIN, 26 + 20 * len(lib.wrap(closing, W - 2 * MARGIN - 40, 2)), lib.PANEL_DARK, lib.BORDER, 2, ornaments=True)
    sy = rows_y + list_h + 28
    h = sy + strip.h + 6 + BOTTOM + 4
    c = new_card(h, "What you need", 61)
    lib.font_text(c, MARGIN, cap_y, "REQUIRED", BRASS[4], 2)
    lib.font_text(c, right_x, cap_y, "OPTIONAL", lib.ACCENT_LIGHT, 2)
    for i, text in enumerate(REQUIRED):
        r = row_panel(col_w, row_h, lib.BORDER)
        row_lines(r, 6 + 18, [text])
        shadowed(c, MARGIN, rows_y + i * (row_h + row_gap), r)
    for i, (name, what) in enumerate(OPTIONAL):
        r = row_panel(col_w, row_h, lib.ACCENT)
        segments(r, 6 + 18, (row_h - 14) // 2, [(name, lib.TEXT), ("  -  " + what, lib.TEXT_DIM)])
        shadowed(c, right_x, rows_y + i * (row_h + row_gap), r)
    for i, line in enumerate(lib.wrap(closing, strip.w - 40, 2)):
        lib.text_centred(strip, strip.w // 2, 15 + 20 * i, line, lib.TEXT, 2)
    shadowed(c, MARGIN, sy, strip, 6)
    return c


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

IMAGES = [
    ("card_per_player", card_per_player),
    ("card_extra_loot", card_extra_loot),
    ("card_item_bans", card_item_bans),
    ("card_verify", card_verify),
    ("card_one_jar", card_one_jar),
    ("card_install", card_install),
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
