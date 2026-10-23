#!/usr/bin/env python3
"""Generate the SparkTube launcher icon.

Writes the adaptive-icon vector layers (background / foreground / monochrome)
and the legacy PNG mipmaps (square + round) from one shared geometry, so the
API 24-25 icons match the API 26+ adaptive icon.

Run from the repo root:  python3 tools/generate-launcher-icons.py
Requires: Pillow, numpy
"""
import math
import os

import numpy as np
from PIL import Image

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

# ---------------------------------------------------------------- geometry
# All coordinates live in the 108 x 108 adaptive-icon viewport.
PLAY = [(42.5, 34.0), (42.5, 74.0), (77.0, 54.0)]   # optically centred
PLAY_RADIUS = 4.2                                   # corner rounding
STAR_C, STAR_R, STAR_K = (74.5, 35.5), 6.8, 1.25    # 4-point spark
RING_R, RING_W = 31.5, 0.7


def f(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


def rounded_poly_cmds(pts, d):
    """Return [(start, ctrl, end), ...] corner curves for a rounded polygon."""
    n = len(pts)
    out = []
    for i in range(n):
        p = pts[i]
        a, b = pts[i - 1], pts[(i + 1) % n]
        va = ((a[0] - p[0]), (a[1] - p[1]))
        vb = ((b[0] - p[0]), (b[1] - p[1]))
        la, lb = math.hypot(*va), math.hypot(*vb)
        s = (p[0] + va[0] / la * d, p[1] + va[1] / la * d)
        e = (p[0] + vb[0] / lb * d, p[1] + vb[1] / lb * d)
        out.append((s, p, e))
    return out


def play_path():
    c = rounded_poly_cmds(PLAY, PLAY_RADIUS)
    d = "M%s,%s" % (f(c[0][2][0]), f(c[0][2][1]))
    for i in range(1, len(c) + 1):
        s, p, e = c[i % len(c)]
        d += "L%s,%sQ%s,%s %s,%s" % (f(s[0]), f(s[1]), f(p[0]), f(p[1]), f(e[0]), f(e[1]))
    return d + "Z"


def star_pts():
    cx, cy = STAR_C
    R, k = STAR_R, STAR_K
    return [(cx, cy - R), (cx + R, cy), (cx, cy + R), (cx - R, cy)], \
           [(cx + k, cy - k), (cx + k, cy + k), (cx - k, cy + k), (cx - k, cy - k)]


def star_path():
    tips, ctrls = star_pts()
    d = "M%s,%s" % (f(tips[0][0]), f(tips[0][1]))
    for i in range(4):
        t = tips[(i + 1) % 4]
        c = ctrls[i]
        d += "Q%s,%s %s,%s" % (f(c[0]), f(c[1]), f(t[0]), f(t[1]))
    return d + "Z"


def ring_path():
    cx = cy = 54.0
    r1, r2 = RING_R + RING_W / 2, RING_R - RING_W / 2

    def circle(r):
        return "M%s,%sa%s,%s 0 1,0 %s,0a%s,%s 0 1,0 %s,0Z" % (
            f(cx - r), f(cy), f(r), f(r), f(2 * r), f(r), f(r), f(-2 * r))
    return circle(r1) + circle(r2)


# ------------------------------------------------------------ vector output
NS = ('xmlns:android="http://schemas.android.com/apk/res/android" '
      'xmlns:aapt="http://schemas.android.com/aapt"')
HEAD = ('<vector %s\n    android:width="108dp"\n    android:height="108dp"\n'
        '    android:viewportWidth="108"\n    android:viewportHeight="108">\n' % NS)


def grad_path(path, gradient, extra=""):
    return ('    <path\n        android:pathData="%s"%s>\n'
            '        <aapt:attr name="android:fillColor">\n%s\n'
            '        </aapt:attr>\n    </path>\n') % (path, extra, gradient)


def linear(x1, y1, x2, y2, stops):
    items = "\n".join('                <item android:offset="%s" android:color="%s"/>' % s
                      for s in stops)
    return ('            <gradient\n                android:type="linear"\n'
            '                android:startX="%s" android:startY="%s"\n'
            '                android:endX="%s" android:endY="%s">\n%s\n'
            '            </gradient>') % (x1, y1, x2, y2, items)


def radial(cx, cy, r, stops):
    items = "\n".join('                <item android:offset="%s" android:color="%s"/>' % s
                      for s in stops)
    return ('            <gradient\n                android:type="radial"\n'
            '                android:centerX="%s" android:centerY="%s"\n'
            '                android:gradientRadius="%s">\n%s\n'
            '            </gradient>') % (cx, cy, r, items)


def write(rel, text):
    path = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write(text)


def write_vectors():
    bg = '<?xml version="1.0" encoding="utf-8"?>\n' + HEAD
    bg += grad_path("M0,0H108V108H0Z",
                    linear(0, 0, 108, 108, [("0", "#2C2C35"), ("0.55", "#131318"), ("1", "#08080B")]))
    # warm red glow sitting behind the play mark
    bg += grad_path("M0,0H108V108H0Z",
                    radial(46, 62, 56, [("0", "#55F03E3E"), ("0.55", "#16F03E3E"), ("1", "#00F03E3E")]))
    # faint top-left sheen
    bg += grad_path("M0,0H108V108H0Z",
                    radial(8, 4, 70, [("0", "#22FFFFFF"), ("1", "#00FFFFFF")]))
    bg += "</vector>\n"
    write("drawable/ic_launcher_background.xml", bg)

    fg = '<?xml version="1.0" encoding="utf-8"?>\n' + HEAD
    fg += grad_path(ring_path(),
                    linear(20, 20, 88, 88, [("0", "#66FFFFFF"), ("0.5", "#14FFFFFF"), ("1", "#40FFB4A8")]),
                    '\n        android:fillType="evenOdd"')
    fg += grad_path(play_path(),
                    linear(42, 34, 78, 76, [("0", "#FF8A73"), ("0.45", "#F03E3E"), ("1", "#B3202C")]))
    fg += grad_path(play_path(),
                    linear(60, 34, 60, 62, [("0", "#52FFFFFF"), ("1", "#00FFFFFF")]))
    fg += grad_path(star_path(),
                    linear(68, 29, 81, 42, [("0", "#FFFFFF"), ("1", "#FFD98A")]))
    fg += "</vector>\n"
    write("drawable/ic_launcher_foreground.xml", fg)

    mono = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            '    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="%s"/>\n'
            '    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="%s"/>\n'
            '</vector>\n') % (play_path(), star_path())
    write("drawable/ic_launcher_monochrome.xml", mono)

    for name in ("ic_launcher", "ic_launcher_round"):
        write("mipmap-anydpi-v26/%s.xml" % name,
              '<?xml version="1.0" encoding="utf-8"?>\n'
              '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
              '    <background android:drawable="@drawable/ic_launcher_background" />\n'
              '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
              '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
              '</adaptive-icon>\n')


# ------------------------------------------------------------ raster output
SS = 4          # supersampling factor
BASE = 108 * 4  # master canvas (px)


def quad(p0, p1, p2, n=24):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1])
            for t in (i / n for i in range(n + 1))]


def play_poly():
    pts = []
    for s, p, e in rounded_poly_cmds(PLAY, PLAY_RADIUS):
        pts += quad(s, p, e)
    return pts


def star_poly():
    tips, ctrls = star_pts()
    pts = []
    for i in range(4):
        pts += quad(tips[i], ctrls[i], tips[(i + 1) % 4])
    return pts


def poly_mask(poly, size):
    from PIL import ImageDraw
    big = size * SS
    img = Image.new("L", (big, big), 0)
    k = big / 108.0
    ImageDraw.Draw(img).polygon([(x * k, y * k) for x, y in poly], fill=255)
    return np.asarray(img.resize((size, size), Image.LANCZOS), dtype=np.float32) / 255.0


def ring_mask(size):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    k = 108.0 / size
    r = np.hypot((xx + 0.5) * k - 54, (yy + 0.5) * k - 54)
    return np.clip(1 - np.abs(r - RING_R) / (RING_W / 2) + 0.5, 0, 1)


def hex_rgba(h):
    h = h.lstrip("#")
    if len(h) == 6:
        h = "FF" + h
    a, r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4, 6))
    return np.array([r, g, b, a], dtype=np.float32) / 255.0


def lin_grad(size, x1, y1, x2, y2, stops):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    k = 108.0 / size
    px, py = (xx + 0.5) * k, (yy + 0.5) * k
    dx, dy = x2 - x1, y2 - y1
    t = np.clip(((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy), 0, 1)
    return stops_to_img(t, stops)


def rad_grad(size, cx, cy, r, stops):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    k = 108.0 / size
    t = np.clip(np.hypot((xx + 0.5) * k - cx, (yy + 0.5) * k - cy) / r, 0, 1)
    return stops_to_img(t, stops)


def stops_to_img(t, stops):
    offs = [float(o) for o, _ in stops]
    cols = [hex_rgba(c) for _, c in stops]
    out = np.zeros(t.shape + (4,), dtype=np.float32)
    for ch in range(4):
        out[..., ch] = np.interp(t, offs, [c[ch] for c in cols])
    return out


def over(dst, src, mask=None):
    """Alpha-composite src over dst (non-premultiplied float RGBA)."""
    a = src[..., 3:4] * (mask[..., None] if mask is not None else 1.0)
    da = dst[..., 3:4]
    oa = a + da * (1 - a)
    rgb = (src[..., :3] * a + dst[..., :3] * da * (1 - a)) / np.maximum(oa, 1e-6)
    return np.concatenate([rgb, oa], axis=-1)


def render_master():
    n = BASE
    img = np.zeros((n, n, 4), dtype=np.float32)
    img = over(img, lin_grad(n, 0, 0, 108, 108,
                             [(0, "#2C2C35"), (0.55, "#131318"), (1, "#08080B")]))
    img = over(img, rad_grad(n, 46, 62, 56,
                             [(0, "#55F03E3E"), (0.55, "#16F03E3E"), (1, "#00F03E3E")]))
    img = over(img, rad_grad(n, 8, 4, 70, [(0, "#22FFFFFF"), (1, "#00FFFFFF")]))
    img = over(img, lin_grad(n, 20, 20, 88, 88,
                             [(0, "#66FFFFFF"), (0.5, "#14FFFFFF"), (1, "#40FFB4A8")]), ring_mask(n))
    pm = poly_mask(play_poly(), n)
    img = over(img, lin_grad(n, 42, 34, 78, 76,
                             [(0, "#FF8A73"), (0.45, "#F03E3E"), (1, "#B3202C")]), pm)
    img = over(img, lin_grad(n, 60, 34, 60, 62, [(0, "#52FFFFFF"), (1, "#00FFFFFF")]), pm)
    img = over(img, lin_grad(n, 70, 26, 84, 40, [(0, "#FFFFFF"), (1, "#FFD98A")]),
               poly_mask(star_poly(), n))
    return img


def shape_mask(size, round_icon):
    yy, xx = np.mgrid[0:size * SS, 0:size * SS].astype(np.float32)
    c = size * SS / 2.0
    if round_icon:
        m = (np.hypot(xx + 0.5 - c, yy + 0.5 - c) <= c).astype(np.float32)
    else:
        r = size * SS * 0.2
        qx = np.maximum(np.abs(xx + 0.5 - c) - (c - r), 0)
        qy = np.maximum(np.abs(yy + 0.5 - c) - (c - r), 0)
        m = (np.hypot(qx, qy) <= r).astype(np.float32)
    im = Image.fromarray((m * 255).astype(np.uint8)).resize((size, size), Image.LANCZOS)
    return np.asarray(im, dtype=np.float32) / 255.0


def write_pngs():
    master = Image.fromarray((np.clip(render_master(), 0, 1) * 255).astype(np.uint8), "RGBA")
    lo = int(BASE * (108 - 72) / 2 / 108)
    visible = master.crop((lo, lo, BASE - lo, BASE - lo))   # 72dp visible window
    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        icon = np.asarray(visible.resize((px, px), Image.LANCZOS), dtype=np.float32) / 255.0
        for name, rnd in (("ic_launcher", False), ("ic_launcher_round", True)):
            out = icon.copy()
            out[..., 3] *= shape_mask(px, rnd)
            path = os.path.join(RES, "mipmap-%s" % density, name + ".png")
            Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8), "RGBA").save(path, optimize=True)
    visible.resize((512, 512), Image.LANCZOS).save("/tmp/icon_preview_512.png")


if __name__ == "__main__":
    write_vectors()
    write_pngs()
    print("launcher icons written")
