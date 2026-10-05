"""Shared voxel -> Minecraft model code (used by voxelize.py for .glb files and sculpt.py for hand-made models).

A model is a grid of voxels (solid mask + RGB colour, plus an optional "glowing" mask that skips shading).
It gets baked shading, a palette texture (up to 1024 colours) and every visible side of the voxel surface merged
into as few flat one-colour rectangles as possible.
"""
import json
import os

import numpy as np
from PIL import Image
from scipy import ndimage

PAL_SIDE = 32
PAL_N = PAL_SIDE * PAL_SIDE
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "built")


def nearest(cols, c, chunk=4096):
    out = np.empty(len(cols), int)
    cn = (c ** 2).sum(1)
    for i in range(0, len(cols), chunk):
        x = cols[i:i + chunk]
        d = cn[None, :] - 2 * x @ c.T
        out[i:i + chunk] = d.argmin(1)
    return out


def kmeans(cols, k, iters=10):
    rng = np.random.default_rng(3)
    k = min(k, len(cols))
    c = cols[rng.choice(len(cols), k, replace=False)].copy()
    lab = None
    for _ in range(iters):
        lab = nearest(cols, c)
        sums = np.zeros_like(c)
        cnt = np.bincount(lab, minlength=k).astype(float)
        np.add.at(sums, lab, cols)
        nz = cnt > 0
        c[nz] = sums[nz] / cnt[nz][:, None]
    return c, lab


def shade(solid, color, glow=None, strength=1.0):
    """Darker in creases, lighter on edges, a touch darker low down. Glowing voxels are left as they are."""
    dims = solid.shape
    occ = ndimage.uniform_filter(solid.astype(float), size=5, mode="constant")
    light = np.clip(1.0 + (0.35 - occ * 0.75) * strength, 0.55, 1.15)
    height_fade = 0.80 + 0.20 * (np.arange(dims[1]) / max(1, dims[1] - 1))
    out = color * light[..., None] * height_fade[None, :, None, None]
    if glow is not None:
        out[glow] = color[glow]
    return np.clip(out, 0, 255)


def finalize(name, solid, color, glow=None, shading=1.0):
    """Writes built/<name>.json (model) and built/<name>.png (palette). Returns the model's y range in units."""
    dims = np.array(solid.shape)
    color = shade(solid, color.astype(float), glow, shading)
    # Only the surface is visible.
    inner = ndimage.binary_erosion(solid)
    surface = solid & ~inner
    vis = color[surface]
    sample = vis[np.random.default_rng(0).choice(len(vis), min(len(vis), 120000), replace=False)]
    uniq = np.unique(sample.round(), axis=0)
    if len(uniq) <= PAL_N:
        pal = uniq.astype(float)
    else:
        pal, _ = kmeans(sample, PAL_N)
    lab = np.full(tuple(dims), -1, int)
    lab[solid] = nearest(color[solid], pal)

    X, Y, Z = dims
    elements = []
    dirs = {"east": (0, 1), "west": (0, -1), "up": (1, 1), "down": (1, -1), "south": (2, 1), "north": (2, -1)}
    padded = np.pad(solid, 1)
    for face, (axis, sgn) in dirs.items():
        a1, a2 = [a for a in range(3) if a != axis]
        # Visible faces: solid here, empty on the facing side.
        shift = [slice(1, -1)] * 3
        shift[axis] = slice(1 + sgn, dims[axis] + 1 + sgn)
        exposed = solid & ~padded[tuple(shift)]
        for layer in range(dims[axis]):
            sl = [slice(None)] * 3
            sl[axis] = layer
            ex = exposed[tuple(sl)]
            if not ex.any():
                continue
            mask = np.where(ex, lab[tuple(sl)], -1)
            used = np.zeros_like(mask, bool)
            n1, n2 = mask.shape
            for u in range(n1):
                for v in range(n2):
                    if mask[u, v] < 0 or used[u, v]:
                        continue
                    col = mask[u, v]
                    w = 1
                    while v + w < n2 and mask[u, v + w] == col and not used[u, v + w]:
                        w += 1
                    h = 1
                    while u + h < n1 and (mask[u + h, v:v + w] == col).all() and not used[u + h, v:v + w].any():
                        h += 1
                    used[u:u + h, v:v + w] = True
                    frm, to = [0, 0, 0], [0, 0, 0]
                    plane = layer + (1 if sgn > 0 else 0)
                    frm[axis] = to[axis] = plane
                    frm[a1], to[a1] = u, u + h
                    frm[a2], to[a2] = v, v + w
                    elements.append((face, frm, to, int(col)))
    # Minecraft models must fit in -16..32 (48 units): big grids use voxels smaller than one unit.
    unit = min(1.0, 47.0 / max(X, Y, Z))
    off = np.array([8 - X * unit / 2, min(0.0, 32.0 - Y * unit), 8 - Z * unit / 2])
    if Y * unit <= 32:
        off[1] = 0.0
    cell = 16.0 / PAL_SIDE
    els = []
    for face, frm, to, col in elements:
        f = (np.array(frm) * unit + off).round(3).tolist()
        t = (np.array(to) * unit + off).round(3).tolist()
        u, v = (col % PAL_SIDE) * cell, (col // PAL_SIDE) * cell
        els.append({"from": f, "to": t, "faces": {face: {"uv": [round(u + cell * 0.25, 4), round(v + cell * 0.25, 4),
                                                              round(u + cell * 0.75, 4), round(v + cell * 0.75, 4)], "texture": "#p"}}})
    pal_img = Image.new("RGB", (PAL_SIDE, PAL_SIDE))
    for i, c in enumerate(pal):
        pal_img.putpixel((i % PAL_SIDE, i // PAL_SIDE), tuple(int(x) for x in c))
    os.makedirs(OUT, exist_ok=True)
    pal_img.save(os.path.join(OUT, name + ".png"))
    model = {"textures": {"p": "vigil:item/" + name, "particle": "vigil:item/" + name}, "elements": els}
    with open(os.path.join(OUT, name + ".json"), "w") as f:
        json.dump(model, f, separators=(",", ":"))
    print(f"{name}: grid {dims.tolist()}, {len(els)} faces, {len(pal)} colours")
    return off[1], off[1] + Y * unit


def model_bounds(name):
    with open(os.path.join(OUT, name + ".json")) as f:
        m = json.load(f)
    ys = [v for e in m["elements"] for v in (e["from"][1], e["to"][1])]
    return min(ys), max(ys)


def spec(name, height_blocks):
    """Scale and lift so the model stands on the ground and is height_blocks tall (item models are centred on 8)."""
    lo, hi = model_bounds(name)
    scale = height_blocks / ((hi - lo) / 16.0)
    lift = -(lo / 16.0 - 0.5) * scale
    return {"scale": round(scale, 4), "lift": round(lift, 4)}
