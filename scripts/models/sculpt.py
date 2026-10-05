#!/usr/bin/env python3
"""Hand-sculpted voxel models for the bosses and their guards (pixelated, like everything in Minecraft).

Each model is built from simple shapes (ellipsoids, limbs, spikes, plates) with surface noise, glowing eyes and
cracks, then baked into a Minecraft item model by voxmesh.finalize. Models face +Z (their front is the high-Z side).

    python3 scripts/models/sculpt.py            # every model, then built/models.json
    python3 scripts/models/sculpt.py name ...   # only these
"""
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voxmesh  # noqa: E402


def hexc(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], float)


class Sculpt:
    def __init__(self, w, h, d, seed=1):
        self.dims = (w, h, d)
        self.solid = np.zeros(self.dims, bool)
        self.color = np.zeros(self.dims + (3,), float)
        self.glow = np.zeros(self.dims, bool)
        self.rng = np.random.default_rng(seed)
        self.X, self.Y, self.Z = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5, np.arange(d) + 0.5, indexing="ij")
        self.noise = self.rng.random(self.dims)

    # -------------------------------------------------------------- painting
    def paint(self, mask, col, noise=0.12, glow=False, add=True):
        col = hexc(col) if isinstance(col, str) else np.asarray(col, float)
        if add:
            self.solid |= mask
        n = 1 + (self.noise[mask] - 0.5) * 2 * noise
        self.color[mask] = np.clip(col[None, :] * n[:, None], 0, 255)
        if glow:
            self.glow |= mask
        else:
            self.glow &= ~mask

    def carve(self, mask):
        self.solid &= ~mask
        self.glow &= ~mask

    def recolor(self, mask, col, noise=0.1, glow=False):
        """Only paints voxels that are already solid."""
        self.paint(mask & self.solid, col, noise, glow, add=False)

    # -------------------------------------------------------------- shapes (masks)
    def ell(self, c, r):
        return ((self.X - c[0]) / r[0]) ** 2 + ((self.Y - c[1]) / r[1]) ** 2 + ((self.Z - c[2]) / r[2]) ** 2 <= 1

    def box(self, a, b):
        lo = [min(a[i], b[i]) for i in range(3)]
        hi = [max(a[i], b[i]) for i in range(3)]
        return ((self.X >= lo[0]) & (self.X < hi[0]) & (self.Y >= lo[1]) & (self.Y < hi[1]) & (self.Z >= lo[2]) & (self.Z < hi[2]))

    def _seg(self, p0, p1):
        p0, p1 = np.array(p0, float), np.array(p1, float)
        d = p1 - p0
        L2 = max(1e-9, (d ** 2).sum())
        t = np.clip(((self.X - p0[0]) * d[0] + (self.Y - p0[1]) * d[1] + (self.Z - p0[2]) * d[2]) / L2, 0, 1)
        dist = np.sqrt((self.X - (p0[0] + t * d[0])) ** 2 + (self.Y - (p0[1] + t * d[1])) ** 2 + (self.Z - (p0[2] + t * d[2])) ** 2)
        return t, dist

    def limb(self, p0, p1, r0, r1=None):
        t, dist = self._seg(p0, p1)
        r = r0 + (r0 if r1 is None else r1) * 0 + ((r1 if r1 is not None else r0) - r0) * t
        return dist <= r

    def spike(self, p0, p1, r):
        t, dist = self._seg(p0, p1)
        return dist <= r * (1 - t) + 0.35

    def path(self, pts, r):
        m = np.zeros(self.dims, bool)
        for a, b in zip(pts, pts[1:]):
            m |= self.limb(a, b, r)
        return m

    def mirror(self, fn, *args, **kw):
        """Applies a mask-making function to a shape and its left-right mirror. Points are (x, y, z) tuples."""
        w = self.dims[0]

        def flip(a):
            if isinstance(a, list):
                return [flip(p) for p in a]
            return (w - a[0], a[1], a[2])
        # Which arguments are positions: the centre of an ellipsoid, both ends of a box / limb / spike, a path's points.
        npts = {"ell": 1, "box": 2, "limb": 2, "spike": 2, "path": 1}[fn.__name__]
        flipped = [flip(a) if i < npts else a for i, a in enumerate(args)]
        return fn(*args, **kw) | fn(*flipped, **kw)

    def surface(self):
        from scipy import ndimage
        return self.solid & ~ndimage.binary_erosion(self.solid)

    def cracks(self, col, count, length, region=None, glow=True, seed=0):
        """Glowing veins that wander across the surface."""
        rng = np.random.default_rng(seed)
        surf = self.surface()
        if region is not None:
            surf &= region
        idx = np.argwhere(surf)
        if not len(idx):
            return
        for _ in range(count):
            p = idx[rng.integers(len(idx))].astype(float)
            pts = [tuple(p)]
            d = rng.normal(size=3)
            d[1] = abs(d[1]) * -0.6
            for _ in range(length):
                d = d * 0.7 + rng.normal(size=3) * 0.6
                d /= max(1e-6, np.linalg.norm(d))
                p = p + d * 1.6
                pts.append(tuple(p))
            m = self.path(pts, 0.8) & self.solid & ~self._inner(2)
            self.recolor(m, col, 0.08, glow)

    def _inner(self, depth):
        from scipy import ndimage
        return ndimage.binary_erosion(self.solid, iterations=depth)

    def speckle(self, col, chance, region=None, glow=False, seed=0):
        rng = np.random.default_rng(seed)
        surf = self.surface()
        if region is not None:
            surf &= region
        m = surf & (rng.random(self.dims) < chance)
        self.recolor(m, col, 0.1, glow)

    def bands(self, region, cols, period, axis=1, noise=0.1):
        coord = [self.X, self.Y, self.Z][axis]
        k = (np.floor(coord / period).astype(int)) % len(cols)
        for i, c in enumerate(cols):
            self.recolor(region & (k == i), c, noise)

    def done(self, name, shading=1.0):
        return voxmesh.finalize(name, self.solid, self.color, self.glow, shading)


# ================================================================== bosses

def deepslate_colossus():
    s = Sculpt(40, 48, 28, seed=11)
    W = 40
    rock, rock2, dark = "#4b4b55", "#5d5d68", "#2e2e36"
    # Legs and feet
    s.paint(s.mirror(s.limb, (13, 15, 13), (12, 4, 14), 5.5), rock, 0.18)
    s.paint(s.mirror(s.box, (6, 0, 8), (19, 4, 21)), dark, 0.15)
    # Hunched torso
    s.paint(s.ell((20, 26, 12), (15, 11, 10)), rock2, 0.2)
    s.paint(s.ell((20, 33, 10), (14, 8, 9)), rock, 0.2)
    # Shoulders and massive arms that hang to the knees
    s.paint(s.mirror(s.ell, (6, 33, 12), (7, 6, 7)), rock2, 0.2)
    s.paint(s.mirror(s.limb, (5, 32, 13), (4, 18, 15), 4.5, 4.0), rock, 0.2)
    s.paint(s.mirror(s.limb, (4, 18, 15), (4, 8, 17), 4.0, 4.5), rock, 0.2)
    s.paint(s.mirror(s.ell, (4, 7, 17), (6, 5.5, 6)), dark, 0.18)
    # Sunken head under a heavy brow
    s.paint(s.ell((20, 38, 20), (7, 6, 6.5)), rock2, 0.15)
    s.paint(s.box((12, 41, 21), (28, 44, 27)), dark, 0.1)          # heavy brow
    s.carve(s.box((14, 33, 24), (26, 37, 28)))                      # gaping mouth
    s.paint(s.box((14, 33, 23), (26, 37, 24)), "#120e14", 0.05)      # dark throat
    for x in range(14, 26, 3):                                      # jagged teeth
        s.paint(s.box((x, 36, 24), (x + 1, 37, 26)), "#d8d8c8", 0.04)
        s.paint(s.box((x, 35, 25), (x + 1, 36, 26)), "#d8d8c8", 0.04)
        s.paint(s.box((x + 1.5, 33, 24), (x + 2.5, 34, 26)), "#d8d8c8", 0.04)
    s.carve(s.mirror(s.box, (14, 38, 25), (19, 41, 28)))           # deep sockets
    s.paint(s.mirror(s.box, (15, 38.5, 25), (18.5, 40.5, 26)), "#5ff0ff", 0.03, glow=True)
    # Amethyst growths bursting from the shoulders and back
    for (p0, p1, r) in [((7, 37, 11), (3, 47, 8), 2.6), ((10, 38, 9), (12, 46, 4), 2.0), ((4, 35, 9), (0, 42, 5), 1.8)]:
        s.paint(s.mirror(s.spike, p0, p1, r), "#9b6bff", 0.12, glow=False)
        s.speckle("#d9c2ff", 0.4, s.mirror(s.spike, p0, p1, r), glow=True, seed=3)
    s.paint(s.spike((20, 36, 4), (20, 46, 1), 2.5), "#8a5cff", 0.1)
    # Moss on top, glowing sculk veins across the body
    s.speckle("#4f7a3a", 0.25, s.Y > 30, seed=5)
    s.cracks("#3fe0e0", 26, 9, seed=7)
    return s.done("deepslate_colossus")


def storm_phantom():
    s = Sculpt(64, 24, 44, seed=12)
    body, bone, membrane = "#2a3348", "#cfd2c4", "#56679a"
    s.paint(s.ell((32, 11, 20), (6, 5, 15)), body, 0.15)
    s.paint(s.ell((32, 13, 22), (5, 3, 10)), "#3b4868", 0.12)
    # Skull head
    s.paint(s.ell((32, 12, 37), (5.5, 4.5, 6)), bone, 0.12)
    s.carve(s.mirror(s.ell, (29.5, 13, 42), (1.6, 1.4, 2)))
    s.paint(s.mirror(s.ell, (29.5, 13, 41.5), (1.4, 1.2, 1.4)), "#fff36b", 0.05, glow=True)
    s.paint(s.mirror(s.path, [(29, 15, 36), (27, 16.5, 31), (26, 17, 26), (26.5, 16, 22)], 0.9), "#e8e2cc", 0.05)
    s.carve(s.box((29, 9, 41), (35, 11, 44)))
    for x in range(29, 35, 2):
        s.paint(s.box((x, 10, 41), (x + 1, 11, 43)), "#f2f2e6", 0.03)
    # Huge torn wings, bones along the leading edge, lightning in the membrane
    for side in (-1, 1):
        for x in range(0, 27):
            xx = 32 + side * (5 + x)
            if not (0 <= xx < 64):
                continue
            y = 13 - x * 0.22
            lead = 28 - x * 0.25
            trail = 6 + x * 0.35 + 3 * math.sin(x * 0.9)
            m = (np.abs(s.X - (xx + 0.5)) < 0.6) & (np.abs(s.Y - y) < 1.4) & (s.Z > trail) & (s.Z < lead)
            s.paint(m, membrane, 0.18)
        s.paint(s.path([(32 + side * 5, 13, 27), (32 + side * 16, 10.5, 24), (32 + side * 31, 7, 21)], 1.1), bone, 0.08)
        for k in range(4):
            tip = (32 + side * (12 + k * 5), 9 - k * 0.6, 6 + k * 3)
            s.paint(s.path([(32 + side * (10 + k * 5), 11 - k * 0.6, 25), tip], 0.7), bone, 0.08)
    holes = s.rng.random(s.dims) < 0.035
    s.carve(holes & (np.abs(s.X - 32) > 12) & (s.Y < 14))
    s.cracks("#ffe066", 18, 10, region=np.abs(s.X - 32) > 9, seed=4)
    # Tail and hanging claws
    s.paint(s.spike((32, 11, 6), (32, 13, 0), 3.0), body, 0.15)
    s.paint(s.mirror(s.path, [(28, 7, 22), (27, 3, 24), (27, 1, 27)], 0.8), bone, 0.05)
    return s.done("storm_phantom")


def forgemaster():
    s = Sculpt(40, 46, 26, seed=13)
    skin, iron, leather = "#9a5a52", "#5a5e66", "#4a2e1e"
    s.paint(s.mirror(s.limb, (14, 14, 13), (13, 3, 13), 5), leather, 0.15)
    s.paint(s.mirror(s.box, (7, 0, 8), (19, 4, 20)), "#2c2c30", 0.12)
    s.paint(s.ell((20, 24, 13), (14, 11, 10)), skin, 0.15)
    # Apron, belt with a skull buckle
    s.paint(s.box((12, 9, 21), (28, 26, 24)), leather, 0.15)
    s.paint(s.box((10, 19, 21), (30, 21, 24)), "#d9a52e", 0.08)
    s.paint(s.box((18, 18, 23), (22, 22, 25)), "#e8e2cc", 0.05)
    # Spiked pauldrons and thick arms
    s.paint(s.mirror(s.ell, (6, 31, 13), (7, 5, 7)), iron, 0.15)
    for k in range(3):
        s.paint(s.mirror(s.spike, (4 + k * 3, 34, 13), (1 + k * 3, 41, 12), 1.6), "#8a8e96", 0.08)
    s.paint(s.mirror(s.limb, (5, 30, 13), (4, 14, 16), 4.5, 4.0), skin, 0.15)
    s.paint(s.mirror(s.ell, (4, 12, 17), (4.5, 4, 4.5)), skin, 0.15)
    # The cleaver, held in the right hand, edge glowing hot
    s.paint(s.limb((36, 12, 17), (36, 22, 17), 1.2), "#3a2a1e", 0.1)
    s.paint(s.box((33, 22, 15), (39, 36, 19)), "#45454c", 0.12)
    s.paint(s.box((33, 34, 15), (39, 36, 19)), "#ff8a2e", 0.05, glow=True)
    s.paint(s.box((38, 22, 15), (39, 36, 19)), "#ffb05a", 0.05, glow=True)
    # Brutish head: snout, tusks, burning eyes, horned helmet
    s.paint(s.ell((20, 38, 15), (7.5, 6.5, 7)), skin, 0.12)
    s.paint(s.box((16, 33, 20), (24, 38, 25)), "#c47e74", 0.1)
    s.carve(s.mirror(s.box, (17, 35, 24), (19, 37, 25)))
    s.paint(s.mirror(s.spike, (16, 33, 22), (14, 39, 24), 1.2), "#efe8d0", 0.05)
    s.paint(s.mirror(s.box, (15, 39, 21), (18, 41, 23)), "#ff7a1a", 0.05, glow=True)
    s.paint(s.ell((20, 42, 14), (8, 3.5, 8)), iron, 0.12)
    s.paint(s.mirror(s.path, [(13, 43, 14), (8, 46, 13), (6, 45, 16)], 1.4), "#d6cfb8", 0.08)
    s.cracks("#ff7a1a", 20, 7, region=s.Y < 32, seed=8)
    return s.done("forgemaster")


def sand_colossus():
    s = Sculpt(40, 52, 24, seed=14)
    wrap, wrap2, sand = "#8f7650", "#5e4a2e", "#3a2c1a"
    s.paint(s.mirror(s.limb, (15, 18, 12), (14, 2, 12), 4.2, 3.6), wrap, 0.15)
    s.paint(s.ell((20, 29, 12), (11, 12, 8)), wrap, 0.15)
    s.bands(s.Y > 0, [wrap, wrap2, wrap, sand], 2, axis=1)
    # Long arms with claws
    s.paint(s.mirror(s.limb, (9, 37, 12), (5, 20, 14), 3.4, 3.0), wrap2, 0.15)
    s.paint(s.mirror(s.limb, (5, 20, 14), (5, 10, 16), 3.0, 2.6), wrap, 0.15)
    for k in range(3):
        s.paint(s.mirror(s.spike, (4 + k * 1.5, 9, 16), (3 + k * 1.5, 4, 19), 1.0), "#e8e2cc", 0.05)
    # Hanging torn wraps
    for x in range(10, 31, 3):
        h = 5 + int(s.rng.integers(0, 6))
        s.paint(s.box((x, 16 - h, 18), (x + 1, 17, 20)), wrap2, 0.15)
    # Skull face, hollow glowing sockets, gaping jaw
    s.paint(s.ell((20, 43, 16), (6.5, 7, 6.5)), "#b5a27c", 0.12)    # skull, pushed forward (hunched)
    s.carve(s.mirror(s.ell, (17, 45, 21.5), (2.4, 2.4, 2.6)))
    s.paint(s.mirror(s.ell, (17, 45, 20.5), (1.5, 1.5, 1.4)), "#ffb020", 0.05, glow=True)
    s.carve(s.box((16, 36, 19), (24, 40, 24)))
    for x in range(16, 24, 2):
        s.paint(s.spike((x + 0.5, 40, 20.5), (x + 0.5, 37.5, 21), 0.9), "#efe4c4", 0.03)
        s.paint(s.spike((x + 1.5, 36, 20.5), (x + 1.5, 38.5, 21), 0.9), "#efe4c4", 0.03)
    # Broken stone circlet and a glowing scarab amulet
    s.paint(s.box((13, 49, 8), (27, 51, 19)) & ~s.box((15, 49, 10), (25, 51, 17)), "#c48a0e", 0.1)
    s.paint(s.mirror(s.spike, (14, 50, 13), (13, 55, 13), 1.2), "#c48a0e", 0.1)
    s.paint(s.ell((20, 31, 20), (2.5, 2.5, 1.2)), "#2ad6a0", 0.05, glow=True)
    s.cracks("#ffb020", 22, 7, seed=9)
    s.speckle("#2a2014", 0.12, seed=3)
    return s.done("sand_colossus")


def frost_titan():
    s = Sculpt(42, 54, 24, seed=15)
    skin, skin2, ice = "#28466e", "#345a88", "#bff4ff"
    s.paint(s.mirror(s.limb, (15, 18, 12), (14, 2, 12), 4.6, 4.0), skin, 0.15)
    s.paint(s.ell((21, 30, 12), (12, 12, 8)), skin2, 0.15)
    s.paint(s.mirror(s.limb, (9, 38, 12), (6, 22, 14), 3.8, 3.4), skin, 0.15)
    s.paint(s.mirror(s.limb, (6, 22, 14), (6, 12, 16), 3.4, 3.2), skin, 0.15)
    s.paint(s.mirror(s.ell, (6, 11, 16), (3.6, 3.4, 3.6)), skin2, 0.15)
    # Ice armour: shards on shoulders, chest plate, back spines
    s.paint(s.mirror(s.ell, (8, 40, 12), (6, 4.5, 6)), "#8fd8f0", 0.1)
    for (p0, p1, r) in [((6, 42, 12), (1, 52, 10), 2.4), ((9, 43, 10), (8, 53, 6), 2.0), ((4, 40, 15), (0, 47, 19), 1.6)]:
        s.paint(s.mirror(s.spike, p0, p1, r), ice, 0.08)
    for k in range(5):
        s.paint(s.spike((21, 26 + k * 4, 4), (21, 29 + k * 4, 0), 1.8), ice, 0.08)
    s.paint(s.box((14, 26, 19), (28, 36, 21)), "#a8e6f8", 0.08)
    # Head with a frozen beard and an ice crown
    s.paint(s.ell((21, 46, 13), (6, 6, 6)), skin2, 0.12)
    s.paint(s.mirror(s.box, (17, 46, 18), (20, 48, 20)), "#7ff6ff", 0.03, glow=True)
    for x in range(16, 27, 2):
        s.paint(s.spike((x, 43, 18), (x, 35 - (x % 4), 18), 1.2), "#eafcff", 0.05)
    for k, x in enumerate(range(15, 29, 3)):
        s.paint(s.spike((x, 50, 13), (x, 55 - (k % 2) * 2, 13), 1.4), ice, 0.06)
    # A club of ice in the right hand
    s.paint(s.limb((37, 10, 16), (39, 30, 16), 1.3, 3.2), "#d6f7ff", 0.08)
    s.speckle("#ffffff", 0.2, s.Y > 36, seed=6)
    s.cracks("#7ff6ff", 14, 7, seed=10)
    return s.done("frost_titan")


def thornback_beast():
    s = Sculpt(32, 34, 50, seed=16)
    hide, hide2, bone = "#3e4a2a", "#55623a", "#d8d0b0"
    s.paint(s.ell((16, 17, 23), (11, 9, 16)), hide, 0.18)
    for (x, z) in [(8, 13), (24, 13), (8, 33), (24, 33)]:
        s.paint(s.limb((x, 14, z), (x, 2, z + 1), 4.0, 3.4), hide2, 0.15)
        s.paint(s.box((x - 3.5, 0, z - 2.5), (x + 3.5, 3, z + 4.5)), "#2a2a1e", 0.12)
        s.paint(s.path([(x - 3.5, 3, z), (x, 7, z + 3.8), (x + 3.5, 11, z), (x, 13, z - 3.8)], 0.7), "#4f8a2a", 0.1)
    # Massive head with an open jaw full of teeth, burning green eyes, curved horns
    s.paint(s.ell((16, 17, 41), (9, 8, 8)), hide2, 0.15)
    s.carve(s.box((10, 11, 44), (22, 15, 50)))
    for x in range(10, 22, 2):
        s.paint(s.spike((x + 0.5, 15, 47), (x + 0.5, 12, 47.5), 0.9), bone, 0.04)
        s.paint(s.spike((x + 1.5, 11, 47), (x + 1.5, 13.5, 47.5), 0.9), bone, 0.04)
    s.paint(s.mirror(s.box, (11, 19, 46), (14, 21, 48)), "#9bff5a", 0.03, glow=True)
    s.paint(s.mirror(s.path, [(9, 22, 40), (5, 27, 44), (6, 30, 49)], 1.6), bone, 0.06)
    # Back covered in thorns
    for k in range(26):
        x = 16 + s.rng.normal() * 4
        z = 10 + s.rng.random() * 28
        y = 17 + 9 * math.sqrt(max(0.0, 1 - ((x - 16) / 11) ** 2 - ((z - 23) / 16) ** 2)) - 1
        s.paint(s.spike((x, y, z), (x + s.rng.normal() * 1.5, y + 5 + s.rng.random() * 4, z - 2), 1.5), "#2f3a1e", 0.1)
        s.recolor(s.ell((x, y + 7, z - 2), (1.2, 2.5, 1.2)), bone, 0.05)
    # Tail ending in a thorn club
    s.paint(s.path([(16, 18, 7), (16, 16, 3), (16, 13, 0)], 2.2), hide, 0.15)
    s.speckle("#6fd05a", 0.18, s.Y > 20, glow=False, seed=4)
    s.cracks("#9bff5a", 8, 6, seed=12)
    return s.done("thornback_beast")


def hollow_watcher():
    s = Sculpt(38, 58, 20, seed=17)
    shade, shade2 = "#141019", "#221a2c"
    s.paint(s.mirror(s.limb, (15, 22, 10), (14, 2, 10), 2.4, 2.0), shade, 0.12)
    s.paint(s.ell((19, 33, 10), (8, 12, 6)), shade2, 0.12)
    s.carve(s.mirror(s.box, (13, 28, 15), (18, 29, 17)) | s.mirror(s.box, (13, 31, 15), (18, 32, 17)))
    # Torn shroud hanging from the shoulders
    for x in range(8, 31):
        bottom = 8 + int(abs(math.sin(x * 1.3)) * 8)
        s.paint(s.box((x, bottom, 4), (x + 1, 42, 6)), "#0e0b12", 0.08)
    # Very long arms ending in long claws
    s.paint(s.mirror(s.limb, (11, 41, 10), (5, 24, 12), 2.2, 1.8), shade, 0.1)
    s.paint(s.mirror(s.limb, (5, 24, 12), (4, 10, 14), 1.8, 1.6), shade, 0.1)
    for k in range(3):
        s.paint(s.mirror(s.spike, (3 + k * 1.4, 10, 14), (2 + k * 1.6, 1, 17), 0.9), "#3a2a4a", 0.05)
    # Long head with many glowing eyes and a crown of thorns
    s.paint(s.ell((19, 48, 10), (5, 8, 5)), shade, 0.08)
    for (x, y) in [(17, 50), (21, 50), (19, 47), (16, 45), (22, 45), (19, 53), (18, 43)]:
        s.paint(s.box((x, y, 14), (x + 1.5, y + 1.2, 15.5)), "#c97bff", 0.03, glow=True)
    for k in range(7):
        a = k / 6 * math.pi
        s.paint(s.spike((19 + math.cos(a) * 4, 54, 10), (19 + math.cos(a) * 7, 58, 10 - math.sin(a) * 2), 1.0), "#2a1f36", 0.05)
    s.cracks("#8a4cff", 16, 8, seed=13)
    return s.done("hollow_watcher")


# ================================================================== guards

def humanoid(name, h, skin, skin2, eye, seed, extras=None, glowcracks=None):
    w, d = 20, 12
    s = Sculpt(w, h, d, seed=seed)
    leg = h * 0.42
    s.paint(s.mirror(s.limb, (7, leg, 6), (7, 1, 6), 2.0, 1.8), skin2, 0.15)
    s.paint(s.ell((10, h * 0.6, 6), (5.5, h * 0.17, 3.6)), skin, 0.15)
    # Long hanging arms with claws
    s.paint(s.mirror(s.limb, (4, h * 0.73, 6), (3, h * 0.33, 8), 1.6, 1.3), skin2, 0.15)
    for k in range(3):
        s.paint(s.mirror(s.spike, (2.2 + k * 0.9, h * 0.33, 8), (1.8 + k * 1.1, h * 0.25, 9.5), 0.6), "#d8d0b8", 0.05)
    # Head pushed forward (hunched)
    s.paint(s.ell((10, h * 0.85, 7.5), (3.9, 3.9, 3.9)), skin, 0.12)
    sk = hexc(skin2) * 0.55
    s.speckle(sk, 0.08, seed=seed)
    if extras:
        extras(s, h)
    # Face last: deep sockets with glowing eyes, a jaw of teeth
    s.carve(s.mirror(s.box, (7, h * 0.86, 10), (9.5, h * 0.86 + 2, 12)))
    s.paint(s.mirror(s.box, (7.5, h * 0.86 + 0.3, 10), (9.2, h * 0.86 + 1.5, 10.8)), eye, 0.02, glow=True)
    s.carve(s.box((8, h * 0.78, 10), (12, h * 0.78 + 1.6, 12)))
    s.paint(s.box((8, h * 0.78, 9.6), (12, h * 0.78 + 1.6, 10)), "#120e14", 0.03)
    for x in (8, 9.5, 11):
        s.paint(s.box((x, h * 0.78 + 0.9, 10), (x + 0.8, h * 0.78 + 1.6, 10.8)), "#e8e0c8", 0.03)
    if glowcracks:
        s.cracks(glowcracks, 6, 5, seed=seed)
    return s.done(name)


def vault_drowned():
    def ex(s, h):
        for (p0, p1, c) in [((5, h * 0.75, 6), (2, h * 0.95, 4), "#ff5a8a"), ((14, h * 0.7, 5), (17, h * 0.9, 3), "#3f7bff"),
                            ((10, h * 0.95, 5), (11, h, 4), "#ffb02e")]:
            s.paint(s.spike(p0, p1, 1.3), c, 0.1)
        s.speckle("#2f6b4a", 0.3, seed=2)
    return humanoid("vault_drowned", 32, "#3a7a78", "#2a5a5a", "#5ff0ff", 21, ex)


def sky_sentry():
    def ex(s, h):
        s.recolor(s.Y > h * 0.5, "#e8e2cc", 0.06)
        s.paint(s.ell((10, h * 0.94, 6.5), (4.2, 1.6, 4.2)), "#d9a52e", 0.08)
        s.paint(s.spike((10, h * 0.97, 5), (10, h + 2, 2), 1.4), "#c8f0ff", 0.05)
    return humanoid("sky_sentry", 32, "#d8d4c4", "#b8b4a4", "#ffe36b", 22, ex)


def forge_brute():
    def ex(s, h):
        s.paint(s.box((7, h * 0.86, 9.5), (13, h * 0.86 + 2.5, 12)), "#c47e74", 0.1)
        s.paint(s.mirror(s.spike, (7.5, h * 0.84, 10), (6.5, h * 0.93, 11.5), 0.8), "#efe8d0", 0.04)
        s.paint(s.ell((10, h * 0.93, 6.5), (4.3, 1.6, 4.3)), "#5a5e66", 0.1)
    return humanoid("forge_brute", 32, "#9a5a52", "#5a3a2e", "#ff7a1a", 23, ex, "#ff7a1a")


def tomb_husk():
    def ex(s, h):
        s.bands(s.solid, ["#c9b183", "#a8905f"], 1.5)
    return humanoid("tomb_husk", 32, "#c9b183", "#a8905f", "#ffd23f", 24, ex)


def ice_stray():
    def ex(s, h):
        s.recolor(s.solid, "#b8d8e0", 0.08)
        for (p0, p1) in [((5, h * 0.78, 5), (2, h * 0.92, 3)), ((15, h * 0.78, 5), (18, h * 0.92, 3)), ((10, h * 0.95, 6), (10, h + 1, 6))]:
            s.paint(s.spike(p0, p1, 1.2), "#bff4ff", 0.05)
    return humanoid("ice_stray", 32, "#c8dce4", "#98b8c4", "#7ff6ff", 25, ex)


def crawler(name, w, h, d, shell, shell2, eye, seed, legs=6, legcol=None, spines=None):
    s = Sculpt(w, h, d, seed=seed)
    s.paint(s.ell((w / 2, h * 0.5, d * 0.45), (w * 0.36, h * 0.42, d * 0.4)), shell, 0.15)
    s.paint(s.ell((w / 2, h * 0.45, d * 0.85), (w * 0.24, h * 0.33, d * 0.16)), shell2, 0.12)
    s.paint(s.mirror(s.box, (w / 2 - w * 0.17, h * 0.5, d * 0.97 - 1.2), (w / 2 - w * 0.06, h * 0.5 + max(1, h * 0.12), d)), eye, 0.03, glow=True)
    for i in range(legs // 2):
        z = d * (0.25 + 0.5 * i / max(1, legs // 2 - 1))
        s.paint(s.mirror(s.path, [(w / 2 - w * 0.25, h * 0.45, z), (0.8, h * 0.6, z + 0.5), (0.5, 0.2, z + 1.5)], max(0.6, w * 0.035)),
                legcol or shell2, 0.1)
    if spines:
        for k in range(10):
            x = w / 2 + s.rng.normal() * w * 0.12
            z = d * (0.15 + s.rng.random() * 0.55)
            s.paint(s.spike((x, h * 0.8, z), (x, h - 0.2, z - 1), max(0.8, w * 0.05)), spines, 0.08)
    return s.done(name)


def stone_crawler():
    return crawler("stone_crawler", 12, 7, 16, "#5d5d68", "#4b4b55", "#ff3d3d", 31, legs=6, spines="#4f7a3a")


def scarab():
    return crawler("scarab", 12, 7, 16, "#b07a10", "#3a2a10", "#2ad6a0", 32, legs=6, legcol="#2a1e0a")


def sculk_lurker():
    return crawler("sculk_lurker", 22, 9, 20, "#0f2a33", "#123a44", "#5ff0ff", 33, legs=8, legcol="#0a1c22", spines="#2ad6c0")


def jungle_stalker():
    return crawler("jungle_stalker", 28, 13, 26, "#2f3a1e", "#3e4a2a", "#ff3d3d", 34, legs=8, legcol="#1f2a14", spines="#6fd05a")


def vine_creeper():
    return crawler("vine_creeper", 20, 9, 18, "#3e6a2a", "#2f5a1e", "#ffe36b", 35, legs=8, legcol="#2a4a1a", spines="#9bff5a")


def gale_phantom():
    s = Sculpt(36, 10, 24, seed=41)
    s.paint(s.ell((18, 5, 11), (3.5, 3, 8)), "#2a3348", 0.15)
    s.paint(s.ell((18, 5.5, 20), (3, 2.5, 3)), "#cfd2c4", 0.1)
    s.paint(s.mirror(s.box, (16.5, 6, 22.5), (17.5, 7, 23.5)), "#fff36b", 0.03, glow=True)
    for side in (-1, 1):
        for x in range(0, 15):
            xx = 18 + side * (3 + x)
            m = (np.abs(s.X - (xx + 0.5)) < 0.6) & (np.abs(s.Y - (6 - x * 0.15)) < 0.8) & (s.Z > 3 + x * 0.3 + 2 * math.sin(x)) & (s.Z < 16 - x * 0.2)
            s.paint(m, "#33405e", 0.18)
    s.paint(s.spike((18, 5, 3), (18, 6, 0), 1.8), "#2a3348", 0.1)
    return s.done("gale_phantom")


def ember_imp():
    s = Sculpt(12, 13, 11, seed=42)
    s.paint(s.ell((6, 5.5, 5.5), (5, 5, 4.5)), "#5a1e10", 0.15)
    s.cracks("#ff7a1a", 8, 4, seed=1)
    s.paint(s.mirror(s.box, (3.5, 6.5, 9), (5, 8, 10.5)), "#ffe36b", 0.03, glow=True)
    s.carve(s.box((4, 3, 9), (8, 4.2, 11)))
    s.paint(s.mirror(s.spike, (3, 9, 5), (1.5, 13, 4), 1.2), "#2a1a14", 0.08)
    return s.done("ember_imp")


def frostbite_bear():
    s = Sculpt(18, 22, 30, seed=43)
    fur, fur2 = "#e8f0f4", "#c8d8e0"
    s.paint(s.ell((9, 12, 14), (7.5, 7, 11)), fur, 0.1)
    for (x, z) in [(5, 7), (13, 7), (5, 21), (13, 21)]:
        s.paint(s.limb((x, 9, z), (x, 1, z), 2.6, 2.4), fur2, 0.1)
    s.paint(s.ell((9, 13, 26), (5, 5, 4.5)), fur, 0.1)
    s.paint(s.box((7, 10, 29), (11, 13, 30)), "#2a2a30", 0.05)
    s.paint(s.mirror(s.box, (6, 14, 29), (7.5, 15.2, 30)), "#7ff6ff", 0.03, glow=True)
    s.paint(s.mirror(s.ell, (5.5, 17.5, 25), (1.4, 1.4, 1)), fur2, 0.1)
    for k in range(8):
        z = 6 + k * 2.5
        s.paint(s.spike((9, 18, z), (9 + s.rng.normal(), 22, z - 1), 1.2), "#bff4ff", 0.05)
    return s.done("frostbite_bear")


def shadow_echo():
    s = Sculpt(14, 18, 10, seed=44)
    s.paint(s.ell((7, 11, 5), (3.5, 5, 2.5)), "#141019", 0.1)
    s.paint(s.ell((7, 15.5, 5), (2.6, 2.6, 2.4)), "#1a1424", 0.08)
    s.paint(s.mirror(s.box, (5.5, 15.5, 7.2), (6.5, 16.5, 7.8)), "#c97bff", 0.03, glow=True)
    s.paint(s.spike((7, 7, 5), (7, 0, 3), 2.6), "#0e0b12", 0.08)
    s.paint(s.mirror(s.path, [(4, 12, 5), (1, 9, 6), (0.5, 6, 7)], 0.8), "#141019", 0.08)
    s.cracks("#8a4cff", 4, 4, seed=2)
    return s.done("shadow_echo")


MODELS = {
    "deepslate_colossus": (deepslate_colossus, 4.3), "storm_phantom": (storm_phantom, 2.4), "forgemaster": (forgemaster, 3.7),
    "sand_colossus": (sand_colossus, 4.7), "frost_titan": (frost_titan, 4.8), "thornback_beast": (thornback_beast, 3.1),
    "hollow_watcher": (hollow_watcher, 4.8),
    "vault_drowned": (vault_drowned, 1.95), "sky_sentry": (sky_sentry, 2.0), "forge_brute": (forge_brute, 1.95),
    "tomb_husk": (tomb_husk, 1.95), "ice_stray": (ice_stray, 2.0), "stone_crawler": (stone_crawler, 0.4),
    "scarab": (scarab, 0.4), "sculk_lurker": (sculk_lurker, 0.55), "jungle_stalker": (jungle_stalker, 0.95),
    "vine_creeper": (vine_creeper, 0.55), "gale_phantom": (gale_phantom, 0.6), "ember_imp": (ember_imp, 0.6),
    "frostbite_bear": (frostbite_bear, 1.4), "shadow_echo": (shadow_echo, 0.85),
}
# Made from .glb files by voxelize.py (target height in blocks).
IMPORTED = {"drowned_warden": 3.3}


def main(names):
    for n in names or MODELS:
        MODELS[n][0]()
    specs = {}
    for n, (_, h) in MODELS.items():
        if os.path.exists(os.path.join(voxmesh.OUT, n + ".json")):
            specs[n] = voxmesh.spec(n, h)
    for n, h in IMPORTED.items():
        if os.path.exists(os.path.join(voxmesh.OUT, n + ".json")):
            specs[n] = voxmesh.spec(n, h)
    path = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                        "src", "main", "resources", "vigil", "models.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(specs, f, indent=1, sort_keys=True)
    print("specs:", len(specs))


if __name__ == "__main__":
    main(sys.argv[1:])
