"""Block-style models for the bosses and their guards, built like vanilla mobs.

Every model is a set of boxes. Each side of each box gets its own small hand-painted pixel texture (one texel per
design unit), so the mobs look like real Minecraft mobs: clean shapes, a few tones per material and readable faces.
All textures of one model are packed into one atlas.

Design coordinates: x centred on 0 (+x is the mob's left), y up from the ground (0), z centred on 0, the face looks
toward +z. Models are scaled down automatically to fit Minecraft's -16..32 limit.

    python3 scripts/models/cubes.py            # builds every model + src/main/resources/vigil/models.json
    python3 scripts/models/cubes.py frost_titan  # builds only that one (models.json still lists all built ones)
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "built")
ROOT = os.path.dirname(os.path.dirname(HERE))
ANGLES = (-45, -22.5, 0, 22.5, 45)
SIDES = ("north", "south", "east", "west", "up", "down")


def C(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], float)


# ----------------------------------------------------------------------------------------------------------------------
# Materials: four tones (shadow, base, light, highlight) arranged in small clusters, plus an optional pattern.


class Mat:
    def __init__(self, ramp, pattern="plain", cell=2, mix=(0.18, 0.62, 0.16, 0.04), top=None, top_rows=1,
                 veins=None, vein_density=0.0, flat=False):
        self.ramp = [C(c) for c in ramp]
        self.pattern = pattern
        self.cell = cell
        self.mix = np.cumsum(mix)[:-1]
        self.top = [C(c) for c in top] if top else None
        self.top_rows = top_rows
        self.veins = C(veins) if veins else None
        self.vein_density = vein_density
        self.flat = flat

    def tones(self, w, h, rng):
        cell = self.cell
        gh, gw = h // cell + 2, w // cell + 2
        v = rng.random((gh, gw))
        oy, ox = rng.integers(0, cell), rng.integers(0, cell)
        big = np.repeat(np.repeat(v, cell, 0), cell, 1)[oy:oy + h, ox:ox + w]
        idx = np.digitize(big, self.mix)
        # A few single pixels so it doesn't look blotchy.
        sp = rng.random((h, w))
        idx = np.where(sp < 0.03, np.clip(idx - 1, 0, 3), idx)
        idx = np.where(sp > 0.98, np.clip(idx + 1, 0, 3), idx)
        if self.flat:
            idx = np.where(idx == 0, 1, idx)
        return idx

    def paint(self, side, w, h, rng):
        idx = self.tones(w, h, rng)
        r, c = np.mgrid[0:h, 0:w]
        p = self.pattern
        vertical = side not in ("up", "down")
        if p == "brick":
            row = r % 6 == 5
            off = ((r // 6) % 2) * 4
            col = (c + off) % 9 == 8
            idx = np.where(row | col, 0, np.maximum(idx, 1))
        elif p == "bands" and vertical:
            shift = (c * 0.25).astype(int) if rng.random() < 0.5 else (-c * 0.25).astype(int)
            k = (r + shift + rng.integers(0, 4)) % 4
            idx = np.where(k == 0, 2, np.where(k == 3, 0, idx))
        elif p == "plates":
            edge = (r == 0) | (c == 0) | (r == h - 1) | (c == w - 1)
            idx = np.where(edge, 0, np.minimum(idx, 2))
            idx = np.where((r == 1) & ~edge, 3, idx)
            if w >= 5 and h >= 5:
                for (yy, xx) in ((1, 1), (1, w - 2), (h - 2, 1), (h - 2, w - 2)):
                    idx[yy, xx] = 3
        elif p == "scales":
            k = (c + (r // 2 % 2) * 2) % 4
            idx = np.where((r % 2 == 1) & (k == 0), 0, idx)
            idx = np.where((r % 2 == 0) & (k == 2), np.maximum(idx, 2), idx)
        elif p == "fur" and vertical:
            streak = rng.random(w) < 0.35
            idx = np.where(streak[None, :] & (rng.random((h, w)) < 0.7), np.clip(idx - 1, 0, 3), idx)
        elif p == "bark" and vertical:
            lines = rng.random(w) < 0.3
            idx = np.where(lines[None, :], 0, idx)
        elif p == "ribs" and vertical:
            idx = np.where((r % 3 == 2) & (c > 0) & (c < w - 1), 0, idx)
            idx = np.where((r % 3 == 0), np.maximum(idx, 2), idx)
        # Simple light: a lit top row, a dark bottom row on the sides.
        if vertical and h >= 3:
            idx[0] = np.maximum(idx[0], 2)
            idx[-1] = np.minimum(idx[-1], 1)
            idx[-1] = np.where(rng.random(w) < 0.5, 0, idx[-1])
        img = np.stack([self.ramp[i] for i in range(4)])[idx]
        if self.top is not None:
            tops = np.stack(self.top)[np.clip(self.tones(w, h, rng), 0, 3)]
            if side == "up":
                img = tops
            elif vertical:
                drip = self.top_rows + (rng.random(w) < 0.3) * rng.integers(1, 3, w)
                m = r < drip[None, :]
                img = np.where(m[..., None], tops, img)
        if self.veins is not None and self.vein_density > 0 and side != "down":
            n = int(w * h * self.vein_density / 12)
            if rng.random() < w * h * self.vein_density / 12 - n:
                n += 1
            for _ in range(n):
                y, x = rng.integers(0, h), rng.integers(0, w)
                d = rng.choice([(0, 1), (0, -1), (1, 0), (-1, 0)])
                for _ in range(rng.integers(3, 9)):
                    img[y, x] = self.veins
                    if rng.random() < 0.35:
                        d = rng.choice([(0, 1), (0, -1), (1, 0), (-1, 0)])
                    y, x = y + d[0], x + d[1]
                    if not (0 <= y < h and 0 <= x < w):
                        break
        return img


# ----------------------------------------------------------------------------------------------------------------------
# Decals: drawn on top of a face texture (img is h x w x 3, row 0 at the top, column 0 at the mob's right).


def face(eye, eyes=2, eye_w=3, eye_h=2, eye_row=0.35, gap=None, mask=0.35, brow="#000000", angry=True,
         mouth=None, mouth_row=0.68, mouth_w=None, mouth_h=3, teeth=None, nose=None, socket=None, fangs=None, core=None):
    """A menacing face: glowing slanted eyes in a shadowed brow band, and a jaw full of jagged teeth.
    (socket / fangs / core are accepted for older calls.)"""
    eye = C(eye)
    hot = eye * 0.45 + 255 * 0.55

    def draw(img):
        h, w = img.shape[:2]
        ey = int(round(h * eye_row))
        g = gap if gap is not None else max(1, int(round(w * 0.16)))
        if eyes == 0:
            xs = []
        elif eyes == 1:
            xs = [(w - eye_w) // 2]
        else:
            left = (w - g) // 2 - eye_w
            xs = [left, w - left - eye_w]
        lo_x, hi_x = (max(0, min(xs) - 1), min(w, max(xs) + eye_w + 1)) if xs else (0, 0)
        if mask is not None and xs:
            img[max(0, ey - 1):min(h, ey + eye_h + 1), lo_x:hi_x] *= mask
        for x in xs:
            # Glow spilling into the socket.
            y0, y1, x0, x1 = max(0, ey - 1), min(h, ey + eye_h + 1), max(0, x - 1), min(w, x + eye_w + 1)
            img[y0:y1, x0:x1] = img[y0:y1, x0:x1] * 0.6 + eye * 0.4 * 0.6
            img[ey:ey + eye_h, x:x + eye_w] = eye
            inner = x + eye_w - 1 if x + eye_w / 2 < w / 2 else x
            if eyes == 1:
                img[ey + eye_h // 2 - (eye_h > 2):ey + eye_h // 2 + 1, x + eye_w // 2 - (eye_w > 2):x + eye_w // 2 + 1] = hot
            else:
                img[ey + eye_h - 1, inner] = hot
                if angry and eye_h >= 2:
                    img[ey, inner] = C(brow)
            if brow is not None and ey - 1 >= 0:
                img[ey - 1, x0:x1] = C(brow)
        if nose is not None:
            ny = ey + eye_h + 1
            if ny < h:
                img[ny, w // 2 - 1:w // 2 + 1] = C(nose)
        if mouth is not None:
            mw = mouth_w if mouth_w else max(2, int(round(w * 0.6)))
            my = min(h - mouth_h, int(round(h * mouth_row)))
            x0 = (w - mw) // 2
            img[my:my + mouth_h, x0:x0 + mw] = C(mouth)
            if teeth is not None:
                t = C(teeth)
                for i in range(mw):
                    if i % 2 == 0:
                        img[my, x0 + i] = t
                    elif mouth_h > 2:
                        img[my + mouth_h - 1, x0 + i] = t
                if mouth_h >= 3:
                    img[my:my + 2, x0] = t
                    img[my:my + 2, x0 + mw - 1] = t
        return img
    return draw


def spot(color, x, y, w=1, h=1, ring=None):
    """A glowing gem / core: a rectangle at relative position (x, y) of the face, with an optional ring."""
    col = C(color)

    def draw(img):
        hh, ww = img.shape[:2]
        x0, y0 = int(round(ww * x - w / 2)), int(round(hh * y - h / 2))
        if ring is not None:
            img[max(0, y0 - 1):y0 + h + 1, max(0, x0 - 1):x0 + w + 1] = C(ring)
        img[y0:y0 + h, x0:x0 + w] = col
        return img
    return draw


def stripes(color, every=3, vertical=True, width=1):
    col = C(color)

    def draw(img):
        h, w = img.shape[:2]
        if vertical:
            for x in range(1, w - 1, every):
                img[:, x:x + width] = col
        else:
            for y in range(1, h - 1, every):
                img[y:y + width] = col
        return img
    return draw


def chain(*fns):
    def draw(img):
        for f in fns:
            img = f(img)
        return img
    return draw


# ----------------------------------------------------------------------------------------------------------------------


def shade(img, side):
    """Painted light like a hand-made mob texture: lit top edge, soft gradient down the side, dark bottom and corners."""
    h, w = img.shape[:2]
    if side in ("up", "down"):
        f = np.full((h, w), 1.06 if side == "up" else 0.72)
        if h > 2 and w > 2:
            f[0, :] *= 0.92
            f[-1, :] *= 0.92
            f[:, 0] *= 0.92
            f[:, -1] *= 0.92
    else:
        f = np.repeat(np.linspace(1.08, 0.80, h)[:, None], w, 1) if h > 1 else np.ones((h, w))
        if h > 2:
            f[0, :] *= 1.10
            f[-1, :] *= 0.85
        if w > 2:
            f[:, 0] *= 0.90
            f[:, -1] *= 0.90
    return img * f[..., None]


class Model:
    """A model made of named parts. Each part turns around its own pivot in game (head, jaw, arms, legs, wings, tail).

    m.part("arm", pivot, "arm", mirror=True) starts a part pair: every box added while it's current is added to
    arm_r as given and, mirrored, to arm_l.
    """

    def __init__(self, name, seed=0):
        self.name = name
        self.boxes = []
        self.parts = {}
        self.order = []
        self.rng = np.random.default_rng(seed or sum(map(ord, name)))
        self.part("body", (0, 0, 0), "body")

    def part(self, name, pivot, role="static", parent=None, mirror=False, phase=None):
        self.cur = (name, mirror)
        pv = np.array(pivot, float)
        if mirror:
            for sfx, sx in (("_r", 1), ("_l", -1)):
                par = parent + sfx if parent and self.parts.get(parent + sfx) else parent
                ph = None if phase is None else phase + (math.pi if sx == -1 else 0.0)
                self._add_part(name + sfx, pv * [sx, 1, 1], role, par, -1 if sx == 1 else 1, ph)
        else:
            self._add_part(name, pv, role, parent, 0, phase)
        return self

    def use(self, name):
        """Switches back to a part made earlier."""
        self.cur = (name, name not in self.parts and name + "_r" in self.parts)
        return self

    def _add_part(self, name, pivot, role, parent, side, phase=None):
        if name not in self.parts:
            self.order.append(name)
        if parent is None and role not in ("body", "leg", "leg_front", "leg_back") and name != "body":
            parent = "body"
        self.parts[name] = dict(pivot=pivot, role=role, parent=parent, side=side, phase=phase)

    def _put(self, frm, size, mat, rot, decal, skip, part):
        frm = np.array(frm, float)
        size = np.array(size, float)
        if rot is not None:
            assert rot[1] in ANGLES, rot
            if len(rot) == 2:
                rot = (rot[0], rot[1], tuple(frm + size / 2))
        self.boxes.append(dict(frm=frm, to=frm + size, mat=mat, rot=rot, decal=decal or {}, skip=set(skip), part=part))

    @staticmethod
    def _mirror(frm, size, rot, decal, skip):
        frm = np.array(frm, float)
        size = np.array(size, float)
        mfrm = (-(frm[0] + size[0]), frm[1], frm[2])
        mrot = None
        if rot is not None:
            axis, ang = rot[0], rot[1]
            pivot = rot[2] if len(rot) > 2 else tuple(frm + size / 2)
            if axis in ("y", "z"):
                ang = -ang
            mrot = (axis, ang, (-pivot[0], pivot[1], pivot[2]))
        flip = {"east": "west", "west": "east"}
        mdec = {flip.get(s, s): (lambda f: lambda img: f(img[:, ::-1])[:, ::-1])(f) for s, f in (decal or {}).items()}
        return mfrm, size, mrot, mdec, {flip.get(s, s) for s in skip}

    def box(self, frm, size, mat, rot=None, decal=None, skip=()):
        """rot = (axis, angle) about the box centre, or (axis, angle, pivot). decal = {side: fn}."""
        name, mirror = self.cur
        if mirror:
            return self.pair(frm, size, mat, rot, decal, skip)
        self._put(frm, size, mat, rot, decal, skip, name)
        return self

    def pair(self, frm, size, mat, rot=None, decal=None, skip=()):
        """A box and its mirror image on the other side (x -> -x)."""
        name, mirror = self.cur
        self._put(frm, size, mat, rot, decal, skip, name + "_r" if mirror else name)
        mfrm, msize, mrot, mdec, mskip = self._mirror(frm, size, rot, decal, skip)
        self._put(mfrm, msize, mat, mrot, mdec, mskip, name + "_l" if mirror else name)
        return self

    # -- export ----------------------------------------------------------------------------------------------------

    def _corners(self, b):
        lo, hi = b["frm"], b["to"]
        pts = np.array([[x, y, z] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])])
        if b["rot"] is not None:
            pts = rotate(pts, *b["rot"])
        return pts

    def build(self, height_blocks):
        used = [p for p in self.order if any(b["part"] == p for b in self.boxes)]
        # Every part's boxes, measured from its pivot, must fit in -24..24 around the model centre (8).
        dev = max(np.abs(self._corners(b) - self.parts[b["part"]]["pivot"]).max() for b in self.boxes)
        k = min(1.0, 23.5 / dev)
        allpts = np.concatenate([self._corners(b) for b in self.boxes])
        ground, top = allpts[:, 1].min(), allpts[:, 1].max()

        faces = []
        for i, b in enumerate(self.boxes):
            sx, sy, sz = (b["to"] - b["frm"])
            dims = {"north": (sx, sy), "south": (sx, sy), "east": (sz, sy), "west": (sz, sy), "up": (sx, sz), "down": (sx, sz)}
            for side in SIDES:
                if side in b["skip"]:
                    continue
                w, h = (max(1, int(round(v))) for v in dims[side])
                img = shade(b["mat"].paint(side, w, h, self.rng).astype(float), side)
                if side in b["decal"]:
                    img = b["decal"][side](img)
                faces.append((i, side, np.clip(img, 0, 255).astype(np.uint8)))
        atlas, where = pack([f[2] for f in faces])
        S = atlas.shape[0]
        per_part = {p: {} for p in used}
        for (i, side, img), (x, y) in zip(faces, where):
            b = self.boxes[i]
            pv = self.parts[b["part"]]["pivot"]

            def T(q):
                return ((np.array(q) - pv) * k + 8).round(4).tolist()
            h, w = img.shape[:2]
            uv = [round(v * 16 / S, 4) for v in (x, y, x + w, y + h)]
            el = per_part[b["part"]].setdefault(i, {"from": T(b["frm"]), "to": T(b["to"]), "faces": {}})
            if b["rot"] is not None:
                el["rotation"] = {"origin": T(b["rot"][2]), "axis": b["rot"][0], "angle": b["rot"][1]}
            el["faces"][side] = {"uv": uv, "texture": "#p"}
        os.makedirs(OUT, exist_ok=True)
        for f in os.listdir(OUT):
            if f in (self.name + ".png", self.name + ".json") or f.startswith(self.name + "__"):
                os.remove(os.path.join(OUT, f))
        Image.fromarray(atlas).save(os.path.join(OUT, self.name + ".png"))
        scale = height_blocks / ((top - ground) * k / 16.0)
        parts = []
        for idx, p in enumerate(used):
            info = self.parts[p]
            model = {"textures": {"p": "vigil:item/" + self.name, "particle": "vigil:item/" + self.name},
                     "elements": [per_part[p][i] for i in sorted(per_part[p])]}
            with open(os.path.join(OUT, f"{self.name}__{p}.json"), "w") as f:
                json.dump(model, f, separators=(",", ":"))
            pv = info["pivot"]
            parent = info["parent"] if info["parent"] in used else None
            # Walking legs: right and left opposite, front and back pairs opposite, extra pairs alternate.
            phase = 0.0
            if info["phase"] is not None:
                phase = info["phase"]
            elif info["role"].startswith("leg"):
                phase = (math.pi if info["side"] > 0 else 0.0) + (math.pi if info["role"] == "leg_back" else 0.0)
            elif info["role"] in ("arm", "tentacle"):
                phase = (0.0 if info["side"] > 0 else math.pi) + idx * 0.9 * (info["role"] == "tentacle")
            parts.append({"id": f"{self.name}__{p}", "role": info["role"], "side": info["side"],
                          "parent": f"{self.name}__{parent}" if parent else None, "phase": round(phase, 3),
                          "pivot": [round(pv[0] * k / 16, 4), round((pv[1] - ground) * k / 16, 4), round(pv[2] * k / 16, 4)]})
        print(f"{self.name}: {len(self.boxes)} boxes in {len(used)} parts, atlas {S}x{S}, k {k:.3f}")
        return {"scale": round(scale, 4), "parts": parts}


def rotate(pts, axis, ang, pivot):
    a = math.radians(ang)
    c, s = math.cos(a), math.sin(a)
    p = np.array(pts, float) - np.array(pivot)
    x, y, z = p[:, 0].copy(), p[:, 1].copy(), p[:, 2].copy()
    if axis == "x":
        y, z = y * c - z * s, y * s + z * c
    elif axis == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return np.stack([x, y, z], 1) + np.array(pivot)


def pack(imgs):
    """Shelf packing with a 1-pixel border copied from each image's edge (stops colours bleeding)."""
    order = sorted(range(len(imgs)), key=lambda i: -imgs[i].shape[0])
    S = 32
    while True:
        where, x, y, shelf, ok = [None] * len(imgs), 0, 0, 0, True
        for i in order:
            h, w = imgs[i].shape[:2]
            if x + w + 2 > S:
                x, y, shelf = 0, y + shelf, 0
            if y + h + 2 > S or w + 2 > S:
                ok = False
                break
            where[i] = (x + 1, y + 1)
            x += w + 2
            shelf = max(shelf, h + 2)
        if ok:
            break
        S *= 2
    atlas = np.zeros((S, S, 4), np.uint8)
    for img, (x, y) in zip(imgs, where):
        h, w = img.shape[:2]
        p = np.pad(img, ((1, 1), (1, 1), (0, 0)), mode="edge")
        atlas[y - 1:y + h + 1, x - 1:x + w + 1, :3] = p
        atlas[y - 1:y + h + 1, x - 1:x + w + 1, 3] = 255
    return atlas, where


def main(names):
    from designs import MODELS
    path = os.path.join(ROOT, "src", "main", "resources", "vigil", "models.json")
    specs = json.load(open(path)) if os.path.exists(path) and names else {}
    for n in names or MODELS:
        fn, h = MODELS[n]
        specs[n] = fn().build(h)
    specs = {n: specs[n] for n in MODELS if n in specs}
    with open(path, "w") as f:
        json.dump(specs, f, indent=1, sort_keys=True)
    print("specs:", len(specs))


if __name__ == "__main__":
    sys.path.insert(0, HERE)
    main(sys.argv[1:])
