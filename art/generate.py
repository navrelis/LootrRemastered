#!/usr/bin/env python3
"""Lootr Remastered -- original pixel art generator.

Original artwork for Lootr Remastered; no pixels derived from Lootr or
Minecraft textures.

Everything the mod ships as an image (plus the trophy block model) is defined
in this one file as palettes, hand-authored character grids and a few small
drawing helpers, and is written to its final place in the repository.  Running
the script again reproduces byte-identical files.

    python art/generate.py                 write textures, trophy model, logo, icons and previews
    python art/generate.py --check         additionally print a sha256 per written file
    python art/generate.py --no-previews   skip art/preview/ (faster)

Only the Python standard library is used.

Layout of this file
    1. PNG writer / reader
    2. Canvas (tiny drawing surface)
    3. Palettes
    4. Shared motifs (wood grain, seal, straps)
    5. Texture builders (barrel, planks, brushables, chest, shulker, pot, GUI)
    6. Trophy model + texture
    7. Logo
    8. Preview tooling (upscales, contact sheets, small software renderer)
    9. main()
"""
import hashlib
import json
import math
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "art")
ASSETS = os.path.join(ROOT, "common", "src", "main", "resources", "assets")
TEX = os.path.join(ASSETS, "lootr", "textures")
PREVIEW = os.path.join(ART, "preview")

# ---------------------------------------------------------------------------
# 1. PNG writer / reader (8-bit RGBA only on the way out)
# ---------------------------------------------------------------------------


def _chunk(tag, body):
    return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)


def png_bytes(canvas):
    """Encode a Canvas as an 8-bit RGBA PNG (filter 0, zlib level 9)."""
    raw = bytearray()
    for row in canvas.px:
        raw.append(0)
        for p in row:
            raw.extend(p if p is not None else (0, 0, 0, 0))
    return (b"\x89PNG\r\n\x1a\n"
            + _chunk(b"IHDR", struct.pack(">IIBBBBB", canvas.w, canvas.h, 8, 6, 0, 0, 0))
            + _chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + _chunk(b"IEND", b""))


def read_png(data):
    """Decode any non-interlaced PNG to (w, h, rows of RGBA tuples).  Used for verification."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, idat, plte, trns = 8, b"", None, None
    while pos < len(data):
        ln, typ = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + ln]
        assert zlib.crc32(typ + body) & 0xFFFFFFFF == struct.unpack(">I", data[pos + 8 + ln:pos + 12 + ln])[0], "bad CRC"
        pos += 12 + ln
        if typ == b"IHDR":
            w, h, bd, ct, _, _, il = struct.unpack(">IIBBBBB", body)
        elif typ == b"PLTE":
            plte = body
        elif typ == b"tRNS":
            trns = body
        elif typ == b"IDAT":
            idat += body
    assert il == 0, "interlaced PNG not supported"
    raw = zlib.decompress(idat)
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    bpp = max(1, ch * bd // 8)
    stride = (w * ch * bd + 7) // 8
    rows, prev, p = [], bytearray(stride), 0
    for _ in range(h):
        ft = raw[p]
        line = bytearray(raw[p + 1:p + 1 + stride])
        p += 1 + stride
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if ft == 1:
                line[i] = (line[i] + a) & 255
            elif ft == 2:
                line[i] = (line[i] + b) & 255
            elif ft == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 255
            elif ft == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else (b if pb <= pc else c))) & 255
        rows.append(line)
        prev = line

    def sample(line, x):
        if bd == 8:
            return line[x]
        if bd == 16:
            return line[x * 2]
        return (line[(x * bd) // 8] >> (8 - bd - (x * bd) % 8)) & ((1 << bd) - 1)

    out = []
    for line in rows:
        r = []
        for x in range(w):
            if ct == 6:
                r.append(tuple(sample(line, x * 4 + i) for i in range(4)))
            elif ct == 2:
                r.append(tuple(sample(line, x * 3 + i) for i in range(3)) + (255,))
            elif ct == 3:
                i = sample(line, x)
                r.append(tuple(plte[i * 3:i * 3 + 3]) + ((trns[i] if trns and i < len(trns) else 255),))
            elif ct == 0:
                g = sample(line, x) * 255 // ((1 << min(bd, 8)) - 1)
                r.append((g, g, g, 255))
            else:
                g = sample(line, x * 2)
                r.append((g, g, g, sample(line, x * 2 + 1)))
        out.append(r)
    return w, h, out


# ---------------------------------------------------------------------------
# 2. Canvas
# ---------------------------------------------------------------------------


class Canvas:
    """A small RGBA pixel surface.  None means fully transparent."""

    def __init__(self, w, h, fill=None):
        self.w, self.h = w, h
        self.px = [[fill] * w for _ in range(h)]

    @staticmethod
    def from_grid(rows, palette):
        """Build a canvas from rows of characters; palette maps a character to a colour (or None)."""
        rows = [r.replace(" ", "") for r in rows]
        w = len(rows[0])
        assert all(len(r) == w for r in rows), [len(r) for r in rows]
        c = Canvas(w, len(rows))
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                c.px[y][x] = palette[ch]
        return c

    def copy(self):
        c = Canvas(self.w, self.h)
        c.px = [row[:] for row in self.px]
        return c

    def set(self, x, y, col):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = col

    def get(self, x, y):
        return self.px[y][x]

    def rect(self, x, y, w, h, col):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                self.set(xx, yy, col)

    def hline(self, x, y, n, col):
        self.rect(x, y, n, 1, col)

    def vline(self, x, y, n, col):
        self.rect(x, y, 1, n, col)

    def frame(self, x, y, w, h, col):
        self.hline(x, y, w, col)
        self.hline(x, y + h - 1, w, col)
        self.vline(x, y, h, col)
        self.vline(x + w - 1, y, h, col)

    def blit(self, src, dx, dy, keep_transparent=False):
        """Copy src onto this canvas.  Transparent source pixels are skipped unless keep_transparent."""
        for y in range(src.h):
            for x in range(src.w):
                p = src.px[y][x]
                if p is not None or keep_transparent:
                    self.set(dx + x, dy + y, p)

    def crop(self, x, y, w, h):
        c = Canvas(w, h)
        for yy in range(h):
            for xx in range(w):
                c.px[yy][xx] = self.px[y + yy][x + xx]
        return c

    def rot180(self):
        c = Canvas(self.w, self.h)
        c.px = [row[::-1] for row in self.px[::-1]]
        return c

    def flip_v(self):
        c = Canvas(self.w, self.h)
        c.px = [row[:] for row in self.px[::-1]]
        return c

    def flip_h(self):
        c = Canvas(self.w, self.h)
        c.px = [row[::-1] for row in self.px]
        return c

    def scaled(self, k):
        c = Canvas(self.w * k, self.h * k)
        for y in range(c.h):
            src = self.px[y // k]
            c.px[y] = [src[x // k] for x in range(c.w)]
        return c

    def over(self, bg):
        """Return a copy with transparent pixels replaced by bg (for previews)."""
        c = self.copy()
        for row in c.px:
            for x, p in enumerate(row):
                if p is None:
                    row[x] = bg
        return c


def rgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def ramp(*hexes):
    return [rgb(h) for h in hexes]


# ---------------------------------------------------------------------------
# 3. Palettes -- every ramp runs dark -> light; shadows lean cool, highlights warm
# ---------------------------------------------------------------------------

TIMBER = ramp("1b1319", "2a1c20", "3c2925", "523728", "6c4a2f", "88623b")   # sealed: dark walnut
ASH = ramp("231c20", "342a2b", "493c39", "625146", "7d6957", "99836b")      # looted: weathered, greyed
BRASS = ramp("46291a", "754b1d", "ab7d25", "d8ad38", "f3d975", "fff5c2")    # sealed: warm brass
IRON = ramp("121117", "1e1d26", "2d2c37", "403f4a", "5a5962", "7d7b82")     # looted: cold dark iron
GEM = ramp("0a3340", "0e6f76", "1cbfab", "6df1d4", "e3fff4")                # the teal seal gem
VOID = ramp("0c0a10", "1c1b22", "3a3a42")                                   # an empty gem socket
RUBY = ramp("440d16", "8a1a1f", "cf3a2a", "ff8a5c")                         # trapped: live
RUST = ramp("2e1519", "512426", "733831", "8f5346")                         # trapped: spent
SHELL = ramp("181322", "281f36", "3a2d4b", "513e62", "6f567c", "97799a")    # sealed shulker shell
HUSK = ramp("2a2430", "3f3646", "594c60", "75667b", "958699", "b4a7b4")     # looted shulker shell
CLAY = ramp("4a221f", "6a3228", "8a4733", "a65d3e", "c27a4f", "dc9a68")     # fired clay
PAPER = ramp("6f5334", "b3925f", "d9c08c", "efdfb4", "faf2d6")              # toast parchment
SAND = ramp("b89f70", "cdb987", "dacca0", "e5dbb4", "f0eaca")               # brushed sand
GRAVEL = ramp("4a474d", "656165", "827e7e", "9c9692", "b5aea7")             # brushed gravel
INK = rgb("0e0b10")                                                         # outlines


class Theme:
    """Everything that changes between 'not yet opened by you' and 'opened'."""

    def __init__(self, opened, trapped=False, shulker=False):
        self.opened, self.trapped = opened, trapped
        if shulker:
            self.wood = HUSK if opened else SHELL
        else:
            self.wood = ASH if opened else TIMBER
        self.metal = IRON if opened else BRASS
        # ring = the setting around a seal gem; red on trapped chests
        if trapped:
            r = RUST if opened else RUBY
            self.ring = [r[0], r[0], r[1], r[2], r[3], r[3]]
        else:
            self.ring = self.metal


# ---------------------------------------------------------------------------
# 4. Shared motifs
# ---------------------------------------------------------------------------

# Hand-drawn plank grain, four boards of three rows plus a seam row.
# 1 seam / joint, 2 dark streak, 3 body, 4 lit top edge.
GRAIN = [
    "3443444444434444",
    "3333322333333333",
    "3223333333333223",
    "1111111111111111",
    "4444244434444443",
    "3333233333322333",
    "3322233333333333",
    "1111111111111111",
    "3444444444444344",
    "3322333333333333",
    "3333332223333333",
    "1111111111111111",
    "4444444344444244",
    "3333223333333233",
    "2333333333332233",
    "1111111111111111",
]


def planks(w, h, wood, ox=0, oy=0, vertical=False):
    """Fill a w x h canvas with plank grain sampled from GRAIN (boards run along x unless vertical)."""
    c = Canvas(w, h)
    for y in range(h):
        for x in range(w):
            gx, gy = (y, x) if vertical else (x, y)
            c.px[y][x] = wood[int(GRAIN[(gy + oy) % 16][(gx + ox) % 16])]
    return c


def seal(c, x, y, theme, size=4):
    """The mod's signature: a gem in a metal setting (sealed) or an empty socket (looted).

    size 4 -> 2x2 gem, size 6 -> 4x4 gem.  Light comes from the top left.
    """
    r = theme.ring
    n = size
    # setting: a ring with clipped corners, lit top/left, shaded bottom/right
    for i in range(1, n - 1):
        c.set(x + i, y, r[4] if i == 1 else r[3])
        c.set(x, y + i, r[4] if i == 1 else r[3])
        c.set(x + i, y + n - 1, r[1])
        c.set(x + n - 1, y + i, r[2] if i == 1 else r[1])
    g = n - 2
    shades = {2: (4, 3, 1), 4: (4, 3, 3, 2, 2, 1, 1)}[g]     # by distance from the lit corner
    for j in range(g):
        for i in range(g):
            if theme.opened:
                # hollow: a dark pit with a faint lip on the far (bottom-right) inner edge
                col = VOID[1] if (i == g - 1 and j == g - 1) else VOID[0]
            else:
                col = GEM[shades[i + j]]
            c.set(x + 1 + i, y + 1 + j, col)


def strap(c, x, y0, n, theme, rivets=(), cap_top=False, cap_bottom=False):
    """A two pixel wide vertical metal strap with rivets and a one pixel cast shadow on its right."""
    m, w = theme.metal, theme.wood
    for i in range(n):
        y = y0 + i
        c.set(x, y, m[3])
        c.set(x + 1, y, m[2])
        if c.get(x + 2, y) in (w[2], w[3], w[4], w[5]):
            c.set(x + 2, y, w[1])
    for ry in rivets:
        c.set(x, ry, m[5] if not theme.opened else m[4])
        c.set(x + 1, ry, m[1])
    if cap_top:
        c.set(x, y0, m[4])
        c.set(x + 1, y0, m[3])
    if cap_bottom:
        c.set(x, y0 + n - 1, m[1])
        c.set(x + 1, y0 + n - 1, m[1])


# ---------------------------------------------------------------------------
# 5. Texture builders
# ---------------------------------------------------------------------------

# --- 5.1 Chest -------------------------------------------------------------
# Sheet layout: net.minecraft.client.renderer.blockentity.ChestRenderer.createSingleBodyLayer
#   bottom  texOffs(0,19) box 14 x 10 x 14      lid  texOffs(0,0) box 14 x 5 x 14
#   lock    texOffs(0,0)  box  2 x  4 x  1
# and the box unwrap of net.minecraft.client.model.geom.ModelPart.Cube: for texOffs(u,v) and a
# w x h x d box the y-min face is (u+d, v, w, d), the y-max face (u+d+w, v, w, d) and the four
# side faces sit in the row below as west(d) north(w) east(d) south(w).  The chest is rendered
# without a y flip, so side faces are stored rotated by 180 degrees and the lid/rim tops are
# stored mirrored top-to-bottom.  The front of the chest is the south face.


def chest_wall(theme, face):
    """One 14 x 14 wall of the closed chest as seen from outside (lid = rows 0-4, body = rows 4-13)."""
    w, m = theme.wood, theme.metal
    ox = {"front": 0, "right": 5, "back": 9, "left": 13}[face]
    c = planks(14, 14, w, ox, 15)
    c.hline(0, 0, 14, w[4])                     # lit top edge of the lid
    c.set(5 + ox % 3, 0, w[5])
    c.set(9, 0, w[5])
    c.hline(0, 13, 14, w[2])                    # base board sits in shadow
    c.vline(0, 0, 14, w[2])                     # corner posts
    c.vline(13, 0, 14, w[2])
    c.set(0, 0, w[3])
    c.set(13, 13, w[1])
    c.set(0, 13, w[1])
    c.hline(0, 4, 14, w[0])                     # the gap between lid and body
    if face in ("front", "back"):
        for sx in (2, 10):
            strap(c, sx, 0, 14, theme, rivets=(2, 7, 11), cap_top=True, cap_bottom=True)
            c.set(sx, 4, m[1])                  # straps are hinged at the gap
            c.set(sx + 1, 4, m[0])
    if face == "back":
        for sx in (2, 10):                      # hinge knuckles
            c.set(sx, 3, m[4])
            c.set(sx + 1, 3, m[3])
            c.set(sx, 5, m[4])
            c.set(sx + 1, 5, m[2])
    if face == "front":
        r = theme.ring
        # escutcheon behind the lock: 4 x 6 plate, its rim is red on trapped chests
        c.rect(5, 2, 4, 6, m[2])
        c.hline(5, 2, 4, r[4])
        c.vline(5, 2, 6, r[3])
        c.set(5, 2, r[4])
        c.hline(5, 7, 4, r[1])
        c.vline(8, 2, 6, r[1])
        c.set(8, 2, r[2])
        c.set(5, 4, m[1])
        c.set(8, 4, m[0])
        c.hline(6, 4, 2, m[0])
        c.hline(5, 8, 4, w[1])                  # shadow under the plate
        # what the lock covers on the body: an empty keyway
        c.set(6, 5, VOID[0])
        c.set(7, 5, VOID[0])
        c.set(6, 6, m[1])
        c.set(7, 6, m[1])
    if face in ("left", "right"):
        seal(c, 5, 7, theme)                    # a seal on each flank
        c.hline(6, 11, 2, w[1])
    return c


def chest_lid_top(theme):
    """14 x 14 lid top seen from above, front edge at the bottom."""
    w = theme.wood
    c = planks(14, 14, w, 3, 0)
    c.frame(0, 0, 14, 14, w[2])
    c.hline(0, 0, 14, w[4])
    c.vline(0, 0, 14, w[3])
    c.set(0, 0, w[4])
    c.hline(1, 13, 13, w[2])
    for sx in (2, 10):
        strap(c, sx, 0, 14, theme, rivets=(2, 6, 11), cap_top=True, cap_bottom=True)
    seal(c, 5, 5, theme)
    c.hline(6, 9, 3, w[1])
    return c


def chest_lid_inside(theme):
    """14 x 14 underside of the lid (what faces you when the chest stands open)."""
    w = theme.wood
    c = Canvas(14, 14, w[1])
    c.frame(0, 0, 14, 14, w[3])
    c.hline(0, 0, 14, w[4])
    c.hline(0, 13, 14, w[2])
    c.frame(1, 1, 12, 12, w[0])
    c.hline(2, 12, 11, w[2])
    c.vline(12, 2, 10, w[2])
    for y in (5, 9):
        c.hline(2, y, 10, w[0])
    return c


def chest_rim(theme):
    """14 x 14 top of the body: the rim and a look into the chest."""
    w, m = theme.wood, theme.metal
    c = Canvas(14, 14, w[0])
    c.frame(0, 0, 14, 14, w[3])
    c.hline(0, 0, 14, w[4])
    c.hline(0, 13, 14, w[2])
    c.vline(13, 1, 12, w[2])
    for sx in (2, 10):                          # strap ends cross the rim, front and back
        for y in (0, 13):
            c.set(sx, y, m[3])
            c.set(sx + 1, y, m[2])
    c.hline(1, 1, 12, INK)                      # deep shadow under the near rim
    c.vline(1, 1, 12, INK)
    c.rect(3, 10, 9, 2, w[1])                   # a hint of the floor boards
    c.hline(3, 11, 9, w[2])
    if not theme.opened:                        # loot glints inside a sealed chest
        for x, y, g in ((6, 7, 2), (7, 7, 3), (6, 8, 1), (7, 8, 2), (5, 8, 1), (8, 8, 1), (9, 6, 1), (4, 5, 1)):
            c.set(x, y, GEM[g])
    return c


def chest_bottom(theme):
    w = theme.wood
    c = planks(14, 14, [w[0], w[0], w[1], w[2], w[2], w[3]], 7, 2)
    c.frame(0, 0, 14, 14, w[1])
    return c


def chest_lock(theme):
    """Returns the faces of the 2 x 4 x 1 lock as seen from outside."""
    m = theme.metal
    front = Canvas(2, 4)
    side_l = Canvas(1, 4)
    side_r = Canvas(1, 4)
    top = Canvas(2, 1)
    under = Canvas(2, 1)
    if theme.opened:
        # the gem is gone and the hasp hangs open: only the upper half of the lock remains
        front.px[0] = [m[4], m[3]]
        front.px[1] = [m[2], m[1]]
        side_l.px[0], side_l.px[1] = [m[3]], [m[2]]
        side_r.px[0], side_r.px[1] = [m[2]], [m[1]]
        top.px[0] = [m[4], m[3]]
    else:
        front.px[0] = [m[5], m[4]]
        front.px[1] = [GEM[4], GEM[3]]
        front.px[2] = [GEM[2], GEM[1]]
        front.px[3] = [m[2], m[1]]
        for s, k in ((side_l, 0), (side_r, 1)):
            s.px[0] = [m[4 - k]]
            s.px[1] = [GEM[3 - k]]
            s.px[2] = [GEM[2 - k]]
            s.px[3] = [m[2 - k]]
        top.px[0] = [m[5], m[4]]
        under.px[0] = [m[1], m[0]]
    return front, side_l, side_r, top, under


def put_box(sheet, u, v, w, h, d, faces):
    """Place faces on a sheet following ModelPart.Cube's unwrap.

    faces: dict with any of ymin, ymax, west, north, east, south -> Canvas, already in sheet orientation.
    """
    spots = {"ymin": (u + d, v, w, d), "ymax": (u + d + w, v, w, d),
             "west": (u, v + d, d, h), "north": (u + d, v + d, w, h),
             "east": (u + d + w, v + d, d, h), "south": (u + d + w + d, v + d, w, h)}
    for name, face in faces.items():
        x, y, fw, fh = spots[name]
        assert (face.w, face.h) == (fw, fh), (name, face.w, face.h, fw, fh)
        sheet.blit(face, x, y, keep_transparent=True)


def chest_sheet(opened, trapped):
    t = Theme(opened, trapped)
    sheet = Canvas(64, 64)
    walls = {f: chest_wall(t, f) for f in ("front", "back", "left", "right")}
    # the chest's south face is its front; west is the viewer's left when facing the front
    names = {"south": "front", "north": "back", "west": "left", "east": "right"}
    lid = {k: walls[n].crop(0, 0, 14, 5).rot180() for k, n in names.items()}
    body = {k: walls[n].crop(0, 4, 14, 10).rot180() for k, n in names.items()}
    lid["ymax"] = chest_lid_top(t).flip_v()
    lid["ymin"] = chest_lid_inside(t)
    body["ymax"] = chest_rim(t).flip_v()
    body["ymin"] = chest_bottom(t)
    put_box(sheet, 0, 19, 14, 10, 14, body)
    put_box(sheet, 0, 0, 14, 5, 14, lid)
    front, side_l, side_r, top, under = chest_lock(t)
    # the lock shares the sheet corner with the (unused) top-left of the lid unwrap
    put_box(sheet, 0, 0, 2, 4, 1, {"south": front.rot180(), "north": front.rot180(),
                                   "west": side_l.rot180(), "east": side_r.rot180(),
                                   "ymax": top.flip_v(), "ymin": under})
    return sheet


# --- 5.2 Barrel --------------------------------------------------------------
# Plain block textures used by models/block/lootr_barrel_*.json (cube_bottom_top).


def barrel_side(theme):
    w, m = theme.wood, theme.metal
    c = planks(16, 16, w, 0, 0, vertical=True)          # staves: three pixels and a seam
    for x in range(16):
        seam = x % 4 == 3
        c.set(x, 0, w[1] if seam else w[2])             # rims sit in shadow
        c.set(x, 15, w[0] if seam else w[1])
    for y0 in (2, 12):                                  # two hoops
        for x in range(16):
            rivet = x % 8 == 1
            c.set(x, y0, m[5 if not theme.opened else 4] if rivet else m[4 if x % 4 == 0 else 3])
            c.set(x, y0 + 1, m[1] if rivet else m[2])
            c.set(x, y0 + 2, w[0] if x % 4 == 3 else w[1])
    seal(c, 6, 6, theme)
    c.hline(7, 10, 3, w[1])
    return c


def barrel_end(theme, kind):
    """16 x 16 barrel end.  kind: 'lid' (closed top), 'open' (looking in) or 'bottom'."""
    w, m = theme.wood, theme.metal
    c = Canvas(16, 16, w[3])
    for i in range(16):                                 # stave ends, seams where the side's seams are
        seam = i % 4 == 3
        c.set(i, 0, w[1] if seam else w[4])
        c.set(0, i, w[1] if seam else w[4])
        c.set(i, 15, w[0] if seam else w[2])
        c.set(15, i, w[0] if seam else w[2])
    c.set(0, 0, w[4])
    c.set(15, 15, w[1])
    dim = kind == "bottom"
    hi, mid, lo = (m[2], m[1], m[0]) if dim else (m[4], m[3], m[1])
    c.frame(1, 1, 14, 14, lo)                           # the hoop seen end-on
    c.hline(1, 1, 14, mid)
    c.vline(1, 1, 14, mid)
    c.set(1, 1, hi)
    c.set(2, 1, hi)
    c.set(1, 2, hi)
    c.set(14, 1, lo)
    c.set(1, 14, lo)
    if kind == "open":
        c.rect(2, 2, 12, 12, w[0])
        c.hline(2, 2, 12, INK)                          # near wall in deep shadow
        c.hline(2, 3, 12, INK)
        c.vline(2, 2, 12, INK)
        c.vline(13, 4, 10, w[1])                        # far wall catches the light
        c.hline(3, 13, 11, w[2])
        c.hline(3, 12, 10, w[1])
        for x in (5, 9):                                # floor boards far below
            c.vline(x, 5, 6, INK)
        if not theme.opened:
            for x, y, g in ((7, 8, 3), (8, 8, 2), (7, 9, 2), (8, 9, 1), (6, 9, 1), (9, 8, 1), (10, 6, 1), (4, 7, 1), (7, 7, 1)):
                c.set(x, y, GEM[g])
    else:
        ww = [w[0], w[0], w[1], w[2], w[3], w[3]] if dim else w
        c.blit(planks(12, 12, ww, 5, 0), 2, 2)
        c.hline(2, 2, 12, ww[2])                        # shadow cast by the hoop
        c.vline(2, 2, 12, ww[2])
        if kind == "lid":
            seal(c, 5, 5, theme, 6)
            c.hline(6, 11, 5, w[1])
            c.vline(11, 6, 5, w[1])
        else:
            for x, y in ((4, 4), (11, 4), (4, 11), (11, 11)):
                c.set(x, y, m[2])
    return c


# --- 5.3 Planks --------------------------------------------------------------
# block/gold_planks + block/silver_planks: particle texture of the chest-like blocks,
# advancement tab background (tiles), and the #wood texture of the loot item frame.
# minecraft:block/template_item_frame takes the frame's visible front from the one pixel
# ring at inset 2 (rows/columns 2 and 13) and its outer sides from rows/columns 0 and 15,
# so that ring is a metal inlay: brass = not looted, iron = looted.


def inlay_planks(theme):
    w, m = theme.wood, theme.metal
    c = planks(16, 16, w, 0, 0)
    c.frame(2, 2, 12, 12, m[1])
    c.hline(2, 2, 12, m[4] if not theme.opened else m[3])
    c.vline(2, 2, 12, m[3])
    c.hline(3, 13, 11, m[2] if not theme.opened else m[1])
    c.vline(13, 3, 11, m[2] if not theme.opened else m[1])
    c.set(2, 2, m[5] if not theme.opened else m[4])
    c.set(13, 13, m[1])
    c.set(13, 2, m[3] if not theme.opened else m[2])
    c.set(2, 13, m[2])
    for i in range(3, 13):                              # shadow the inlay casts on the boards
        if c.get(i, 3) != w[1]:
            c.set(i, 3, w[2])
        c.set(i, 14, w[1] if c.get(i, 14) != w[1] else w[0])
        c.set(14, i, w[1] if c.get(14, i) != w[1] else w[0])
    # a 2 x 2 stud where four tiles meet: this tile's bottom-right pixel is the stud's lit top-left one
    for (x, y), i in (((15, 15), 4), ((0, 15), 3), ((15, 0), 2), ((0, 0), 1)):
        c.set(x, y, m[i] if not theme.opened else m[max(1, i - 1)])
    return c


# --- 5.4 Brushable blocks ------------------------------------------------------
# block/suspicious_sand_open + suspicious_gravel_open: the cube_all texture the brushable
# model swaps in (instead of the dusted stages) once you have already dug the block out:
# ordinary sand / gravel with an emptied, brushed-out hollow.

SAND_OPEN = [
    "2232222322212223",
    "2222122222322222",
    "3222222322222322",
    "2212200000122221",
    "2222000000001222",
    "2320011111100322",
    "2220111111111332",
    "2220111211111433",
    "1220111111211432",
    "2222111111114422",
    "2232311111144322",
    "2222234444432212",
    "2122222332222222",
    "2223222222232222",
    "3222212222222232",
    "2222222321222222",
]

GRAVEL_OPEN = [
    "2233211223221233",
    "1233221233321123",
    "1122232223222122",
    "2124300000123321",
    "3223000000003322",
    "3320011011002223",
    "2210011112113233",
    "2110112111114322",
    "3210111101114312",
    "3322121111144211",
    "2322311111443221",
    "2212234444322322",
    "1223322332212332",
    "1123321222112232",
    "2122221123322222",
    "2232122223321223",
]


def brushed(grid, pal):
    return Canvas.from_grid(grid, {str(i): pal[i] for i in range(5)})


# --- 5.5 Shulker box ---------------------------------------------------------
# Sheet layout: net.minecraft.client.model.ShulkerModel.createBodyLayer
#   lid  texOffs(0,0)  box 16 x 12 x 16     base texOffs(0,28) box 16 x 8 x 16
#   head texOffs(0,52) box  6 x  6 x  6     (ShulkerModel.parts() = base, lid: never drawn for boxes)
# LootrShulkerBlockRenderer flips y and z, so faces are stored upright.  The lid's lowest four
# rows and the base's top four rows occupy the same place when the box is closed; they hold the
# same pixels (the belt) so that it does not matter which of the two wins the depth test.


def shulker_belt(theme):
    w, m = theme.wood, theme.metal
    c = Canvas(16, 4)
    for x in range(16):
        rivet = x in (2, 6, 9, 13)
        c.set(x, 0, m[4])
        c.set(x, 1, (m[5] if not theme.opened else m[4]) if rivet else m[3])
        c.set(x, 2, m[1] if rivet else m[2])
        c.set(x, 3, w[0])
    c.set(0, 0, m[3])
    c.set(15, 0, m[3])
    c.set(15, 1, m[2])
    c.set(15, 2, m[1])
    return c


def shulker_lid_wall(theme):
    w = theme.wood
    c = Canvas(16, 12, w[3])
    c.hline(0, 0, 16, w[4])
    c.hline(1, 0, 3, w[5])
    c.vline(0, 1, 7, w[2])
    c.vline(15, 0, 8, w[2])
    c.rect(2, 1, 12, 6, w[2])                           # recessed panel
    c.hline(2, 1, 12, w[1])
    c.vline(2, 1, 6, w[1])
    c.hline(3, 7, 12, w[4])                             # lit lower lip of the recess
    c.vline(14, 2, 5, w[4])
    seal(c, 6, 2, theme)
    c.blit(shulker_belt(theme), 0, 8)
    return c


def shulker_base_wall(theme):
    w, m = theme.wood, theme.metal
    c = Canvas(16, 8, w[3])
    c.blit(shulker_belt(theme), 0, 0)
    c.hline(0, 4, 16, w[2])
    c.hline(0, 7, 16, w[1])
    c.vline(0, 4, 4, w[2])
    c.vline(15, 4, 4, w[1])
    for x in (2, 13):                                   # a stud under each outer rivet
        c.set(x, 5, m[3])
        c.set(x, 6, m[1])
    return c


def shulker_lid_top(theme):
    w, m = theme.wood, theme.metal
    c = Canvas(16, 16, w[3])
    c.frame(0, 0, 16, 16, w[2])
    c.hline(0, 0, 16, w[4])
    c.vline(0, 0, 16, w[4])
    c.rect(3, 3, 10, 10, w[2])                          # recessed panel, as on the walls
    c.hline(3, 3, 10, w[1])
    c.vline(3, 3, 10, w[1])
    c.hline(4, 13, 10, w[4])
    c.vline(13, 4, 10, w[4])
    for cx, cy, dx, dy in ((0, 0, 1, 1), (15, 0, -1, 1), (0, 15, 1, -1), (15, 15, -1, -1)):   # corner caps
        lit = dx > 0 and dy > 0
        for k in range(3):
            c.set(cx + dx * k, cy, m[4] if lit else m[3] if dy > 0 else m[1])
            c.set(cx, cy + dy * k, m[4] if lit else m[3] if dx > 0 else m[1])
        c.set(cx, cy, m[5] if lit and not theme.opened else m[3] if (dx > 0 or dy > 0) else m[1])
    seal(c, 5, 5, theme, 6)
    return c


def shulker_inside(theme, floor):
    """Lid underside (floor=False) or the top of the base you look onto when the lid lifts (floor=True)."""
    w, m = theme.wood, theme.metal
    c = Canvas(16, 16, w[0])
    c.frame(0, 0, 16, 16, m[2] if floor else w[2])
    if floor:
        c.hline(0, 0, 16, m[4])
        c.vline(0, 0, 16, m[3])
        c.set(0, 0, m[4])
    c.frame(1, 1, 14, 14, INK)
    c.hline(2, 14, 13, w[1])
    c.vline(14, 2, 12, w[1])
    if floor and not theme.opened:
        for x, y, g in ((7, 7, 3), (8, 7, 2), (7, 8, 2), (8, 8, 1), (6, 8, 1), (9, 7, 1), (10, 9, 1), (5, 6, 1)):
            c.set(x, y, GEM[g])
    return c


def shulker_sheet(opened):
    t = Theme(opened, shulker=True)
    w = t.wood
    sheet = Canvas(64, 64)
    lid_wall, base_wall = shulker_lid_wall(t), shulker_base_wall(t)
    under = Canvas(16, 16, w[1])
    under.frame(0, 0, 16, 16, w[2])
    assert lid_wall.crop(0, 8, 16, 4).px == base_wall.crop(0, 0, 16, 4).px   # the shared belt, see above
    put_box(sheet, 0, 0, 16, 12, 16, {"ymin": shulker_lid_top(t), "ymax": shulker_inside(t, False),
                                      "west": lid_wall, "north": lid_wall, "east": lid_wall, "south": lid_wall})
    put_box(sheet, 0, 28, 16, 8, 16, {"ymin": shulker_inside(t, True), "ymax": under,
                                      "west": base_wall, "north": base_wall, "east": base_wall, "south": base_wall})
    head = Canvas(6, 6, w[2])                           # unused by the box renderer; kept sane anyway
    head.frame(0, 0, 6, 6, w[1])
    put_box(sheet, 0, 52, 6, 6, 6, {k: head for k in ("ymin", "ymax", "west", "north", "east", "south")})
    return sheet


# --- 5.6 Decorated pot -------------------------------------------------------
# entity/loot_pot (32 x 32): net.minecraft.client.renderer.blockentity.DecoratedPotRenderer.createBaseLayer
#   neck  texOffs(0,0) box 8 x 3 x 8 (the lip)  +  texOffs(0,5) box 6 x 1 x 6 (the throat), part turned upside
#         down by its pose, so the box's y-min face (8,0,8,8) is the lip's top
#   top / bottom  texOffs(-14,13) box 14 x 0 x 14: y-min face (0,13,14,14) is the pot's underside,
#         y-max face (14,13,14,14) is the shoulder you see from above
# The four walls are NOT on this sheet: LootrDecoratedPotRenderer draws them with the sherd/pattern
# materials (16 x 16, columns 1-14 used -- createSidesLayer: texOffs(1,0), 14 x 16 north face).
# entity/loot_pot_open (64 x 32): LootrDecoratedPotRenderer.createBodyLayer -- the looted pot is drawn as a
# broken pot: the neck lying on a pile of three flat sherds.  The neck keeps the lip/throat layout above;
# the sherd boxes unwrap to (4..48, 16..28), which is why this sheet has to be 64 wide.

SHERD_SKIN = [
    "3333433333323333",
    "3343333322333333",
    "3333333233334333",
    "3223333333343333",
    "3333334333333322",
    "3333343333223333",
    "3233333333333333",
    "3333332233334333",
    "3334333333343333",
    "3343333323333332",
    "3333333333333223",
]


def pot_lip_top(theme, sealed):
    c = Canvas(8, 8, CLAY[3])
    c.frame(0, 0, 8, 8, CLAY[2])
    c.hline(0, 0, 8, CLAY[4])
    c.vline(0, 0, 8, CLAY[4])
    c.set(7, 7, CLAY[1])
    if sealed:
        m = theme.metal
        c.rect(1, 1, 6, 6, m[2])                        # a brass cap ...
        c.hline(1, 1, 6, m[4])
        c.vline(1, 1, 6, m[3])
        c.hline(2, 6, 5, m[1])
        c.vline(6, 2, 5, m[1])
        seal(c, 2, 2, theme)                            # ... holding the seal gem
    else:
        c.rect(1, 1, 6, 6, INK)                         # an open mouth
        c.hline(2, 6, 5, CLAY[0])
        c.vline(6, 2, 5, CLAY[0])
    return c


def pot_lip_under():
    c = Canvas(8, 8, CLAY[1])
    c.frame(0, 0, 8, 8, CLAY[2])
    c.rect(2, 2, 4, 4, CLAY[0])
    return c


def pot_lip_wall(theme):
    m = theme.metal
    c = Canvas(8, 3)
    c.hline(0, 0, 8, CLAY[4])
    c.set(0, 0, CLAY[5])
    c.set(7, 0, CLAY[3])
    c.hline(0, 1, 8, m[3])                              # a metal collar round the lip
    c.set(0, 1, m[4])
    c.set(3, 1, m[5] if not theme.opened else m[4])
    c.set(4, 1, m[1])
    c.set(7, 1, m[2])
    c.hline(0, 2, 8, CLAY[2])
    c.set(7, 2, CLAY[1])
    return c


def pot_neck(sheet, theme, sealed):
    wall = pot_lip_wall(theme)
    throat = Canvas(6, 1, CLAY[1])
    throat.set(0, 0, CLAY[2])
    throat.set(5, 0, CLAY[0])
    hidden = Canvas(6, 6, CLAY[1])
    put_box(sheet, 0, 5, 6, 1, 6, {"ymin": hidden, "ymax": hidden,
                                   "west": throat, "north": throat, "east": throat, "south": throat})
    # the lip is placed last: where the two unwraps overlap only the lip's faces can be seen in game
    put_box(sheet, 0, 0, 8, 3, 8, {"ymin": pot_lip_top(theme, sealed), "ymax": pot_lip_under(),
                                   "west": wall, "north": wall, "east": wall, "south": wall})


def pot_sheet():
    t = Theme(False)
    sheet = Canvas(32, 32)
    pot_neck(sheet, t, True)
    shoulder = Canvas(14, 14, CLAY[3])
    shoulder.frame(0, 0, 14, 14, CLAY[2])
    shoulder.hline(0, 0, 14, CLAY[4])
    shoulder.vline(0, 0, 14, CLAY[4])
    shoulder.set(13, 13, CLAY[1])
    shoulder.rect(3, 3, 9, 9, CLAY[1])                  # shadow of the lip
    shoulder.rect(3, 3, 8, 8, CLAY[2])
    shoulder.rect(4, 4, 6, 6, CLAY[0])
    under = Canvas(14, 14, CLAY[1])
    under.frame(0, 0, 14, 14, CLAY[2])
    under.frame(3, 3, 8, 8, CLAY[0])
    sheet.blit(under, 0, 13)
    sheet.blit(shoulder, 14, 13)
    return sheet


def pot_open_sheet():
    t = Theme(True)
    sheet = Canvas(64, 32)
    skin = {str(i): CLAY[i] for i in range(6)}
    for y in range(11):                                 # all sherd faces share one continuous clay skin
        for x in range(15, 40):
            sheet.set(x, 16 + y, skin[SHERD_SKIN[y][(x - 15) % 16]])
    for x in range(4, 48):                              # broken edges: pale raw clay
        sheet.set(x, 27, (CLAY[5], CLAY[4], CLAY[4], CLAY[3], CLAY[4], CLAY[2])[x % 6])
    pot_neck(sheet, t, False)
    return sheet


def pot_pattern():
    """16 x 16 pot wall with the seal embossed on it: a raised lozenge inside a raised lozenge ring."""
    c = Canvas(16, 16, CLAY[2])
    c.hline(1, 0, 14, CLAY[3])                          # lip
    c.hline(1, 1, 14, CLAY[1])                          # groove under the lip
    c.hline(1, 14, 14, CLAY[1])                         # groove above the foot
    c.set(15, 15, CLAY[1])
    for y in range(2, 14):
        for x in range(1, 15):
            dx, dy = x - 7.5, y - 7.5
            m = abs(dx) + abs(dy)
            lit = dx + dy < 0
            if m in (5, 6):                             # ring
                c.set(x, y, CLAY[4] if lit else CLAY[1])
            elif m <= 2:                                # the gem
                c.set(x, y, CLAY[5] if (dx, dy) == (-0.5, -0.5) else CLAY[4] if lit else CLAY[3] if dx + dy == 0 else CLAY[1])
            elif m in (3, 4):                           # the sunken field between them
                c.set(x, y, CLAY[1] if lit and m == 4 else CLAY[2])
    return c


# --- 5.7 GUI -------------------------------------------------------------------
# gui/sprites/toast/container_alert (160 x 32, no .mcmeta -> stretched 1:1): LootrToast blits it at 0,0,
# draws the 20 x 20 icon at 6,6, the title at 30,7 in dark purple (0x500050) and the message at 30,18 in
# black, both without shadow -- so everything right of x = 29 is light parchment.

DECAY_ICON = [
    "....................",
    "...kkkkkkkkkkkkkk...",
    "...khhhBBBBBBBBdk...",
    "...kBbbbbbbbbbbdk...",
    "....kkkkkkkkkkkk....",
    "....kgGGGGGGGGgk....",
    "....kgtTttttttGk....",
    ".....kgtTttttGk.....",
    "......kgtTttGk......",
    ".......kgttGk.......",
    "........ktGk........",
    ".......kgtGGk.......",
    "......kgGGtGGk......",
    ".....kgGGGaGGGk.....",
    "....kgGGGaaAGGGk....",
    "....kgGaaaaaaAGk....",
    "....kkkkkkkkkkkk....",
    "...kBhhBBBBBBBBdk...",
    "...kbbbbbbbbbbbdk...",
    "...kkkkkkkkkkkkkk...",
]


def toast_background():
    t = Theme(False)
    w, m = t.wood, t.metal
    c = Canvas(160, 32, PAPER[3])
    c.frame(0, 0, 160, 32, INK)
    for x, y in ((0, 0), (159, 0), (0, 31), (159, 31)):
        c.set(x, y, None)
    c.frame(1, 1, 158, 30, m[1])                        # brass binding
    c.hline(1, 1, 158, m[4])
    c.vline(1, 1, 30, m[3])
    c.hline(2, 30, 157, m[1])
    c.vline(158, 2, 29, m[2])
    for x, y in ((1, 1), (158, 1), (1, 30), (158, 30)):
        c.set(x, y, INK)
    c.frame(2, 2, 156, 28, w[2])                        # timber edge
    c.hline(2, 2, 156, w[3])
    c.hline(2, 29, 156, w[1])
    # parchment
    c.hline(29, 3, 128, PAPER[4])
    c.hline(29, 28, 128, PAPER[2])
    c.vline(156, 4, 25, PAPER[2])
    for x, y in ((47, 12), (48, 12), (83, 25), (84, 25), (85, 25), (121, 6), (122, 6), (139, 22), (64, 26), (103, 14), (104, 14), (150, 9)):
        c.set(x, y, PAPER[2])                           # a few paper fibres
    for x, y in ((154, 5), (154, 26)):                  # tacks
        c.set(x, y, m[3])
        c.set(x + 1, y, m[1])
        c.set(x, y + 1, m[1])
        c.set(x + 1, y + 1, PAPER[1])
    # icon bay: dark timber behind the 20 x 20 icon at (6, 6)
    c.rect(3, 3, 24, 26, w[1])
    c.hline(3, 3, 24, w[0])                             # recessed: shadow along the top and left ...
    c.vline(3, 3, 26, w[0])
    c.hline(4, 28, 23, w[3])                            # ... and a lit lip along the bottom and right
    c.vline(26, 4, 25, w[3])
    c.set(26, 28, w[4])
    c.vline(27, 3, 26, m[3])                            # brass divider
    c.set(27, 3, m[4])
    c.set(27, 28, m[1])
    c.vline(28, 3, 26, m[1])
    c.vline(29, 4, 25, PAPER[1])
    c.vline(30, 4, 25, PAPER[2])
    return c


def decay_icon():
    m = BRASS
    return Canvas.from_grid(DECAY_ICON, {
        ".": None, "k": INK, "h": m[5], "B": m[3], "b": m[2], "d": m[1],
        "g": rgb("e6f6f0"), "G": rgb("9dbdb9"), "t": GEM[2], "T": GEM[3], "a": IRON[5], "A": IRON[4]})


def refresh_icon():
    """Two arrows chasing each other round a seal gem (an octagonal ring, two pixels thick)."""
    c = Canvas(20, 20)

    def band(x, y):
        dx, dy = abs(x - 9.5), abs(y - 9.5)
        return max(dx, dy, (dx + dy) * 0.7072)          # octagonal distance from the centre

    body = {}
    for y in range(20):
        for x in range(20):
            a = math.degrees(math.atan2(9.5 - y, x - 9.5))   # 0 = east, 90 = north
            if 5.0 <= band(x, y) < 7.0 and (4 <= a <= 142 or -176 <= a <= -38):
                outer = band(x, y) >= 6.0
                lit = (x - 9.5) + (y - 9.5) < 0
                body[(x, y)] = GEM[3 if lit else 1] if outer else GEM[2]
    # arrow heads fill the two gaps: the east one points down, the west one points up
    for x0, x1, y, col in ((13, 18, 10, GEM[2]), (14, 17, 11, GEM[2]), (15, 16, 12, GEM[1]),
                           (1, 6, 9, GEM[2]), (2, 5, 8, GEM[3]), (3, 4, 7, GEM[3])):
        for x in range(x0, x1 + 1):
            body[(x, y)] = col
    for (x, y), col in body.items():
        c.set(x, y, col)
    for (x, y) in body:                                 # one pixel outline
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if (nx, ny) not in body and 0 <= nx < 20 and 0 <= ny < 20:
                c.set(nx, ny, INK)
    seal(c, 8, 8, Theme(False))
    return c


# ---------------------------------------------------------------------------
# 6. Trophy: block model + texture
# ---------------------------------------------------------------------------
# A small brass goblet on a timber plinth holding an oversized seal gem.  It stays inside the
# block's selection box for facing=north (TrophyBlock: box(4, 0, 1.5, 12, 14.5, 14.5)).
# Each entry: name, from, to, {face: (x, y) top-left pixel on the 32 x 32 texture}.  Face sizes
# follow from the element; the painter below fills exactly those rectangles.

TROPHY_ELEMENTS = [
    ("plinth", (4, 0, 4), (12, 2, 12), {"up": (0, 0), "down": (8, 0), "north": (0, 8), "south": (8, 8), "west": (8, 8), "east": (8, 8)}),
    ("step", (5, 2, 5), (11, 3, 11), {"up": (16, 0), "north": (16, 6), "south": (16, 6), "west": (16, 6), "east": (16, 6)}),
    ("stem", (7, 3, 7), (9, 6, 9), {"north": (22, 0), "south": (22, 0), "west": (22, 0), "east": (22, 0)}),
    ("bowl", (5, 6, 5), (11, 11, 11), {"up": (6, 10), "down": (12, 10), "north": (0, 10), "south": (0, 10), "west": (0, 10), "east": (0, 10)}),
    ("handle_west", (4, 7, 7), (5, 10, 9), {"up": (21, 10), "down": (22, 10), "north": (20, 10), "south": (20, 10), "west": (18, 10)}),
    ("handle_east", (11, 7, 7), (12, 10, 9), {"up": (21, 10), "down": (22, 10), "north": (20, 10), "south": (20, 10), "east": (18, 10)}),
    ("gem", (6, 11, 6), (10, 13, 10), {"up": (4, 16), "north": (0, 16), "south": (0, 16), "west": (0, 16), "east": (0, 16)}),
    ("gem_tip", (7, 13, 7), (9, 14, 9), {"up": (10, 16), "north": (8, 16), "south": (8, 16), "west": (8, 16), "east": (8, 16)}),
]


def face_size(frm, to, face):
    dx, dy, dz = (to[i] - frm[i] for i in range(3))
    return {"up": (dx, dz), "down": (dx, dz), "north": (dx, dy), "south": (dx, dy), "west": (dz, dy), "east": (dz, dy)}[face]


def trophy_texture():
    t = Theme(False)
    w, m = t.wood, t.metal
    c = Canvas(32, 32, w[2])                            # opaque everywhere: also serves as particle texture
    # plinth top (8 x 8), bottom, plaque side, plain side
    top = planks(8, 8, w, 1, 0)
    top.frame(0, 0, 8, 8, w[2])
    top.hline(0, 0, 8, w[4])
    top.vline(0, 0, 8, w[4])
    top.rect(1, 1, 6, 6, w[1])                          # shadow under the step
    c.blit(top, 0, 0)
    bottom = Canvas(8, 8, w[1])
    bottom.frame(0, 0, 8, 8, w[0])
    c.blit(bottom, 8, 0)
    for x0, plaque in ((0, True), (8, False)):
        c.hline(x0, 8, 8, w[4])
        c.hline(x0, 9, 8, w[2])
        c.set(x0, 8, w[3])
        c.set(x0 + 7, 8, w[3])
        c.set(x0, 9, w[1])
        c.set(x0 + 7, 9, w[1])
        if plaque:
            c.hline(x0 + 2, 8, 4, m[4])
            c.hline(x0 + 2, 9, 4, m[2])
            c.set(x0 + 2, 8, m[5])
            c.set(x0 + 5, 9, m[1])
    # step: top 6 x 6 at (16,0), side 6 x 1 at (16,6)
    c.rect(16, 0, 6, 6, m[3])
    c.hline(16, 0, 6, m[4])
    c.vline(16, 0, 6, m[4])
    c.hline(17, 5, 5, m[2])
    c.vline(21, 1, 5, m[2])
    c.rect(18, 2, 3, 3, m[1])
    c.hline(16, 6, 6, m[2])
    c.set(16, 6, m[3])
    c.set(21, 6, m[1])
    # stem 2 x 3 at (22,0)
    for y, (a, b) in enumerate(((m[2], m[1]), (m[4], m[2]), (m[3], m[1]))):
        c.set(22, y, a)
        c.set(23, y, b)
    # bowl side 6 x 5 at (0,10)
    rows = [(5, 4, 4, 4, 3, 2), (4, 4, 3, 3, 2, 1), (4, 3, 3, 3, 2, 1), (3, 3, 3, 2, 2, 1), (1, 2, 2, 1, 1, 0)]
    for y, row in enumerate(rows):
        for x, i in enumerate(row):
            c.set(x, 10 + y, m[i])
    # bowl top 6 x 6 at (6,10): rim round a dark well (the gem covers the middle)
    c.rect(6, 10, 6, 6, m[0])
    c.frame(6, 10, 6, 6, m[2])
    c.hline(6, 10, 6, m[4])
    c.vline(6, 10, 6, m[4])
    c.set(6, 10, m[5])
    # bowl underside 6 x 6 at (12,10)
    c.rect(12, 10, 6, 6, m[1])
    c.frame(12, 10, 6, 6, m[0])
    # handle: outer face 2 x 3 at (18,10), edge 1 x 3 at (20,10), top 1 x 2 at (21,10), underside at (22,10)
    for y, (a, b) in enumerate(((m[4], m[3]), (m[3], m[0]), (m[2], m[1]))):
        c.set(18, 10 + y, a)
        c.set(19, 10 + y, b)
    for y, i in enumerate((4, 3, 1)):
        c.set(20, 10 + y, m[i])
    c.set(21, 10, m[4])
    c.set(21, 11, m[3])
    c.set(22, 10, m[1])
    c.set(22, 11, m[0])
    # gem side 4 x 2 at (0,16), gem top 4 x 4 at (4,16), tip side 2 x 1 at (8,16), tip top 2 x 2 at (10,16)
    for y, row in enumerate(((3, 3, 2, 2), (2, 2, 1, 1))):
        for x, i in enumerate(row):
            c.set(x, 16 + y, GEM[i])
    for y in range(4):
        for x in range(4):
            c.set(4 + x, 16 + y, GEM[(3, 3, 2, 2, 2, 1, 1)[x + y]])
    c.set(4, 16, GEM[4])
    c.set(8, 16, GEM[4])
    c.set(9, 16, GEM[3])
    for (x, y), i in (((10, 16), 4), ((11, 16), 4), ((10, 17), 4), ((11, 17), 3)):
        c.set(x, y, GEM[i])
    return c


def _num(v):
    return int(v) if float(v).is_integer() else v


def model_json(model):
    """Serialise a block model the way they are usually hand-written: one line per vector and per face."""
    def one(v):
        return json.dumps(v, separators=(", ", ": "))

    def block(items, indent):
        return ["%s%s: %s%s" % (indent, one(k), one(v), "," if i < len(items) - 1 else "") for i, (k, v) in enumerate(items)]

    lines = ["{", '  "parent": %s,' % one(model["parent"]), '  "textures": {']
    lines += block(list(model["textures"].items()), "    ")
    lines += ["  },", '  "elements": [']
    for n, el in enumerate(model["elements"]):
        lines += ["    {", '      "name": %s,' % one(el["name"]), '      "from": %s,' % one(el["from"]),
                  '      "to": %s,' % one(el["to"]), '      "faces": {']
        lines += block(list(el["faces"].items()), "        ")
        lines += ["      }", "    }" + ("," if n < len(model["elements"]) - 1 else "")]
    lines += ["  ],", '  "display": {']
    lines += block(list(model["display"].items()), "    ")
    lines += ["  }", "}"]
    return "\n".join(lines) + "\n"


def trophy_model():
    """models/block/trophy.json.  uv is in 1/16ths of the texture, i.e. half pixels on a 32 x 32 sheet."""
    elements = []
    for name, frm, to, faces in TROPHY_ELEMENTS:
        out = {}
        for face in ("north", "east", "south", "west", "up", "down"):
            if face not in faces:
                continue
            x, y = faces[face]
            fw, fh = face_size(frm, to, face)
            entry = {"uv": [_num(x / 2), _num(y / 2), _num((x + fw) / 2), _num((y + fh) / 2)], "texture": "#all"}
            if name == "plinth" and face == "down":
                entry["cullface"] = "down"
            out[face] = entry
        elements.append({"name": name, "from": list(frm), "to": list(to), "faces": out})
    return {
        "parent": "minecraft:block/block",
        "textures": {"all": "lootr:block/trophy", "particle": "lootr:block/trophy"},
        "elements": elements,
        "display": {
            "gui": {"rotation": [30, 225, 0], "translation": [0, 0.75, 0], "scale": [0.8, 0.8, 0.8]},
            "fixed": {"rotation": [0, 0, 0], "translation": [0, 0.5, 0], "scale": [0.75, 0.75, 0.75]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
        },
    }


# ---------------------------------------------------------------------------
# 7. Logo (32 x 32 native)
# ---------------------------------------------------------------------------

LOGO_GEM = [
    "..Wz..",
    ".Wzzy.",
    "Wzzyyx",
    "zzyyxx",
    ".yyxx.",
    "..xw..",
]


def logo():
    """The sealed chest, seen slightly from above, on a night-blue tile."""
    t = Theme(False)
    w, m = t.wood, t.metal
    night, lit, dim, rim = rgb("12222d"), rgb("1d3a48"), rgb("0b161e"), rgb("070d13")
    c = Canvas(32, 32, night)
    c.frame(1, 1, 30, 30, dim)                          # a bevelled tile
    c.hline(1, 1, 30, lit)
    c.vline(1, 1, 30, lit)
    c.frame(0, 0, 32, 32, rim)
    for x, y in ((0, 0), (1, 0), (0, 1), (31, 0), (30, 0), (31, 1), (0, 31), (1, 31), (0, 30), (31, 31), (30, 31), (31, 30)):
        c.set(x, y, None)
    for x, y in ((1, 1), (30, 1), (1, 30), (30, 30)):
        c.set(x, y, rim)

    k = Canvas(32, 32)                                  # the chest on its own layer, outlined afterwards
    # lid top (rows 5-7), lid front (rows 8-14, one pixel proud of the body), body (rows 16-26)
    k.hline(7, 5, 18, w[5])
    k.hline(6, 6, 20, w[4])
    k.hline(5, 7, 22, w[4])
    for x in (11, 12, 17, 24):
        k.set(x, 6, w[3])
    for x in (6, 14, 15, 19):
        k.set(x, 7, w[3])
    k.blit(planks(24, 7, w, 2, 4), 4, 8)
    k.hline(4, 8, 24, w[5])                             # the lid's lit front edge
    k.vline(4, 9, 6, w[2])
    k.vline(27, 9, 6, w[1])
    k.hline(4, 14, 24, w[1])
    k.hline(4, 15, 24, INK)                             # the gap under the lid
    k.blit(planks(22, 11, w, 5, 7), 5, 16)
    k.hline(5, 16, 22, w[1])                            # shadow of the lid
    k.vline(5, 17, 10, w[2])
    k.vline(26, 17, 10, w[1])
    k.hline(5, 26, 22, w[1])
    for sx in (8, 21):                                  # straps, three pixels wide, running over the top
        for y in range(5, 27):
            top = y < 8
            k.set(sx, y, m[5] if top else m[4])
            k.set(sx + 1, y, m[4] if top else m[3])
            k.set(sx + 2, y, m[3] if top else m[2])
            if not top and y not in (8, 15) and k.get(sx + 3, y) != INK:
                k.set(sx + 3, y, w[1])                  # cast shadow
        k.hline(sx, 8, 3, m[5])
        k.hline(sx, 15, 3, m[0])
        k.hline(sx, 26, 3, m[1])
        for ry in (11, 19, 23):                         # rivets
            k.set(sx, ry, m[5])
            k.set(sx + 1, ry, m[5])
            k.set(sx + 2, ry, m[1])
            k.set(sx + 1, ry + 1, m[1])
    # lock plate carrying the seal gem
    k.rect(11, 10, 10, 13, INK)
    k.rect(12, 11, 8, 11, m[3])
    k.hline(12, 11, 8, m[5])
    k.vline(12, 12, 10, m[4])
    k.hline(13, 21, 7, m[1])
    k.vline(19, 12, 9, m[2])
    k.rect(13, 13, 6, 6, m[0])
    k.blit(Canvas.from_grid(LOGO_GEM, {".": None, "W": GEM[4], "z": GEM[3], "y": GEM[2], "x": GEM[1], "w": GEM[0]}), 13, 13)
    for x, y in ((13, 13), (18, 13), (13, 18), (18, 18)):
        k.set(x, y, m[2])
    k.set(15, 20, m[1])                                 # keyhole
    k.set(16, 20, m[1])
    outline = Canvas(32, 32)
    for y in range(32):
        for x in range(32):
            if k.px[y][x] is None and any(0 <= x + dx < 32 and 0 <= y + dy < 32 and k.px[y + dy][x + dx] is not None
                                          for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                outline.px[y][x] = INK
    c.hline(6, 28, 20, rim)                             # ground shadow
    c.blit(outline, 0, 0)
    c.blit(k, 0, 0)
    # sparkles
    for (x, y), col in (((27, 4), GEM[4]), ((26, 4), GEM[2]), ((28, 4), GEM[2]), ((27, 3), GEM[2]), ((27, 5), GEM[2]),
                        ((4, 4), GEM[3]), ((28, 21), GEM[1]), ((3, 13), GEM[1])):
        c.set(x, y, col)
    return c


# ---------------------------------------------------------------------------
# 8. Preview tooling
# ---------------------------------------------------------------------------

BG = rgb("23262b")
BG2 = rgb("2c3036")

FONT = {
    "A": "010101111101101", "B": "110101110101110", "C": "011100100100011", "D": "110101101101110",
    "E": "111100110100111", "F": "111100110100100", "G": "011100101101011", "H": "101101111101101",
    "I": "111010010010111", "J": "001001001101010", "K": "101101110101101", "L": "100100100100111",
    "M": "101111111101101", "N": "110101101101101", "O": "010101101101010", "P": "110101110100100",
    "Q": "010101101110011", "R": "110101110101101", "S": "011100010001110", "T": "111010010010010",
    "U": "101101101101111", "V": "101101101101010", "W": "101101111111101", "X": "101101010101101",
    "Y": "101101010010010", "Z": "111001010100111", "0": "111101101101111", "1": "010110010010111",
    "2": "110001010100111", "3": "110001010001110", "4": "101101111001001", "5": "111100110001110",
    "6": "011100110101010", "7": "111001010010010", "8": "010101010101010", "9": "010101011001110",
    "_": "000000000000111", "-": "000000111000000", ".": "000000000000010", "/": "001001010100100",
    " ": "000000000000000", "(": "010100100100010", ")": "010001001001010", ":": "000010000010000",
    "+": "000010111010000", "=": "000111000111000",
}


def text(c, x, y, s, col=rgb("c9ced6"), k=2):
    """Draw s with the built-in 3 x 5 preview font at scale k."""
    for ch in s.upper():
        bits = FONT.get(ch, FONT[" "])
        for i, b in enumerate(bits):
            if b == "1":
                c.rect(x + (i % 3) * k, y + (i // 3) * k, k, k, col)
        x += 4 * k
    return x


def checker(w, h, k=8):
    c = Canvas(w, h)
    for y in range(h):
        for x in range(w):
            c.px[y][x] = BG if ((x // k) + (y // k)) % 2 == 0 else BG2
    return c


def sheet_of(items, scale=8, cols=4, pad=12, title=None):
    """A labelled contact sheet: items is a list of (label, Canvas)."""
    cw = max(i[1].w for i in items) * scale
    chh = max(i[1].h for i in items) * scale
    rows = (len(items) + cols - 1) // cols
    top = 28 if title else 0
    out = Canvas(pad + cols * (cw + pad), top + pad + rows * (chh + 18 + pad), BG)
    if title:
        text(out, pad, 10, title, rgb("f3d975"), 2)
    for n, (label, img) in enumerate(items):
        x = pad + (n % cols) * (cw + pad)
        y = top + pad + (n // cols) * (chh + 18 + pad)
        out.blit(checker(img.w * scale, img.h * scale), x, y)
        out.blit(img.scaled(scale), x, y)
        text(out, x, y + img.h * scale + 4, label, k=2 if len(label) * 8 <= cw else 1)
    return out


# --- a small orthographic software renderer for textured boxes ---------------


def _rot(v, rx=0.0, ry=0.0, rz=0.0):
    """Rotate like ModelPart.translateAndRotate: v' = Rz * Ry * Rx * v."""
    x, y, z = v
    if rx:
        c, s = math.cos(rx), math.sin(rx)
        y, z = y * c - z * s, y * s + z * c
    if ry:
        c, s = math.cos(ry), math.sin(ry)
        x, z = x * c + z * s, -x * s + z * c
    if rz:
        c, s = math.cos(rz), math.sin(rz)
        x, y = x * c - y * s, x * s + y * c
    return (x, y, z)


def cube_quads(u, v, x, y, z, w, h, d, grow=0.0, offset=(0, 0, 0), rot=(0, 0, 0), only=None):
    """Polygons of ModelPart.Cube (same vertex order, same UV assignment), posed by its ModelPart.

    Returns a list of (four model-space points, four (u, v) texel coordinates).
    """
    x0, y0, z0, x1, y1, z1 = x - grow, y - grow, z - grow, x + w + grow, y + h + grow, z + d + grow
    p = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    f4, f5, f6, f7, f8, f9 = u, u + d, u + d + w, u + d + w + w, u + d + w + d, u + d + w + d + w
    g0, g1, g2 = v, v + d, v + d + h
    polys = {
        "down": ((5, 4, 0, 1), (f5, g0, f6, g1)),
        "up": ((2, 3, 7, 6), (f6, g1, f7, g0)),
        "west": ((0, 4, 7, 3), (f4, g1, f5, g2)),
        "north": ((1, 0, 3, 2), (f5, g1, f6, g2)),
        "east": ((5, 1, 2, 6), (f6, g1, f8, g2)),
        "south": ((4, 5, 6, 7), (f8, g1, f9, g2)),
    }
    out = []
    for name, (idx, (u0, v0, u1, v1)) in polys.items():
        if only and name not in only:
            continue
        pts = []
        for i in idx:
            q = _rot(p[i], *rot)
            pts.append((q[0] + offset[0], q[1] + offset[1], q[2] + offset[2]))
        out.append((pts, [(u1, v0), (u0, v0), (u0, v1), (u1, v1)]))
    return out


def render(quad_sets, size=(360, 360), yaw=35.0, pitch=28.0, scale=14.0, center=(8, 8, 8), bg=None):
    """Render textured quads orthographically.

    quad_sets: list of (texture Canvas, quads, world transform) drawn in order; later draws win ties
    like LEQUAL depth testing does.  World units are texels (16 per block), y up.
    """
    W, H = size
    img = Canvas(W, H, bg if bg else BG)
    zbuf = [[-1e9] * W for _ in range(H)]
    th, ph = math.radians(yaw), math.radians(pitch)
    cam = (math.sin(th) * math.cos(ph), math.sin(ph), math.cos(th) * math.cos(ph))
    right = (math.cos(th), 0.0, -math.sin(th))
    up = (-math.sin(th) * math.sin(ph), math.cos(ph), -math.cos(th) * math.sin(ph))

    def proj(pt):
        q = (pt[0] - center[0], pt[1] - center[1], pt[2] - center[2])
        return (W / 2 + scale * (q[0] * right[0] + q[2] * right[2]),
                H / 2 - scale * (q[0] * up[0] + q[1] * up[1] + q[2] * up[2]),
                q[0] * cam[0] + q[1] * cam[1] + q[2] * cam[2])

    for entry in quad_sets:
        tex, quads, xf = entry[0], entry[1], entry[2]
        cull = entry[3] if len(entry) > 3 else True
        for quad in quads:
            pts, uvs = quad[0], quad[1]
            wp = [xf(q) for q in pts]
            e1 = tuple(wp[0][i] - wp[1][i] for i in range(3))
            e2 = tuple(wp[2][i] - wp[1][i] for i in range(3))
            n = (e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
            ln = math.sqrt(sum(a * a for a in n)) or 1.0
            n = tuple(a / ln for a in n)
            # winding in ModelPart polygons is such that this cross product points inwards
            n_out = quad[2] if len(quad) > 2 else tuple(-a for a in n)
            facing = n_out[0] * cam[0] + n_out[1] * cam[1] + n_out[2] * cam[2]
            if cull and facing <= 0:
                continue
            shade = n_out[0] ** 2 * 0.62 + n_out[2] ** 2 * 0.82 + n_out[1] ** 2 * (1.0 if n_out[1] > 0 else 0.5)
            sp = [proj(q) for q in wp]
            ox, oy, oz = sp[1]
            ax, ay, az = sp[0][0] - ox, sp[0][1] - oy, sp[0][2] - oz
            bx, by, bz = sp[2][0] - ox, sp[2][1] - oy, sp[2][2] - oz
            det = ax * by - ay * bx
            if abs(det) < 1e-9:
                continue
            (ua, va), (uo, vo), (ub, vb) = uvs[0], uvs[1], uvs[2]
            xs, ys = [q[0] for q in sp], [q[1] for q in sp]
            for py in range(max(0, int(min(ys))), min(H, int(max(ys)) + 1)):
                for pxx in range(max(0, int(min(xs))), min(W, int(max(xs)) + 1)):
                    dx, dy = pxx + 0.5 - ox, py + 0.5 - oy
                    s = (dx * by - dy * bx) / det
                    t = (ax * dy - ay * dx) / det
                    if s < 0 or s >= 1 or t < 0 or t >= 1:
                        continue
                    depth = oz + s * az + t * bz
                    if depth < zbuf[py][pxx] - 1e-6:
                        continue
                    tu = uo + s * (ua - uo) + t * (ub - uo)
                    tv = vo + s * (va - vo) + t * (vb - vo)
                    tx, ty = min(tex.w - 1, max(0, int(tu))), min(tex.h - 1, max(0, int(tv)))
                    col = tex.px[ty][tx]
                    if col is None:
                        continue
                    k = shade if facing > 0 else shade * 0.7   # back faces (no-cull layers) a little darker
                    zbuf[py][pxx] = depth
                    img.px[py][pxx] = (int(col[0] * k), int(col[1] * k), int(col[2] * k), 255)
    return img


def ident(p):
    return p


def element_quads(frm, to, faces):
    """Quads of one JSON block-model element.  faces: {face: (u1, v1, u2, v2) in texels}."""
    (x0, y0, z0), (x1, y1, z1) = frm, to
    # per face: the corners that receive (u1,v1), (u2,v1), (u1,v2) under vanilla's default UV orientation
    corners = {
        "north": ((x1, y1, z0), (x0, y1, z0), (x1, y0, z0), (0, 0, -1)),
        "south": ((x0, y1, z1), (x1, y1, z1), (x0, y0, z1), (0, 0, 1)),
        "west": ((x0, y1, z0), (x0, y1, z1), (x0, y0, z0), (-1, 0, 0)),
        "east": ((x1, y1, z1), (x1, y1, z0), (x1, y0, z1), (1, 0, 0)),
        "up": ((x0, y1, z0), (x1, y1, z0), (x0, y1, z1), (0, 1, 0)),
        "down": ((x0, y0, z1), (x1, y0, z1), (x0, y0, z0), (0, -1, 0)),
    }
    out = []
    for face, (u1, v1, u2, v2) in faces.items():
        o, a, b, n = corners[face]
        d = tuple(a[i] + b[i] - o[i] for i in range(3))
        out.append(([a, o, b, d], [(u2, v1), (u1, v1), (u1, v2), (u2, v2)], n))
    return out


def chest_scene(tex, open_amount=0.0, **kw):
    """The chest exactly as LootrChestBlockRenderer draws it for a south-facing block: lid, lock, bottom."""
    ang = -(open_amount * math.pi / 2)
    lid = cube_quads(0, 0, 1, 0, 0, 14, 5, 14, offset=(0, 9, 1), rot=(ang, 0, 0))
    lock = cube_quads(0, 0, 7, -2, 14, 2, 4, 1, offset=(0, 9, 1), rot=(ang, 0, 0))
    bottom = cube_quads(0, 19, 1, 0, 1, 14, 10, 14)
    return render([(tex, lid, ident), (tex, lock, ident), (tex, bottom, ident)], **kw)


def shulker_scene(tex, progress=0.0, **kw):
    """As LootrShulkerBlockRenderer for facing=up: scale(1,-1,-1), translate(0,-1,0); base then lid, no culling."""
    def xf(p):
        return (p[0] + 8, 24 - p[1], 8 - p[2])
    lid = cube_quads(0, 0, -8, -16, -8, 16, 12, 16, offset=(0, 24 - progress * 8, 0), rot=(0, math.radians(270 * progress), 0))
    base = cube_quads(0, 28, -8, -8, -8, 16, 8, 16, offset=(0, 24, 0))
    return render([(tex, base, xf, False), (tex, lid, xf, False)], **kw)


def pot_scene(base, side, **kw):
    """Sealed pot as LootrDecoratedPotRenderer draws it (walls use the sherd materials; 'side' stands in)."""
    pi = math.pi
    neck = (cube_quads(0, 0, 4, 17, 4, 8, 3, 8, grow=-0.1, offset=(0, 37, 16), rot=(pi, 0, 0))
            + cube_quads(0, 5, 5, 20, 5, 6, 1, 6, grow=0.2, offset=(0, 37, 16), rot=(pi, 0, 0)))
    top = cube_quads(-14, 13, 0, 0, 0, 14, 0, 14, offset=(1, 16, 1), only=("down", "up"))
    bottom = cube_quads(-14, 13, 0, 0, 0, 14, 0, 14, offset=(1, 0, 1), only=("down", "up"))
    walls = []
    for off, rot in (((15, 16, 1), (0, 0, pi)), ((1, 16, 1), (0, -pi / 2, pi)),
                     ((15, 16, 15), (0, pi / 2, pi)), ((1, 16, 15), (pi, 0, 0))):
        walls += cube_quads(1, 0, 0, 0, 0, 14, 16, 0, offset=off, rot=rot, only=("north",))
    return render([(base, neck + top + bottom, ident), (side, walls, ident)], **kw)


def pot_open_scene(tex, **kw):
    """Looted pot: LootrDecoratedPotRenderer.createBodyLayer under scale(1,-1,-1) at the block centre."""
    def xf(p):
        return (8 + p[0], -p[1], 8 - p[2])
    q = cube_quads(17, 21, -0.5, -0.5, -4.5, 5, 1, 6, offset=(3.5, -3.5, 1.5), rot=(0, 0, 0.829))
    q += cube_quads(14, 19, -4.5, -0.5, -4.5, 9, 1, 8, offset=(-1, -1.5, 0.5), rot=(0, 0.1309, 0))
    q += cube_quads(4, 16, -4.5, -0.5, -3.5, 10, 1, 11, offset=(-1.5, -0.5, -1.5), rot=(0, -0.3054, 0))
    for u, v, box in ((0, 0, (-4, -0.5, -4, 8, 3, 8)), (0, 5, (-2.8257, 2.4924, -3, 6, 1, 6))):
        q += cube_quads(u, v, *box, offset=(-1.75, -5, 0), rot=(0, -0.1309, -0.0873))
    return render([(tex, q, xf)], **kw)


def cube_scene(top, side, bottom, **kw):
    """A cube_bottom_top block."""
    full = (0, 0, 16, 16)
    frm, to = (0, 0, 0), (16, 16, 16)
    return render([(side, element_quads(frm, to, {f: full for f in ("north", "south", "west", "east")}), ident),
                   (top, element_quads(frm, to, {"up": full}), ident),
                   (bottom, element_quads(frm, to, {"down": full}), ident)], **kw)


def model_scene(model, tex, tex_px, **kw):
    """A JSON block model with one texture; uv is in 1/16ths of the texture."""
    k = tex_px / 16.0
    quads = []
    for el in model["elements"]:
        faces = {f: tuple(v * k for v in d["uv"]) for f, d in el["faces"].items()}
        quads += element_quads(tuple(el["from"]), tuple(el["to"]), faces)
    return render([(tex, quads, ident)], **kw)


# minecraft:block/template_item_frame (layout facts only): which wood/back texels each face shows
ITEM_FRAME = [
    ((3, 3, 15.5), (13, 13, 16), "back", {"north": (3, 3, 13, 13), "south": (3, 3, 13, 13)}),
    ((2, 2, 15), (14, 3, 16), "wood", {"down": (2, 0, 14, 1), "up": (2, 15, 14, 16), "north": (2, 13, 14, 14),
                                       "south": (2, 13, 14, 14), "west": (15, 13, 16, 14), "east": (0, 13, 1, 14)}),
    ((2, 13, 15), (14, 14, 16), "wood", {"down": (2, 0, 14, 1), "up": (2, 15, 14, 16), "north": (2, 2, 14, 3),
                                         "south": (2, 2, 14, 3), "west": (15, 2, 16, 3), "east": (0, 2, 1, 3)}),
    ((2, 3, 15), (3, 13, 16), "wood", {"north": (13, 3, 14, 13), "south": (2, 3, 3, 13), "west": (15, 3, 16, 13), "east": (0, 3, 1, 13)}),
    ((13, 3, 15), (14, 13, 16), "wood", {"north": (2, 3, 3, 13), "south": (13, 3, 14, 13), "west": (15, 3, 16, 13), "east": (0, 3, 1, 13)}),
]


def frame_scene(wood, **kw):
    """The loot item frame.  The back plate is vanilla's block/item_frame; a flat stand-in is used here."""
    back = Canvas(16, 16, rgb("8a6a4a"))
    sets = []
    for frm, to, which, faces in ITEM_FRAME:
        sets.append((wood if which == "wood" else back, element_quads(frm, to, faces), ident))
    return render(sets, **kw)


def row_of(images, labels=None, pad=8):
    w = sum(i.w for i in images) + pad * (len(images) + 1)
    h = max(i.h for i in images) + pad * 2 + (14 if labels else 0)
    out = Canvas(w, h, BG)
    x = pad
    for n, im in enumerate(images):
        out.blit(im, x, pad)
        if labels:
            text(out, x, pad + im.h + 3, labels[n], k=2 if len(labels[n]) * 8 <= im.w else 1)
        x += im.w + pad
    return out


def stack(images, pad=0):
    out = Canvas(max(i.w for i in images), sum(i.h for i in images) + pad * (len(images) - 1), BG)
    y = 0
    for im in images:
        out.blit(im, 0, y)
        y += im.h + pad
    return out


def tiled(tile, n=3):
    c = Canvas(tile.w * n, tile.h * n)
    for j in range(n):
        for i in range(n):
            c.blit(tile, i * tile.w, j * tile.h)
    return c


def grey(c):
    """Luminance-only copy: the colour-blind check for sealed / looted pairs."""
    out = c.copy()
    for row in out.px:
        for x, p in enumerate(row):
            if p is not None:
                g = int(0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2])
                row[x] = (g, g, g, p[3])
    return out


# ---------------------------------------------------------------------------
# 9. main
# ---------------------------------------------------------------------------

def build_textures():
    """name (relative to the lootr textures folder, no extension) -> Canvas, in a stable order."""
    sealed, looted = Theme(False), Theme(True)
    tex = {}
    for prefix, th in (("", sealed), ("opened_", looted)):
        tex["block/%sbarrel_top" % prefix] = barrel_end(th, "lid")
        tex["block/%sbarrel_top_open" % prefix] = barrel_end(th, "open")
        tex["block/%sbarrel_side" % prefix] = barrel_side(th)
        tex["block/%sbarrel_bottom" % prefix] = barrel_end(th, "bottom")
    tex["block/gold_planks"] = inlay_planks(sealed)
    tex["block/silver_planks"] = inlay_planks(looted)
    tex["block/suspicious_sand_open"] = brushed(SAND_OPEN, SAND)
    tex["block/suspicious_gravel_open"] = brushed(GRAVEL_OPEN, GRAVEL)
    tex["block/trophy"] = trophy_texture()
    tex["chest"] = chest_sheet(False, False)
    tex["chest_opened"] = chest_sheet(True, False)
    tex["chest_trapped"] = chest_sheet(False, True)
    tex["chest_trapped_opened"] = chest_sheet(True, True)
    tex["shulker"] = shulker_sheet(False)
    tex["shulker_opened"] = shulker_sheet(True)
    tex["entity/loot_pot"] = pot_sheet()
    tex["entity/loot_pot_open"] = pot_open_sheet()
    tex["entity/decorated_pot/loot_pottery_pattern"] = pot_pattern()
    tex["gui/sprites/toast/container_alert"] = toast_background()
    tex["gui/sprites/toast/decay"] = decay_icon()
    tex["gui/sprites/toast/refresh"] = refresh_icon()
    return tex


def build_previews(tex, model, native):
    """Preview sheets: name -> Canvas.  Nothing in here ships with the mod."""
    out = {}

    def P(name, canvas):
        out[name] = canvas

    def short(k):
        return k.split("/")[-1]

    blocks = [(short(k), v) for k, v in tex.items() if v.w == 16 and v.h == 16]
    P("blocks_x8", sheet_of(blocks, 8, 4, title="16 X 16 TEXTURES X8"))
    P("blocks_tiled", sheet_of([(short(k), tiled(tex[k])) for k in (
        "block/gold_planks", "block/silver_planks", "block/barrel_side", "block/opened_barrel_side",
        "block/suspicious_sand_open", "block/suspicious_gravel_open")], 5, 3, title="TILING 3 X 3"))
    chests = ("chest", "chest_opened", "chest_trapped", "chest_trapped_opened")
    pots = ("entity/loot_pot", "entity/loot_pot_open", "entity/decorated_pot/loot_pottery_pattern")
    P("chest_sheets_x8", sheet_of([(k, tex[k]) for k in chests], 8, 2, title="CHEST SHEETS X8"))
    P("shulker_sheets_x8", sheet_of([(k, tex[k]) for k in ("shulker", "shulker_opened")], 8, 2, title="SHULKER SHEETS X8"))
    P("pot_sheets_x8", sheet_of([(short(k), tex[k]) for k in pots], 8, 3, title="POT SHEETS X8"))
    P("trophy_x8", sheet_of([("trophy", tex["block/trophy"])], 8, 1))
    P("gui_x8", sheet_of([("container_alert", tex["gui/sprites/toast/container_alert"]), ("decay", tex["gui/sprites/toast/decay"]),
                          ("refresh", tex["gui/sprites/toast/refresh"])], 8, 1, title="GUI SPRITES X8"))

    S = (280, 280)
    rows = []
    for k in chests:
        c = tex[k]
        far = chest_scene(c, 0.0, size=(110, 110), yaw=30, pitch=25, scale=4.0)
        rows.append(row_of([chest_scene(c, 0.0, size=S, yaw=30, pitch=25), chest_scene(c, 0.0, size=S, yaw=-145, pitch=30),
                            chest_scene(c, 0.8, size=S, yaw=25, pitch=35, scale=12), far, grey(far)],
                           [k, "BACK", "LID UP", "FAR", "GREY"]))
    P("chest_3d", stack(rows))

    rows = []
    for k in ("shulker", "shulker_opened"):
        c = tex[k]
        far = shulker_scene(c, 0.0, size=(110, 110), yaw=30, pitch=25, scale=4.0)
        rows.append(row_of([shulker_scene(c, 0.0, size=S, yaw=30, pitch=25, scale=11),
                            shulker_scene(c, 0.5, size=S, yaw=30, pitch=25, scale=10, center=(8, 10, 8)),
                            shulker_scene(c, 1.0, size=S, yaw=30, pitch=40, scale=10, center=(8, 12, 8)), far, grey(far)],
                           [k, "HALF OPEN", "OPEN", "FAR", "GREY"]))
    P("shulker_3d", stack(rows))

    rows = []
    for prefix in ("", "opened_"):
        def g(n):
            return tex["block/%sbarrel_%s" % (prefix, n)]
        far = cube_scene(g("top"), g("side"), g("bottom"), size=(110, 110), yaw=30, pitch=25, scale=4.0)
        rows.append(row_of([cube_scene(g("top"), g("side"), g("bottom"), size=S, yaw=30, pitch=25, scale=11),
                            cube_scene(g("top_open"), g("side"), g("bottom"), size=S, yaw=30, pitch=35, scale=11),
                            cube_scene(g("top"), g("side"), g("bottom"), size=S, yaw=30, pitch=-30, scale=11), far, grey(far)],
                           [prefix + "barrel", "LID OPEN", "FROM BELOW", "FAR", "GREY"]))
    P("barrel_3d", stack(rows))

    pat = tex["entity/decorated_pot/loot_pottery_pattern"]
    P("pot_3d", row_of([pot_scene(tex["entity/loot_pot"], pat, size=S, yaw=30, pitch=25, scale=11, center=(8, 10, 8)),
                        pot_scene(tex["entity/loot_pot"], pat, size=S, yaw=30, pitch=60, scale=11, center=(8, 10, 8)),
                        pot_open_scene(tex["entity/loot_pot_open"], size=S, yaw=30, pitch=35, scale=14, center=(8, 3, 8)),
                        pot_open_scene(tex["entity/loot_pot_open"], size=S, yaw=-150, pitch=55, scale=14, center=(8, 3, 8))],
                       ["LOOT POT", "FROM ABOVE", "LOOTED (BROKEN)", "LOOTED BACK"]))

    tt = tex["block/trophy"]
    P("trophy_3d", row_of([model_scene(model, tt, 32, size=S, yaw=-155, pitch=25, scale=15, center=(8, 7, 8)),
                           model_scene(model, tt, 32, size=S, yaw=-180, pitch=10, scale=15, center=(8, 7, 8)),
                           model_scene(model, tt, 32, size=S, yaw=40, pitch=40, scale=15, center=(8, 7, 8)),
                           model_scene(model, tt, 32, size=(64, 64), yaw=-135, pitch=30, scale=3.2, center=(8, 7, 8))],
                          ["TROPHY (FRONT = NORTH)", "FRONT", "BACK ABOVE", "ITEM"]))

    P("item_frame_3d", row_of([frame_scene(tex["block/gold_planks"], size=S, yaw=-160, pitch=15, scale=16, center=(8, 8, 15)),
                               frame_scene(tex["block/silver_planks"], size=S, yaw=-160, pitch=15, scale=16, center=(8, 8, 15))],
                              ["FRAME GOLD (UNLOOTED)", "FRAME SILVER (LOOTED)"]))

    # toast: background, icon at 6,6 and stand-in text lines where LootrToast draws them
    toasts = []
    for icon, title, msg in (("decay", "CONTAINER DECAYING", "THIS CHEST WILL DECAY SOON"),
                             ("refresh", "CONTAINER REFRESHED", "NEW LOOT IS WAITING")):
        t = tex["gui/sprites/toast/container_alert"].copy()
        t.blit(tex["gui/sprites/toast/" + icon], 6, 6)
        text(t, 30, 8, title, rgb("500050"), 1)
        text(t, 30, 19, msg, rgb("000000"), 1)
        toasts.append(t.scaled(4).over(BG))
    P("toast", stack([row_of([t]) for t in toasts]))
    P("logo", row_of([native.scaled(16).over(BG), native.scaled(4).over(BG), native.scaled(2).over(BG), native.over(BG),
                      native.scaled(4).over(rgb("f2f2f2"))], ["LOGO X16", "X4", "X2", "X1", "ON LIGHT"]))
    return out


def build_all(previews=True):
    """Everything this script owns, as a list of (absolute path, file bytes) in a stable order."""
    files = []
    tex = build_textures()
    for name, canvas in tex.items():
        files.append((os.path.join(TEX, *name.split("/")) + ".png", png_bytes(canvas)))

    model = trophy_model()
    model_bytes = model_json(model).encode("utf-8")
    assert json.loads(model_bytes) == model
    files.append((os.path.join(ASSETS, "lootr", "models", "block", "trophy.json"), model_bytes))

    native = logo()
    files.append((os.path.join(ART, "logo", "logo_native.png"), png_bytes(native)))
    files.append((os.path.join(ART, "logo", "logo_512.png"), png_bytes(native.scaled(16))))
    files.append((os.path.join(ROOT, "fabric", "src", "main", "resources", "icon.png"), png_bytes(native.scaled(8))))
    for pack in ("lootr_no_advancements", "lootr_no_suspicious_blocks"):
        files.append((os.path.join(ROOT, "datapacks", pack, "pack.png"), png_bytes(native.scaled(2))))

    if previews:
        for name, canvas in build_previews(tex, model, native).items():
            files.append((os.path.join(PREVIEW, name + ".png"), png_bytes(canvas)))
    return files


def main():
    files = build_all(previews="--no-previews" not in sys.argv)
    for path, data in files:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
    if "--check" in sys.argv:
        for path, data in sorted(files):
            print(hashlib.sha256(data).hexdigest(), os.path.relpath(path, ROOT).replace("\\", "/"))
    print("wrote %d files" % len(files))


if __name__ == "__main__":
    main()
