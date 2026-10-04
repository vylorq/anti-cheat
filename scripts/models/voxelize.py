#!/usr/bin/env python3
"""Turns a textured 3D model (.glb) into a Minecraft item model made of flat colored faces (voxel style).

Minecraft models only support boxes, so the mesh is sampled into a voxel grid (colors from its texture), the inside
is filled, and every visible side of the voxel surface is merged into as few rectangles of one color as possible.
Colors are reduced to a 16x16 palette texture; each face points at its color's pixel.

    python3 scripts/models/voxelize.py model.glb out_name [height_in_voxels]
writes scripts/models/built/<out_name>.json (model) and <out_name>.png (palette).
"""
import json
import os
import sys

import numpy as np
import trimesh
from PIL import Image
from scipy import ndimage

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "built")


def load(path):
    scene = trimesh.load(path)
    meshes = []
    for node in scene.graph.nodes_geometry:
        T, name = scene.graph[node]
        g = scene.geometry[name].copy()
        g.apply_transform(T)
        meshes.append(g)
    return meshes


def sample(meshes, n):
    pts, cols = [], []
    total = sum(m.area for m in meshes)
    for m in meshes:
        k = max(1000, int(n * m.area / total))
        p, fi = trimesh.sample.sample_surface(m, k, seed=1)
        tex = np.asarray(m.visual.material.baseColorTexture.convert("RGB"))
        H, W = tex.shape[:2]
        # Barycentric UV of each sample point
        tri = m.triangles[fi]
        bary = trimesh.triangles.points_to_barycentric(tri, p)
        uv = np.einsum("ij,ijk->ik", bary, m.visual.uv[m.faces[fi]])
        x = np.clip((uv[:, 0] % 1.0) * (W - 1), 0, W - 1).astype(int)
        y = np.clip((1 - uv[:, 1] % 1.0) * (H - 1), 0, H - 1).astype(int)
        pts.append(p)
        cols.append(tex[y, x])
    return np.concatenate(pts), np.concatenate(cols).astype(float)


def kmeans(cols, k, iters=12):
    rng = np.random.default_rng(3)
    c = cols[rng.choice(len(cols), k, replace=False)]
    for _ in range(iters):
        d = ((cols[:, None, :] - c[None]) ** 2).sum(-1)
        lab = d.argmin(1)
        for i in range(k):
            sel = cols[lab == i]
            if len(sel):
                c[i] = sel.mean(0)
    return c, lab


def build(path, name, height):
    meshes = load(path)
    pts, cols = sample(meshes, 1_500_000)
    lo, hi = pts.min(0), pts.max(0)
    size = hi - lo
    scale = (height - 0.01) / size[1]
    dims = np.ceil(size * scale).astype(int) + 1
    idx = np.clip(((pts - lo) * scale).astype(int), 0, dims - 1)
    # Average color per surface voxel
    flat = np.ravel_multi_index(idx.T, dims)
    sums = np.zeros((np.prod(dims), 3))
    cnt = np.zeros(np.prod(dims))
    np.add.at(sums, flat, cols)
    np.add.at(cnt, flat, 1)
    surface = (cnt > 0).reshape(dims)
    color = (sums / np.maximum(cnt, 1)[:, None]).reshape(*dims, 3)
    # Fill the inside (so no faces point inward)
    solid = surface.copy()
    for ax in range(3):
        solid |= np.apply_along_axis(lambda a: ndimage.binary_fill_holes(a), ax, solid) if False else solid
    solid = ndimage.binary_fill_holes(solid)
    # Inside voxels take the color of the nearest surface voxel
    _, (ix, iy, iz) = ndimage.distance_transform_edt(~surface, return_indices=True)
    color = color[ix, iy, iz]
    # Palette (256 colors) from the visible voxels
    vis = color[surface]
    pal, _ = kmeans(vis[np.random.default_rng(0).choice(len(vis), min(len(vis), 60000), replace=False)], 256)
    d = ((color.reshape(-1, 3)[:, None, :] - pal[None]) ** 2).sum(-1)
    lab = d.argmin(1).reshape(dims)

    # Greedy-merge visible faces per direction into rectangles of one color.
    X, Y, Z = dims
    elements = []
    dirs = {"east": (0, 1), "west": (0, -1), "up": (1, 1), "down": (1, -1), "south": (2, 1), "north": (2, -1)}
    for face, (axis, sgn) in dirs.items():
        a1, a2 = [a for a in range(3) if a != axis]
        for layer in range(dims[axis]):
            mask = np.full((dims[a1], dims[a2]), -1, int)
            for u in range(dims[a1]):
                for v in range(dims[a2]):
                    c = [0, 0, 0]
                    c[axis], c[a1], c[a2] = layer, u, v
                    if not solid[tuple(c)]:
                        continue
                    n = list(c)
                    n[axis] += sgn
                    if 0 <= n[axis] < dims[axis] and solid[tuple(n)]:
                        continue
                    mask[u, v] = lab[tuple(c)]
            used = np.zeros_like(mask, bool)
            for u in range(dims[a1]):
                for v in range(dims[a2]):
                    if mask[u, v] < 0 or used[u, v]:
                        continue
                    col = mask[u, v]
                    w = 1
                    while v + w < dims[a2] and mask[u, v + w] == col and not used[u, v + w]:
                        w += 1
                    h = 1
                    while u + h < dims[a1] and all(mask[u + h, v + k] == col and not used[u + h, v + k] for k in range(w)):
                        h += 1
                    used[u:u + h, v:v + w] = True
                    frm, to = [0, 0, 0], [0, 0, 0]
                    plane = layer + (1 if sgn > 0 else 0)
                    frm[axis] = to[axis] = plane
                    frm[a1], to[a1] = u, u + h
                    frm[a2], to[a2] = v, v + w
                    elements.append((face, frm, to, col))
    # Center: x/z around 8, feet at y=0 shifted so it fits Minecraft's -16..32 range.
    off = np.array([8 - X / 2, min(0, 32 - Y), 8 - Z / 2])
    els = []
    for face, frm, to, col in elements:
        f = (np.array(frm) + off).round(3).tolist()
        t = (np.array(to) + off).round(3).tolist()
        u, v = col % 16, col // 16
        els.append({"from": f, "to": t, "faces": {face: {"uv": [u + 0.25, v + 0.25, u + 0.75, v + 0.75], "texture": "#p"}}})
    pal_img = Image.new("RGB", (16, 16))
    for i, c in enumerate(pal):
        pal_img.putpixel((i % 16, i // 16), tuple(int(x) for x in c))
    os.makedirs(OUT, exist_ok=True)
    pal_img.save(os.path.join(OUT, name + ".png"))
    model = {"textures": {"p": "vigil:item/" + name, "particle": "vigil:item/" + name}, "elements": els,
             "display": {"fixed": {"scale": [1, 1, 1]}, "gui": {"rotation": [15, 30, 0], "scale": [0.45, 0.45, 0.45]}}}
    with open(os.path.join(OUT, name + ".json"), "w") as f:
        json.dump(model, f, separators=(",", ":"))
    print(name, "grid", dims.tolist(), "faces", len(els), "solid", int(solid.sum()))
    np.save(os.path.join(OUT, name + "_grid.npy"), np.where(solid, lab, -1))
    pal_img.save(os.path.join(OUT, name + ".png"))


if __name__ == "__main__":
    build(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 40)
