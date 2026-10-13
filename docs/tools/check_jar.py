#!/usr/bin/env python3
"""Licence check of a built Lootr Remastered jar.  Python 3 standard library plus git.

    python docs/tools/check_jar.py versions/1.21.1/fabric/build/libs/lootr-remastered-fabric-1.21.1-2.1.0.jar [upstream-revision]

Run it from the repository root (the hub branch main; the jar is built on a version branch, here the
worktree versions/1.21.1).  The upstream revision defaults to b62e382a (Lootr 1.11.38.128), the commit this
project started from; it is looked up in the history of this repository.  The licence files are compared
with LICENSE and LICENSE_ASSETS.txt of the current directory.  The script reads the jar and git; it
changes nothing, and it needs no game resources of the working tree (so LOOTR_VERSION_DIR does not apply).

It reports
  1. entries whose names must not be in the jar (old artwork, source art, other loaders, tests, ...);
  2. every non-class entry that is byte-identical to a file of the upstream revision, with the
     upstream path: images and sounds identical to upstream are a FAIL; a JSON file identical to
     one in upstream's hand-written assets folder is EXPECTED if it is one of the six that cannot
     differ (EXPECTED_IDENTICAL, see docs/licence-audit.md, 1b) and a FAIL otherwise; files that
     upstream's data generator wrote are listed as GENERATED;
  3. text found in entries that points at third-party projects (see PATTERNS);
  4. whether LICENSE and LICENSE_ASSETS.txt are at the jar root and equal the files of the repository
     (line endings ignored), and what fabric.mod.json says under "breaks".

Exit code 1 if there is at least one FAIL.
"""
import hashlib
import json
import re
import subprocess
import sys
import zipfile

JAR = sys.argv[1] if len(sys.argv) > 1 else None
UPSTREAM = sys.argv[2] if len(sys.argv) > 2 else "b62e382a"

CRLF = bytes([13, 10])
LF = bytes([10])

# Entry names that must not exist in the jar.
FORBIDDEN_NAMES = [
    (r"^assets/lootr/textures/(.*/)?old_", "texture of the old-textures pack"),
    (r"^resourcepacks/", "built-in resource pack"),
    (r"^assets/minecraft/atlases/blocks\.json$", "block atlas of the old textures"),
    (r"\.(psd|bbmodel|xcf|kra|aseprite)$", "source file of artwork"),
    (r"(^|/)logo\.png$", "logo of the original Lootr"),
    (r"^assets/lootr/lang/(?!en_us\.json$|de_de\.json$)", "language file other than en_us and de_de"),
    (r"^(META-INF/)?neoforge|accesstransformer|neoforge\.mods\.toml", "NeoForge file"),
    (r"gametest", "game test material"),
    (r"(^|/)\.cache/", "data generator cache"),
    (r"^art/|^\.knowledge/", "repository folder that is not part of the mod"),
    (r"\.ogg$", "sound file (this mod ships none)"),
    (r"^lootintegrations|/lootintegrations", "namespace or folder of Loot Integrations"),
]

# Text that points at third-party projects.  The first two are expected in a few entries (see
# docs/licence-audit.md, point 3); elsewhere they are listed as CHECK: look at the entry.  Every other
# pattern is a FAIL wherever it is found.
PATTERNS = [b"lootintegrations", b"elocindev", b"someaddons", b"noobanidus.com",
            b"curseforge.com/minecraft/mc-mods/lootr", b"615106", b"361276", b"EltpO5cN", b"LootrMinecraft"]
EXPECTED_TEXT = {
    b"lootintegrations": ["LootIntegrationsImport.class", "LootrConfigInit.class", "assets/lootr/lang/de_de.json",
                          "fabric.mod.json"],  # fabric.mod.json: the "breaks" entry
    b"elocindev": ["ObliteratorBanSource.class"],
    b"LootrMinecraft": ["LICENSE_ASSETS.txt"],  # it names the original repository
}

# Hand-written files of upstream's assets folder that are byte-identical in our tree and cannot be otherwise:
# a block state with one variant that points at one model, and an item model with one parent line.
EXPECTED_IDENTICAL = {
    "assets/lootr/blockstates/decorated_pot.json", "assets/lootr/blockstates/lootr_chest.json",
    "assets/lootr/blockstates/lootr_inventory.json", "assets/lootr/blockstates/lootr_shulker.json",
    "assets/lootr/blockstates/lootr_trapped_chest.json", "assets/lootr/models/item/trophy.json",
}
# Licence files that must be at the jar root.
LICENCE_FILES = ["LICENSE", "LICENSE_ASSETS.txt"]

TEXT_SUFFIX = (".json", ".mcmeta", ".txt", ".cfg", ".toml", ".properties", ".md", ".accesswidener", ".mf")
BINARY_SUFFIX = (".png", ".ogg", ".jpg", ".jpeg", ".gif", ".psd", ".bbmodel", ".ttf", ".otf", ".wav", ".mp3")


def git_blob_hash(data):
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def upstream_blobs(rev):
    out = subprocess.run(["git", "ls-tree", "-r", "-z", rev], capture_output=True)
    if out.returncode != 0:
        sys.exit("git ls-tree %s failed: %s" % (rev, out.stderr.decode("utf-8", "replace")))
    blobs = {}
    for record in out.stdout.decode("utf-8").split("\0"):
        if record:
            meta, path = record.split("\t", 1)
            blobs.setdefault(meta.split()[2], []).append(path)
    return blobs


def main():
    if not JAR:
        sys.exit(__doc__)
    blobs = upstream_blobs(UPSTREAM)
    fails = 0
    with zipfile.ZipFile(JAR) as zf:
        names = [n for n in zf.namelist() if not n.endswith("/")]
        print("%s: %d entries (%d classes)" % (JAR, len(names), sum(n.endswith(".class") for n in names)))

        print("\n== 1. forbidden entry names")
        found = False
        for name in names:
            for pattern, why in FORBIDDEN_NAMES:
                if re.search(pattern, name):
                    print("FAIL  %s  (%s)" % (name, why))
                    fails += 1
                    found = True
        if not found:
            print("none")

        print("\n== 2. entries identical to a file of %s" % UPSTREAM)
        counts = {"GENERATED": 0, "EXPECTED": 0}
        for name in sorted(names):
            if name.endswith(".class"):
                continue
            data = zf.read(name)
            hit = blobs.get(git_blob_hash(data))
            if not hit and name.lower().endswith(TEXT_SUFFIX):
                # a checkout with CRLF line endings builds a jar whose text differs from the git blob only there
                hit = blobs.get(git_blob_hash(data.replace(CRLF, LF)))
            if not hit:
                continue
            origin = hit[0]
            if name.lower().endswith(BINARY_SUFFIX):
                print("FAIL  %s  identical to %s" % (name, origin))
                fails += 1
            elif "/src/generated/" in origin or origin.startswith("datapacks/"):
                counts["GENERATED"] += 1
                print("GENERATED  %s  <- %s" % (name, origin))
            elif "/src/main/resources/assets/" in origin:
                if name in EXPECTED_IDENTICAL:
                    counts["EXPECTED"] += 1
                    print("EXPECTED  %s  <- %s  (cannot differ)" % (name, origin))
                else:
                    print("FAIL  %s  identical to hand-written upstream file %s" % (name, origin))
                    fails += 1
            else:
                print("OTHER  %s  <- %s" % (name, origin))
        print("-- %d written by upstream's data generator, %d from upstream's hand-written assets folder (6 expected)"
              % (counts["GENERATED"], counts["EXPECTED"]))

        print("\n== 3. text that points at third-party projects")
        any_hit = False
        for name in sorted(names):
            if not (name.endswith(".class") or name.lower().endswith(TEXT_SUFFIX)):
                continue
            data = zf.read(name)
            for pattern in PATTERNS:
                if pattern not in data:
                    continue
                short = name.rsplit("/", 1)[-1]
                allowed = EXPECTED_TEXT.get(pattern)
                if allowed is not None and (name in allowed or short in allowed):
                    verdict = "expected"
                elif allowed is not None:
                    verdict = "CHECK   "
                else:
                    verdict = "FAIL    "
                    fails += 1
                print("%s  %s contains %r" % (verdict, name, pattern.decode()))
                any_hit = True
        if not any_hit:
            print("none")

        print(chr(10) + "== 4. licence files and breaks entry")
        for licence in LICENCE_FILES:
            if licence not in names:
                print("FAIL  %s is not at the jar root" % licence)
                fails += 1
                continue
            try:
                with open(licence, "rb") as f:
                    same = f.read().replace(CRLF, LF) == zf.read(licence).replace(CRLF, LF)
            except OSError:
                print("WARN  %s is in the jar; no copy to compare with (run from the repository root)" % licence)
                continue
            if same:
                print("ok    %s is in the jar and equals the repository file" % licence)
            else:
                print("FAIL  %s in the jar differs from the repository file" % licence)
                fails += 1
        if "fabric.mod.json" in names:
            text = zf.read("fabric.mod.json").decode("utf-8")
            print("breaks: %s" % json.dumps(json.loads(text).get("breaks")))
            if "${" in text:
                print("FAIL  fabric.mod.json still contains an unexpanded ${...}")
                fails += 1
        else:
            print("FAIL  no fabric.mod.json")
            fails += 1

    print("\nresult: %s" % ("FAIL (%d)" % fails if fails else "no FAIL"))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
