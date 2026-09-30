#!/usr/bin/env python3
"""Draws Vigil's boss bar art and builds the two resource packs (needs Pillow: pip install pillow).

  resourcepack/vigil-bossbars.zip      Java: ornate frames around the Ender Dragon, Wither and Raid bars
  resourcepack/vigil-bossbars.mcpack   Bedrock (served by Geyser): ornaments on each side of the name

The server mod puts the art into the bar titles (see feature/BossBarArt.java), so nothing else in the game changes.
Run from the repository root:  python3 scripts/make-bossbar-packs.py

Everything is drawn in "bar pixels" (the vanilla bar is 182 x 5 of them) on a 256 x 32 layout, rendered 8x
larger and scaled down to 4x, so the art has 4 real pixels per bar pixel: smooth, shaded and anti-aliased.
"""
import io
import json
import math
import os
import zipfile

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "resourcepack")
W, H = 256, 32          # layout size in bar pixels (the glyph is shown this big in game)
DETAIL = 4              # texture pixels per bar pixel
SS = 8                  # drawing resolution per bar pixel (scaled down to DETAIL for anti-aliasing)
# The vanilla bar in the layout: x 37..218, rows 13..17. It stays clear so the real bar shows through.
BAR_L, BAR_R, BAR_T, BAR_B = 37, 219, 13, 18


# ---------------------------------------------------------------- drawing helpers (coordinates in bar pixels)

def rgba(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def new_mask():
    return Image.new("L", (W * SS, H * SS), 0)


def sc(pts):
    return [(x * SS, y * SS) for x, y in pts]


def poly(pts):
    m = new_mask()
    ImageDraw.Draw(m).polygon(sc(pts), fill=255)
    return m


def ellipse(x0, y0, x1, y1):
    m = new_mask()
    ImageDraw.Draw(m).ellipse([x0 * SS, y0 * SS, x1 * SS, y1 * SS], fill=255)
    return m


def rrect(x0, y0, x1, y1, r):
    m = new_mask()
    ImageDraw.Draw(m).rounded_rectangle([x0 * SS, y0 * SS, x1 * SS, y1 * SS], radius=r * SS, fill=255)
    return m


def lines(pts, width):
    m = new_mask()
    d = ImageDraw.Draw(m)
    d.line(sc(pts), fill=255, width=max(1, int(width * SS)), joint="curve")
    r = width * SS / 2
    for x, y in sc(pts):
        d.ellipse([x - r, y - r, x + r, y + r], fill=255)
    return m


def union(*ms):
    out = ms[0]
    for m in ms[1:]:
        out = ImageChops.lighter(out, m)
    return out


def minus(a, b):
    return ImageChops.subtract(a, b)


def shift(m, dx, dy):
    out = new_mask()
    out.paste(m, (int(round(dx * SS)), int(round(dy * SS))))
    return out


def grow(m, px):
    size = 2 * int(px * SS) + 1
    return m.filter(ImageFilter.MaxFilter(size)) if size > 1 else m


def mirror(m):
    return m.transpose(Image.FLIP_LEFT_RIGHT)


def vgrad(stops, y0, y1):
    """A full-size image filled with a vertical gradient (stops: list of (t, color))."""
    col = Image.new("RGBA", (1, H * SS))
    for py in range(H * SS):
        t = min(1.0, max(0.0, (py / SS - y0) / max(0.001, y1 - y0)))
        for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
            if t0 <= t <= t1:
                f = (t - t0) / max(0.0001, t1 - t0)
                col.putpixel((0, py), tuple(int(c0[i] + (c1[i] - c0[i]) * f) for i in range(4)))
                break
    return col.resize((W * SS, H * SS))


def paint(img, m, fill, alpha=1.0):
    layer = fill.copy() if isinstance(fill, Image.Image) else Image.new("RGBA", img.size, fill)
    a = ImageChops.multiply(layer.getchannel("A"), m)
    if alpha != 1.0:
        a = a.point(lambda v: int(v * alpha))
    layer.putalpha(a)
    img.alpha_composite(layer)


def glow(img, m, color, radius, strength=1.0):
    blurred = grow(m, radius / 3).filter(ImageFilter.GaussianBlur(radius * SS))
    paint(img, blurred, color, strength)


def shadow(img, m, dy=1.0, blur=0.8, alpha=0.55):
    paint(img, shift(m, 0.3, dy).filter(ImageFilter.GaussianBlur(blur * SS)), (0, 0, 0, 255), alpha)


def solid(img, m, fill, light, dark, edge, bevel=0.6, edge_w=0.5):
    """A shaded object: dark outline, fill, light top-left rim and dark bottom-right rim."""
    paint(img, minus(grow(m, edge_w), m), edge)
    paint(img, m, fill)
    paint(img, minus(m, shift(m, bevel * 0.6, bevel)), light)
    paint(img, minus(m, shift(m, -bevel * 0.6, -bevel)), dark)


def gem(img, cx, cy, r, base, light, dark, edge):
    m = poly([(cx, cy - r), (cx + r * 0.85, cy), (cx, cy + r), (cx - r * 0.85, cy)])
    glow(img, m, base, r * 0.9, 0.55)
    solid(img, m, vgrad([(0, light), (1, base)], cy - r, cy + r), light, dark, edge, 0.5, 0.45)
    paint(img, poly([(cx, cy - r * 0.8), (cx + r * 0.35, cy - r * 0.15), (cx, cy), (cx - r * 0.45, cy - r * 0.2)]),
          (255, 255, 255, 255), 0.5)
    paint(img, ellipse(cx - r * 0.35, cy - r * 0.55, cx - r * 0.05, cy - r * 0.25), (255, 255, 255, 255), 0.9)


def finish(img):
    """Scales down to the texture size and clears the bar."""
    out = img.resize((W * DETAIL, H * DETAIL), Image.LANCZOS)
    ImageDraw.Draw(out).rectangle([BAR_L * DETAIL, BAR_T * DETAIL, BAR_R * DETAIL - 1, BAR_B * DETAIL - 1],
                                  fill=(0, 0, 0, 0))
    # Minecraft measures a glyph up to its last visible column; keep the full width so the centring holds.
    for x in (0, W * DETAIL - 1):
        if out.getpixel((x, H * DETAIL // 2))[3] == 0:
            out.putpixel((x, H * DETAIL // 2), (0, 0, 0, 1))
    return out


# ---------------------------------------------------------------- shared parts

FRAME_OUT = (31.5, 10.2, 224.5, 20.8)


def frame(img, fill_stops, light, dark, edge, glow_color=None):
    """The frame around the bar, with the bar cut out."""
    outer = rrect(*FRAME_OUT, 2.2)
    hole = rrect(BAR_L - 0.5, BAR_T - 0.5, BAR_R + 0.5, BAR_B + 0.5, 0.8)
    m = minus(outer, hole)
    if glow_color:
        glow(img, m, glow_color, 2.2, 0.55)
    shadow(img, m)
    solid(img, m, vgrad(fill_stops, FRAME_OUT[1], FRAME_OUT[3]), light, dark, edge, 0.55, 0.5)
    # an inner groove next to the bar
    paint(img, minus(grow(hole, 0.55), hole), dark, 0.9)
    return m


def plaque(img, fill, border, border_light, x0=66, x1=190, y0=1.6, y1=12.2, tip=7):
    """A dark banner behind the name."""
    mid = (y0 + y1) / 2
    shape = [(x0 - tip, mid), (x0, y0), (x1, y0), (x1 + tip, mid), (x1, y1), (x0, y1)]
    m = poly(shape)
    shadow(img, m, 0.8, 0.9, 0.5)
    paint(img, minus(grow(m, 0.9), m), border)
    paint(img, minus(grow(m, 0.45), m), border_light, 0.8)
    paint(img, m, fill)
    inner = poly([(x0 - tip + 2.2, mid), (x0 + 1, y0 + 1.1), (x1 - 1, y0 + 1.1), (x1 + tip - 2.2, mid),
                  (x1 - 1, y1 - 1.1), (x0 + 1, y1 - 1.1)])
    paint(img, minus(inner, shift(inner, 0, 0.35)), border_light, 0.35)
    paint(img, minus(grow(inner, 0.3), inner), border, 0.6)
    return m


# ---------------------------------------------------------------- Ender Dragon

def dragon():
    img = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0))
    obsidian = [(0, rgba("4a2f73")), (0.45, rgba("2a1745")), (1, rgba("140a24"))]
    light, dark, edge = rgba("c79bff"), rgba("0d0619"), rgba("07030d")
    glow_c = rgba("b04dff")

    # Wings (left; mirrored to the right). Bones fan out from the frame's end; the membrane hangs between them.
    joint = (33, 14.5)
    bones = [(1.5, 1.0), (0.2, 11.5), (4.5, 22.5), (15.0, 30.0)]
    edge_pts = [(24, 4.0), (1.5, 1.0), (7.0, 7.5), (0.2, 11.5), (6.8, 16.0), (4.5, 22.5), (12.5, 23.5),
                (15.0, 30.0), (24.0, 25.0), (33, 20)]
    membrane = poly([joint] + edge_pts)
    wing = union(membrane, lines([joint, (24, 4.0), (1.5, 1.0)], 1.6))
    wing = union(wing, mirror(wing))
    glow(img, wing, glow_c, 2.5, 0.5)
    shadow(img, wing)
    for w_mask in (membrane, mirror(membrane)):
        paint(img, minus(grow(w_mask, 0.5), w_mask), edge)
        paint(img, w_mask, vgrad([(0, rgba("5b2a8f")), (0.6, rgba("35195a")), (1, rgba("1c0c33"))], 0, 30))
        # membrane folds: darker bands between bones
        for a, b in zip(bones, bones[1:]):
            fold = poly([joint, ((a[0] + b[0]) / 2 + 2, (a[1] + b[1]) / 2), b])
            paint(img, ImageChops.multiply(fold, w_mask) if w_mask is membrane else
                  ImageChops.multiply(mirror(fold), w_mask), rgba("12061f"), 0.35)
    for flip in (False, True):
        for t in bones:
            bone = lines([joint, ((joint[0] + t[0]) / 2, (joint[1] + t[1]) / 2 - 1.2), t], 0.9)
            bone = mirror(bone) if flip else bone
            paint(img, minus(grow(bone, 0.35), bone), edge)
            paint(img, bone, rgba("9d6fd6"))
            paint(img, minus(bone, shift(bone, 0, 0.4)), rgba("e2ccff"), 0.8)
        arm = lines([joint, (24, 4.0), (1.5, 1.0)], 1.5)
        arm = mirror(arm) if flip else arm
        solid(img, arm, rgba("7d52b8"), rgba("e2ccff"), rgba("2a1446"), edge, 0.4, 0.4)
        claw = poly([(1.5, 1.0), (4.8, 0.2), (3.0, 2.8)])
        claw = mirror(claw) if flip else claw
        solid(img, claw, rgba("f1e6ff"), rgba("ffffff"), rgba("8c7aa6"), edge, 0.3, 0.35)

    frame(img, obsidian, light, dark, edge, glow_c)
    # purple runes along the frame
    for x in range(44, 214, 12):
        for y in (11.4, 19.0):
            paint(img, ellipse(x - 0.45, y - 0.45, x + 0.45, y + 0.45), rgba("d9a6ff"), 0.9)
    # end caps: crystals
    for cx in (31.5, 224.5):
        gem(img, cx, 15.5, 4.4, rgba("d44dff"), rgba("ffc8ff"), rgba("5a0d6e"), edge)

    # Horned banner behind the name.
    for flip in (False, True):
        horn = poly([(66, 3), (58, -1), (54, -0.5), (60, 1.5), (62, 6)])
        horn = mirror(horn) if flip else horn
        solid(img, horn, vgrad([(0, rgba("efe4ff")), (1, rgba("7c5aa8"))], -1, 6), rgba("ffffff"), rgba("3a2358"),
              edge, 0.35, 0.4)
    plaque(img, rgba("0f0719", 215), rgba("07030d"), rgba("a66ae0"))

    # The eye of the dragon, hanging under the bar.
    eye_back = poly([(128, 17.5), (137, 22.5), (128, 28.5), (119, 22.5)])
    glow(img, eye_back, glow_c, 2.5, 0.7)
    solid(img, eye_back, vgrad(obsidian, 17.5, 28.5), light, dark, edge, 0.5, 0.5)
    iris = ellipse(123.2, 19.6, 132.8, 25.4)
    solid(img, iris, vgrad([(0, rgba("ffe066")), (0.5, rgba("ff9ad5")), (1, rgba("c21fa0"))], 19.6, 25.4),
          rgba("fff6c4"), rgba("5a0a44"), edge, 0.3, 0.4)
    paint(img, poly([(128, 19.9), (129, 22.5), (128, 25.1), (127, 22.5)]), rgba("0b0410"))
    paint(img, ellipse(125.1, 20.4, 126.3, 21.6), (255, 255, 255, 255), 0.95)
    return finish(img)


# ---------------------------------------------------------------- Wither

def skull(img, cx, top, s, eye, edge):
    """A wither skull, s bar-pixels wide."""
    head = rrect(cx - s / 2, top, cx + s / 2, top + s * 0.95, s * 0.16)
    jaw = rrect(cx - s * 0.36, top + s * 0.7, cx + s * 0.36, top + s * 1.12, s * 0.1)
    m = union(head, jaw)
    shadow(img, m, 0.9, 0.9, 0.7)
    solid(img, m, vgrad([(0, rgba("5a5a63")), (0.5, rgba("34343b")), (1, rgba("1a1a1f"))], top, top + s * 1.12),
          rgba("9a9aa6"), rgba("0b0b0e"), edge, 0.45, 0.5)
    ew, eh, ey = s * 0.24, s * 0.2, top + s * 0.4
    for ex in (cx - s * 0.27, cx + s * 0.03):
        socket = rrect(ex, ey, ex + ew, ey + eh, s * 0.04)
        paint(img, socket, rgba("050507"))
        glow(img, socket, eye, s * 0.14, 0.9)
        pupil = ellipse(ex + ew * 0.3, ey + eh * 0.25, ex + ew * 0.7, ey + eh * 0.75)
        paint(img, pupil, eye)
        paint(img, ellipse(ex + ew * 0.4, ey + eh * 0.33, ex + ew * 0.55, ey + eh * 0.5), (255, 255, 255, 255), 0.9)
    paint(img, poly([(cx, top + s * 0.63), (cx + s * 0.07, top + s * 0.75), (cx - s * 0.07, top + s * 0.75)]),
          rgba("050507"))
    for i in range(-2, 3):
        x = cx + i * s * 0.13
        paint(img, lines([(x, top + s * 0.86), (x, top + s * 1.05)], 0.35), rgba("050507"), 0.9)
    return m


def wither():
    img = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0))
    edge = rgba("050507")
    eye = rgba("7fe8ff")
    smoke = rgba("1b0a2a")

    # Dark smoke behind everything.
    haze = union(rrect(4, 3, 40, 29, 8), mirror(rrect(4, 3, 40, 29, 8)), rrect(108, 16, 148, 30, 6))
    paint(img, haze.filter(ImageFilter.GaussianBlur(3 * SS)), smoke, 0.8)

    # Thorns all along the frame, pointing outwards.
    thorns = new_mask()
    for i, x in enumerate(range(42, 216, 7)):
        h = 3.2 if i % 2 == 0 else 2.0
        thorns = union(thorns, poly([(x - 1.2, 10.8), (x + 0.8, 10.8 - h), (x + 1.0, 10.8)]),
                       poly([(x - 1.2, 20.2), (x + 0.8, 20.2 + h), (x + 1.0, 20.2)]))
    shadow(img, thorns, 0.6)
    solid(img, thorns, vgrad([(0, rgba("6d6d78")), (1, rgba("2a2a31"))], 6, 25), rgba("b4b4c2"), rgba("0e0e12"),
          edge, 0.3, 0.35)

    frame(img, [(0, rgba("55555f")), (0.5, rgba("2c2c33")), (1, rgba("141418"))], rgba("a3a3b0"), rgba("08080a"),
          edge, rgba("4b1d6e"))
    # chain links on the frame
    for x in range(42, 216, 6):
        for y in (11.5, 19.5):
            link = minus(ellipse(x - 1.4, y - 0.7, x + 1.4, y + 0.7), ellipse(x - 0.7, y - 0.25, x + 0.7, y + 0.25))
            paint(img, link, rgba("8a8a98"), 0.8)

    # Bone spikes at the ends and a skull on each.
    for flip in (False, True):
        spikes = union(poly([(34, 12), (2, 15.5), (34, 19)]), poly([(30, 11), (8, 2), (34, 13.5)]),
                       poly([(30, 20), (8, 29), (34, 17.5)]))
        spikes = mirror(spikes) if flip else spikes
        shadow(img, spikes)
        solid(img, spikes, vgrad([(0, rgba("d9d4c7")), (1, rgba("6f6a5e"))], 2, 29), rgba("fffdf4"),
              rgba("2d2a24"), edge, 0.4, 0.45)
    skull(img, 20, 7.2, 13, eye, edge)
    skull(img, 236, 7.2, 13, eye, edge)

    # The banner behind the name, with spiked ends.
    for flip in (False, True):
        spike = poly([(60, 7), (50, 3), (55, 7), (50, 11)])
        spike = mirror(spike) if flip else spike
        solid(img, spike, rgba("4a4a55"), rgba("a3a3b0"), rgba("0e0e12"), edge, 0.3, 0.4)
    plaque(img, rgba("0a0a0d", 220), edge, rgba("6a6a78"))

    # Three skulls hanging under the bar (like the Wither's three heads).
    skull(img, 118.5, 18.6, 7.4, eye, edge)
    skull(img, 137.5, 18.6, 7.4, eye, edge)
    skull(img, 128, 18.0, 9.4, eye, edge)
    return finish(img)


# ---------------------------------------------------------------- Raid

def feather_wing(img, flip, edge):
    gold_stops = [(0, rgba("fff2b0")), (0.35, rgba("f5c542")), (1, rgba("9a5f12"))]
    root = (34, 15)
    # (tip, width, droop), back to front
    feathers = [((2, 3.0), 3.4, -2), ((0, 9.5), 3.6, -1), ((1.5, 16.0), 3.6, 0), ((5.0, 22.0), 3.4, 1),
                ((11.0, 27.0), 3.0, 1), ((19.0, 29.5), 2.6, 1)]
    for tip, wd, droop in feathers:
        dx, dy = tip[0] - root[0], tip[1] - root[1]
        ln = math.hypot(dx, dy)
        nx, ny = -dy / ln * wd / 2, dx / ln * wd / 2
        mid = (root[0] + dx * 0.55, root[1] + dy * 0.55 + droop)
        m = poly([root, (mid[0] + nx, mid[1] + ny), tip, (mid[0] - nx, mid[1] - ny)])
        m = mirror(m) if flip else m
        shadow(img, m, 0.6, 0.6, 0.45)
        solid(img, m, vgrad(gold_stops, 0, 30), rgba("fffbe0"), rgba("5c3608"), edge, 0.4, 0.45)
        shaft = lines([root, mid, tip], 0.35)
        paint(img, mirror(shaft) if flip else shaft, rgba("7a4a0e"), 0.8)
    # a covert (the rounded top of the wing)
    cov = poly([(34, 11), (22, 4.5), (12, 3.2), (18, 8), (14, 11.5), (24, 13), (34, 17)])
    cov = mirror(cov) if flip else cov
    solid(img, cov, vgrad([(0, rgba("fff6c7")), (1, rgba("d99a2b"))], 3, 17), rgba("ffffff"), rgba("6e420c"),
          edge, 0.45, 0.45)
    for i in range(3):
        sc_ = ellipse(18 + i * 4.5, 6.5 + i * 1.2, 22 + i * 4.5, 9.5 + i * 1.2)
        paint(img, minus(sc_, shift(sc_, 0, 0.6)), rgba("fff9dc"), 0.7)


def axe(img, flip, edge):
    handle = lines([(58, 13.5), (69, -0.5)], 1.1)
    head = poly([(66.0, 0.6), (61.0, -0.5), (58.5, 3.4), (61.5, 5.8), (67.8, 3.2)])
    if flip:
        handle, head = mirror(handle), mirror(head)
    shadow(img, union(handle, head), 0.7)
    solid(img, handle, rgba("7a5230"), rgba("c99a6a"), rgba("2d1a0b"), edge, 0.3, 0.35)
    solid(img, head, vgrad([(0, rgba("f4f4fa")), (1, rgba("7c7c88"))], -0.5, 6), rgba("ffffff"), rgba("3b3b44"),
          edge, 0.4, 0.4)


def ominous_banner(img, edge):
    pole = lines([(120.5, 18.6), (135.5, 18.6)], 0.9)
    cloth = poly([(121.5, 19), (134.5, 19), (134.5, 29), (128, 31.2), (121.5, 29)])
    shadow(img, cloth, 0.8)
    solid(img, cloth, vgrad([(0, rgba("f2f2ee")), (1, rgba("bdbdb6"))], 19, 31), rgba("ffffff"), rgba("6b6b66"),
          edge, 0.35, 0.45)
    paint(img, poly([(128, 20.5), (133, 24.5), (128, 28.5), (123, 24.5)]), rgba("16706f"))
    paint(img, rrect(125.6, 22.3, 130.4, 27.3, 0.3), rgba("22222a"))
    paint(img, rrect(126.4, 23.6, 129.6, 24.5, 0.1), rgba("9d9d96"))
    paint(img, rrect(127.4, 24.6, 128.6, 26.4, 0.1), rgba("bdbdb6"))
    paint(img, lines([(122, 21), (134, 21)], 0.6), rgba("5b5b56"), 0.7)
    solid(img, pole, rgba("8a6a45"), rgba("d7b98f"), rgba("3b2812"), edge, 0.3, 0.35)


def raid():
    img = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0))
    edge = rgba("2a1703")
    wings = new_mask()
    for flip in (False, True):
        m = poly([(34, 12), (0, 2), (0, 16), (18, 30), (34, 18)])
        wings = union(wings, mirror(m) if flip else m)
    glow(img, wings, rgba("ffcc55"), 2.5, 0.45)
    for flip in (False, True):
        feather_wing(img, flip, edge)

    frame(img, [(0, rgba("fff0a8")), (0.35, rgba("e5b23a")), (1, rgba("7d4c0c"))], rgba("fffbe6"), rgba("4a2a04"),
          edge, rgba("ffb52e"))
    # red enamel inlay with gold studs
    for y0, y1 in ((10.9, 12.0), (19.0, 20.1)):
        paint(img, rrect(40, y0, 216, y1, 0.4), rgba("a3161b"), 0.9)
    for x in range(44, 214, 10):
        for y in (11.45, 19.55):
            solid(img, ellipse(x - 0.7, y - 0.7, x + 0.7, y + 0.7), rgba("ffd760"), rgba("fffbe0"), rgba("7a4a0e"),
                  edge, 0.2, 0.2)
    for cx in (31.5, 224.5):
        gem(img, cx, 15.5, 4.3, rgba("e3262e"), rgba("ffb3b3"), rgba("5c0508"), edge)

    for flip in (False, True):
        axe(img, flip, edge)
    plaque(img, rgba("1a0f05", 215), edge, rgba("e5b23a"))
    ominous_banner(img, edge)
    return finish(img)


# ---------------------------------------------------------------- packs

def small(img, box, size):
    """A square Bedrock glyph from part of the Java art."""
    part = img.crop(box)
    part.thumbnail((size, size), Image.LANCZOS)
    cell = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    cell.alpha_composite(part, ((size - part.width) // 2, (size - part.height) // 2))
    return cell


def png_bytes(img):
    b = io.BytesIO()
    img.save(b, "PNG", optimize=True)
    return b.getvalue()


def write_zip(path, files):
    """Deterministic zip (fixed dates, sorted), so its SHA-1 only changes when the content does."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(files):
            info = zipfile.ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0))
            info.external_attr = 0o644 << 16
            z.writestr(info, files[name])


def space_advances():
    adv = {}
    for i in range(10):
        adv[chr(0xF801 + i)] = -(1 << i)
        adv[chr(0xF821 + i)] = 1 << i
    return adv


def preview(art, name_w):
    """Roughly how a bar looks in game: sky, the vanilla bar under the art, a stand-in for the name."""
    bg = Image.new("RGBA", (W * DETAIL, H * DETAIL + 8), rgba("6f9ad6"))
    d = ImageDraw.Draw(bg)
    d.rectangle([BAR_L * DETAIL, BAR_T * DETAIL, BAR_R * DETAIL - 1, BAR_B * DETAIL - 1], fill=rgba("3b1d4a"))
    d.rectangle([BAR_L * DETAIL, BAR_T * DETAIL, int((BAR_L + 120) * DETAIL) - 1, BAR_B * DETAIL - 1],
                fill=rgba("d63bd6"))
    bg.alpha_composite(art)
    x0 = (W // 2 - name_w // 2) * DETAIL
    d = ImageDraw.Draw(bg)
    d.rectangle([x0, 4 * DETAIL, x0 + name_w * DETAIL, 11 * DETAIL], fill=(255, 255, 255, 160))
    return bg


def main():
    os.makedirs(OUT, exist_ok=True)
    art = {"dragon": dragon(), "wither": wither(), "raid": raid()}
    chars = {"dragon": "", "wither": "", "raid": ""}
    icon = open(os.path.join(ROOT, "src/main/resources/assets/vigil/icon.png"), "rb").read()

    # ---- Java ----
    # The title's text sits 9 px above the bar. ascent 11 puts row 0 of the art 4 px above the title's top,
    # so the art's bar rows (13-17) land exactly on the bar. height 32 shows the 1024 x 128 texture 256 x 32 big.
    providers = [{"type": "space", "advances": space_advances()}]
    for k in art:
        providers.append({"type": "bitmap", "file": "vigil:font/bossbar/%s.png" % k, "ascent": 11, "height": H,
                          "chars": [chars[k]]})
    java = {
        "pack.mcmeta": json.dumps({"pack": {
            "description": "Vigil boss bars: Ender Dragon, Wither, Raid",
            "pack_format": 75, "min_format": 69, "max_format": 99}}, indent=2),
        "pack.png": icon,
        "assets/vigil/font/bossbar.json": json.dumps({"providers": providers}, indent=2, ensure_ascii=True),
    }
    widths = {"dragon": 68, "wither": 36, "raid": 24}
    for k, img in art.items():
        java["assets/vigil/textures/font/bossbar/%s.png" % k] = png_bytes(img)
        preview(img, widths[k]).save(os.path.join(OUT, "preview-%s.png" % k))
    write_zip(os.path.join(OUT, "vigil-bossbars.zip"), java)

    # ---- Bedrock (Geyser) ----
    # glyph_E2.png: 16 x 16 cells. 64 px cells keep the detail. U+E200.. are the ornaments beside the name.
    cell = 64
    sheet = Image.new("RGBA", (16 * cell, 16 * cell), (0, 0, 0, 0))
    d4 = DETAIL
    left, right = (0, 0, 27 * d4, 32 * d4), (229 * d4, 0, 256 * d4, 32 * d4)
    cells = [small(art["dragon"], left, cell), small(art["dragon"], right, cell),
             small(art["wither"], (2 * d4, 2 * d4, 30 * d4, 30 * d4), cell),
             small(art["wither"], (226 * d4, 2 * d4, 254 * d4, 30 * d4), cell),
             small(art["raid"], left, cell), small(art["raid"], right, cell)]
    for i, c in enumerate(cells):
        sheet.alpha_composite(c, ((i % 16) * cell, (i // 16) * cell))
    manifest = {
        "format_version": 2,
        "header": {"name": "Vigil boss bars", "description": "Ender Dragon, Wither and Raid bars",
                   "uuid": "8f3c2a1e-6b7d-4c9e-9a51-2d4e7f0b1c33", "version": [1, 1, 0],
                   "min_engine_version": [1, 20, 0]},
        "modules": [{"type": "resources", "uuid": "c4e1b7a2-93d5-4f60-8b2c-5a7e9d1f3b44", "version": [1, 1, 0]}],
    }
    bedrock = {
        "manifest.json": json.dumps(manifest, indent=2),
        "pack_icon.png": icon,
        "font/glyph_E2.png": png_bytes(sheet),
    }
    write_zip(os.path.join(OUT, "vigil-bossbars.mcpack"), bedrock)
    sheet.crop((0, 0, 6 * cell, cell)).save(os.path.join(OUT, "preview-bedrock-glyphs.png"))
    print("wrote", os.path.join(OUT, "vigil-bossbars.zip"), "and .mcpack")


if __name__ == "__main__":
    main()
