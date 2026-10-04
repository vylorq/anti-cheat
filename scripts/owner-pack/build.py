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


SOUNDS = {"power_on": s_power_on, "power_off": s_power_off, "zap": s_zap, "mode": s_mode, "launch": s_launch,
          "heal": s_heal, "repair": s_repair, "give": s_give, "owner_join": s_owner_join}

# ------------------------------------------------------------------ pack

TOOLS = {"lightning_wand": ("blaze_rod", lightning_wand), "launch_stick": ("stick", launch_stick), "heal_wand": ("ghast_tear", heal_wand)}


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
