#!/usr/bin/env python3
"""Checks for the generated art.  Standard library only.

    python art/verify.py [git-revision]

For every shipped image this re-reads the file from disk with the generator's own PNG reader,
checks its dimensions, checks that it is exactly what generate.py produces, and compares it with
the same path at the given git revision (default HEAD): byte identity and the share of pixels
that differ.  It also checks the trophy model.  Exit code 1 if any check fails.
"""
import hashlib
import json
import os
import subprocess
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import generate as g  # noqa: E402

T = "common/src/main/resources/assets/lootr/textures/"
EXPECTED = {
    T + "block/barrel_top.png": (16, 16), T + "block/barrel_top_open.png": (16, 16),
    T + "block/barrel_side.png": (16, 16), T + "block/barrel_bottom.png": (16, 16),
    T + "block/opened_barrel_top.png": (16, 16), T + "block/opened_barrel_top_open.png": (16, 16),
    T + "block/opened_barrel_side.png": (16, 16), T + "block/opened_barrel_bottom.png": (16, 16),
    T + "block/gold_planks.png": (16, 16), T + "block/silver_planks.png": (16, 16),
    T + "block/suspicious_gravel_open.png": (16, 16), T + "block/suspicious_sand_open.png": (16, 16),
    T + "block/trophy.png": (32, 32),
    T + "chest.png": (64, 64), T + "chest_opened.png": (64, 64),
    T + "chest_trapped.png": (64, 64), T + "chest_trapped_opened.png": (64, 64),
    T + "shulker.png": (64, 64), T + "shulker_opened.png": (64, 64),
    T + "entity/loot_pot.png": (32, 32), T + "entity/loot_pot_open.png": (64, 32),
    T + "entity/decorated_pot/loot_pottery_pattern.png": (16, 16),
    T + "gui/sprites/toast/container_alert.png": (160, 32),
    T + "gui/sprites/toast/decay.png": (20, 20), T + "gui/sprites/toast/refresh.png": (20, 20),
    "fabric/src/main/resources/icon.png": (256, 256),
    "datapacks/lootr_no_advancements/pack.png": (64, 64),
    "datapacks/lootr_no_suspicious_blocks/pack.png": (64, 64),
    "art/logo/logo_native.png": (32, 32), "art/logo/logo_512.png": (512, 512),
}
# sheets on which nothing may be see-through (drawn by solid render types / tiled / GUI background)
OPAQUE = [T + "block/" + n + ".png" for n in (
    "barrel_top", "barrel_top_open", "barrel_side", "barrel_bottom", "opened_barrel_top", "opened_barrel_top_open",
    "opened_barrel_side", "opened_barrel_bottom", "gold_planks", "silver_planks", "suspicious_gravel_open",
    "suspicious_sand_open", "trophy")]


def git_show(rev, path):
    r = subprocess.run(["git", "show", "%s:%s" % (rev, path)], cwd=g.ROOT, capture_output=True)
    return r.stdout if r.returncode == 0 else None


def main():
    rev = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
    ok = True
    files = g.build_all(previews=False)
    produced = {os.path.relpath(p, g.ROOT).replace("\\", "/"): d for p, d in files}

    print("%-52s %-8s %-6s %-10s %s" % ("file", "size", "fresh", "vs " + rev, "pixels changed (of all / of drawn)"))
    for path, want in EXPECTED.items():
        name = path[len(T):] if path.startswith(T) else path
        data = open(os.path.join(g.ROOT, path), "rb").read()
        try:
            w, h, px = g.read_png(data)                  # also validates signature, chunk CRCs and the zlib stream
        except Exception as e:  # noqa: BLE001
            print("%-52s UNREADABLE: %s   <-- FAIL" % (name, e))
            ok = False
            continue
        fresh = produced.get(path) == data
        old = git_show(rev, path)
        if old is None:
            ident, diff = "new file", "-"
        else:
            ident = "IDENTICAL" if old == data else "differs"
            ow, oh, opx = g.read_png(old)
            if (ow, oh) != (w, h):
                diff = "n/a (was %dx%d)" % (ow, oh)
            else:
                # 'drawn' = pixels that are not fully transparent in at least one of the two images
                drawn = [(px[y][x], opx[y][x]) for y in range(h) for x in range(w) if px[y][x][3] or opx[y][x][3]]
                n = sum(1 for new, was in drawn if new != was)
                diff = "%5.1f%% / %5.1f%%  (%d px)" % (100.0 * n / (w * h), 100.0 * n / max(1, len(drawn)), n)
        good = (w, h) == want and fresh and ident != "IDENTICAL"
        if path in OPAQUE and any(q[3] != 255 for row in px for q in row):
            good = False
            diff += "  NOT OPAQUE"
        ok = ok and good
        print("%-52s %-8s %-6s %-10s %s%s" % (name, "%dx%d" % (w, h), "yes" if fresh else "NO", ident, diff,
                                              "" if good else "   <-- FAIL"))

    # trophy model: valid JSON, only existing textures, no player skin
    mpath = "common/src/main/resources/assets/lootr/models/block/trophy.json"
    text = open(os.path.join(g.ROOT, mpath), encoding="utf-8").read()
    model = json.loads(text)
    problems = []
    for key, ref in model["textures"].items():
        ns, _, name = ref.partition(":")
        if ns != "lootr" or not os.path.isfile(os.path.join(g.ROOT, T, name + ".png")):
            problems.append("texture %s -> %s does not exist" % (key, ref))
    if "particle" not in model["textures"]:
        problems.append("no particle texture")
    if "steve" in text or "entity/player" in text:
        problems.append("player skin still referenced")
    for el in model["elements"]:
        for face, d in el["faces"].items():
            if d["texture"].lstrip("#") not in model["textures"]:
                problems.append("%s.%s uses unknown texture %s" % (el["name"], face, d["texture"]))
            if not all(0 <= v <= 16 for v in d["uv"]):
                problems.append("%s.%s uv out of range" % (el["name"], face))
        if not all(0 <= v <= 16 for v in el["from"] + el["to"]):
            problems.append("%s leaves the block" % el["name"])
    if produced.get(mpath) != text.encode("utf-8"):
        problems.append("trophy.json on disk is not what generate.py produces")
    for rel in ("common/src/main/resources/assets/lootr/blockstates/trophy.json",
                "common/src/main/resources/assets/lootr/models/item/trophy.json"):
        if "lootr:block/trophy" not in open(os.path.join(g.ROOT, rel), encoding="utf-8").read():
            problems.append(rel + " does not point at lootr:block/trophy")
    atlas = open(os.path.join(g.ROOT, "common/src/main/resources/assets/minecraft/atlases/blocks.json"), encoding="utf-8").read()
    json.loads(atlas)
    if "entity/player" in atlas:
        problems.append("blocks atlas still stitches the player skin")
    if os.path.exists(os.path.join(g.ROOT, T, "logo.png")):
        problems.append("textures/logo.png still exists")
    print("\ntrophy model / atlas / logo: %s" % ("ok (%d elements)" % len(model["elements"]) if not problems else "; ".join(problems)))
    ok = ok and not problems

    digest = hashlib.sha256(b"".join(hashlib.sha256(d).digest() for _, d in sorted(files))).hexdigest()
    print("combined sha256 of all %d generated files (no previews): %s" % (len(files), digest))
    print("RESULT: %s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
