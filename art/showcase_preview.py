#!/usr/bin/env python3
"""Lootr Remastered -- offline preview of the CurseForge description, and a checker for its images.

    python art/showcase_preview.py            write docs/curseforge/preview.html from description.md
    python art/showcase_preview.py --check    write nothing; verify markers, images and that preview.html is current

The description carries one paste marker per image, on a line of its own:

    >>> PASTE IMAGE: file_name.png | Alt text <<<

The preview turns each marker into an <img> that points at docs/curseforge/img/, so the page can be
opened from docs/curseforge/ without a network.  Only the Markdown the description uses is handled:
# and ## headings, paragraphs, "-" lists, "1." lists, **bold**, `code` and bare https:// links.
The output is deterministic (no date, no absolute path).  Python standard library only.
"""
import html
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs", "curseforge")
DESCRIPTION = os.path.join(DOCS, "description.md")
PREVIEW = os.path.join(DOCS, "preview.html")
IMG = os.path.join(DOCS, "img")

MAX_WIDTH, MAX_BYTES = 1000, 400000
MARKER = re.compile(r"^>>> PASTE IMAGE: ([A-Za-z0-9_]+\.png) \| ([^|<>]+?) <<<$")
INLINE = re.compile(r"`([^`]+)`|\*\*(.+?)\*\*|(https://[^\s<>]+)")
ORDERED = re.compile(r"^\d+\. (.*)$")

NOTE = "Preview of docs/curseforge/description.md - not the live page"
CSS = """\
:root { color-scheme: dark; }
body { margin: 0; padding: 0 16px 48px; background: #0f1a22; color: #dbe5ea;
  font: 16px/1.6 system-ui, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
.note { max-width: 1000px; margin: 0 auto; padding: 10px 0; color: #8fa3ad; font-size: 13px;
  border-bottom: 1px solid #243845; }
main { max-width: 1000px; margin: 0 auto; }
h1, h2 { color: #f2f6f8; line-height: 1.25; }
h1 { margin: 28px 0 16px; font-size: 32px; }
h2 { margin: 36px 0 12px; padding-bottom: 6px; font-size: 24px; border-bottom: 1px solid #243845; }
p, ul, ol { margin: 0 0 14px; }
li { margin: 3px 0; }
strong { color: #ffffff; }
a { color: #5fd6c8; }
code { padding: 1px 5px; border-radius: 3px; background: #1a2a35; color: #f0d68a;
  font: 14px/1.4 Consolas, "Courier New", monospace; }
img { display: block; max-width: 100%; height: auto; margin: 18px 0; image-rendering: pixelated; }
"""


# ---------------------------------------------------------------------------
# Markdown -> HTML
# ---------------------------------------------------------------------------


def inline(text):
    """Escaped HTML for one run of text: code spans, bold and bare links."""
    out, pos = [], 0
    for m in INLINE.finditer(text):
        out.append(html.escape(text[pos:m.start()], quote=False))
        code, bold, link = m.groups()
        if code is not None:
            out.append("<code>%s</code>" % html.escape(code, quote=False))
        elif bold is not None:
            out.append("<strong>%s</strong>" % inline(bold))
        else:
            tail = ""
            while link[-1] in ".,;:)":                  # punctuation after a link is not part of it
                link, tail = link[:-1], link[-1] + tail
            out.append('<a href="%s">%s</a>%s' % (html.escape(link), html.escape(link, quote=False), html.escape(tail, quote=False)))
        pos = m.end()
    out.append(html.escape(text[pos:], quote=False))
    return "".join(out)


def read_description():
    with open(DESCRIPTION, encoding="utf-8", newline=None) as f:   # newline=None turns CRLF into \n
        return f.read().split("\n")


def markers(lines):
    """[(line number, text)] of every line that looks like a paste marker, valid or not."""
    return [(n + 1, ln) for n, ln in enumerate(lines) if ln.lstrip().startswith(">>>") or "PASTE IMAGE" in ln]


def render(lines):
    body, para, items, kind = [], [], [], None

    def flush():
        nonlocal para, items, kind
        if para:
            body.append("<p>%s</p>" % inline(" ".join(para)))
        if items:
            body.append("<%s>\n%s\n</%s>" % (kind, "\n".join("<li>%s</li>" % inline(i) for i in items), kind))
        para, items, kind = [], [], None

    for ln in lines:
        ln = ln.rstrip()
        om = ORDERED.match(ln)
        if not ln:
            flush()
        elif MARKER.match(ln):
            flush()
            name, alt = MARKER.match(ln).groups()
            body.append('<img src="img/%s" alt="%s">' % (name, html.escape(alt)))
        elif ln.startswith("## "):
            flush()
            body.append("<h2>%s</h2>" % inline(ln[3:]))
        elif ln.startswith("# "):
            flush()
            body.append("<h1>%s</h1>" % inline(ln[2:]))
        elif ln.startswith("- ") or om:
            want = "ul" if ln.startswith("- ") else "ol"
            if kind != want:
                flush()
                kind = want
            items.append(ln[2:] if want == "ul" else om.group(1))
        else:
            if items:
                flush()
            para.append(ln)
    flush()
    return ("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
            "<title>Lootr Remastered - description preview</title>\n<style>\n%s</style>\n</head>\n<body>\n"
            "<div class=\"note\">%s</div>\n<main>\n%s\n</main>\n</body>\n</html>\n" % (CSS, NOTE, "\n".join(body)))


# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------


def png_size(path):
    """(width, height) from the IHDR chunk, or None if the file is not a PNG."""
    with open(path, "rb") as f:
        head = f.read(24)
    if len(head) < 24 or head[:8] != b"\x89PNG\r\n\x1a\n" or head[12:16] != b"IHDR":
        return None
    return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")


def check():
    lines = read_description()
    found = markers(lines)
    pngs = sorted(f for f in os.listdir(IMG) if f.lower().endswith(".png")) if os.path.isdir(IMG) else []
    results = []                                         # (label, [problem, ...])

    # a. every marker names an existing file
    named = [(n, MARKER.match(ln).group(1)) for n, ln in found if MARKER.match(ln)]
    results.append(("a. every marker names an existing image (%d markers)" % len(named),
                    ["line %d: img/%s is missing" % (n, f) for n, f in named if not os.path.isfile(os.path.join(IMG, f))]))

    # b. every image is used by exactly one marker
    uses = {}
    for _, f in named:
        uses[f] = uses.get(f, 0) + 1
    bad = ["%s is not used by any marker" % f for f in pngs if f not in uses]
    bad += ["%s is used by %d markers" % (f, c) for f, c in sorted(uses.items()) if c > 1]
    results.append(("b. every image is used by exactly one marker (%d images)" % len(pngs), bad))

    # c. marker form, ASCII alt text
    bad = []
    for n, ln in found:
        m = MARKER.match(ln)
        if not m:
            bad.append("line %d is not of the form '>>> PASTE IMAGE: <name>.png | <alt> <<<'" % n)
        elif not (m.group(2).strip() and m.group(2).isascii() and m.group(2).isprintable()):
            bad.append("line %d: alt text must be non-empty printable ASCII" % n)
        if not ln.isascii():
            bad.append("line %d contains non-ASCII characters" % n)
    results.append(("c. every marker has the exact form and ASCII alt text", bad))

    # d. size limits
    bad = []
    for f in pngs:
        path = os.path.join(IMG, f)
        size = png_size(path)
        if size is None:
            bad.append("%s is not a readable PNG" % f)
            continue
        if size[0] > MAX_WIDTH:
            bad.append("%s is %d px wide (limit %d)" % (f, size[0], MAX_WIDTH))
        if os.path.getsize(path) > MAX_BYTES:
            bad.append("%s is %d bytes (limit %d)" % (f, os.path.getsize(path), MAX_BYTES))
    results.append(("d. every PNG is at most %d px wide and %d bytes" % (MAX_WIDTH, MAX_BYTES), bad))

    # e. preview.html is current
    want = render(lines).encode("utf-8")
    have = open(PREVIEW, "rb").read() if os.path.isfile(PREVIEW) else None
    results.append(("e. preview.html is what the description produces now",
                    [] if have == want else ["preview.html is missing" if have is None else "preview.html is stale; run python art/showcase_preview.py"]))

    problems = 0
    for label, bad in results:
        print("%s  %s" % ("ok  " if not bad else "FAIL", label))
        for b in bad:
            print("       - " + b)
        problems += len(bad)
    print("result: OK" if not problems else "result: FAIL (%d problems)" % problems)
    return 1 if problems else 0


def main():
    args = sys.argv[1:]
    if "--check" in args:
        sys.exit(check())
    if args:
        sys.exit("usage: python art/showcase_preview.py [--check]")
    data = render(read_description()).encode("utf-8")
    with open(PREVIEW, "wb") as f:
        f.write(data)
    print("wrote %s (%d bytes)" % (os.path.relpath(PREVIEW, ROOT).replace(os.sep, "/"), len(data)))


if __name__ == "__main__":
    main()
