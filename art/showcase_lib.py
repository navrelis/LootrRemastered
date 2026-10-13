#!/usr/bin/env python3
"""Lootr Remastered -- shared library for the CurseForge showcase images.

Everything a showcase script needs to draw a card or an isometric scene:

    1. Sources      the Minecraft client jar (read at run time, nothing is extracted),
                    the mod's shipped textures, the trophy model
    2. Palette      colours derived from generate.py's ramps and its logo tile
    3. Canvas help  paste, scale, mix, save
    4. Text         the vanilla font (ascii.png out of the jar), width, word wrap
    5. Cards        card, panel, chip, heading, label_box, sparkle, arrow
    6. Scenes       place blocks and the mod's containers by block coordinates and
                    render them together with generate.render (a z-buffer renderer)

Only the Python standard library is used.  Geometry and PNG code live in generate.py and
are imported from there, not copied.

A scene in ten lines:

    import showcase_lib as lib
    sc = lib.scene()
    lib.add_floor(sc, 3, 3, ["stone_bricks"])
    lib.add_chest(sc, 0, 0, 2)
    lib.add_chest(sc, 2, 0, 0, looted=True)
    img = lib.render_scene(sc, k=4)                 # transparent background
    out = lib.Canvas(560, 340, lib.PANEL)
    lib.paste(out, img, (560 - img.w) // 2, 40)
    lib.paste(out, lib.label_box("Loot Chest"), 8, 8)
    lib.save(out, "example")

Block coordinates: x east, y up, z south, one unit = one block.  The camera looks from the
south-east, so the faces you see are top, south (left on screen) and east (right on screen).
"""
import json
import math
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import generate as g                                    # noqa: E402
from generate import (Canvas, TIMBER, ASH, BRASS, IRON, GEM, VOID, RUBY, RUST, SHELL, HUSK,   # noqa: E402,F401
                      CLAY, PAPER, SAND, GRAVEL, INK, rgb)

# ---------------------------------------------------------------------------
# 1. Sources
# ---------------------------------------------------------------------------

OUT = os.path.join(g.ROOT, "docs", "curseforge", "img")
JAR_ENV = "LOOTR_MC_JAR"
JAR_DIR = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "fabric-loom", "1.21.1")
JAR_NAMES = ("minecraft-client.jar", "minecraft-client-only.jar", "minecraft-merged.jar")

_cache = {}


def mc_jar():
    """Path of the Minecraft 1.21.1 client jar (LOOTR_MC_JAR, else the one Loom cached); exits if there is none."""
    if "jar" not in _cache:
        tried = [os.environ[JAR_ENV]] if os.environ.get(JAR_ENV) else [os.path.join(JAR_DIR, n) for n in JAR_NAMES]
        found = [p for p in tried if os.path.isfile(p)]
        if not found:
            sys.exit("No Minecraft 1.21.1 client jar found (tried: %s).\nSet the environment variable %s to the "
                     "path of a 1.21.1 client jar." % (", ".join(tried), JAR_ENV))
        _cache["jar"] = found[0]
    return _cache["jar"]


def _to_canvas(data):
    w, h, rows = g.read_png(data)
    c = Canvas(w, h)
    c.px = [[p if p[3] else None for p in row] for row in rows]     # alpha 0 -> None, as Canvas expects
    return c


def vanilla(path):
    """A vanilla texture as a Canvas, e.g. vanilla("block/stone_bricks.png"); animated strips give their first frame."""
    key = ("mc", path)
    if key not in _cache:
        if "zip" not in _cache:
            _cache["zip"] = zipfile.ZipFile(mc_jar())
        try:
            data = _cache["zip"].read("assets/minecraft/textures/" + path)
        except KeyError:
            sys.exit("%s has no assets/minecraft/textures/%s" % (mc_jar(), path))
        c = _to_canvas(data)
        if c.h > c.w and c.h % c.w == 0:
            c = c.crop(0, 0, c.w, c.w)
        _cache[key] = c
    return _cache[key]


def lootr(name):
    """A shipped texture of the mod as a Canvas, e.g. lootr("chest_opened") or lootr("block/barrel_top")."""
    key = ("lootr", name)
    if key not in _cache:
        with open(os.path.join(g.TEX, *name.split("/")) + ".png", "rb") as f:
            _cache[key] = _to_canvas(f.read())
    return _cache[key]


def lootr_names():
    """Every shipped texture name (as lootr() takes it), sorted."""
    out = []
    for folder, _, files in os.walk(g.TEX):
        for f in files:
            if f.endswith(".png"):
                out.append(os.path.relpath(os.path.join(folder, f), g.TEX).replace("\\", "/")[:-4])
    return sorted(out)


def trophy_model():
    """The shipped models/block/trophy.json as a dict."""
    if "trophy" not in _cache:
        with open(os.path.join(g.ASSETS, "lootr", "models", "block", "trophy.json"), encoding="utf-8") as f:
            _cache["trophy"] = json.load(f)
    return _cache["trophy"]


# ---------------------------------------------------------------------------
# 2. Palette
# ---------------------------------------------------------------------------


def mix(a, b, t):
    """A flat colour between a and b (t = 0 gives a, 1 gives b)."""
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3)) + (255,)


_logo = g.logo()                    # the slate/teal of the cards is the logo tile's, read from the logo itself
PANEL = _logo.get(3, 16)            # night blue-teal: the card background
PANEL_MID = _logo.get(1, 16)        # lit bevel: chips, raised strips
PANEL_DARK = _logo.get(30, 16)      # shaded bevel: recessed strips, tiles
RIM = _logo.get(0, 16)              # near black: drop shadows, the name box
BORDER = BRASS[3]                   # card frame
BORDER_DARK = BRASS[1]
BORDER_LIGHT = BRASS[4]
TEXT = PAPER[4]                     # body text
TEXT_DIM = GRAVEL[4]                # captions, secondary text
ACCENT = GEM[2]                     # teal: "not looted yet", chips
ACCENT_LIGHT = GEM[3]
SPENT = IRON[5]                     # grey: "looted"
BACKDROP = mix(PANEL, PANEL_DARK, 0.5)  # flat background of the isometric images

# ---------------------------------------------------------------------------
# 3. Canvas helpers
# ---------------------------------------------------------------------------


def scale_nn(canvas, k):
    """Nearest-neighbour upscale by the whole factor k."""
    assert k == int(k) and k >= 1, k
    return canvas.scaled(int(k))


def paste(dst, src, x, y):
    """Draw src onto dst at (x, y); transparent pixels are skipped, half-transparent ones blended."""
    for sy in range(max(0, -y), min(src.h, dst.h - y)):
        row, out = src.px[sy], dst.px[y + sy]
        for sx in range(max(0, -x), min(src.w, dst.w - x)):
            p = row[sx]
            if p is None or p[3] == 0:
                continue
            if p[3] < 255 and out[x + sx] is not None:
                out[x + sx] = mix(out[x + sx], p, p[3] / 255.0)
            else:
                out[x + sx] = (p[0], p[1], p[2], 255)


def paste_centred(dst, src, cx, y):
    """paste() with src centred on the column cx; returns the x it was drawn at."""
    x = int(cx - src.w // 2)
    paste(dst, src, x, y)
    return x


def checker(w, h, k=8, a=None, b=None):
    """A two-tone checker of k-pixel squares (the background of flat texture sheets)."""
    a, b = a or PANEL_DARK, b or mix(PANEL_DARK, PANEL, 0.6)
    c = Canvas(w, h)
    for y in range(h):
        c.px[y] = [a if ((x // k) + (y // k)) % 2 == 0 else b for x in range(w)]
    return c


def check(canvas, name="image"):
    """Assert the rules every showcase image obeys: at most 1000 wide, opaque, no missing-texture magenta."""
    assert canvas.w <= 1000, "%s is %d px wide" % (name, canvas.w)
    for y, row in enumerate(canvas.px):
        for x, p in enumerate(row):
            assert p is not None and p[3] == 255, "%s has a transparent pixel at %d,%d" % (name, x, y)
            assert not (p[0] > 240 and p[1] < 16 and p[2] > 240), "%s has a magenta pixel at %d,%d" % (name, x, y)


def set_out(folder):
    """Change the folder save() writes to."""
    global OUT
    OUT = folder


def save(canvas, name, out=None):
    """Check canvas and write it as <out or OUT>/<name>.png; returns (path, file bytes)."""
    check(canvas, name)
    data = g.png_bytes(canvas)
    folder = out or OUT
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, name + ".png")
    with open(path, "wb") as f:
        f.write(data)
    return path, data


# ---------------------------------------------------------------------------
# 4. Text: the vanilla font, read from the jar
# ---------------------------------------------------------------------------
# font/ascii.png is a 16 x 16 grid of 8 x 8 cells indexed by character code.  A glyph is as wide
# as its last used column + 1, a space advances 4 pixels and one empty column separates glyphs,
# which is how the game lays out this bitmap font.


def _glyphs():
    if "font" not in _cache:
        sheet = vanilla("font/ascii.png")
        cell = sheet.w // 16
        table = {}
        for code in range(256):
            gx, gy = code % 16 * cell, code // 16 * cell
            dots = [(x, y) for y in range(cell) for x in range(cell) if sheet.px[gy + y][gx + x] is not None]
            table[code] = (dots, max(x for x, _ in dots) + 1 if dots else 0)
        _cache["font"] = table
    return _cache["font"]


def _glyph(ch):
    code = ord(ch)
    # ascii.png only follows Unicode up to 126; anything else draws as '?', as an unknown glyph would
    return _glyphs()[code if 32 <= code <= 126 else ord("?")]


def _advance(ch):
    return 4 if ch == " " else _glyph(ch)[1] + 1


def text_width(string, scale=1):
    """Width in pixels of string in the vanilla font at a whole scale."""
    return max(0, sum(_advance(ch) for ch in string) - 1) * scale if string else 0


def font_text(canvas, x, y, string, colour=None, scale=1, shadow=True):
    """Draw string in the vanilla font; shadow=True is the game's drop shadow, a colour gives that shadow colour.

    Returns the x just after the text.  A line is 8 * scale high (9 with descenders).
    """
    colour = colour or TEXT
    if shadow:
        dark = shadow if isinstance(shadow, tuple) else (colour[0] // 4, colour[1] // 4, colour[2] // 4, 255)
        font_text(canvas, x + scale, y + scale, string, dark, scale, False)
    for ch in string:
        if ch != " ":
            for dx, dy in _glyph(ch)[0]:
                canvas.rect(x + dx * scale, y + dy * scale, scale, scale, colour)
        x += _advance(ch) * scale
    return x - scale


def text_centred(canvas, cx, y, string, colour=None, scale=1, shadow=True):
    """font_text() centred on the column cx."""
    return font_text(canvas, int(cx - text_width(string, scale) // 2), y, string, colour, scale, shadow)


def wrap(string, max_px, scale=1, breaks=" "):
    """Split string into lines no wider than max_px; breaks lists the characters a line may end after."""
    words, cur = [], ""
    for ch in string:
        cur += ch
        if ch in breaks:
            words.append(cur)
            cur = ""
    if cur:
        words.append(cur)
    lines, line = [], ""
    for word in words:
        if line and text_width((line + word).rstrip(" "), scale) > max_px:
            lines.append(line.rstrip(" "))
            line = word
        else:
            line += word
    if line.strip(" "):
        lines.append(line.rstrip(" "))
    return lines


def paragraph(canvas, x, y, string, max_px, colour=None, scale=1, shadow=True, leading=3, centred=False):
    """Draw string word-wrapped to max_px (centred within it if asked); returns the y below the last line."""
    for line in wrap(string, max_px, scale):
        if centred:
            text_centred(canvas, x + max_px // 2, y, line, colour, scale, shadow)
        else:
            font_text(canvas, x, y, line, colour, scale, shadow)
        y += (8 + leading) * scale
    return y


# ---------------------------------------------------------------------------
# 5. Cards
# ---------------------------------------------------------------------------


def _noise(seed):
    """A tiny fixed generator, so the dither is the same on every machine and Python version."""
    state = [seed * 2654435761 % 4294967296 or 1]

    def nxt(n):
        state[0] = (state[0] * 1103515245 + 12345) % 2147483648
        return (state[0] >> 8) % n
    return nxt


def panel(w, h, fill=None, border=None, edge=2, ornaments=False):
    """A flat framed box: fill inside an edge-pixel border, optionally with a small light corner mark."""
    fill, border = fill or PANEL_DARK, border or BORDER
    c = Canvas(w, h, fill)
    for i in range(edge):
        c.frame(i, i, w - 2 * i, h - 2 * i, border)
    if ornaments:
        n = edge * 2
        for cx, cy, dx, dy in ((edge + 2, edge + 2, 1, 1), (w - edge - 3, edge + 2, -1, 1),
                               (edge + 2, h - edge - 3, 1, -1), (w - edge - 3, h - edge - 3, -1, -1)):
            for i in range(n):
                for j in range(edge):
                    c.set(cx + dx * i, cy + dy * j, BORDER_LIGHT)
                    c.set(cx + dx * j, cy + dy * i, BORDER_LIGHT)
    return c


def card(w, h, dither=True, seed=7, fill=None):
    """The framed card: brass border with corner ornaments round a slate panel (dither adds a blocky pixel texture)."""
    fill = fill or PANEL
    c = Canvas(w, h, fill)
    if dither:
        rnd = _noise(seed)
        tones = (mix(fill, PANEL_DARK, 0.45), mix(fill, PANEL_DARK, 0.2), mix(fill, PANEL_MID, 0.18))
        for y in range(0, h, 4):
            for x in range(0, w, 4):
                r = rnd(16)
                if r < 6:
                    c.rect(x, y, 4, 4, tones[r % 3])
    # frame: dark lip, brass band with a lit top-left edge, dark lip, then a shadow line on the panel
    for i, col in enumerate((BORDER_DARK, BORDER_DARK, BORDER, BORDER, BORDER, BORDER, BORDER_DARK, BORDER_DARK, RIM, RIM)):
        c.frame(i, i, w - 2 * i, h - 2 * i, col)
    c.hline(2, 2, w - 4, BORDER_LIGHT)
    c.vline(2, 2, h - 4, BORDER_LIGHT)
    c.hline(2, 3, w - 5, BORDER_LIGHT)
    c.vline(3, 2, h - 5, BORDER_LIGHT)
    # corner ornaments: a brass angle with a pale stud, sitting just inside the frame
    for cx, cy, dx, dy in ((14, 14, 1, 1), (w - 15, 14, -1, 1), (14, h - 15, 1, -1), (w - 15, h - 15, -1, -1)):
        for i in range(20):
            for j in range(4):
                c.set(cx + dx * i, cy + dy * j, BORDER)
                c.set(cx + dx * j, cy + dy * i, BORDER)
        for i in range(8):
            for j in range(8):
                c.set(cx + dx * i, cy + dy * j, BORDER_LIGHT)
        for i in range(4):
            for j in range(4):
                c.set(cx + dx * (2 + i), cy + dy * (2 + j), BRASS[5])
    return c


def chip(text, fill=None, border=None, colour=None, scale=2, pad=(14, 10)):
    """A tag chip: text in a bordered box (pass fill=BORDER, colour=INK for the solid brass kind)."""
    fill, border, colour = fill or PANEL_MID, border or ACCENT, colour or TEXT
    c = panel(text_width(text, scale) + 2 * pad[0], 7 * scale + 2 * pad[1], fill, border, 2)
    font_text(c, pad[0], pad[1], text, colour, scale, shadow=False)
    return c


def chips_row(chips, gap=16):
    """Several chips side by side on a transparent canvas."""
    c = Canvas(sum(ch.w for ch in chips) + gap * (len(chips) - 1), max(ch.h for ch in chips))
    x = 0
    for ch in chips:
        paste(c, ch, x, 0)
        x += ch.w + gap
    return c


def heading(text, scale=4, colour=None, shade=None):
    """Big pixel lettering with a darker copy offset one font pixel down-right; returns a transparent canvas."""
    colour, shade = colour or BORDER_LIGHT, shade or BORDER_DARK
    c = Canvas(text_width(text, scale) + scale, 9 * scale)
    font_text(c, 0, 0, text, colour, scale, shadow=shade)
    return c


def label_box(text, scale=2, colour=None):
    """The dark name box that sits top left on the block images."""
    c = panel(text_width(text, scale) + 22, 8 * scale + 14, RIM, BORDER_DARK, 2)
    c.rect(2, 2, 4, c.h - 4, BORDER)                    # a brass tab, so the box reads as a label
    font_text(c, 13, 7, text, colour or TEXT, scale, shadow=True)
    return c


def sparkle(canvas, x, y, k=4, big=True):
    """A teal glint like the ones on the logo: a plus of k-pixel blocks (big) or a single block."""
    if big:
        for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1)):
            canvas.rect(x + dx * k, y + dy * k, k, k, GEM[2])
        canvas.rect(x, y, k, k, GEM[4])
    else:
        canvas.rect(x, y, k, k, GEM[1])


def arrow(length=96, k=4, colour=None, shade=None):
    """A blocky arrow pointing right, drawn in k-pixel steps with a dark outline; returns a transparent canvas."""
    colour, shade = colour or BORDER_LIGHT, shade or BORDER_DARK
    head = 5                                            # half height of the head in steps
    c = Canvas(length + 2 * k, (2 * head + 1) * k + 2 * k)
    body = set()
    cells = length // k
    for i in range(cells - head):
        for j in (-1, 0, 1):
            body.add((i, j))
    for i in range(head + 1):                           # the head narrows by one step per column
        for j in range(-(head - i), head - i + 1):
            body.add((cells - head - 1 + i, j))
    for (i, j) in body:
        lit = (i, j - 1) not in body
        c.rect(k + i * k, k + (j + head) * k, k, k, BRASS[5] if lit else colour)
    for (i, j) in body:
        for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (i + di, j + dj) not in body:
                c.rect(k + (i + di) * k, k + (j + dj + head) * k, k, k, shade)
    return c


# ---------------------------------------------------------------------------
# 6. Scenes
# ---------------------------------------------------------------------------
# generate.render is an orthographic renderer with a z-buffer (not a painter's sort), so any
# number of objects can go into one call and hide each other correctly.  It also shades faces
# the way the game does: top full, north/south 0.82, east/west 0.62, underside 0.5.
#
# A scene is the list render() takes: (texture, quads, transform[, cull]).  The add_* functions
# append to it with every point already moved to its place, in texels (16 per block).

YAW, PITCH = 45.0, 30.0             # the classic 2:1 isometric view from the south-east
FACING = {"south": 0, "east": 1, "north": 2, "west": 3}     # quarter turns that bring a south front there


def scene():
    """A new, empty scene."""
    return []


def _captured(fn, *args):
    """The quad sets one of generate.py's *_scene functions would render, without rendering them.

    Those functions hold the model geometry (box sizes, poses, UV offsets) and end in a call to
    render(); swapping that call out for a moment gives the geometry without copying it.
    """
    keep = g.render
    g.render = lambda sets, **kw: sets
    try:
        return fn(*args)
    finally:
        g.render = keep


def _turn(p, turns):
    x, y, z = p
    for _ in range(turns % 4):
        x, z = z, -x                                    # a quarter turn about the y axis, +z going to +x
    return (x, y, z)


def _place(sc, sets, x, y, z, turns=0, zoom=1.0, pivot=(8, 0, 8)):
    """Append quad sets to the scene, turned about the block's vertical axis and moved to block x, y, z."""
    for entry in sets:
        tex, quads, xf = entry[0], entry[1], entry[2]
        moved = []
        for quad in quads:
            pts = []
            for p in quad[0]:
                q = xf(p)
                q = _turn((q[0] - pivot[0], q[1] - pivot[1], q[2] - pivot[2]), turns)
                pts.append((q[0] * zoom + pivot[0] + 16 * x, q[1] * zoom + pivot[1] + 16 * y, q[2] * zoom + pivot[2] + 16 * z))
            # an explicit normal (JSON model elements carry one) has to turn with the points
            moved.append((pts, quad[1]) + ((_turn(quad[2], turns),) if len(quad) > 2 else ()))
        sc.append((tex, moved, g.ident) + tuple(entry[3:]))


def _tex(t):
    return vanilla("block/%s.png" % t) if isinstance(t, str) else t


def add_cube(sc, x, y, z, top, side=None, bottom=None, height=16):
    """A full cube by vanilla block texture name (or Canvas); height < 16 gives a slab showing the texture's top rows."""
    top = _tex(top)
    side, bottom = _tex(side) if side is not None else top, _tex(bottom) if bottom is not None else top
    frm, to = (0, 0, 0), (16, height, 16)
    wall = (0, 0, 16, height)
    sets = [(side, g.element_quads(frm, to, {f: wall for f in ("north", "south", "west", "east")}), g.ident),
            (top, g.element_quads(frm, to, {"up": (0, 0, 16, 16)}), g.ident),
            (bottom, g.element_quads(frm, to, {"down": (0, 0, 16, 16)}), g.ident)]
    _place(sc, sets, x, y, z)


def add_floor(sc, nx, nz, blocks, y=-1, height=8, seed=3, x0=0, z0=0):
    """An nx by nz plate of ground under y = 0, each block drawn from the list `blocks` by a fixed seed.

    An entry of blocks is a texture name or a (top, side, bottom) tuple; height is the plate's thickness in texels.
    """
    rnd = _noise(seed)
    for z in range(z0, z0 + nz):
        for x in range(x0, x0 + nx):
            b = blocks[rnd(len(blocks))]
            b = (b,) if isinstance(b, str) else b
            add_cube(sc, x, y + (16 - height) / 16.0, z, *b, height=height)


def add_chest(sc, x, y, z, looted=False, trapped=False, lid=0.0, facing="south"):
    """The loot chest as the mod's renderer poses it; lid 0..1 opens it, facing is where the lock points."""
    name = "chest" + ("_trapped" if trapped else "") + ("_opened" if looted else "")
    _place(sc, _captured(g.chest_scene, lootr(name), lid), x, y, z, FACING[facing])


def add_barrel(sc, x, y, z, looted=False, open_top=False):
    """The loot barrel standing upright (facing up); open_top shows the lid-off texture."""
    p = "block/%sbarrel_" % ("opened_" if looted else "")
    sets = _captured(g.cube_scene, lootr(p + ("top_open" if open_top else "top")), lootr(p + "side"), lootr(p + "bottom"))
    _place(sc, sets, x, y, z)


def add_shulker(sc, x, y, z, looted=False, lid=0.0):
    """The loot shulker box, opening upwards; lid 0..1 lifts and twists the lid as the game does."""
    _place(sc, _captured(g.shulker_scene, lootr("shulker_opened" if looted else "shulker"), lid), x, y, z)


def add_pot(sc, x, y, z, looted=False, facing="south"):
    """The decorated loot pot: sealed with the brass cap, or the broken pot the mod draws once it is looted.

    The four walls of the sealed pot are vanilla's plain pot side, which is what the mod's renderer uses
    when the pot carries no sherds.
    """
    if looted:
        sets = _captured(g.pot_open_scene, lootr("entity/loot_pot_open"))
    else:
        sets = _captured(g.pot_scene, lootr("entity/loot_pot"), vanilla("entity/decorated_pot/decorated_pot_side.png"))
    _place(sc, sets, x, y, z, FACING[facing])


def suspicious_texture(kind="sand", looted=False, dusted=0):
    """The one texture a suspicious block shows: vanilla's dusted stage, or the mod's brushed-out one once looted."""
    return lootr("block/suspicious_%s_open" % kind) if looted else vanilla("block/suspicious_%s_%d.png" % (kind, dusted))


def add_suspicious(sc, x, y, z, kind="sand", looted=False, dusted=0, height=16):
    """Suspicious sand or gravel (kind) as a cube; height < 16 sets it into a floor plate."""
    add_cube(sc, x, y, z, suspicious_texture(kind, looted, dusted), height=height)


def add_frame(sc, x, y, z, looted=False, facing="south", item="item/elytra.png"):
    """The loot item frame hanging in block x, y, z on the wall behind it; brass with its item, or iron and empty.

    facing is the way the frame looks (south = it hangs on the block to the north).
    """
    wood = lootr("block/silver_planks" if looted else "block/gold_planks")
    back = vanilla("block/item_frame.png")
    sets = [(wood if entry[0] is wood else back,) + tuple(entry[1:]) for entry in _captured(g.frame_scene, wood)]
    if not looted and item:
        # the framed item is drawn at half size, flat, just proud of the back plate
        sets.append((vanilla(item), g.element_quads((4, 4, 15), (12, 12, 15.4), {"north": (0, 0, 16, 16)}), g.ident))
    # the template model sits against the block's south side; half a turn hangs it on the north wall
    _place(sc, sets, x, y, z, FACING[facing] + 2, pivot=(8, 8, 8))


def add_trophy(sc, x, y, z, facing="south"):
    """The Centennial Trophy from the shipped trophy.json (its front is north in the model)."""
    sets = _captured(g.model_scene, trophy_model(), lootr("block/trophy"), 32)
    _place(sc, sets, x, y, z, FACING[facing] + 2)


def add_rail(sc, x, y, z, along="x"):
    """A straight vanilla rail lying on top of the block below, running along x or z."""
    quads = g.element_quads((0, 1, 0), (16, 1, 16), {"up": (0, 0, 16, 16)})
    _place(sc, [(vanilla("block/rail.png"), quads, g.ident)], x, y, z, 1 if along == "x" else 0)


def add_minecart(sc, x, y, z, looted=False, along="x"):
    """Minecart with Loot Chest standing on a rail in block x, y, z.

    The cart is vanilla's MinecartModel (boxes and poses of createBodyLayer) with entity/minecart.png,
    the chest sits where LootrChestCartRenderer puts it: scaled 0.75, turned a quarter, 6 texels up.
    """
    pi = math.pi
    box = (-8, -9, -1, 16, 8, 2)
    cart = (g.cube_quads(0, 10, -10, -8, -1, 20, 16, 2, offset=(0, 4.1, 0), rot=(pi / 2, 0, 0))
            + g.cube_quads(0, 0, *box, offset=(-9, 4, 0), rot=(0, pi * 1.5, 0))
            + g.cube_quads(0, 0, *box, offset=(9, 4, 0), rot=(0, pi / 2, 0))
            + g.cube_quads(0, 0, *box, offset=(0, 4, -7), rot=(0, pi, 0))
            + g.cube_quads(0, 0, *box, offset=(0, 4, 7)))

    def cart_xf(p):                                     # the entity renderer flips x and y; +1: a cart rides a texel up
        return (8 - p[0], 7 - p[1], 8 + p[2])

    def chest_xf(p):                                    # rotate 90 about y, centre, scale 0.75, lift into the cart
        return (8 + (p[2] - 8) * 0.75, 7 + p[1] * 0.75, 8 + (8 - p[0]) * 0.75)

    name = "chest_opened" if looted else "chest"
    sets = [(vanilla("entity/minecart.png"), cart, cart_xf)]
    sets += [(e[0], e[1], chest_xf) for e in _captured(g.chest_scene, lootr(name), 0.0)]
    _place(sc, sets, x, y, z, 0 if along == "x" else 3)


def _basis():
    th, ph = math.radians(YAW), math.radians(PITCH)
    return ((math.cos(th), 0.0, -math.sin(th)),
            (-math.sin(th) * math.sin(ph), math.cos(ph), -math.cos(th) * math.sin(ph)))


def render_scene(sc, k=3, pad=2):
    """Render a scene to a tightly fitting canvas with a transparent background.

    k is the width of one texel in pixels (a block is 32 * k wide, 16 * k from top corner to side corner).
    The canvas gets a method project(x, y, z) -> (px, py) giving the pixel of a block coordinate, for captions.
    """
    scale = k * math.sqrt(2.0)                          # at 45 degrees a texel is exactly k pixels wide
    right, up = _basis()
    xs, ys = [], []
    for entry in sc:
        for quad in entry[1]:
            for p in quad[0]:
                xs.append(scale * (p[0] * right[0] + p[2] * right[2]))
                ys.append(-scale * (p[0] * up[0] + p[1] * up[1] + p[2] * up[2]))
    x0, y0 = math.floor(min(xs)) - pad, math.floor(min(ys)) - pad
    w, h = int(math.ceil(max(xs))) + pad - x0, int(math.ceil(max(ys))) + pad - y0
    # render() centres on a world point; choose the one that puts (x0, y0) at the canvas origin
    a, b = (w / 2.0 + x0) / scale, (-y0 - h / 2.0) / scale
    centre = tuple(a * right[i] + b * up[i] for i in range(3))
    key = (1, 2, 3, 0)                                  # a background object to recognise untouched pixels by
    img = g.render(sc, size=(w, h), yaw=YAW, pitch=PITCH, scale=scale, center=centre, bg=key)
    for row in img.px:
        for i, p in enumerate(row):
            if p is key:
                row[i] = None

    def project(bx, by, bz):
        p = (bx * 16.0, by * 16.0, bz * 16.0)
        return (int(round(scale * (p[0] * right[0] + p[2] * right[2]) - x0)),
                int(round(-scale * (p[0] * up[0] + p[1] * up[1] + p[2] * up[2]) - y0)))
    img.project = project
    return img
