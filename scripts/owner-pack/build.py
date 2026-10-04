#!/usr/bin/env python3
"""Builds the owner pack (resourcepack/vigil-owner.zip): tool textures, power sounds and item model overrides.

Needs pillow, numpy and soundfile:  pip install pillow numpy soundfile
Run from the repo root:             python3 scripts/owner-pack/build.py
"""
import hashlib
import io
import json
import os
import zipfile

import numpy as np
import soundfile as sf
from PIL import Image, ImageDraw

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
    img = canvas()
    px = img.load()
    rod(img, 1, 14, 7, 8, hexc("#7a4a24"), hexc("#a46a3a"), hexc("#4a2a12"))
    # The hammer head, tilted, with gold bands.
    head = {2: [9, 10, 11], 3: [8, 9, 10, 11, 12], 4: [8, 9, 10, 11, 12, 13], 5: [9, 10, 11, 12, 13, 14],
            6: [10, 11, 12, 13, 14], 7: [11, 12, 13]}
    for y, xs in head.items():
        for x in xs:
            px[x, y] = hexc("#8b5a2b")
        px[xs[0], y] = hexc("#5a3517")
        px[xs[-1], y] = hexc("#b07a45")
    for (x, y) in [(9, 3), (10, 4), (11, 5), (12, 6)]:
        px[x, y] = hexc("#ffd23f")
    for (x, y) in [(8, 7), (9, 8)]:
        px[x, y] = hexc("#7a4a24")
    for (x, y) in [(15, 1), (14, 9), (6, 3)]:
        px[x, y] = hexc("#ffe79a", 170)
    return img


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

SOUNDS = {"zap": s_zap, "mode": s_mode, "launch": s_launch,
          "heal": s_heal, "repair": s_repair, "give": s_give, "owner_join": s_owner_join, **SOUNDS2}

# ------------------------------------------------------------------ pack

TOOLS = {"lightning_wand": ("blaze_rod", lightning_wand), "launch_stick": ("stick", launch_stick), "heal_wand": ("ghast_tear", heal_wand),
         "freeze_wand": ("prismarine_shard", freeze_wand), "judge_gavel": ("breeze_rod", judge_gavel)}
ICON_ITEMS = {**{k: (v[0], (lambda rows=v[1], o=v[2]: grid(rows, o))) for k, v in ICONS.items()},
              "icon_join": ("nether_star", star_icon), "icon_pack": ("painting", palette_icon)}


def build():
    files["pack.mcmeta"] = json.dumps({"pack": {
        "description": "§6Vigil owner pack §7(tools and power sounds)",
        "pack_format": 46, "supported_formats": [46, 1000], "min_format": 46, "max_format": 1000}}, indent=2).encode()
    files["pack.png"] = png(pack_icon())
    by_base = {}
    for tool, (base, draw) in TOOLS.items():
        files[f"assets/vigil/textures/item/{tool}.png"] = png(draw())
        files[f"assets/vigil/models/item/{tool}.json"] = json.dumps(
            {"parent": "minecraft:item/handheld", "textures": {"layer0": f"vigil:item/{tool}"}}, indent=2).encode()
        by_base.setdefault(base, []).append(tool)
    for icon, (base, draw) in ICON_ITEMS.items():
        files[f"assets/vigil/textures/item/{icon}.png"] = png(draw())
        files[f"assets/vigil/models/item/{icon}.json"] = json.dumps(
            {"parent": "minecraft:item/generated", "textures": {"layer0": f"vigil:item/{icon}"}}, indent=2).encode()
        by_base.setdefault(base, []).append(icon)
    for base, tools in by_base.items():
        files[f"assets/minecraft/items/{base}.json"] = json.dumps({"model": {
            "type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
            "cases": [{"when": f"vigil:{tl}", "model": {"type": "minecraft:model", "model": f"vigil:item/{tl}"}} for tl in tools],
            "fallback": {"type": "minecraft:model", "model": f"minecraft:item/{base}"}}}, indent=2).encode()
    sounds = {}
    for name, fn in SOUNDS.items():
        files[f"assets/vigil/sounds/{name}.ogg"] = ogg(fn())
        sounds[name] = {"sounds": [{"name": f"vigil:{name}"}]}
    files["assets/vigil/sounds.json"] = json.dumps(sounds, indent=2).encode()

    # Readable copies of the source files next to the zip, then the zip itself (fixed timestamps: same input, same zip).
    for path, data in files.items():
        full = os.path.join(OUT, "src", path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as f:
            f.write(data)
    zpath = os.path.join(OUT, "vigil-owner.zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for path in sorted(files):
            info = zipfile.ZipInfo(path, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, files[path])
    sha = hashlib.sha1(open(zpath, "rb").read()).hexdigest()
    with open(os.path.join(ROOT, "core", "src", "main", "resources", "vigil", "owner-pack.sha1"), "w") as f:
        f.write(sha + "\n")
    print(zpath, os.path.getsize(zpath), "bytes, sha1", sha)


if __name__ == "__main__":
    build()
