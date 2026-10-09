"""Ore blocks at twice the game's resolution (32x32): faceted crystals (a lit side, a shadowed side, a bright edge
and a glint) set into the stone, each with a dark rim where it meets the rock.

    python3 scripts/armor/ores_hd.py <folder with the game's textures>

Overwrites scripts/armor/built/ore_*.png (run after art.py).
"""
import math
import os
import random
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from art import RAMPS, OUT, hexc  # noqa: E402

DEEP = {"ruby": True, "voidstone": True}
# (count, length range, width range) per gem: big rare crystals for voidstone, many small ones for sapphire...
STYLE = {"ruby": (5, (7, 11), (4, 6)), "sapphire": (7, (5, 8), (3, 5)), "topaz": (5, (8, 12), (3, 5)),
         "voidstone": (4, (8, 11), (4, 6)), "bloodstone": (6, (6, 9), (3, 5))}


def shade(c, k):
    return tuple(max(0, min(255, round(v * k))) for v in c[:3]) + (255,)


def crystal(d, rim, ramp, cx, cy, ang, length, width):
    ux, uy = math.cos(ang), math.sin(ang)
    px, py = -uy, ux
    tip = (cx + ux * length, cy + uy * length)
    mid_l = (cx + ux * length * 0.55 + px * width / 2, cy + uy * length * 0.55 + py * width / 2)
    mid_r = (cx + ux * length * 0.55 - px * width / 2, cy + uy * length * 0.55 - py * width / 2)
    back_l = (cx + px * width / 2.6, cy + py * width / 2.6)
    back_r = (cx - px * width / 2.6, cy - py * width / 2.6)
    axis_b = (cx, cy)
    whole = [tip, mid_l, back_l, back_r, mid_r]
    # rim in the rock, a pixel bigger all round
    rim_poly = [(x + (x - cx) * 0.18, y + (y - cy) * 0.18) for x, y in whole]
    d.polygon(rim_poly, fill=rim)
    d.polygon(whole, fill=hexc(ramp[0]))
    d.polygon([tip, mid_l, back_l, axis_b], fill=hexc(ramp[3]))          # lit face
    d.polygon([tip, mid_r, back_r, axis_b], fill=hexc(ramp[1]))          # shadowed face
    d.line([axis_b, tip], fill=hexc(ramp[2]))                            # the ridge
    d.line([mid_l, tip], fill=hexc(ramp[4]))                             # bright edge
    gx, gy = tip[0] - ux * 2 + px * 0.8, tip[1] - uy * 2 + py * 0.8
    d.point((round(gx), round(gy)), fill=(255, 255, 255, 255))           # glint


def ore(g, src):
    base = Image.open(os.path.join(src, "blocks", "deepslate.png" if DEEP.get(g) else "stone.png")).convert("RGBA")
    img = base.resize((32, 32), Image.NEAREST)
    d = ImageDraw.Draw(img)
    rim = shade(hexc(RAMPS[g][0]), 0.6) if not DEEP.get(g) else (12, 10, 14, 255)
    rng = random.Random("vigil-ore-2-" + g)
    count, (l0, l1), (w0, w1) = STYLE[g]
    placed = []
    tries = 0
    while len(placed) < count and tries < 400:
        tries += 1
        cx, cy = rng.uniform(5, 27), rng.uniform(5, 27)
        if any((cx - x) ** 2 + (cy - y) ** 2 < 70 for x, y in placed):
            continue
        ang = rng.uniform(-math.pi * 0.95, -math.pi * 0.05)                # crystals grow roughly upward
        length, width = rng.uniform(l0, l1), rng.uniform(w0, w1)
        tx, ty = cx + math.cos(ang) * length, cy + math.sin(ang) * length
        if not (2 <= tx <= 30 and 2 <= ty <= 30):
            continue                                                        # it would be cut off at the edge
        placed.append((cx, cy))
        crystal(d, rim, RAMPS[g], cx, cy, ang, length, width)
        # a little one beside some of them
        if rng.random() < 0.35:
            crystal(d, rim, RAMPS[g], cx + rng.uniform(-3, 3), cy + rng.uniform(1, 3), ang + rng.uniform(-0.7, 0.7),
                    rng.uniform(3, 5), rng.uniform(2, 3))
    return img


def main(src):
    for g in RAMPS:
        if g in STYLE:
            ore(g, src).save(os.path.join(OUT, f"ore_{g}.png"))
    print("HD ores drawn")


if __name__ == "__main__":
    main(sys.argv[1])
