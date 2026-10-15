#!/usr/bin/env python3
"""Generates the launcher icon: vector drawables (background, foreground, monochrome), the Lab variant and the legacy PNGs.

Run by hand when the artwork changes (needs shapely, numpy, Pillow); the outputs are committed. CI only renders them (vd_render.py).
All geometry is on the 1254 x 1254 reference canvas of the design and scaled into the 108 dp adaptive canvas.
"""
import math, sys
from pathlib import Path
import numpy as np
from PIL import Image
from shapely.affinity import rotate as shp_rotate
from shapely.geometry import Polygon, box
from shapely.ops import unary_union

sys.path.insert(0, str(Path(__file__).parent))
import vd_render as vr

SRC = Path(__file__).resolve().parents[2] / "app/src"
NS = 'xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"'

# ---- the design (reference canvas units) ----------------------------------------------------------------------------------------
CARDS = [  # bottom to top: the bright green card is lowest, the dark teal card sits closest to the wallet; centre, width, height, angle (deg), gradient
    dict(c=(634, 390), w=540, h=230, a=-19, r=40, g=((0, 0), (540, 230), "#74E693", "#2BC4A0")),
    dict(c=(656, 464), w=540, h=230, a=-11.3, r=40, g=((0, 0), (540, 0), "#1E8E8A", "#1B6F78")),
    dict(c=(674, 517), w=591, h=230, a=-4.5, r=40, g=((0, 0), (0, 230), "#1A6A6C", "#0D474B")),
]
BODY = dict(x=268, y=428, w=714, h=550, r=(110, 110, 110, 110))
FLAP = dict(x=738, y=586, w=272, h=224, r=(112, 18, 18, 112))
DOT = dict(c=(853, 698), r=44)
BARS = [dict(x=393, w=70, top=692), dict(x=488, w=70, top=631), dict(x=587, w=76, top=557)]
BAR_BOTTOM = 835
GAP = 22  # monochrome separation, reference units


def rr_points(x, y, w, h, r, n=28):
    tl, tr, br, bl = r
    pts = []
    def corner(cx, cy, rad, a0):
        if rad <= 0:
            pts.append((cx, cy)); return
        for i in range(n + 1):
            a = math.radians(a0 + 90 * i / n)
            pts.append((cx + rad * math.cos(a), cy + rad * math.sin(a)))
    corner(x + tl, y + tl, tl, 180)
    corner(x + w - tr, y + tr, tr, 270)
    corner(x + w - br, y + h - br, br, 0)
    corner(x + bl, y + h - bl, bl, 90)
    return pts


def rr_path(x, y, w, h, r):
    tl, tr, br, bl = r
    f = lambda v: f"{v:.1f}".rstrip("0").rstrip(".")
    d = [f"M{f(x + tl)},{f(y)}", f"H{f(x + w - tr)}"]
    if tr: d.append(f"A{f(tr)},{f(tr)} 0 0 1 {f(x + w)},{f(y + tr)}")
    d.append(f"V{f(y + h - br)}")
    if br: d.append(f"A{f(br)},{f(br)} 0 0 1 {f(x + w - br)},{f(y + h)}")
    d.append(f"H{f(x + bl)}")
    if bl: d.append(f"A{f(bl)},{f(bl)} 0 0 1 {f(x)},{f(y + h - bl)}")
    d.append(f"V{f(y + tl)}")
    if tl: d.append(f"A{f(tl)},{f(tl)} 0 0 1 {f(x + tl)},{f(y)}")
    d.append("Z")
    return "".join(d)


def circle_path(cx, cy, r):
    return f"M{cx - r},{cy}A{r},{r} 0 1 1 {cx + r},{cy}A{r},{r} 0 1 1 {cx - r},{cy}Z"


def poly_path(poly):
    out = []
    for ring in [poly.exterior, *poly.interiors]:
        c = list(ring.coords)[:-1]
        out.append("M" + "L".join(f"{x:.1f},{y:.1f}" for x, y in c) + "Z")
    return "".join(out)


def card_poly(card):
    cx, cy = card["c"]
    p = Polygon(rr_points(cx - card["w"] / 2, cy - card["h"] / 2, card["w"], card["h"], (card["r"],) * 4))
    return shp_rotate(p, card["a"], origin=(cx, cy))


def body_poly(): return Polygon(rr_points(BODY["x"], BODY["y"], BODY["w"], BODY["h"], BODY["r"]))
def flap_poly(): return Polygon(rr_points(FLAP["x"], FLAP["y"], FLAP["w"], FLAP["h"], FLAP["r"]))
def bar_poly(b): return Polygon(rr_points(b["x"], b["top"], b["w"], BAR_BOTTOM - b["top"], (18, 18, 14, 14)))
def dot_poly(): return Point_buffer(DOT["c"], DOT["r"])


def Point_buffer(c, r):
    from shapely.geometry import Point
    return Point(*c).buffer(r, quad_segs=32)


def all_shapes():
    return [card_poly(c) for c in CARDS] + [body_poly(), flap_poly()]


def fit():
    """Scale and translation that put the artwork inside the safe zone: the bounding box in 66 dp and every point within the 36 dp mask circle."""
    u = unary_union(all_shapes())
    x0, y0, x1, y1 = u.bounds
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    rmax = max(math.hypot(x - cx, y - cy) for x, y in u.convex_hull.exterior.coords)
    s = min(66 / (x1 - x0), 66 / (y1 - y0), 33.0 / rmax)
    return s, 54 - cx * s, 54 - cy * s, (x0, y0, x1, y1, rmax)


def grad_linear(p0, p1, c0, c1, ind="        "):
    return (f'{ind}<aapt:attr name="android:fillColor">\n{ind}    <gradient android:type="linear" android:startX="{p0[0]}" android:startY="{p0[1]}" '
            f'android:endX="{p1[0]}" android:endY="{p1[1]}">\n{ind}        <item android:offset="0" android:color="{c0}" />\n'
            f'{ind}        <item android:offset="1" android:color="{c1}" />\n{ind}    </gradient>\n{ind}</aapt:attr>\n')


def path_el(data, fill=None, grad=None, evenodd=False, ind="        "):
    s = f'{ind}<path\n{ind}    android:pathData="{data}"'
    if evenodd: s += f'\n{ind}    android:fillType="evenOdd"'
    if fill: return s + f'\n{ind}    android:fillColor="{fill}" />\n'
    return s + ">\n" + grad + f"{ind}</path>\n"


def foreground(lab=False):
    s, tx, ty, _ = fit()
    out = [f'<vector {NS}\n    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
           f'    <!-- Generated by scripts/icon/make_icon.py. Reference canvas 1254 units, scaled into the 66 dp safe zone. -->\n'
           f'    <group android:translateX="{tx:.3f}" android:translateY="{ty:.3f}" android:scaleX="{s:.5f}" android:scaleY="{s:.5f}">\n']
    for c in CARDS:
        cx, cy = c["c"]
        (x0, y0), (x1, y1), c0, c1 = c["g"]
        out.append(f'        <group android:rotation="{c["a"]}" android:pivotX="{cx}" android:pivotY="{cy}">\n')
        path = rr_path(cx - c["w"] / 2, cy - c["h"] / 2, c["w"], c["h"], (c["r"],) * 4)
        # gradient is in the path's own (unrotated) coordinates
        out.append(path_el(path, grad=grad_linear((cx - c["w"] / 2 + x0, cy - c["h"] / 2 + y0), (cx - c["w"] / 2 + x1, cy - c["h"] / 2 + y1), c0, c1, "            "), ind="            "))
        out.append("        </group>\n")
    b = BODY
    out.append(path_el(rr_path(b["x"], b["y"], b["w"], b["h"], b["r"]), grad=grad_linear((b["x"], b["y"]), (b["x"] + 330, b["y"] + b["h"]), "#383B49", "#14161E")))
    out.append(path_el(rr_path(b["x"], b["y"], b["w"], b["h"], b["r"]), grad=grad_linear((b["x"], b["y"]), (b["x"] + 420, b["y"] + 330), "#2EFFFFFF", "#00FFFFFF")))
    f = FLAP
    out.append(path_el(rr_path(f["x"] - 6, f["y"] + 16, f["w"], f["h"], f["r"]), fill="#66000000"))
    out.append(path_el(rr_path(f["x"], f["y"], f["w"], f["h"], f["r"]), grad=grad_linear((f["x"], f["y"]), (f["x"] + 120, f["y"] + f["h"]), "#2D313D", "#1B1D26")))
    out.append(path_el(circle_path(DOT["c"][0], DOT["c"][1], DOT["r"]), grad=grad_linear((DOT["c"][0] - 44, DOT["c"][1] - 44), (DOT["c"][0] + 44, DOT["c"][1] + 44), "#6BE3A0", "#2FC9A0")))
    for bar in BARS:
        out.append(path_el(rr_path(bar["x"], bar["top"], bar["w"], BAR_BOTTOM - bar["top"], (18, 18, 14, 14)),
                           grad=grad_linear((0, bar["top"]), (0, BAR_BOTTOM), "#6AE08A", "#0E9EA0")))
    out.append("    </group>\n")
    if lab:
        # amber badge, bottom right, with a dark ring so it reads on any wallpaper (108 dp canvas units)
        out.append(path_el(circle_path(73.5, 74, 7.8), fill="#FF0A0B0F", ind="    "))
        out.append(path_el(circle_path(73.5, 74, 6.2), fill="#FFFFB300", ind="    "))
    out.append("</vector>\n")
    return "".join(out)


def background():
    return (f'<vector {NS}\n    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            '    <path android:pathData="M0,0H108V108H0Z">\n'
            '        <aapt:attr name="android:fillColor">\n'
            '            <gradient android:type="radial" android:centerX="54" android:centerY="54" android:gradientRadius="78">\n'
            '                <item android:offset="0" android:color="#FF14161D" />\n'
            '                <item android:offset="1" android:color="#FF08090C" />\n'
            '            </gradient>\n'
            '        </aapt:attr>\n'
            '    </path>\n</vector>\n')


def monochrome():
    s, tx, ty, _ = fit()
    cards = [card_poly(c) for c in CARDS]
    body, flap = body_poly(), flap_poly()
    bars = unary_union([bar_poly(b) for b in BARS])
    dot = dot_poly()
    solid_front = unary_union([body.buffer(GAP * 0.0), flap])
    pieces = []
    higher = None
    for card in reversed(cards):  # front first
        vis = card
        if higher is not None:
            vis = vis.difference(higher.buffer(GAP, join_style=2))
        vis = vis.difference(body.buffer(GAP, join_style=2))
        higher = card if higher is None else unary_union([higher, card])
        for g in (vis.geoms if hasattr(vis, "geoms") else [vis]):
            if g.area > 2500:
                pieces.append(g)
    wallet = body.difference(flap.buffer(GAP, join_style=2)).difference(bars)
    clasp = flap.difference(dot)
    parts = pieces + [wallet, clasp]
    out = [f'<vector {NS}\n    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
           '    <!-- Generated by scripts/icon/make_icon.py. Single-colour silhouette for themed icons (Android 13+). -->\n'
           f'    <group android:translateX="{tx:.3f}" android:translateY="{ty:.3f}" android:scaleX="{s:.5f}" android:scaleY="{s:.5f}">\n']
    for g in parts:
        for poly in (g.geoms if hasattr(g, "geoms") else [g]):
            out.append(path_el(poly_path(poly), fill="#FF000000", evenodd=True))
    out.append("    </group>\n</vector>\n")
    return "".join(out)


ADAPTIVE = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n')


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text if text.startswith("<?xml") else '<?xml version="1.0" encoding="utf-8"?>\n' + text)


def legacy_pngs(res_root, fg_layer_xml):
    main = SRC / "main/res"
    px = 432
    bg = vr.render_layer(res_root / "drawable/ic_launcher_background.xml", px) if (res_root / "drawable/ic_launcher_background.xml").exists() else vr.render_layer(main / "drawable/ic_launcher_background.xml", px)
    fg = vr.render_layer(fg_layer_xml, px)
    full = vr.composite(fg, bg)
    crop = (int(px * 16 / 108), int(px * 16 / 108), int(px * 92 / 108), int(px * 92 / 108))  # a little wider than the 72 dp window: legacy icons are full bleed
    ys, xs = np.mgrid[0:px, 0:px]
    for name, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        for fname, kind in (("ic_launcher.png", "rounded"), ("ic_launcher_round.png", "round")):
            rgba = full.copy()
            n = (xs + 0.5) / px * 108 - 54, (ys + 0.5) / px * 108 - 54
            half = 38.0
            if kind == "round":
                m = np.hypot(*n) <= half
            else:
                rr = 9.0
                qx, qy = np.abs(n[0]) - (half - rr), np.abs(n[1]) - (half - rr)
                m = (np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) <= rr) & (np.abs(n[0]) <= half) & (np.abs(n[1]) <= half)
            rgba[..., 3] = m * 255
            im = Image.fromarray(rgba.astype(np.uint8), "RGBA").crop(crop).resize((size, size), Image.LANCZOS)
            d = res_root / f"mipmap-{name}"
            d.mkdir(parents=True, exist_ok=True)
            im.save(d / fname, optimize=True)


def write_lab():
    lab = SRC / "lab/res"
    write(lab / "drawable/ic_launcher_foreground.xml", foreground(lab=True))
    write(lab / "mipmap-anydpi-v26/ic_launcher.xml", ADAPTIVE)
    legacy_pngs(lab, lab / "drawable/ic_launcher_foreground.xml")


def main(lab_only=False):
    m = SRC / "main/res"
    if lab_only:  # the Lab variant only: the main artwork stays exactly as it is
        write_lab()
        return
    write(m / "drawable/ic_launcher_background.xml", background())
    write(m / "drawable/ic_launcher_foreground.xml", foreground())
    write(m / "drawable/ic_launcher_monochrome.xml", monochrome())
    write(m / "mipmap-anydpi-v26/ic_launcher.xml", ADAPTIVE)
    old = m / "values/ic_launcher_background.xml"
    if old.exists(): old.unlink()
    legacy_pngs(m, m / "drawable/ic_launcher_foreground.xml")
    write_lab()
    s, tx, ty, (x0, y0, x1, y1, rmax) = fit()
    print(f"bbox {x0:.0f},{y0:.0f}-{x1:.0f},{y1:.0f} (reference units); scale {s:.5f}; artwork {(x1 - x0) * s:.1f} x {(y1 - y0) * s:.1f} dp; farthest point {rmax * s:.1f} dp from the centre")


if __name__ == "__main__":
    main(lab_only="--lab-only" in sys.argv)
