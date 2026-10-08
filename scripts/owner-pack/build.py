#!/usr/bin/env python3
"""Builds the server pack (resourcepack/pack.zip): tool textures, power sounds and item model overrides.

Needs pillow, numpy and soundfile:  pip install pillow numpy soundfile
Run from the repo root:             python3 scripts/owner-pack/build.py
"""
import hashlib
import io
import json
import math
import os
import zipfile

import numpy as np
import soundfile as sf
from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "resourcepack")
SR = 44100
files = {}


def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


# ------------------------------------------------------------------ textures (16x16 pixel art)

def canvas():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def rod(img, x0, y0, x1, y1, body, light, dark):
    """A 2-pixel diagonal rod from bottom-left (x0,y0) to top-right (x1,y1), with a light and a dark edge."""
    px = img.load()
    n = x1 - x0
    for i in range(n + 1):
        x, y = x0 + i, y0 - i
        px[x, y] = body
        if x + 1 < 16:
            px[x + 1, y] = light if i % 2 == 0 else body
        if y + 1 < 16:
            px[x, y + 1] = dark


def lightning_wand():
    img = canvas()
    px = img.load()
    rod(img, 1, 14, 8, 7, hexc("#3b2f52"), hexc("#6c5a92"), hexc("#1d1629"))
    for (x, y) in [(3, 12), (4, 12), (6, 10), (7, 10)]:
        px[x, y] = hexc("#e8b830")
    # A crisp zigzag bolt: bright core, darker gold on its right edge.
    bolt = {0: [13, 14], 1: [12, 13], 2: [11, 12], 3: [11, 12, 13, 14], 4: [12, 13], 5: [11, 12], 6: [10, 11], 7: [9, 10]}
    for y, xs in bolt.items():
        for x in xs:
            px[x, y] = hexc("#fff59a")
        px[xs[-1], y] = hexc("#f2b705")
    px[14, 3] = hexc("#f2b705")
    for (x, y) in [(15, 0), (15, 2), (9, 2), (14, 6)]:
        px[x, y] = hexc("#fffbe0", 150)
    return img


def launch_stick():
    img = canvas()
    px = img.load()
    rod(img, 2, 14, 10, 6, hexc("#8b5a2b"), hexc("#b07a45"), hexc("#5a3517"))
    # Two gusts of wind curling up from the tip.
    for (x, y) in [(10, 4), (11, 3), (12, 3), (13, 3), (14, 4)]:
        px[x, y] = hexc("#8fefff")
    for (x, y) in [(11, 1), (12, 0), (13, 0), (14, 0), (15, 1)]:
        px[x, y] = hexc("#d8fbff")
    px[12, 3] = hexc("#ffffff")
    px[13, 0] = hexc("#ffffff")
    for (x, y) in [(8, 3), (15, 6), (6, 6)]:
        px[x, y] = hexc("#bff6ff", 160)
    return img


def heal_wand():
    img = canvas()
    px = img.load()
    rod(img, 2, 14, 10, 6, hexc("#e2b23a"), hexc("#fbe08a"), hexc("#9a6d12"))
    heart = {0: [10, 11, 13, 14], 1: list(range(9, 16)), 2: list(range(9, 16)), 3: list(range(10, 15)), 4: [11, 12, 13], 5: [12]}
    for y, xs in heart.items():
        for x in xs:
            px[x, y] = hexc("#ff3d63")
    for y, xs in heart.items():
        for x in (xs[0], xs[-1]):
            px[x, y] = hexc("#b0153a")
    px[12, 5] = hexc("#b0153a")
    for (x, y) in [(10, 1), (11, 1), (10, 2)]:
        px[x, y] = hexc("#ffc2cf")
    for (x, y) in [(5, 4), (7, 2), (15, 8), (3, 9)]:
        px[x, y] = hexc("#7cff8a", 220)
    return img



PAL = {"K": "#1b1726", "W": "#f4f7ff", "L": "#a9d8ff", "Y": "#ffd23f", "O": "#e8a628", "R": "#ff3d63",
       "G": "#5dff7a", "D": "#1f8a3a", "S": "#b9bfcc", "N": "#6b4220", "B": "#3d7bff", "C": "#7fe7ff",
       "M": "#d65cff", "P": "#9b6bff", "T": "#c8823c"}


def grid(rows, outline=True):
    img = canvas()
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                px[x, y] = hexc(PAL[ch])
    if outline:
        solid = [(x, y) for y in range(16) for x in range(16) if px[x, y][3] > 0 and px[x, y][:3] != hexc(PAL["K"])[:3]]
        for x, y in solid:
            for dx, dy in [(1, 0), (-1, 0), (0, 1), (0, -1)]:
                nx, ny = x + dx, y + dy
                if 0 <= nx < 16 and 0 <= ny < 16 and px[nx, ny][3] == 0:
                    px[nx, ny] = hexc("#1b1726", 200)
    return img


def pad(rows):
    rows = [r.ljust(16, ".")[:16] for r in rows]
    return rows + ["." * 16] * (16 - len(rows))


ICONS = {
    "icon_fly": ("feather", pad([
        "................", "................", ".KK..........KK.", "KWWK........KWWK", "KWWWK......KWWWK", "KLWWWK....KWWWLK",
        ".KLWWWK..KWWWLK.", ".KLLWWWKKWWWLLK.", "..KLLWWWWWWLLK..", "..KLLLWWWWLLLK..", "...KLLLWWLLLK...",
        "....KKLLLLKK....", "......KKKK......"]), False),
    "icon_god": ("totem_of_undying", pad([
        "................", "....YYYYYYYY....", "...Y........Y...", "....YYYYYYYY....", "................",
        "..O....OO....O..", "..OO..OOOO..OO..", "..OOOOOOOOOOOO..", "..OYOOORROOOYO..", "..OOOOOOOOOOOO..",
        "..OOOOOOOOOOOO.."]), True),
    "icon_speed": ("sugar", pad([
        "................", ".........YYY....", "........YYY.....", ".......YYY......", ".SS...YYYYYYY...",
        "......YYYYYY....", ".SSS.....YYY....", "........YYY.....", ".SS....YYY......", "......YY........",
        ".....Y.........."]), True),
    "icon_night": ("ender_eye", pad([
        "................", "................", "................", "................", ".....WWWWWW.....",
        "...WWWWGGWWWW...", "..WWWGGGGGGWWW..", ".WWWGGDKKDGGWWW.", ".WWWGGDKKDGGWWW.", "..WWWGGGGGGWWW..",
        "...WWWWGGWWWW...", ".....WWWWWW....."]), True),
    "icon_break": ("flint", pad([
        "................", "...SSSSSSS......", "..SS.....SS.....", ".S.....N...S....", ".......N........",
        "........N.......", ".........N......", "..........N.....", "...........N....", "............N...",
        "......Y......N..", "....Y.Y.Y.......", ".....YYY........", "....YYRYY.......", ".....YYY........",
        "......Y........."]), True),
    "icon_radar": ("amethyst_shard", pad([
        "................", ".....GGGGGG.....", "...GG......GG...", "..G..........G..", ".G....DDDD....G.",
        ".G...D....D...G.", "G...D......D...G", "G...D..GG..D.R.G", "G...D..GGG.D...G", "G...D......D...G",
        ".G...D....D...G.", ".G..R.DDDD....G.", "..G..........G..", "...GG......GG...", ".....GGGGGG....."]), False),
    "icon_ghost": ("phantom_membrane", pad([
        "................", "................", ".....WWWWWW.....", "....WWWWWWWW....", "...WWWWWWWWWW...",
        "...WWKKWWKKWW...", "...WWKKWWKKWW...", "...WWWWWWWWWW...", "...WWWWKKWWWW...", "...WWWWWWWWWW...",
        "...LWWWWWWWWL...", "...WW.WWWW.WW...", "...W...WW...W..."]), True),
    "icon_repair": ("iron_nugget", pad([
        "................", "................", "...SSSSSS.......", "..SSSSSSSS......", "..SSSSSSSS..Y...",
        "...SSSSSS..YYY..", ".....NN.....Y...", "......NN........", ".......NN.......", "........NN......",
        ".........NN.....", "..........NN....", "...........NN..."]), True),
    "icon_items": ("paper", pad([
        "................", "....Y......Y....", "...YYY....YYY...", "....Y......Y....", "..NNNNNNNNNNNN..",
        "..NTTTTTTTTTTN..", "..NTTTTTTTTTTN..", "..NNNNNYYNNNNN..", "..NTTTTYYTTTTN..", "..NTTTTTTTTTTN..",
        "..NTTTTTTTTTTN..", "..NNNNNNNNNNNN.."]), True),
}


def star_icon():
    img = canvas()
    px = img.load()
    cx, cy = 7, 7
    for dx, dy in [(1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, -1), (1, -1), (-1, 1)]:
        n = 6 if dx == 0 or dy == 0 else 4
        for i in range(1, n + 1):
            px[cx + dx * i, cy + dy * i] = hexc(PAL["Y"])
        px[cx + dx * n, cy + dy * n] = hexc(PAL["R"] if (dx + dy) % 2 else PAL["M"])
    px[cx, cy] = hexc(PAL["O"])
    for (x, y) in [(2, 12), (13, 2), (12, 13)]:
        px[x, y] = hexc(PAL["C"])
    return img


def palette_icon():
    img = canvas()
    d = ImageDraw.Draw(img)
    d.ellipse([1, 3, 14, 13], fill=hexc("#c8823c"), outline=hexc("#6b4220"))
    px = img.load()
    for (x, y), c in zip([(4, 6), (7, 5), (10, 6), (5, 9), (11, 9)], ["R", "Y", "G", "B", "M"]):
        px[x, y] = hexc(PAL[c])
        px[x + 1, y] = hexc(PAL[c])
    px[8, 10] = (0, 0, 0, 0)
    px[9, 10] = (0, 0, 0, 0)
    return img


def freeze_wand():
    img = canvas()
    px = img.load()
    rod(img, 1, 14, 8, 7, hexc("#bdefff"), hexc("#ffffff"), hexc("#4aa6c8"))
    flake = [(12, y) for y in range(0, 7)] + [(x, 3) for x in range(9, 16)] + \
            [(10, 1), (11, 2), (13, 4), (14, 5), (14, 1), (13, 2), (11, 4), (10, 5)]
    for (x, y) in flake:
        px[x, y] = hexc("#d6f6ff")
    px[12, 3] = hexc("#ffffff")
    for (x, y) in [(12, 0), (12, 6), (9, 3), (15, 3)]:
        px[x, y] = hexc("#7fe7ff")
    for (x, y) in [(15, 7), (8, 1), (5, 5)]:
        px[x, y] = hexc("#e6fdff", 170)
    return img


def judge_gavel():
    """A courtroom gavel, pixel by pixel on the 45-degree grid vanilla tools use: a barrel head lit from the top,
    lighter striking faces, two gold rings, a straight two-pixel handle with a gold knob, and a solid outline."""
    img = canvas()
    px = img.load()
    wood = {9: "#c9743a", 8: "#a4562a", 7: "#82401e", 6: "#622d14", 5: "#45200d"}
    cap = {9: "#e8a868", 8: "#cc8648", 7: "#a8663a", 6: "#844c2a", 5: "#5e341c"}
    gold = {9: "#fff6b0", 8: "#ffd94a", 7: "#eeb21e", 6: "#c48a0e", 5: "#8e600a"}
    # Handle along x + y = 15/16, from the knob up to the head.
    for x in range(1, 10):
        px[x, 15 - x] = hexc("#b0683a")
        px[x, 16 - x] = hexc("#5e2c14")
    for (x, y) in [(0, 15), (1, 15), (0, 14)]:
        px[x, y] = hexc("#c48a0e")
    px[1, 14] = hexc("#ffd94a")
    # Head: x - y from 5 (shadow side) to 9 (lit side), x + y from 10 to 20 (its length).
    for y in range(16):
        for x in range(16):
            c, a_ = x - y, x + y
            if not (5 <= c <= 9 and 10 <= a_ <= 20):
                continue
            if a_ in (10, 20) and c in (5, 9):
                continue                       # rounded corners
            if a_ in (10, 11, 19, 20):
                col = cap[c]                   # striking faces
            elif a_ in (12, 13, 17, 18):
                col = gold[c]                  # gold rings
            else:
                col = wood[c]
            px[x, y] = hexc(col)
    # Solid outline, like vanilla items.
    solid = [(x, y) for y in range(16) for x in range(16) if px[x, y][3] == 255]
    for x, y in solid:
        for dx, dy in [(1, 0), (-1, 0), (0, 1), (0, -1)]:
            nx, ny = x + dx, y + dy
            if 0 <= nx < 16 and 0 <= ny < 16 and px[nx, ny][3] == 0:
                px[nx, ny] = hexc("#2a170b")
    return img


# ------------------------------------------------------------------ combat weapons

def outline(img, color="#1b1209", alpha=255):
    px = img.load()
    solid = [(x, y) for y in range(img.height) for x in range(img.width) if px[x, y][3] == 255]
    for x, y in solid:
        for dx, dy in [(1, 0), (-1, 0), (0, 1), (0, -1)]:
            nx, ny = x + dx, y + dy
            if 0 <= nx < img.width and 0 <= ny < img.height and px[nx, ny][3] == 0:
                px[nx, ny] = hexc(color, alpha)
    return img


def thor_hammer():
    """Mjolnir on the 45-degree grid: a wide steel head with a glowing rune, a leather-wrapped handle and a strap loop."""
    img = canvas()
    px = img.load()
    for x in range(2, 9):
        px[x, 15 - x] = hexc("#8a5a33" if x % 2 else "#5e3b1f")
        px[x, 16 - x] = hexc("#3d2412")
    for (x, y) in [(0, 14), (0, 15), (1, 15), (1, 13)]:
        px[x, y] = hexc("#6b4423")
    px[1, 14] = hexc("#c8a060")
    steel = {10: "#f2f6fa", 9: "#d5dde6", 8: "#b8c3cf", 7: "#9aa7b5", 6: "#7c8999", 5: "#5f6b7a", 4: "#465160"}
    for y in range(16):
        for x in range(16):
            c, a_ = x - y, x + y
            if not (4 <= c <= 10 and 9 <= a_ <= 21):
                continue
            if a_ in (9, 21) and c in (4, 10):
                continue
            col = steel[c]
            if a_ in (9, 10, 20, 21):
                col = steel[min(10, c + 1)] if c < 10 else "#ffffff"
            px[x, y] = hexc(col)
    # The rune: a glowing blue mark in the middle of the face.
    for (x, y) in [(10, 3), (11, 4), (12, 5), (11, 3), (12, 4)]:
        px[x, y] = hexc("#7fe0ff")
    px[11, 4] = hexc("#ffffff")
    outline(img, "#141a24")
    for (x, y) in [(15, 0), (14, 9), (5, 1)]:
        px[x, y] = hexc("#9fe8ff", 200)
    return img


def flame_sword():
    img = canvas()
    px = img.load()
    for x in range(6, 15):
        px[x, 15 - x] = hexc("#fff3b0")
        px[x, 14 - x] = hexc("#ffb02e")
        if x + 1 < 16:
            px[x + 1, 15 - x] = hexc("#e2461b")
    px[15, 0] = hexc("#fff8d0")
    for (x, y) in [(3, 8), (4, 9), (5, 10), (6, 11), (7, 12)]:
        px[x, y] = hexc("#f2c94c")
    px[5, 10] = hexc("#ff3d1f")
    for x in range(2, 5):
        px[x, 15 - x] = hexc("#5a2a12" if x % 2 else "#7a3d1b")
    px[1, 14] = hexc("#ff7a1a")
    px[0, 15] = hexc("#b0200c")
    px[1, 15] = hexc("#b0200c")
    px[0, 14] = hexc("#b0200c")
    outline(img, "#2a0d05")
    for (x, y, c, a) in [(9, 3, "#ffb02e", 220), (11, 1, "#ffd84a", 200), (7, 5, "#ff7a1a", 180), (13, 4, "#ff7a1a", 170),
                         (12, 0, "#ffe9a0", 150), (5, 7, "#ffb02e", 140)]:
        if px[x, y][3] == 0:
            px[x, y] = hexc(c, a)
    return img


def godslayer():
    """A black blade with a blood-red edge and a glowing red core, on a bone hilt with a skull pommel."""
    img = canvas()
    px = img.load()
    for x in range(5, 15):
        y = 15 - x
        px[x, y] = hexc("#1a0a0e")
        px[x, y - 1] = hexc("#2a1016")
        if x + 1 < 16:
            px[x + 1, y] = hexc("#e0123a")
        if y - 2 >= 0 and x % 2 == 0:
            px[x, y - 2] = hexc("#ff3d63")
    px[15, 0] = hexc("#ff8aa0")
    for i in range(6, 13, 2):
        px[i, 15 - i] = hexc("#ff2a4a")
    for (x, y) in [(2, 9), (3, 10), (4, 11), (5, 12), (6, 13)]:
        px[x, y] = hexc("#cfcbb4")
    px[4, 11] = hexc("#e0123a")
    for x in range(1, 4):
        px[x, 15 - x] = hexc("#5a5546" if x % 2 else "#8e8a76")
    px[0, 15] = hexc("#ebe8d6")
    px[1, 15] = hexc("#cfcbb4")
    px[0, 14] = hexc("#cfcbb4")
    outline(img, "#0a0204")
    for (x, y, c, a) in [(10, 2, "#ff3d63", 200), (12, 1, "#ff8aa0", 170), (8, 4, "#e0123a", 160), (13, 3, "#e0123a", 150)]:
        if px[x, y][3] == 0:
            px[x, y] = hexc(c, a)
    return img


def bow_art(limb, limb_light, grip, string, tip, glow):
    img = canvas()
    px = img.load()
    p0, p1, p2 = np.array([2.5, 1.5]), np.array([15.5, -0.5]), np.array([13.5, 12.5])
    pts = []
    for i in range(200):
        tt = i / 199
        q = (1 - tt) ** 2 * p0 + 2 * (1 - tt) * tt * p1 + tt * tt * p2
        pts.append((int(q[0]), int(q[1]), tt))
    for (x, y, tt) in pts:
        if 0 <= x < 16 and 0 <= y < 16:
            px[x, y] = hexc(grip if 0.42 < tt < 0.58 else limb)
    for (x, y, tt) in pts:
        if 0 <= x - 1 < 16 and 0 <= y < 16 and px[x - 1, y][3] == 0 and not (0.42 < tt < 0.58):
            px[x - 1, y] = hexc(limb_light)
    for i in range(11):
        x, y = 3 + i, 2 + i
        if px[x, y][3] == 0:
            px[x, y] = hexc(string, 230)
    for (x, y) in [(2, 1), (13, 12)]:
        px[x, y] = hexc(tip)
    outline(img, "#10141c")
    for (x, y) in glow:
        px[x, y] = hexc(tip, 190)
    return img


def frost_bow():
    return bow_art("#8fdcf5", "#e8fbff", "#3a6f9e", "#ffffff", "#bff4ff", [(1, 0), (14, 14), (15, 11), (0, 3)])


def blast_bow():
    return bow_art("#4a4550", "#ff8a2a", "#7a2a12", "#ffd2a0", "#ffb02e", [(1, 0), (14, 14), (15, 11), (0, 3)])


def meteor_staff():
    img = canvas()
    px = img.load()
    rod(img, 1, 14, 8, 7, hexc("#3a2a4a"), hexc("#6a4a80"), hexc("#1c1424"))
    for (x, y) in [(7, 8), (8, 8), (8, 7)]:
        px[x, y] = hexc("#f2c94c")
    cx, cy = 11.5, 3.5
    for y in range(16):
        for x in range(16):
            d = ((x + 0.5 - cx - 0.5) ** 2 + (y + 0.5 - cy - 0.5) ** 2) ** 0.5
            if d <= 3.6:
                shade = "#4a3a33" if (x + y) % 3 else "#2b2220"
                if d < 1.6:
                    shade = "#ffcf3a"
                elif (x * 7 + y * 3) % 5 == 0:
                    shade = "#ff6a00"
                px[x, y] = hexc(shade)
    px[12, 4] = hexc("#fff3b0")
    outline(img, "#1a0c06")
    for (x, y, c, a) in [(15, 7, "#ff7a1a", 200), (8, 1, "#ffb02e", 190), (15, 0, "#ffd84a", 170), (6, 3, "#ff6a00", 150)]:
        if px[x, y][3] == 0:
            px[x, y] = hexc(c, a)
    return img


GLOVE = pad([
    "................",
    "......W..W......",
    ".....WS.WS.W....",
    ".....WS.WS.WS...",
    ".....WS.WS.WS...",
    "..W..WSWWSWWS...",
    "..WS.WWWWWWWS...",
    "...WSWWWWWWWS...",
    "....WWWWWWWWS...",
    "....WWWWWWWS....",
    ".....WWWWWWS....",
    ".....YYYYYYY....",
    ".....YOOYOOY....",
    ".....YYYYYYY...."])


def disarm_gloves():
    img = grid(GLOVE, False)
    outline(img, "#1b1726")
    return img


HOME = pad([
    ".......W........",
    "......WCW.......",
    ".....WCCCW......",
    "....WCCLCCW.....",
    "...WCCLWLCCW....",
    "..WCCLWWWLCCW...",
    "..WCCWWWWWCCW...",
    "..WCCWBBBWCCW...",
    "..WCCWBBBWCCW...",
    "...WCCCCCCCW....",
    "....WCCCCCW.....",
    ".....WCCCW......",
    "......WCW.......",
    ".......W........"])


def home_teleporter():
    img = grid(HOME, False)
    outline(img, "#0c2a30")
    return img


def meteor_texture():
    img = Image.new("RGBA", (16, 16), hexc("#2b2220"))
    px = img.load()
    r = np.random.default_rng(11)
    for y in range(16):
        for x in range(16):
            v = r.integers(0, 4)
            px[x, y] = hexc(["#2b2220", "#3a2d29", "#4a3a33", "#221a18"][v])
    # Glowing cracks
    for start in [(1, 3), (8, 0), (15, 9), (4, 15), (0, 11)]:
        x, y = start
        for _ in range(9):
            if 0 <= x < 16 and 0 <= y < 16:
                px[x, y] = hexc("#ff6a00")
                for (nx, ny) in [(x + 1, y), (x, y + 1)]:
                    if 0 <= nx < 16 and 0 <= ny < 16 and r.random() < 0.3:
                        px[nx, ny] = hexc("#ffcf3a")
            x += int(r.integers(-1, 2))
            y += int(r.integers(-1, 2)) or 1
    return img


def rune_circle():
    S = 64
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([2, 2, S - 3, S - 3], outline=hexc("#7fd8ff", 230), width=2)
    d.ellipse([7, 7, S - 8, S - 8], outline=hexc("#ffe27a", 200), width=1)
    d.ellipse([18, 18, S - 19, S - 19], outline=hexc("#7fd8ff", 210), width=2)
    import math
    c = S / 2
    for i in range(8):
        a = i * math.pi / 4
        x, y = c + math.cos(a) * 24, c + math.sin(a) * 24
        d.line([x - 2, y - 3, x + 2, y + 3], fill=hexc("#ffe27a", 230), width=1)
        d.line([x - 2, y, x + 2, y], fill=hexc("#ffe27a", 230), width=1)
    pts = [(c + math.cos(i * 4 * math.pi / 5 - math.pi / 2) * 13, c + math.sin(i * 4 * math.pi / 5 - math.pi / 2) * 13) for i in range(5)]
    d.line(pts + [pts[0]], fill=hexc("#bff4ff", 240), width=1)
    d.ellipse([c - 3, c - 3, c + 3, c + 3], fill=hexc("#ffffff", 220))
    return img


ICONS.update({
    "icon_punch": ("brick", pad([
        "................", "..Y...........Y.", "...Y...WW...Y...", "......WWWW......", ".....WSWWSW.....",
        "....WSSWSSWW....", "....WSSWSSWSW...", "....WWWWWWWSW...", "....WSSSSSWSW...", "....WWWWWWWW....",
        ".....WWWWWW.....", "......RRRR......", "......RRRR......", "..Y.........Y...", ".Y...........Y.."]), True),
    "icon_steal": ("red_dye", pad([
        "................", "...RR....RR.....", "..RRRR..RRRR....", "..RRRRRRRRRR....", "..RRRRRRRRRR....",
        "...RRRRRRRR.....", "....RRRRRR......", ".....RRRR.......", "......RR........", "................",
        "..........M.....", ".........MMM....", "........MMMMM...", "........MMMMM...", ".........MMM...."]), True),
    "icon_knock": ("slime_ball", pad([
        "................", "................", "..CCCCCCC.......", ".........CC.....", "...........C....",
        "..WWWWWWWWWWWW..", "............WWW.", "..WWWWWWWWWWWW..", "...........C....", ".........CC.....",
        "..CCCCCCC.......", "................"]), True),
    "icon_nocool": ("glowstone_dust", pad([
        "..............W.", ".............WSW", "............WSW.", "...........WSW..", "..........WSW...",
        ".........WSW....", "..Y.....WSW.....", "..YY...WSW......", "...YY.WSW.......", "....YYSW........",
        "....NYY.........", "...NN.YY........", "..NN............", ".OO.......YYY...", ".OO......Y......", "..........YYY..."]), True),
    "icon_field": ("heart_of_the_sea", pad([
        "................", ".....PPPPPP.....", "...PP......PP...", "..P...MMMM...P..", ".P...M....M...P.",
        ".P..M......M..P.", "P...M..WW..M...P", "P...M.WWWW.M...P", "P...M.WWWW.M...P", "P...M..WW..M...P",
        ".P..M......M..P.", ".P...M....M...P.", "..P...MMMM...P..", "...PP......PP...", ".....PPPPPP....."]), True),
    "icon_berserk": ("blaze_powder", pad([
        "................", "...R...RR...R...", "...RR.RRRR.RR...", "....RRRRRRRR....", "...RRWWRRWWRR...",
        "...RRKWRRWKRR...", "...RRRRRRRRRR...", "....RRRRRRRR....", "....RKKKKKKR....", ".....RWKWKR.....",
        "......RRRR......", ".......RR......."]), True),
    "icon_wipe": ("bone", pad([
        ".........Y......", "........YY......", ".......YY.......", "......YYYYY.....", "........YY......",
        ".......YY.......", "...WWWWWWW......", "..WWWWWWWWW.....", "..WKKWWWKKW.....", "..WKKWWWKKW.....",
        "..WWWWKWWWW.....", "...WWWWWWW......", "...WKWKWKW......", "................"]), True),
    "icon_smite": ("gold_nugget", pad([
        "..........YYY...", ".........YYY....", "........YYY.....", ".......YYY......", "......YYYYYYY...",
        ".........YYY....", "........YYY.....", ".......YYY......", "......YY........", ".....Y..........",
        "...C...C...C....", "..CCC.CCC.CCC...", "................"]), True),
})


CANNON = pad([
    "............R...",
    "...........RWR..",
    "..........SRRRS.",
    ".........SSKKSS.",
    "........SSKRKSS.",
    ".......SSSKKSSS.",
    "......SSSSSSSS..",
    ".....SSLSSSSS...",
    "....SSLSSSSS....",
    "...KSSSSSSS.....",
    "..KKKSSSSS......",
    ".KKYKKSSS.......",
    "KKKKKKSS........",
    "KKKKKK..........",
    ".KKK............"])


def orbital_cannon():
    img = grid(CANNON, False)
    outline(img, "#0e0f16")
    px = img.load()
    for (x, y) in [(14, 0), (15, 2), (10, 1)]:
        px[x, y] = hexc("#ff3d3d", 170)
    return img


# ------------------------------------------------------------------ secret items and new tools (everyone sees these)

def pix(rows, pal, edge="#140f1c"):
    img = canvas()
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row[:16]):
            if ch != "." and ch in pal:
                px[x, y] = hexc(pal[ch])
    return outline(img, edge)


def sword_art(core, light, dark, guard, gem, grip, pommel, sparkles):
    img = canvas()
    px = img.load()
    for x in range(6, 15):
        px[x, 15 - x] = hexc(core)
        px[x, 14 - x] = hexc(light)
        if x + 1 < 16:
            px[x + 1, 15 - x] = hexc(dark)
    px[15, 0] = hexc(light)
    for (x, y) in [(3, 8), (4, 9), (5, 10), (6, 11), (7, 12)]:
        px[x, y] = hexc(guard)
    px[5, 10] = hexc(gem)
    for x in range(2, 5):
        px[x, 15 - x] = hexc(grip)
    for (x, y) in [(0, 15), (1, 15), (0, 14), (1, 14)]:
        px[x, y] = hexc(pommel)
    outline(img, "#120a1c")
    for (x, y, c, a) in sparkles:
        if px[x, y][3] == 0:
            px[x, y] = hexc(c, a)
    return img


def voidblade():
    return sword_art("#e6d4ff", "#b488ff", "#4b1f8a", "#2a2238", "#d65cff", "#3a2a4a", "#6a2bd6",
                     [(9, 3, "#b488ff", 200), (12, 1, "#d65cff", 170), (6, 5, "#8a5cff", 150), (14, 4, "#b488ff", 140)])


def stormbreaker():
    return pix([
        "........SSSS....",
        ".......SLLLSS...",
        "......SLLYLLSS..",
        "......SLLYYLLS..",
        ".......SLYLLWN..",
        "........SSLWNS..",
        ".........WNSS...",
        "........WN......",
        ".......WN.......",
        "......WN........",
        ".....WN.........",
        "....WN..........",
        "...WN...........",
        "..WN............",
        ".WN.............",
        "BB.............."], {"S": "#4a6a8a", "L": "#bfe4ff", "Y": "#ffe27a", "W": "#9a6a3a", "N": "#5a3a1a", "B": "#7fd8ff"})


def phoenix_feather():
    return pix([
        "............RYY.",
        "...........RYYO.",
        "..........ROYOR.",
        ".........ROYOR..",
        "........ROYOR...",
        ".......ROYOR....",
        "......ROYOR.....",
        ".....ROYWR......",
        "....ROYWR.......",
        "...ROYWR........",
        "...RYWR.........",
        "..RYW...........",
        "..YW............",
        ".W..............",
        "W..............."], {"R": "#d6301a", "O": "#ff7a1a", "Y": "#ffd23f", "W": "#fff3d0"}, "#3a0e05")


def shadow_cloak():
    return pix([
        "......DDDD......",
        ".....DKKKKD.....",
        "....DKKPPKKD....",
        "....DKP..PKD....",
        "....DKP..PKD....",
        "...DDKKPPKKDD...",
        "..DKKKKKKKKKKD..",
        "..DKKKKDDKKKKD..",
        "..DKKKKDDKKKKD..",
        ".DKKKKKDDKKKKKD.",
        ".DKKKKKDDKKKKKD.",
        ".DKKKKDDDDKKKKD.",
        "DKKKKDD..DDKKKKD",
        "DPKPKD....DKPKPD",
        "DD.DD......DD.DD"], {"D": "#2a2238", "K": "#151020", "P": "#8a73b0"}, "#06040a")


def seeker_compass():
    return pix([
        ".....GGGGG......",
        "...GGTTTTTGG....",
        "..GTTTTWTTTTG...",
        ".GTTTTTWTTTTTG..",
        ".GTTTTWCWTTTTG..",
        "GTTTTTWCWTTTTTG.",
        "GTTTTTWCWTTTTTG.",
        "GTTTTTTKTTTTTTG.",
        "GTTTTTTDTTTTTTG.",
        "GTTTTTTDTTTTTTG.",
        ".GTTTTTDTTTTTG..",
        ".GTTTTTTTTTTTG..",
        "..GTTTTTTTTTG...",
        "...GGTTTTTGG....",
        ".....GGGGG......"], {"G": "#d9a52e", "T": "#1b4a52", "W": "#e8fffb", "C": "#7fffd4", "K": "#ffffff", "D": "#3a8a8a"}, "#2a1a06")


def hammer_tool():
    img = canvas()
    px = img.load()
    for x in range(1, 10):
        px[x, 15 - x] = hexc("#a0703a")
        px[x, 16 - x] = hexc("#5a3a1a")
    steel = {9: "#f0f2f5", 8: "#d0d6dd", 7: "#b0b8c2", 6: "#8f99a5", 5: "#6c7684"}
    for y in range(16):
        for x in range(16):
            c, a_ = x - y, x + y
            if 5 <= c <= 9 and 10 <= a_ <= 20 and not (a_ in (10, 20) and c in (5, 9)):
                px[x, y] = hexc(steel[c])
    outline(img, "#1a1d22")
    return img


def lumber_axe():
    return pix([
        ".......SSS......",
        "......SLLLS.....",
        ".....SLLLLLS....",
        ".....SLLLLLLS...",
        "......SLLLLNS...",
        ".......SSLNWS...",
        ".........NWSS...",
        "........NW......",
        ".......NW.......",
        "......NW........",
        ".....NW.........",
        "....NW..........",
        "...NW...........",
        "..NW............",
        ".NW.............",
        "NN.............."], {"S": "#6c7684", "L": "#d8dee6", "N": "#5a3a1a", "W": "#b07a40"})


def grappling_hook(cast=False):
    img = canvas()
    px = img.load()
    rod(img, 1, 14, 9, 6, hexc("#6b4a2a"), hexc("#9a6e40"), hexc("#3d2814"))
    px[3, 12] = hexc("#c0c8d0")
    px[4, 11] = hexc("#c0c8d0")
    if not cast:
        for (x, y) in [(10, 5), (11, 4), (12, 3), (13, 2)]:
            px[x, y] = hexc("#dfe5ea", 200)
        hook = [(13, 3), (14, 3), (13, 4), (12, 4), (14, 2), (15, 2), (14, 4), (14, 5), (12, 5), (11, 5)]
        for (x, y) in hook:
            px[x, y] = hexc("#c0c8d0")
        px[15, 3] = hexc("#8a96a2")
        px[10, 6] = hexc("#8a96a2")
    else:
        for (x, y) in [(10, 5), (11, 4), (12, 4), (13, 4), (14, 5), (15, 6)]:
            px[x, y] = hexc("#dfe5ea", 200)
    return outline(img, "#141414")


def magnet_charm():
    return pix([
        "................",
        "................",
        "....RRRRRRR.....",
        "...RRLLLLLRR....",
        "..RRL.....LRR...",
        "..RR.......RR...",
        "..RR.......RR...",
        "..RR.......RR...",
        "..RR.......RR...",
        "..RR.......RR...",
        "..SS.......SS...",
        "..SW.......SW...",
        "..SS.......SS..."], {"R": "#d42a2a", "L": "#ff8080", "S": "#b8c0c8", "W": "#ffffff"})


def ender_pouch():
    return pix([
        "................",
        ".....S...S......",
        "......SSS.......",
        ".....PPPPP......",
        "....PPDDDPP.....",
        "...PPPPPPPPP....",
        "..PPPPGGGPPPP...",
        "..PPPGCKCGPPP...",
        "..PPPGCCCGPPP...",
        "..PPPPGGGPPPP...",
        "..PPPPPPPPPPP...",
        "...PPPPPPPPP....",
        "....DDDDDDD....."], {"P": "#7a3ab0", "D": "#4a1f70", "S": "#d8c8a0", "G": "#e2c050", "C": "#2ad6a0", "K": "#0a3a30"}, "#1a0828")


def backpack():
    return pix([
        "................",
        "......NNNN......",
        ".....N....N.....",
        "...BBBBBBBBBB...",
        "..BLLLLLLLLLLB..",
        "..BLBBBBBBBBLB..",
        "..BLBGGLLGGBLB..",
        "..BLBLLLLLLBLB..",
        "..BLBBBBBBBBLB..",
        "..BLLLYYYYLLLB..",
        "..BLLLYLLYLLLB..",
        "..BLLLYYYYLLLB..",
        "..BLLLLLLLLLLB..",
        "...BBBBBBBBBB..."], {"B": "#5a3417", "L": "#a8682e", "N": "#3a2210", "G": "#d0d0d0", "Y": "#7a4a20"}, "#1c0e04")



# ------------------------------------------------------------------ boss weapons

def storm_fang():
    return sword_art("#f2fbff", "#b8e8ff", "#3a7ab0", "#e2e8f0", "#ffe27a", "#2a3a5a", "#7fd8ff",
                     [(10, 2, "#ffe27a", 220), (13, 3, "#b8e8ff", 170), (7, 4, "#ffffff", 150), (15, 5, "#ffe27a", 140)])


def dune_blade():
    return sword_art("#fff1c8", "#e8c77a", "#9a6a2a", "#c48a0e", "#2ad6a0", "#6b4a20", "#e2b23a",
                     [(9, 3, "#e8c77a", 170), (12, 1, "#fff1c8", 140), (6, 5, "#e8c77a", 120)])


def thornspine():
    return sword_art("#d8ffc8", "#6fd05a", "#2a6a1a", "#3a5a20", "#d6301a", "#2a3a14", "#6fd05a",
                     [(8, 3, "#6fd05a", 200), (11, 2, "#a8f080", 160), (13, 5, "#6fd05a", 150), (6, 6, "#4a9a30", 140)])


def hollow_edge():
    return sword_art("#e2d4ff", "#9a7bd0", "#2a1a4a", "#15101c", "#d65cff", "#1a1426", "#6a2bd6",
                     [(9, 3, "#9a7bd0", 200), (12, 1, "#d65cff", 170), (14, 4, "#6a2bd6", 160), (6, 5, "#9a7bd0", 130)])


def axe_art(head, edge, shine, handle, handle_dark, accent):
    return pix([
        "........SSSS....",
        ".......SLLLSS...",
        "......SLLALLSS..",
        "......SLLAALLS..",
        ".......SLALLWN..",
        "........SSLWNS..",
        ".........WNSS...",
        "........WN......",
        ".......WN.......",
        "......WN........",
        ".....WN.........",
        "....WN..........",
        "...WN...........",
        "..WN............",
        ".WN.............",
        "BB.............."], {"S": head, "L": edge, "A": accent, "W": handle, "N": handle_dark, "B": shine})


def forge_cleaver():
    return axe_art("#3a2a2a", "#ff9a3a", "#ffd23f", "#4a3a3a", "#1a1010", "#fff3b0")


def glacier_axe():
    return axe_art("#4a8ab0", "#e8fbff", "#bff4ff", "#6a9ab8", "#2a4a60", "#7fe7ff")


def colossus_maul():
    return pix([
        "........SSSSS...",
        ".......SLLLLLS..",
        "......SLLDLLLS..",
        "......SLDDDLLS..",
        "......SLLDLLLS..",
        ".......SLLLLWS..",
        "........SSSWNS..",
        ".........WN.....",
        "........WN......",
        ".......WN.......",
        "......WN........",
        ".....WN.........",
        "....WN..........",
        "...WN...........",
        "..WN............",
        ".BB............."], {"S": "#3a3f48", "L": "#6c7684", "D": "#9fe8ff", "W": "#5a4a3a", "N": "#2a2018", "B": "#8a93a6"})


SECRET = {"voidblade": ("diamond_sword", voidblade, "minecraft:item/handheld"),
          "stormbreaker": ("diamond_axe", stormbreaker, "minecraft:item/handheld"),
          "phoenix_feather": ("feather", phoenix_feather, "minecraft:item/generated"),
          "shadow_cloak": ("phantom_membrane", shadow_cloak, "minecraft:item/generated"),
          "seeker_compass": ("nautilus_shell", seeker_compass, "minecraft:item/generated"),
          "hammer": ("iron_pickaxe", hammer_tool, "minecraft:item/handheld"),
          "lumber_axe": ("iron_axe", lumber_axe, "minecraft:item/handheld"),
          "grappling_hook": ("fishing_rod", grappling_hook, "minecraft:item/handheld_rod"),
          "magnet_charm": ("iron_nugget", magnet_charm, "minecraft:item/generated"),
          "ender_pouch": ("rabbit_hide", ender_pouch, "minecraft:item/generated"),
          "backpack": ("leather", backpack, "minecraft:item/generated"),
          "storm_fang": ("diamond_sword", storm_fang, "minecraft:item/handheld"),
          "dune_blade": ("diamond_sword", dune_blade, "minecraft:item/handheld"),
          "thornspine": ("diamond_sword", thornspine, "minecraft:item/handheld"),
          "hollow_edge": ("netherite_sword", hollow_edge, "minecraft:item/handheld"),
          "forge_cleaver": ("netherite_axe", forge_cleaver, "minecraft:item/handheld"),
          "glacier_axe": ("diamond_axe", glacier_axe, "minecraft:item/handheld"),
          "colossus_maul": ("mace", colossus_maul, "minecraft:item/handheld")}
# Every boss / guard model listed in src/main/resources/vigil/models.json: one texture and one model per moving part
# (each part is shown on its own item display of a nautilus shell).
_SPECS = json.load(open(os.path.join(ROOT, "src", "main", "resources", "vigil", "models.json")))
MODELS3D = {name: [p["id"] for p in spec["parts"]] for name, spec in _SPECS.items()}
# Item models that aren't one plain model (the grappling hook looks different once cast).
CASE_MODELS = {"grappling_hook": {"type": "minecraft:condition", "property": "minecraft:fishing_rod/cast",
                                  "on_false": {"type": "minecraft:model", "model": "vigil:item/grappling_hook"},
                                  "on_true": {"type": "minecraft:model", "model": "vigil:item/grappling_hook_cast"}}}


def pack_icon():
    img = Image.new("RGBA", (64, 64), hexc("#14101f"))
    d = ImageDraw.Draw(img)
    d.polygon([(10, 44), (14, 18), (24, 32), (32, 12), (40, 32), (50, 18), (54, 44)], fill=hexc("#f2c94c"), outline=hexc("#9a6d12"))
    d.rectangle([10, 44, 54, 52], fill=hexc("#e2b23a"), outline=hexc("#9a6d12"))
    for x in (20, 32, 44):
        d.ellipse([x - 3, 45, x + 3, 51], fill=hexc("#ff3d63"))
    return img


def png(img):
    b = io.BytesIO()
    img.save(b, "PNG")
    return b.getvalue()


# ------------------------------------------------------------------ boss bars

# Pixel art at twice the game's resolution (364x10 for its 182x5): stepped round ends, a bevel and a shine, in the
# same blocky style as the rest of the game's screens.
BAR_W, BAR_H = 364, 10
BAR_COLOURS = {"green": ((24, 100, 34), (96, 226, 106)), "yellow": ((150, 104, 8), (255, 214, 64)),
               "red": ((118, 14, 22), (250, 72, 72))}


def _inside(x, y):
    """The bar's shape: a 10-high strip with corners stepped off two pixels deep."""
    for cx in (min(x, BAR_W - 1 - x),):
        if cx == 0 and (y < 2 or y > BAR_H - 3):
            return False
        if cx == 1 and (y < 1 or y > BAR_H - 2):
            return False
    return True


def _edge(x, y):
    return _inside(x, y) and not all(_inside(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                                     if 0 <= x + dx < BAR_W and 0 <= y + dy < BAR_H) or (
        _inside(x, y) and (x in (0, BAR_W - 1) or y in (0, BAR_H - 1)))


def bar_background():
    """The empty part: a dark track with a black outline and a faint lower lip."""
    im = Image.new("RGBA", (BAR_W, BAR_H), (0, 0, 0, 0))
    px = im.load()
    for x in range(BAR_W):
        for y in range(BAR_H):
            if not _inside(x, y):
                continue
            if _edge(x, y):
                px[x, y] = (8, 6, 8, 255)
            elif y == BAR_H - 2:
                px[x, y] = (58, 50, 52, 255)
            elif y == 1 or y == 2:
                px[x, y] = (22, 18, 20, 255)
            else:
                px[x, y] = (34, 29, 31, 240)
    return im


def bar_progress(name):
    """The fill: a light top row, a shine, the colour, and a darker bottom, inside the same outline."""
    dark, bright = BAR_COLOURS[name]
    mid = tuple((a + b) // 2 for a, b in zip(dark, bright))
    light = tuple(min(255, int(v * 1.25) + 30) for v in bright)
    rows = [None, light, bright, bright, bright, mid, mid, dark, tuple(int(v * 0.7) for v in dark), None]
    im = Image.new("RGBA", (BAR_W, BAR_H), (0, 0, 0, 0))
    px = im.load()
    for x in range(BAR_W):
        for y in range(BAR_H):
            if not _inside(x, y):
                continue
            if _edge(x, y):
                px[x, y] = tuple(int(v * 0.35) for v in dark) + (255,)
                continue
            c = rows[y] or dark
            if y == 2 and (x // 2) % 9 == 0:
                c = light  # a sparkle along the shine
            px[x, y] = c + (255,)
    return im


BOSS_HEADS = ["drowned_warden", "deepslate_colossus", "storm_phantom", "forgemaster", "sand_colossus", "frost_titan",
              "thornback_beast", "hollow_watcher", "tempest_lord"]
HEAD_CHAR = 0xE100   # the bosses' heads, in the order above
PHASE_CHAR = 0xE110  # the phase badges I, II, III
SCARE_CHAR = 0xE200  # The Boiled One's jumpscare face


_BUILT = os.path.join(ROOT, "scripts", "models", "built")


def _face_img(tex, uv):
    TW,TH=tex.size; u1,v1,u2,v2=uv
    a,b=sorted((u1*TW/16,u2*TW/16)); c,d=sorted((v1*TH/16,v2*TH/16))
    im=tex.crop((int(a),int(c),max(int(b),int(a)+1),max(int(d),int(c)+1)))
    if u1>u2: im=im.transpose(Image.FLIP_LEFT_RIGHT)
    if v1>v2: im=im.transpose(Image.FLIP_TOP_BOTTOM)
    return im
def head_icon(boss, size=128, yaw=-32, pitch=22):
    """The boss's head as a small 3D picture (seen from the front and a little from the side and above, shaded like
    an item in the inventory, with a dark outline), drawn from its model. The Hollow Watcher is all eye: its body."""
    part=os.path.join(_BUILT, boss + "__head.json")
    if not os.path.exists(part): part=os.path.join(_BUILT, boss + "__body.json")
    els=json.load(open(part))["elements"]; tex=Image.open(os.path.join(_BUILT, boss + ".png")).convert("RGBA")
    th,ph=math.radians(yaw),math.radians(pitch)
    def proj(p):
        x,y,z=p
        xr=x*math.cos(th)+z*math.sin(th); zr=-x*math.sin(th)+z*math.cos(th)
        yr=y*math.cos(ph)-zr*math.sin(ph); dr=zr*math.cos(ph)+y*math.sin(ph)
        return (xr,-yr,dr)
    faces=[]
    for e in els:
        (x0,y0,z0),(x1,y1,z1)=e["from"],e["to"]
        # face: (name, origin corner, U edge end, V edge end, shade) -- texture u along U, v along V (v down)
        defs={
         "south":((x0,y1,z1),(x1,y1,z1),(x0,y0,z1),0.82),
         "east":((x1,y1,z1),(x1,y1,z0),(x1,y0,z1),0.62),
         "west":((x0,y1,z0),(x0,y1,z1),(x0,y0,z0),0.62),
         "up":((x0,y1,z0),(x1,y1,z0),(x0,y1,z1),1.0),
        }
        rot=e.get("rotation")
        def R(p,rot=rot):
            if not rot or not rot.get("angle") or rot["axis"] != "x": return p
            a=math.radians(rot["angle"]); ox,oy,oz=rot["origin"]; x,y,z=p[0]-ox,p[1]-oy,p[2]-oz
            ca,sa=math.cos(a),math.sin(a)
            if rot["axis"]=="x": y,z=y*ca-z*sa,y*sa+z*ca
            elif rot["axis"]=="y": x,z=x*ca+z*sa,-x*sa+z*ca
            else: x,y=x*ca-y*sa,x*sa+y*ca
            return (x+ox,y+oy,z+oz)
        for n,(o,u,v,sh) in defs.items():
            f=e["faces"].get(n)
            if not f: continue
            o,u,v=R(o),R(u),R(v)
            P=[proj(o),proj(u),proj(v)]
            c=[(o[i]+u[i]+v[i])/3 for i in range(3)]
            # back-face cull with normal
            ux,uy=P[1][0]-P[0][0],P[1][1]-P[0][1]; vx,vy=P[2][0]-P[0][0],P[2][1]-P[0][1]
            if ux*vy-uy*vx<=0: continue
            depth=proj(((o[0]+u[0]+v[0]-o[0]*0)/1, 0,0))[2]
            cen=proj(((u[0]+v[0])/2,(u[1]+v[1])/2,(u[2]+v[2])/2))
            faces.append((cen[2],P,f["uv"],sh))
    pts=[p for _,P,_,_ in faces for p in P]+[]
    xs=[]; ys=[]
    for _,P,_,_ in faces:
        o,u,v=P; w=(u[0]+v[0]-o[0],u[1]+v[1]-o[1])
        xs+= [o[0],u[0],v[0],w[0]]; ys+=[o[1],u[1],v[1],w[1]]
    minx,maxx,miny,maxy=min(xs),max(xs),min(ys),max(ys)
    sc=(size-16)/max(maxx-minx,maxy-miny)
    offx=(size-(maxx-minx)*sc)/2-minx*sc; offy=(size-(maxy-miny)*sc)/2-miny*sc
    out=Image.new("RGBA",(size,size),(0,0,0,0))
    for _,P,uv,sh in sorted(faces,key=lambda f:f[0]):
        fi=_face_img(tex,uv); tw,th2=fi.size
        fi=fi.resize((tw*8,th2*8),Image.NEAREST); tw,th2=fi.size
        r,g,b,a=fi.split()
        fi=Image.merge("RGBA",(r.point(lambda v:int(v*sh)),g.point(lambda v:int(v*sh)),b.point(lambda v:int(v*sh)),a))
        O=(P[0][0]*sc+offx,P[0][1]*sc+offy); U=((P[1][0]-P[0][0])*sc,(P[1][1]-P[0][1])*sc); V=((P[2][0]-P[0][0])*sc,(P[2][1]-P[0][1])*sc)
        m00,m01,m10,m11=U[0]/tw,V[0]/th2,U[1]/tw,V[1]/th2
        det=m00*m11-m01*m10
        if abs(det)<1e-9: continue
        i00,i01,i10,i11=m11/det,-m01/det,-m10/det,m00/det
        coeffs=(i00,i01,-(i00*O[0]+i01*O[1]),i10,i11,-(i10*O[0]+i11*O[1]))
        layer=fi.transform((size,size),Image.AFFINE,coeffs,resample=Image.NEAREST,fillcolor=(0,0,0,0))
        out.alpha_composite(layer)
    # dark outline for contrast on any sky
    a=out.split()[3]; grown=a.filter(ImageFilter.MaxFilter(5))
    ol=Image.new("RGBA",(size,size),(10,8,10,0)); ol.putalpha(ImageChops.subtract(grown,a).point(lambda v:200 if v>0 else 0))
    base=Image.new("RGBA",(size,size),(0,0,0,0)); base.alpha_composite(ol); base.alpha_composite(out)
    return base

def phase_badge(n):
    """A pixel-art "PHASE II" badge in the phase's colour, in the game's own blocky lettering, at 1:1 with the font."""
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import pixelfont
    dark, bright = BAR_COLOURS[["green", "yellow", "red"][n - 1]]
    label = "PHASE " + ["I", "II", "III"][n - 1]
    w = pixelfont.width(label) + 8
    h = 11
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = im.load()
    for x in range(w):
        for y in range(h):
            corner = (x in (0, w - 1)) and (y in (0, h - 1))
            if corner:
                continue
            edge = x in (0, w - 1) or y in (0, h - 1)
            px[x, y] = (tuple(int(v * 0.45) for v in dark) if edge else tuple(int(v * 0.32) for v in bright)) + (255,)
    for x in range(1, w - 1):
        px[x, 1] = tuple(int(v * 0.5) for v in bright) + (255,)
    pixelfont.draw(im, 4, 2, label, bright + (255,), shadow=tuple(int(v * 0.25) for v in bright) + (255,))
    return im


def boss_bars():
    """The bars (by phase colour) and the heads and badges the bars' titles show, as font glyphs."""
    out = {}
    for name in BAR_COLOURS:
        out[f"assets/minecraft/textures/gui/sprites/boss_bar/{name}_progress.png"] = png(bar_progress(name))
        out[f"assets/minecraft/textures/gui/sprites/boss_bar/{name}_background.png"] = png(bar_background())
    providers = []
    for i, boss in enumerate(BOSS_HEADS):
        out[f"assets/vigil/textures/font/head_{i}.png"] = png(head_icon(boss))
        providers.append({"type": "bitmap", "file": f"vigil:font/head_{i}.png", "height": 22, "ascent": 16,
                          "chars": [chr(HEAD_CHAR + i)]})
    for n in (1, 2, 3):
        out[f"assets/vigil/textures/font/phase_{n}.png"] = png(phase_badge(n))
        providers.append({"type": "bitmap", "file": f"vigil:font/phase_{n}.png", "height": 11, "ascent": 9,
                          "chars": [chr(PHASE_CHAR + n - 1)]})
    # The Boiled One's jumpscare: its face, filling the screen when shown as a title.
    face = os.path.join(_BUILT, "boiled_one_face.png")
    if os.path.exists(face):
        out["assets/vigil/textures/font/boiled_scare.png"] = open(face, "rb").read()
        providers.append({"type": "bitmap", "file": "vigil:font/boiled_scare.png", "height": 64, "ascent": 40,
                          "chars": [chr(SCARE_CHAR)]})
    # Fonts from every pack are put together, so this only adds the glyphs to the game's own font.
    out["assets/minecraft/font/default.json"] = json.dumps({"providers": providers}, indent=2).encode()
    return out


# ------------------------------------------------------------------ sounds

def t(sec):
    return np.linspace(0, sec, int(SR * sec), endpoint=False)


def env(x, attack=0.01, release=0.1):
    n = len(x)
    e = np.ones(n)
    a = max(1, int(SR * attack))
    r = max(1, int(SR * release))
    e[:a] = np.linspace(0, 1, a)
    e[-r:] *= np.linspace(1, 0, r)
    return x * e


def sweep(f0, f1, sec):
    tt = t(sec)
    f = np.linspace(f0, f1, len(tt))
    return np.sin(2 * np.pi * np.cumsum(f) / SR)


def bell(f, sec, decay=5.0):
    tt = t(sec)
    return (np.sin(2 * np.pi * f * tt) + 0.4 * np.sin(2 * np.pi * f * 2.76 * tt) + 0.2 * np.sin(2 * np.pi * f * 5.4 * tt)) * np.exp(-tt * decay)


def lowpass(x, k):
    return np.convolve(x, np.ones(k) / k, mode="same")


def mix(total, parts):
    out = np.zeros(int(SR * total))
    for start, sig in parts:
        i = int(SR * start)
        j = min(len(out), i + len(sig))
        out[i:j] += sig[: j - i]
    return out


def norm(x, peak=0.85):
    m = np.max(np.abs(x)) or 1
    return (x / m * peak).astype("float32")


def ogg(x):
    b = io.BytesIO()
    sf.write(b, norm(x), SR, format="OGG", subtype="VORBIS")
    return b.getvalue()


rng = np.random.default_rng(7)


def s_power_on():
    a = sweep(330, 880, 0.35) + 0.5 * sweep(495, 1320, 0.35)
    shimmer = 0.3 * bell(1760, 0.5, 7)
    return mix(0.6, [(0, env(a, 0.01, 0.15)), (0.25, shimmer)])


def s_power_off():
    a = sweep(880, 300, 0.32) + 0.4 * sweep(1320, 450, 0.32)
    return env(a, 0.005, 0.15)


def s_zap():
    n = rng.standard_normal(int(SR * 0.7))
    crack = np.diff(n, prepend=0) * np.exp(-t(0.7) * 9)
    pops = np.zeros(len(n))
    for i in rng.integers(0, int(SR * 0.4), 40):
        pops[i:i + 60] += rng.uniform(-1, 1) * np.exp(-np.arange(min(60, len(pops) - i)) / 10)
    thump = np.sin(2 * np.pi * 55 * t(0.7)) * np.exp(-t(0.7) * 6) * 1.5
    return env(crack + pops * 0.8 + thump, 0.002, 0.2)


def s_mode():
    return mix(0.25, [(0, env(np.sin(2 * np.pi * 880 * t(0.07)), 0.003, 0.03)),
                      (0.09, env(np.sin(2 * np.pi * 1320 * t(0.08)), 0.003, 0.04))])


def s_launch():
    n = rng.standard_normal(int(SR * 0.8))
    w = lowpass(n, 18) * np.sin(np.linspace(0, np.pi, len(n))) ** 1.5
    rise = 0.35 * sweep(180, 700, 0.8) * np.linspace(0.2, 1, len(n))
    return env(w * 3 + rise, 0.05, 0.25)


def s_heal():
    notes = [1047, 1319, 1568, 2093]
    return mix(1.1, [(i * 0.08, 0.7 * bell(f, 0.9, 4.5)) for i, f in enumerate(notes)])


def s_repair():
    def ping():
        tt = t(0.45)
        return (np.sin(2 * np.pi * 1200 * tt) + 0.6 * np.sin(2 * np.pi * 2650 * tt) + 0.35 * np.sin(2 * np.pi * 4100 * tt)) * np.exp(-tt * 9)
    return mix(0.7, [(0, ping()), (0.18, 0.8 * ping())])


def s_give():
    return env(sweep(620, 200, 0.12), 0.002, 0.05)


def brass(f, sec):
    tt = t(sec)
    vib = 1 + 0.004 * np.sin(2 * np.pi * 5.5 * tt)
    x = sum(np.sin(2 * np.pi * f * k * vib * tt) / k for k in range(1, 7))
    return env(x, 0.04, min(0.2, sec / 3))


def s_owner_join():
    G4, C5, E5, G5 = 392, 523.25, 659.25, 783.99
    dry = mix(2.4, [(0, brass(G4, 0.24)), (0.24, brass(C5, 0.24)), (0.48, brass(E5, 0.24)),
                    (0.72, brass(G5, 1.3)), (0.72, 0.6 * brass(C5, 1.3)), (0.72, 0.5 * brass(E5, 1.3)),
                    (0.72, 0.6 * bell(G5 * 2, 1.3, 2.5))])
    wet = dry.copy()
    d = int(SR * 0.11)
    for i in range(d, len(wet)):
        wet[i] += 0.35 * wet[i - d]
    return wet




def glide(f0, f1, sec, vib=0.0, rate=6.0):
    tt = t(sec)
    f = np.linspace(f0, f1, len(tt)) * (1 + vib * np.sin(2 * np.pi * rate * tt))
    return np.sin(2 * np.pi * np.cumsum(f) / SR)


def echo(x, delay, fb, n=3):
    out = np.concatenate([x, np.zeros(int(SR * delay * n))])
    d = int(SR * delay)
    for k in range(1, n + 1):
        out[k * d:k * d + len(x)] += x * (fb ** k)
    return out


def hp(x):
    return np.diff(x, prepend=0)


def chord(freqs, sec, attack=0.15, vib=0.004):
    tt = t(sec)
    x = sum(np.sin(2 * np.pi * f * (1 + vib * np.sin(2 * np.pi * 5 * tt)) * tt) + 0.3 * np.sin(4 * np.pi * f * tt) for f in freqs)
    return env(x, attack, sec * 0.5)


def whoosh(sec, up=True):
    n = rng.standard_normal(int(SR * sec))
    shape = np.linspace(0, 1, len(n)) if up else np.linspace(1, 0, len(n))
    return lowpass(n, 12) * np.sin(np.pi * np.linspace(0, 1, len(n))) * (0.4 + shape)


SOUNDS2 = {
    "fly_on": lambda: mix(0.8, [(0, env(whoosh(0.6, True) * 3, 0.05, 0.2)), (0.25, 0.5 * bell(1568, 0.5, 6))]),
    "fly_off": lambda: env(whoosh(0.5, False) * 3, 0.02, 0.2),
    "god_on": lambda: mix(1.4, [(0, chord([523.25, 659.25, 783.99, 1046.5], 1.2)), (0.3, 0.4 * bell(2093, 1.0, 3))]),
    "god_off": lambda: env(chord([523.25, 659.25, 783.99], 0.8) * np.linspace(1, 0.2, int(SR * 0.8)), 0.05, 0.4),
    "speed_on": lambda: mix(0.35, [(0, env(sweep(300, 2400, 0.18), 0.005, 0.05)), (0.18, 0.5 * bell(2400, 0.15, 20))]),
    "speed_off": lambda: env(sweep(2400, 300, 0.2), 0.005, 0.06),
    "night_on": lambda: mix(1.1, [(0, chord([220, 261.63, 329.63], 1.0, 0.35)), (0.4, 0.3 * bell(1760, 0.6, 5))]),
    "night_off": lambda: env(chord([329.63, 261.63, 220], 0.7, 0.05) * np.linspace(1, 0, int(SR * 0.7)), 0.02, 0.3),
    "break_on": lambda: mix(0.6, [(0, env(hp(rng.standard_normal(int(SR * 0.12))) * np.exp(-t(0.12) * 30), 0.001, 0.05)),
                                  (0.08, 0.6 * bell(2200, 0.45, 8))]),
    "break_off": lambda: env(np.sin(2 * np.pi * 80 * t(0.3)) * np.exp(-t(0.3) * 12) * 2
                             + lowpass(rng.standard_normal(int(SR * 0.3)), 30) * np.exp(-t(0.3) * 15), 0.002, 0.1),
    "radar_on": lambda: echo(env(np.sin(2 * np.pi * 1100 * t(0.25)) * np.exp(-t(0.25) * 10), 0.002, 0.05), 0.22, 0.45, 3),
    "radar_off": lambda: mix(0.35, [(0, env(np.sin(2 * np.pi * 900 * t(0.08)), 0.003, 0.03)),
                                    (0.12, env(np.sin(2 * np.pi * 600 * t(0.1)), 0.003, 0.04))]),
    "radar_ping": lambda: 0.45 * echo(env(np.sin(2 * np.pi * 1300 * t(0.15)) * np.exp(-t(0.15) * 18), 0.002, 0.03), 0.18, 0.35, 2),
    "ghost_on": lambda: echo(env(glide(300, 700, 0.9, 0.03), 0.15, 0.3), 0.15, 0.4, 3),
    "ghost_off": lambda: echo(env(glide(700, 250, 0.7, 0.03), 0.05, 0.3), 0.15, 0.35, 2),
    "freeze": lambda: mix(1.2, [(rng.uniform(0, 0.45), 0.35 * bell(rng.uniform(2000, 5000), 0.6, 8)) for _ in range(14)]
                          + [(0, 0.6 * hp(rng.standard_normal(int(SR * 0.9))) * np.exp(-t(0.9) * 5)),
                             (0, 0.8 * hp(rng.standard_normal(int(SR * 0.08))) * np.exp(-t(0.08) * 40))]),
    "thaw": lambda: mix(1.0, [(k * 0.14, 0.6 * env(glide(1300 - k * 80, 600, 0.1), 0.002, 0.05)) for k in range(6)]
                        + [(0, 0.25 * lowpass(rng.standard_normal(int(SR * 0.9)), 6) * np.exp(-t(0.9) * 3))]),
    "gavel": lambda: mix(0.7, [(0, env(np.sin(2 * np.pi * 140 * t(0.25)) * np.exp(-t(0.25) * 18) * 2
                                       + lowpass(hp(rng.standard_normal(int(SR * 0.25))), 4) * np.exp(-t(0.25) * 35), 0.001, 0.05)),
                               (0.28, env(np.sin(2 * np.pi * 120 * t(0.3)) * np.exp(-t(0.3) * 14) * 2.4
                                          + lowpass(hp(rng.standard_normal(int(SR * 0.3))), 4) * np.exp(-t(0.3) * 30), 0.001, 0.08))]),
    "gavel_soft": lambda: env(np.sin(2 * np.pi * 300 * t(0.15)) * np.exp(-t(0.15) * 30)
                              + 0.5 * lowpass(hp(rng.standard_normal(int(SR * 0.15))), 4) * np.exp(-t(0.15) * 45), 0.001, 0.04),
    "insta": lambda: mix(0.2, [(0, env(hp(rng.standard_normal(int(SR * 0.07))) * np.exp(-t(0.07) * 50), 0.001, 0.03)),
                               (0, 0.4 * env(np.sin(2 * np.pi * 3000 * t(0.05)), 0.001, 0.03))]),
}


def saw(f, sec, vib=0.0):
    tt = t(sec)
    ph = np.cumsum(f * (1 + vib * np.sin(2 * np.pi * 6 * tt))) / SR
    return 2 * (ph % 1) - 1


def noise(sec):
    return rng.standard_normal(int(SR * sec))


def boom(sec, f=45, decay=4.0):
    return np.sin(2 * np.pi * np.cumsum(np.linspace(f * 1.6, f * 0.6, int(SR * sec))) / SR) * np.exp(-t(sec) * decay)


def rumble(sec, k=60, decay=2.0):
    return lowpass(noise(sec), k) * np.exp(-t(sec) * decay) * 6


def crack(sec=0.12, decay=35):
    return hp(noise(sec)) * np.exp(-t(sec) * decay)


def crackle(sec, n=60):
    out = np.zeros(int(SR * sec))
    for i in rng.integers(0, len(out) - 200, n):
        out[i:i + 120] += rng.uniform(-1, 1) * np.exp(-np.arange(120) / 18)
    return out


def tremolo(x, rate, depth=0.5):
    return x * (1 - depth + depth * np.sin(2 * np.pi * rate * np.arange(len(x)) / SR) ** 2)


def toggle_pair(on_fn, off_fn):
    return on_fn, off_fn


SOUNDS3 = {
    "thor_smash": lambda: echo(mix(2.2, [(0, 1.4 * crack(0.15, 25)), (0, 2.2 * boom(1.2, 45, 3)), (0.05, rumble(2.0, 70, 1.6)),
                                         (0.02, 0.5 * bell(330, 1.4, 2.5)), (0.02, 0.3 * bell(495, 1.2, 3))]), 0.12, 0.3, 2),
    "thor_hit": lambda: mix(1.0, [(0, 1.2 * crack(0.1, 30)), (0, 0.8 * bell(620, 0.8, 5)), (0, 1.4 * boom(0.5, 70, 7)), (0.05, 0.5 * rumble(0.9, 60, 3))]),
    "flame_hit": lambda: mix(0.8, [(0, env(whoosh(0.3, True) * 2.5, 0.01, 0.1)), (0.05, 0.6 * crackle(0.6, 50)), (0.05, 1.2 * boom(0.3, 90, 10))]),
    "flame_wave": lambda: mix(1.4, [(0, env(tremolo(lowpass(noise(1.3), 8) * 4, 13, 0.4), 0.08, 0.5)),
                                    (0, 0.25 * env(sweep(180, 420, 1.3), 0.1, 0.5)), (0.1, 0.4 * crackle(1.1, 90))]),
    "frost_shot": lambda: mix(0.5, [(0, env(sweep(1800, 3600, 0.18), 0.003, 0.08)), (0.05, 0.4 * bell(4200, 0.4, 9)), (0.1, 0.3 * bell(3100, 0.35, 10))]),
    "frost_hit": lambda: mix(1.1, [(rng.uniform(0, 0.3), 0.35 * bell(rng.uniform(2500, 5500), 0.6, 9)) for _ in range(12)]
                             + [(0, 0.9 * crack(0.1, 40)), (0, 0.4 * hp(noise(0.8)) * np.exp(-t(0.8) * 6))]),
    "blast_shot": lambda: mix(0.5, [(0, 1.2 * boom(0.25, 110, 12)), (0, 0.5 * env(sweep(500, 160, 0.3), 0.003, 0.1))]),
    "blast_boom": lambda: echo(mix(1.6, [(0, 1.3 * crack(0.12, 25)), (0, 2.0 * boom(0.9, 55, 4)), (0.02, 1.2 * rumble(1.5, 40, 2.4)),
                                         (0.05, 0.4 * crackle(1.0, 80))]), 0.1, 0.25, 2),
    "meteor_cast": lambda: mix(1.6, [(0, env(saw(np.linspace(70, 150, int(SR * 1.4)), 1.4) * 0.4, 0.2, 0.4)),
                                     (0, env(lowpass(noise(1.4), 30) * np.linspace(0.2, 2.5, int(SR * 1.4)), 0.1, 0.3)),
                                     (0.9, 0.5 * bell(220, 0.7, 3))]),
    "meteor_fall": lambda: mix(2.0, [(0, env(sweep(1700, 260, 1.8) * np.linspace(0.15, 0.6, int(SR * 1.8)), 0.1, 0.2)),
                                     (0, env(lowpass(noise(1.9), 14) * np.linspace(0.3, 4.0, int(SR * 1.9)), 0.2, 0.15)),
                                     (0.3, 0.3 * crackle(1.5, 120))]),
    "meteor_impact": lambda: echo(mix(3.2, [(0, 1.6 * crack(0.2, 15)), (0, 3.0 * boom(2.0, 35, 2.2)), (0.03, 2.0 * rumble(3.0, 90, 1.2)),
                                            (0.1, 0.6 * crackle(2.4, 160)), (0.05, 0.3 * bell(110, 2.5, 1.5))]), 0.18, 0.35, 3),
    "disarm": lambda: mix(0.7, [(0, 0.7 * bell(1450, 0.6, 9)), (0.01, 0.5 * bell(2180, 0.5, 11)), (0, 0.9 * crack(0.06, 50)),
                                (0.02, env(whoosh(0.25, False) * 2, 0.01, 0.1))]),
    "onepunch_hit": lambda: echo(mix(1.2, [(0, 2.2 * boom(0.7, 70, 6)), (0, 1.6 * crack(0.08, 40)), (0, 0.6 * env(sweep(140, 35, 0.8), 0.002, 0.3))]), 0.15, 0.35, 3),
    "lifesteal_hit": lambda: mix(0.9, [(0, 0.6 * env(glide(520, 880, 0.6, 0.02), 0.05, 0.3)), (0.15, 0.4 * bell(1320, 0.6, 6)),
                                       (0, 0.4 * env(lowpass(noise(0.6), 20) * np.linspace(1, 0, int(SR * 0.6)), 0.01, 0.2))]),
    "mega_hit": lambda: mix(0.9, [(0, 1.6 * boom(0.4, 80, 9)), (0, env(whoosh(0.7, False) * 3.5, 0.005, 0.3)), (0, 0.7 * crack(0.06, 50))]),
    "field_push": lambda: mix(0.45, [(0, 0.6 * env(np.sin(2 * np.pi * 140 * t(0.4)) + 0.5 * np.sin(2 * np.pi * 280 * t(0.4)), 0.01, 0.2)),
                                     (0, 0.4 * env(sweep(900, 300, 0.25), 0.005, 0.1))]),
    "berserk": lambda: mix(1.8, [(0, env(saw(np.full(int(SR * 1.3), 95.0), 1.3, 0.04) * 0.6 + lowpass(noise(1.3), 6) * 2.5, 0.08, 0.5)),
                                 (0, env(saw(np.full(int(SR * 1.3), 142.5), 1.3, 0.05) * 0.3, 0.08, 0.5)),
                                 (1.1, 1.4 * boom(0.4, 60, 8)), (1.35, 1.4 * boom(0.4, 60, 8))]),
    "mobwipe": lambda: echo(mix(2.4, [(0, 1.2 * crack(0.12, 25)), (0.1, 1.6 * rumble(2.2, 80, 1.3)),
                                      (0.05, 0.6 * chord([110, 130.81, 164.81], 1.8, 0.2)), (0.3, 0.8 * crack(0.1, 30)), (0.6, 0.7 * crack(0.1, 30))]), 0.15, 0.3, 2),
    "smite": lambda: mix(1.8, [(0, 1.5 * crack(0.15, 22)), (0, 1.8 * boom(0.8, 50, 4)), (0.02, 0.5 * chord([523.25, 659.25, 783.99], 1.3, 0.01)),
                               (0.05, 1.2 * rumble(1.6, 70, 1.8))]),
    "punch_on": lambda: mix(0.7, [(0, 1.3 * boom(0.25, 90, 12)), (0.18, 1.6 * boom(0.3, 70, 10)), (0.3, 0.4 * env(sweep(300, 1200, 0.25), 0.01, 0.1))]),
    "punch_off": lambda: mix(0.5, [(0, 1.2 * boom(0.35, 60, 9)), (0, 0.3 * env(sweep(800, 200, 0.3), 0.01, 0.1))]),
    "steal_on": lambda: mix(1.1, [(0, chord([220, 261.63, 329.63], 0.9, 0.2)), (0.3, 0.4 * bell(1318.5, 0.7, 5))]),
    "steal_off": lambda: env(chord([329.63, 261.63, 196], 0.7, 0.05) * np.linspace(1, 0, int(SR * 0.7)), 0.02, 0.3),
    "knock_on": lambda: mix(0.8, [(0, env(whoosh(0.6, True) * 3.5, 0.02, 0.2)), (0.4, 1.0 * boom(0.3, 90, 10))]),
    "knock_off": lambda: env(whoosh(0.5, False) * 3, 0.02, 0.25),
    "nocool_on": lambda: mix(0.9, [(sum(0.12 * 0.75 ** k for k in range(i)), 0.5 * env(np.sin(2 * np.pi * 2000 * t(0.03)), 0.001, 0.02)) for i in range(8)]
                             + [(0.5, 0.6 * bell(1760, 0.4, 6))]),
    "nocool_off": lambda: mix(0.8, [(i * 0.13, 0.5 * env(np.sin(2 * np.pi * (1600 - i * 200) * t(0.04)), 0.001, 0.02)) for i in range(5)]),
    "field_on": lambda: mix(1.2, [(0, env((np.sin(2 * np.pi * np.cumsum(np.linspace(80, 170, int(SR * 1.0))) / SR) * 0.8
                                           + 0.3 * sweep(160, 340, 1.0)), 0.1, 0.3)), (0.5, 0.4 * bell(1400, 0.6, 5))]),
    "field_off": lambda: env(np.sin(2 * np.pi * np.cumsum(np.linspace(170, 60, int(SR * 0.8))) / SR), 0.02, 0.3),
    "home_place": lambda: mix(1.2, [(i * 0.09, 0.6 * bell(f, 0.8, 4)) for i, f in enumerate([659.25, 830.61, 987.77, 1318.5])]),
    "home_charge": lambda: mix(3.2, [(0, env(tremolo(sweep(220, 880, 3.0), 8, 0.5) * 0.5, 0.2, 0.2))]
                               + [(0.3 + i * 0.3, 0.25 * bell(1000 + i * 130, 0.5, 7)) for i in range(9)]),
    "orbital_fire": lambda: mix(1.4, [(0, 0.7 * env(sweep(2400, 600, 0.35), 0.002, 0.1)), (0.05, 0.5 * echo(env(np.sin(2 * np.pi * 880 * t(0.08)), 0.002, 0.04), 0.12, 0.5, 3)),
                                      (0.3, 0.6 * env(lowpass(noise(0.9), 10) * np.linspace(1, 0, int(SR * 0.9)), 0.05, 0.3))]),
    "orbital_undo": lambda: mix(1.2, [(0, 0.6 * env(sweep(300, 1200, 0.6), 0.02, 0.2)), (0.4, chord([523.25, 659.25, 783.99], 0.7, 0.01))]),
    "boss_growl": lambda: echo(mix(2.0, [(0, env(saw(np.linspace(48, 38, int(SR * 1.6)), 1.6, 0.03) * 0.7 + lowpass(noise(1.6), 8) * 2.5, 0.25, 0.6)),
                                         (0, env(saw(np.linspace(72, 57, int(SR * 1.6)), 1.6, 0.04) * 0.35, 0.3, 0.6))]), 0.18, 0.3, 2),
    "boss_rise": lambda: echo(mix(2.4, [(0, env(lowpass(noise(2.0), 5) * np.linspace(0.5, 5, int(SR * 2.0)), 0.3, 0.4)),
                                        (0.2, 0.8 * env(saw(np.full(int(SR * 1.8), 55.0), 1.8, 0.02), 0.4, 0.6)), (1.2, 1.4 * boom(0.8, 45, 4))]), 0.2, 0.3, 2),
    "boss_shock": lambda: mix(1.4, [(0, 1.6 * boom(0.8, 50, 4)), (0, env(lowpass(noise(1.2), 6) * np.sin(np.linspace(0, np.pi, int(SR * 1.2))) * 6, 0.02, 0.4))]),
    "boss_throw": lambda: mix(0.6, [(0, env(whoosh(0.4, True) * 3, 0.01, 0.15)), (0.05, 0.4 * env(sweep(900, 300, 0.3), 0.005, 0.1))]),
    "boss_defeated": lambda: echo(mix(3.0, [(0, 1.5 * boom(1.2, 40, 2.5)), (0.3, chord([261.63, 329.63, 392.0, 523.25], 2.2, 0.1)),
                                            (0.8, 0.5 * bell(1046.5, 1.6, 2))]), 0.2, 0.35, 2),
    "home_tp": lambda: mix(1.3, [(0, env(whoosh(0.5, True) * 3, 0.02, 0.2)), (0.25, chord([659.25, 830.61, 987.77, 1318.5], 0.9, 0.01))]),
}


SOUNDS4 = {
    "voidblade": lambda: mix(0.9, [(0, 0.7 * env(glide(900, 180, 0.6, 0.02), 0.01, 0.3)), (0, 0.5 * env(lowpass(noise(0.6), 25), 0.2, 0.3)),
                                   (0.05, 0.4 * bell(330, 0.7, 4))]),
    "stormbreaker": lambda: mix(1.4, [(0, 1.3 * crack(0.12, 28)), (0, 1.4 * boom(0.6, 60, 5)), (0.03, rumble(1.2, 60, 2.2)),
                                      (0.02, 0.4 * bell(880, 0.6, 6))]),
    "tidecaller": lambda: mix(1.3, [(0, env(lowpass(noise(1.2), 6) * np.sin(np.linspace(0, np.pi, int(SR * 1.2))) * 5, 0.05, 0.3)),
                                    (0.1, 0.3 * env(sweep(200, 90, 1.0), 0.05, 0.4)), (0.3, 0.3 * bell(660, 0.6, 5))]),
    "phoenix": lambda: echo(mix(2.0, [(0, env(lowpass(noise(1.4), 10) * np.linspace(0.3, 3, int(SR * 1.4)), 0.05, 0.3)),
                                      (0.5, chord([523.25, 659.25, 783.99, 1046.5], 1.2, 0.05)), (0.9, 0.5 * bell(2093, 0.9, 3))]), 0.12, 0.3, 2),
    "shadow_on": lambda: echo(env(glide(500, 180, 0.6, 0.03) * 0.6 + lowpass(noise(0.6), 12) * 1.5, 0.1, 0.3), 0.15, 0.4, 2),
    "shadow_off": lambda: env(glide(200, 520, 0.4, 0.02) * 0.6 + lowpass(noise(0.4), 12), 0.02, 0.2),
    "seeker": lambda: mix(1.2, [(i * 0.12, 0.5 * bell(f, 0.7, 5)) for i, f in enumerate([987.77, 1318.5, 1567.98, 1975.5])]),
    "magnet_on": lambda: mix(0.4, [(0, 0.5 * env(np.sin(2 * np.pi * 220 * t(0.35)) + 0.5 * np.sin(2 * np.pi * 440 * t(0.35)), 0.02, 0.15)),
                                   (0, 0.3 * env(sweep(400, 1200, 0.2), 0.005, 0.08))]),
    "magnet_off": lambda: env(np.sin(2 * np.pi * np.cumsum(np.linspace(440, 120, int(SR * 0.3))) / SR) * 0.6, 0.01, 0.15),
    "pouch_open": lambda: mix(0.7, [(0, 0.6 * env(lowpass(noise(0.15), 8) * 4, 0.005, 0.08)), (0.08, 0.5 * env(glide(300, 600, 0.4, 0.02), 0.02, 0.2))]),
    "backpack_open": lambda: mix(0.5, [(0, env(lowpass(noise(0.25), 5) * 5, 0.01, 0.1)), (0.18, 0.4 * env(lowpass(noise(0.12), 4) * 5, 0.005, 0.05))]),
    "grapple": lambda: mix(0.7, [(0, 0.5 * env(sweep(1400, 2600, 0.12), 0.002, 0.05)), (0.05, env(whoosh(0.5, True) * 3, 0.02, 0.2))]),
    "hammer": lambda: mix(0.5, [(0, 1.4 * boom(0.3, 90, 12)), (0, 0.7 * crack(0.08, 40)), (0.02, 0.3 * bell(700, 0.3, 12))]),
    "lumber": lambda: mix(1.2, [(i * 0.07, 0.6 * env(lowpass(hp(noise(0.08)), 3) * 3, 0.002, 0.04)) for i in range(8)]
                          + [(0.5, 1.0 * boom(0.6, 70, 5))]),
    "vault_open": lambda: mix(1.6, [(0, 1.2 * boom(0.5, 60, 6)), (0.1, 0.6 * env(sweep(80, 160, 1.0) * 0.8, 0.1, 0.3)),
                                    (0.6, chord([392, 493.88, 587.33], 0.9, 0.05))]),
    "vault_wrong": lambda: mix(0.5, [(0, 0.6 * env(saw(np.full(int(SR * 0.3), 110.0), 0.3), 0.005, 0.1)),
                                     (0, 0.4 * env(saw(np.full(int(SR * 0.3), 116.5), 0.3), 0.005, 0.1))]),
}

def s_boiled_scream():
    """The Boiled One's jumpscare: a hit, a shrieking scream sliding down, and a low boom under it. Loud."""
    n = int(SR * 2.2)
    shriek = saw(np.linspace(1500, 420, n), 2.2, 0.09) + 0.8 * saw(np.linspace(2100, 640, n), 2.2, 0.12)
    rough = lowpass(noise(2.2), 2) * 2.2
    scream = env(np.tanh((shriek + rough) * 2.5), 0.004, 0.7)
    return mix(2.4, [(0, 2.0 * crack(0.2, 22)), (0, 1.8 * boom(1.4, 38, 2.2)), (0.01, scream),
                     (0.02, 0.6 * env(np.tanh(saw(np.linspace(300, 90, int(SR * 1.8)), 1.8, 0.2) * 3), 0.01, 0.6))])


SOUNDS4["boiled_scream"] = s_boiled_scream


def s_boiled_breath():
    """Slow, heavy, wet breathing: two long breaths, in and out, rough and close."""
    def breath(sec, rise):
        n = int(SR * sec)
        shape = np.sin(np.linspace(0, np.pi, n)) ** (1.4 if rise else 0.8)
        air = lowpass(noise(sec), 6) * 3.0 + lowpass(noise(sec), 30) * 1.5
        rasp = saw(np.linspace(70, 55, n), sec, 0.3) * 0.25 * (0 if rise else 1)
        return (air + rasp) * shape
    return mix(4.6, [(0.0, breath(1.1, True)), (1.15, breath(1.2, False)), (2.4, breath(1.0, True)), (3.45, breath(1.1, False))])


SOUNDS4["boiled_breath"] = s_boiled_breath


def s_boiled_static():
    """Radio static breaking up, with a low drone under it: what you hear when it's close."""
    n = int(SR * 2.0)
    crackle = noise(2.0) * (rng.random(n) < 0.35) * 1.4
    hiss = lowpass(noise(2.0), 2) * 0.8
    gate = np.repeat(rng.random(40) > 0.3, n // 40 + 1)[:n]
    drone = saw(np.full(n, 46.0), 2.0, 0.02) * 0.35
    return env((crackle + hiss) * gate + drone, 0.05, 0.4)


SOUNDS4["boiled_static"] = s_boiled_static

SOUNDS = {"zap": s_zap, "mode": s_mode, "launch": s_launch,
          "heal": s_heal, "repair": s_repair, "give": s_give, "owner_join": s_owner_join, **SOUNDS2, **SOUNDS3, **SOUNDS4}

# ------------------------------------------------------------------ pack

TOOLS = {"lightning_wand": ("blaze_rod", lightning_wand), "launch_stick": ("stick", launch_stick), "heal_wand": ("ghast_tear", heal_wand),
         "freeze_wand": ("prismarine_shard", freeze_wand), "judge_gavel": ("breeze_rod", judge_gavel),
         "thor_hammer": ("mace", thor_hammer), "flame_sword": ("golden_sword", flame_sword), "frost_bow": ("bow", frost_bow),
         "blast_bow": ("bow", blast_bow), "meteor_staff": ("magma_cream", meteor_staff), "disarm_gloves": ("leather", disarm_gloves),
         "orbital_cannon": ("prismarine_crystals", orbital_cannon), "godslayer": ("netherite_sword", godslayer)}
# Plain (not hand-held) items.
FLAT = {"home_teleporter": ("echo_shard", home_teleporter), "rune_circle": ("light_blue_stained_glass_pane", rune_circle)}
# Models that are cubes (the meteor flying down).
CUBES = {"meteor": ("magma_block", meteor_texture)}
ICON_ITEMS = {**{k: (v[0], (lambda rows=v[1], o=v[2]: grid(rows, o))) for k, v in ICONS.items()},
              "icon_join": ("nether_star", star_icon), "icon_pack": ("painting", palette_icon)}


def code(name):
    """Coded pack names (opening the pack shouldn't reveal the items or secrets). Same as PackIds.code in the mod."""
    return "x" + hashlib.sha256(("vigil-pack:" + name).encode()).hexdigest()[:10]


def obfuscate():
    """Renames every texture, model and sound in the pack to its code, and points every reference at the codes."""
    names = set()
    for path in files:
        for prefix in ("assets/vigil/textures/item/", "assets/vigil/models/item/", "assets/vigil/sounds/"):
            if path.startswith(prefix):
                names.add(path[len(prefix):].rsplit(".", 1)[0])

    def fix(v):
        if isinstance(v, str):
            if v.startswith("vigil:item/") and v[11:] in names:
                return "vigil:item/" + code(v[11:])
            if v.startswith("vigil:") and v[6:] in names:
                return "vigil:" + code(v[6:])
            return v
        if isinstance(v, list):
            return [fix(x) for x in v]
        if isinstance(v, dict):
            return {(code(k) if k in names else k): fix(x) for k, x in v.items()}
        return v

    out = {}
    for path, data in files.items():
        new = path
        for prefix in ("assets/vigil/textures/item/", "assets/vigil/models/item/", "assets/vigil/sounds/", "assets/vigil/items/"):
            if path.startswith(prefix):
                stem, ext = path[len(prefix):].rsplit(".", 1)
                new = prefix + code(stem) + "." + ext
        if path.endswith(".json"):
            data = json.dumps(fix(json.loads(data)), indent=2).encode()
        out[new] = data
    files.clear()
    files.update(out)


def build():
    files["pack.mcmeta"] = json.dumps({"pack": {
        "description": "§7Server resources",
        "pack_format": 46, "supported_formats": [46, 1000], "min_format": 46, "max_format": 1000}}, indent=2).encode()
    files["pack.png"] = png(pack_icon())
    by_base = {}
    for tool, (base, draw) in TOOLS.items():
        files[f"assets/vigil/textures/item/{tool}.png"] = png(draw())
        files[f"assets/vigil/models/item/{tool}.json"] = json.dumps(
            {"parent": "minecraft:item/bow" if base == "bow" else "minecraft:item/handheld", "textures": {"layer0": f"vigil:item/{tool}"}}, indent=2).encode()
        by_base.setdefault(base, []).append(tool)
    for name, (base, draw) in CUBES.items():
        files[f"assets/vigil/textures/item/{name}.png"] = png(draw())
        files[f"assets/vigil/models/item/{name}.json"] = json.dumps(
            {"parent": "minecraft:block/cube_all", "textures": {"all": f"vigil:item/{name}"}}, indent=2).encode()
        by_base.setdefault(base, []).append(name)
    for name, (base, draw, parent) in SECRET.items():
        files[f"assets/vigil/textures/item/{name}.png"] = png(draw())
        files[f"assets/vigil/models/item/{name}.json"] = json.dumps(
            {"parent": parent, "textures": {"layer0": f"vigil:item/{name}"}}, indent=2).encode()
        by_base.setdefault(base, []).append(name)
    # 3D boss and guard models (made by scripts/models/cubes.py)
    mdir = os.path.join(ROOT, "scripts", "models", "built")
    for name, parts in MODELS3D.items():
        files[f"assets/vigil/textures/item/{name}.png"] = open(os.path.join(mdir, name + ".png"), "rb").read()
        for part in parts:
            model = open(os.path.join(mdir, part + ".json"), "rb").read()
            files[f"assets/vigil/models/item/{part}.json"] = model
            # a part can have its own picture too (The Boiled One's sharper head)
            for tex in json.loads(model).get("textures", {}).values():
                extra = os.path.join(mdir, tex[len("vigil:item/"):] + ".png")
                if tex.startswith("vigil:item/") and tex[len("vigil:item/"):] != name and os.path.exists(extra):
                    files[f"assets/vigil/textures/item/{tex[len('vigil:item/'):]}.png"] = open(extra, "rb").read()
            by_base.setdefault("nautilus_shell", []).append(part)
    files["assets/vigil/textures/item/grappling_hook_cast.png"] = png(grappling_hook(True))
    files["assets/vigil/models/item/grappling_hook_cast.json"] = json.dumps(
        {"parent": "minecraft:item/handheld_rod", "textures": {"layer0": "vigil:item/grappling_hook_cast"}}, indent=2).encode()
    for icon, (base, draw) in {**ICON_ITEMS, **FLAT}.items():
        files[f"assets/vigil/textures/item/{icon}.png"] = png(draw())
        files[f"assets/vigil/models/item/{icon}.json"] = json.dumps(
            {"parent": "minecraft:item/generated", "textures": {"layer0": f"vigil:item/{icon}"}}, indent=2).encode()
        by_base.setdefault(base, []).append(icon)
    for base, tools in by_base.items():
        # Each custom item also gets its own item definition, which the mod points at with the item_model component:
        # newer game versions (reached through ViaVersion) show items this way even when the custom_model_data
        # select on the base item doesn't come through.
        for tl in tools:
            files[f"assets/vigil/items/{tl}.json"] = json.dumps({"model": CASE_MODELS.get(
                tl, {"type": "minecraft:model", "model": f"vigil:item/{tl}"})}, indent=2).encode()
        # The game's own items are left alone (replacing their definitions broke them on newer game versions):
        # every custom item carries the item_model component pointing at its definition above.
    files.update(boss_bars())
    sounds = {}
    for name, fn in SOUNDS.items():
        files[f"assets/vigil/sounds/{name}.ogg"] = ogg(fn())
        sounds[name] = {"sounds": [{"name": f"vigil:{name}"}]}
    files["assets/vigil/sounds.json"] = json.dumps(sounds, indent=2).encode()

    obfuscate()
    # Copies of the files next to the zip, then the zip itself (fixed timestamps: same input, same zip).
    import shutil
    shutil.rmtree(os.path.join(OUT, "src"), ignore_errors=True)
    for path, data in files.items():
        full = os.path.join(OUT, "src", path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as f:
            f.write(data)
    zpath = os.path.join(OUT, "pack.zip")
    old = os.path.join(OUT, "vigil-owner.zip")
    if os.path.exists(old):
        os.remove(old)
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for path in sorted(files):
            info = zipfile.ZipInfo(path, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, files[path])
    sha = hashlib.sha1(open(zpath, "rb").read()).hexdigest()
    with open(os.path.join(ROOT, "core", "src", "main", "resources", "vigil", "owner-pack.sha1"), "w") as f:
        f.write(sha + "\n")
    print(zpath, os.path.getsize(zpath), "bytes, sha1", sha)
    bedrock_pack()


# ------------------------------------------------------------------ Bedrock pack (sent by Geyser)

BEDROCK_UUID = "6e0d4c58-3f1b-4a39-9d4c-2b7f1e9a5c11"
BEDROCK_MODULE = "a1c7e2f4-8b3d-4e6a-9f10-5d2c8b7e4a33"
BOILED_NAME = "The Boiled One"


def bedrock_pack():
    """What Bedrock players need: The Boiled One's painted figure (on the wither skeleton it's built on, only when it
    carries its name, so real wither skeletons look as always), its jumpscare face and its scream."""
    bdir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bedrock")
    out = {}
    ent = json.load(open(os.path.join(bdir, "wither_skeleton.entity.json")))
    d = ent["minecraft:client_entity"]["description"]
    d["materials"]["vigil_boiled"] = "entity_alphatest"
    d["textures"]["vigil_boiled"] = "textures/entity/vigil/boiled_one"
    d["geometry"]["vigil_boiled"] = "geometry.vigil.boiled_one"
    is_it = f"query.get_name == '{BOILED_NAME}'"
    d["render_controllers"] = [{rc: f"!({is_it})"} if isinstance(rc, str) else rc for rc in d["render_controllers"]] + [
        {"controller.render.vigil_boiled_one": is_it}]
    out["entity/wither_skeleton.entity.json"] = json.dumps(ent, indent=2).encode()
    out["render_controllers/vigil_boiled_one.render_controllers.json"] = json.dumps({
        "format_version": "1.8.0",
        "render_controllers": {"controller.render.vigil_boiled_one": {
            "geometry": "Geometry.vigil_boiled", "materials": [{"*": "Material.vigil_boiled"}],
            "textures": ["Texture.vigil_boiled"]}}}, indent=2).encode()
    # The 3D figure (scripts/models/billboard.py), 49 pixels tall (x1.5 scale from the server = 4.6 blocks).
    out["models/entity/vigil_boiled_one.geo.json"] = open(os.path.join(_BUILT, "boiled_one.geo.json"), "rb").read()
    out["textures/entity/vigil/boiled_one.png"] = open(os.path.join(_BUILT, "boiled_one.png"), "rb").read()
    # The jumpscare face: the same character the mod sends in the title (U+E200 = glyph_E2, first cell).
    face = Image.open(os.path.join(_BUILT, "boiled_one_face.png")).convert("RGBA").resize((128, 128), Image.LANCZOS)
    sheet = Image.new("RGBA", (2048, 2048), (0, 0, 0, 0))
    sheet.paste(face, (0, 0))
    out["font/glyph_E2.png"] = png(sheet)
    # The scream, under the coded name the mod plays it by (with and without the namespace, whichever Geyser sends).
    defs = {}
    for snd in ("boiled_scream", "boiled_breath", "boiled_static"):
        out[f"sounds/vigil/{snd}.ogg"] = ogg(SOUNDS[snd]())
        entry = {"category": "hostile", "sounds": [{"name": f"sounds/vigil/{snd}", "volume": 1.0, "load_on_low_memory": True}]}
        defs["vigil:" + code(snd)] = entry
        defs[code(snd)] = entry
    out["sounds/sound_definitions.json"] = json.dumps({"format_version": "1.14.0", "sound_definitions": defs}, indent=2).encode()
    out["pack_icon.png"] = png(pack_icon())
    digest = hashlib.sha1(b"".join(out[k] for k in sorted(out))).hexdigest()
    version = [1, 0, int(digest[:6], 16) % 100000]
    out["manifest.json"] = json.dumps({
        "format_version": 2,
        "header": {"name": "Vigil", "description": "Vigil server pack for Bedrock", "uuid": BEDROCK_UUID,
                   "version": version, "min_engine_version": [1, 21, 0]},
        "modules": [{"type": "resources", "uuid": BEDROCK_MODULE, "version": version}]}, indent=2).encode()
    zpath = os.path.join(OUT, "bedrock.mcpack")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for path in sorted(out):
            info = zipfile.ZipInfo(path, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, out[path])
    print(zpath, os.path.getsize(zpath), "bytes, version", version)


if __name__ == "__main__":
    build()
