#!/usr/bin/env python3
"""Renders the launcher icon straight from the committed VectorDrawable XML (so CI checks what ships) and checks it.

Supports exactly what the icon uses: <group> (translate/scale/rotation/pivot), <path> with fillColor (#RRGGBB / #AARRGGBB) or an
aapt gradient (linear / radial), fillType evenOdd, and path commands M L H V A Z (absolute).

usage: vd_render.py PREVIEW.png        renders the sheet + prints GitHub notices for the safe zone and the monochrome layer
Needs numpy and Pillow only.
"""
import math, re, sys, xml.etree.ElementTree as ET
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

ANDROID = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"
RES = Path(__file__).resolve().parents[2] / "app/src"
SS = 4  # supersampling per axis


def _f(el, name, default=0.0):
    v = el.get(ANDROID + name)
    return float(v) if v is not None else default


def _color(c):
    c = c.lstrip("#")
    if len(c) == 6:
        c = "FF" + c
    a, r, g, b = (int(c[i:i + 2], 16) for i in (0, 2, 4, 6))
    return np.array([r, g, b, a], dtype=float)


def _mat(tx=0.0, ty=0.0, sx=1.0, sy=1.0, rot=0.0, px=0.0, py=0.0):
    t = lambda x, y: np.array([[1, 0, x], [0, 1, y], [0, 0, 1.0]])
    c, s = math.cos(math.radians(rot)), math.sin(math.radians(rot))
    r = np.array([[c, -s, 0], [s, c, 0], [0, 0, 1.0]])
    sc = np.array([[sx, 0, 0], [0, sy, 0], [0, 0, 1.0]])
    return t(tx, ty) @ t(px, py) @ r @ sc @ t(-px, -py)


def _arc(p0, rx, ry, large, sweep, p1, n=24):
    (x1, y1), (x2, y2) = p0, p1
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    rx, ry = abs(rx), abs(ry)
    lam = (dx / rx) ** 2 + (dy / ry) ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * dy * dy - ry * ry * dx * dx
    den = rx * rx * dy * dy + ry * ry * dx * dx
    co = math.sqrt(max(0.0, num / den)) * (-1 if large == sweep else 1)
    cxp, cyp = co * rx * dy / ry, -co * ry * dx / rx
    cx, cy = cxp + (x1 + x2) / 2, cyp + (y1 + y2) / 2
    a1 = math.atan2((dy - cyp) / ry, (dx - cxp) / rx)
    a2 = math.atan2((-dy - cyp) / ry, (-dx - cxp) / rx)
    d = a2 - a1
    if sweep and d < 0:
        d += 2 * math.pi
    if not sweep and d > 0:
        d -= 2 * math.pi
    return [(cx + rx * math.cos(a1 + d * i / n), cy + ry * math.sin(a1 + d * i / n)) for i in range(1, n + 1)]


def parse_path(d):
    toks = re.findall(r"[MLHVAZ]|-?\d*\.?\d+(?:e-?\d+)?", d)
    subs, cur, i, cmd = [], [], 0, None
    pos = (0.0, 0.0)
    def num():
        nonlocal i
        v = float(toks[i]); i += 1; return v
    while i < len(toks):
        if re.match(r"[A-Z]", toks[i]):
            cmd = toks[i]; i += 1
            if cmd == "Z":
                if cur: subs.append(cur); cur = []
                continue
        if cmd == "M":
            if cur: subs.append(cur)
            pos = (num(), num()); cur = [pos]; cmd = "L"
        elif cmd == "L":
            pos = (num(), num()); cur.append(pos)
        elif cmd == "H":
            pos = (num(), pos[1]); cur.append(pos)
        elif cmd == "V":
            pos = (pos[0], num()); cur.append(pos)
        elif cmd == "A":
            rx, ry, _rot, large, sweep, x, y = (num() for _ in range(7))
            cur.extend(_arc(pos, rx, ry, int(large), int(sweep), (x, y)))
            pos = (x, y)
    if cur: subs.append(cur)
    return subs


def _gradient(el):
    g = el.find(f"{AAPT}attr/gradient")
    if g is None:
        return None
    stops = sorted((float(it.get(ANDROID + "offset")), _color(it.get(ANDROID + "color"))) for it in g.findall("item"))
    return g.get(ANDROID + "type", "linear"), g, stops


def _paint(grad, lx, ly):
    kind, g, stops = grad
    if kind == "radial":
        t = np.hypot(lx - float(g.get(ANDROID + "centerX")), ly - float(g.get(ANDROID + "centerY"))) / float(g.get(ANDROID + "gradientRadius"))
    else:
        sx, sy, ex, ey = (float(g.get(ANDROID + k)) for k in ("startX", "startY", "endX", "endY"))
        vx, vy = ex - sx, ey - sy
        t = ((lx - sx) * vx + (ly - sy) * vy) / (vx * vx + vy * vy)
    t = np.clip(t, 0, 1)
    out = np.zeros(t.shape + (4,))
    offs = [s[0] for s in stops]
    for ch in range(4):
        out[..., ch] = np.interp(t, offs, [s[1][ch] for s in stops])
    return out


def render_layer(path, px):
    """Renders a 108x108 VectorDrawable to a px x px float RGBA array (premultiplied-free, 0..255)."""
    root = ET.parse(path).getroot()
    vw, vh = float(root.get(ANDROID + "viewportWidth")), float(root.get(ANDROID + "viewportHeight"))
    k = px / vw
    img = np.zeros((px, px, 4))  # straight (non-premultiplied) RGBA
    ys, xs = np.mgrid[0:px, 0:px]
    vx, vy = (xs + 0.5) / k, (ys + 0.5) / k
    def walk(el, m):
        nonlocal img
        for ch in el:
            if ch.tag == "group":
                gm = _mat(_f(ch, "translateX"), _f(ch, "translateY"), _f(ch, "scaleX", 1), _f(ch, "scaleY", 1), _f(ch, "rotation"), _f(ch, "pivotX"), _f(ch, "pivotY"))
                walk(ch, m @ gm)
            elif ch.tag == "path":
                even = ch.get(ANDROID + "fillType") == "evenOdd"
                acc = np.zeros((px * SS, px * SS), dtype=np.uint8)
                for sub in parse_path(ch.get(ANDROID + "pathData")):
                    pts = [tuple((m @ np.array([x, y, 1.0]))[:2] * k * SS) for x, y in sub]
                    one = Image.new("L", (px * SS, px * SS), 0)
                    ImageDraw.Draw(one).polygon(pts, fill=1)
                    a = np.asarray(one, dtype=np.uint8)
                    acc = (acc ^ a) if even else (acc | a)
                cov = acc.reshape(px, SS, px, SS).mean(axis=(1, 3))
                inv = np.linalg.inv(m)
                lx, ly = inv[0, 0] * vx + inv[0, 1] * vy + inv[0, 2], inv[1, 0] * vx + inv[1, 1] * vy + inv[1, 2]
                grad = _gradient(ch)
                col = _paint(grad, lx, ly) if grad else np.broadcast_to(_color(ch.get(ANDROID + "fillColor")), (px, px, 4))
                a = (cov * col[..., 3] / 255.0)[..., None]
                oa = img[..., 3:4] / 255.0
                na = a + oa * (1 - a)
                rgb = (col[..., :3] * a + img[..., :3] * oa * (1 - a)) / np.maximum(na, 1e-6)
                img = np.concatenate([rgb, na * 255.0], axis=-1)
    walk(root, np.eye(3))
    return img


def composite(fg, bg):
    a = fg[..., 3:4] / 255.0
    out = fg.copy()
    out[..., :3] = fg[..., :3] * a + bg[..., :3] * (1 - a)
    out[..., 3] = 255
    return out


def mask_shape(px, kind):
    """Visible area is the central 72dp of the 108dp canvas: returns a 0..1 mask over the full canvas."""
    ys, xs = np.mgrid[0:px, 0:px]
    u, v = (xs + 0.5) / px * 108 - 54, (ys + 0.5) / px * 108 - 54
    if kind == "circle":
        return ((np.hypot(u, v) <= 36)).astype(float)
    n = 5.0  # squircle
    return ((np.abs(u / 36) ** n + np.abs(v / 36) ** n) <= 1).astype(float)


def safe_zone_report(fg):
    """Share of foreground pixels outside the 66dp-diameter safe circle and inside nothing visible (outside the 72dp circle)."""
    px = fg.shape[0]
    ys, xs = np.mgrid[0:px, 0:px]
    r = np.hypot((xs + 0.5) / px * 108 - 54, (ys + 0.5) / px * 108 - 54)
    solid = fg[..., 3] > 25
    return float((solid & (r > 33)).sum()), float((solid & (r > 36)).sum()), float(r[solid].max())


def view(res, layer, px):
    return render_layer(res / "drawable" / f"{layer}.xml", px)


def sheet(out):
    main = RES / "main/res"
    lab = RES / "lab/res"
    pxc = 432  # full-canvas render, then scaled down per cell
    bg = view(main, "ic_launcher_background", pxc)
    fg = view(main, "ic_launcher_foreground", pxc)
    mono = view(main, "ic_launcher_monochrome", pxc)
    labfg = render_layer(lab / "drawable/ic_launcher_foreground.xml", pxc) if (lab / "drawable/ic_launcher_foreground.xml").exists() else None
    outside33, outside36, rmax = safe_zone_report(fg)
    print(f"SAFE ZONE: farthest foreground pixel {rmax:.1f} dp from the centre; pixels outside 33 dp: {outside33:.0f}; outside the 36 dp mask circle: {outside36:.0f}")
    print(f"::notice title=Icon safe zone::farthest foreground pixel {rmax:.1f} dp from centre (66 dp zone = 33, visible circle = 36); outside the 36 dp circle: {outside36:.0f} px")
    tint_bg = np.zeros((pxc, pxc, 4)); tint_bg[..., :3] = (214, 232, 222); tint_bg[..., 3] = 255
    mono_col = np.zeros_like(mono); mono_col[..., :3] = (22, 78, 60); mono_col[..., 3] = mono[..., 3]
    full = composite(fg, bg)
    variants = [("circle", full, "circle"), ("squircle", full, "squircle"), ("themed", composite(mono_col, tint_bg), "circle")]
    if labfg is not None:
        variants.append(("lab", composite(labfg, bg), "circle"))
    sizes = (48, 96, 192)
    pad = 16
    W = pad + sum((s + pad) for s in [192] * len(variants)) 
    H = pad + sum((s + pad) for s in sizes)
    sheet = Image.new("RGB", (W, H), (58, 58, 62))
    y = pad
    for s in sizes:
        x = pad
        for _name, canvas, kind in variants:
            rgba = canvas.copy()
            rgba[..., 3] = mask_shape(pxc, kind) * 255
            im = Image.fromarray(rgba.astype(np.uint8), "RGBA").crop((int(pxc * 18 / 108),) * 2 + (int(pxc * 90 / 108),) * 2)
            im = im.resize((s, s), Image.LANCZOS)
            sheet.paste(im, (x + (192 - s) // 2, y), im)
            x += 192 + pad
        y += s + pad
    sheet.save(out)
    # monochrome layer: share of the visible disc that is solid, and the minimum contrast of the tinted silhouette against its backdrop
    a = mono[..., 3] > 128
    disc = mask_shape(pxc, "circle") > 0
    def lum(c):
        c = np.asarray(c) / 255.0
        c = np.where(c <= 0.03928, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    l1, l2 = lum((22, 78, 60)), lum((214, 232, 222))
    ratio = (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    print(f"MONOCHROME: silhouette covers {a[disc].mean() * 100:.0f}% of the visible disc; tint contrast {ratio:.1f}:1")
    print(f"::notice title=Icon monochrome::silhouette covers {a[disc].mean() * 100:.0f}% of the visible disc, sample tint contrast {ratio:.1f}:1")


if __name__ == "__main__":
    sheet(sys.argv[1] if len(sys.argv) > 1 else "icon-preview.png")
