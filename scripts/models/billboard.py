"""Flat, painted mobs (not blocky): a picture cut out of its paper background and shown on one plane that always turns
to face whoever looks at it (the item display's billboard). Used for The Boiled One.

    python3 scripts/models/billboard.py     # writes scripts/models/built/<name>.png + <name>__body.json, models.json

Run scripts/owner-pack/build.py afterwards to put it in the pack.
"""
import json
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(HERE, "built")
SIZE = 512

# name -> (painting in scripts/models/art, height in blocks of the whole square picture)
BILLBOARDS = {"boiled_one": ("boiled_one.png", 4.6)}
# Jumpscare faces (full-screen pictures shown as a title): name -> (painting, part of it with the face)
FACES = {"boiled_one": ("boiled_one.png", (500, 0, 1000, 470))}


def cutout(path, box=None, size=SIZE):
    """The figure, its paper made transparent (blood and needles kept), cropped and centred on a square, feet down.
    box (left, top, right, bottom) takes just that part of the painting first."""
    im0 = Image.open(path).convert("RGB")
    paper_img = np.asarray(im0).astype(np.float32) / 255
    if box is not None:
        im0 = im0.crop(box)
    img = np.asarray(im0).astype(np.float32) / 255
    paper = np.median(paper_img[:40, :40].reshape(-1, 3), axis=0)
    lum = img @ np.array([0.299, 0.587, 0.114], np.float32)
    plum = float(paper @ np.array([0.299, 0.587, 0.114], np.float32))
    a = np.clip((plum - lum - 0.04) / 0.3, 0, 1)
    # take the paper back out of the half-covered edge pixels
    col = np.clip((img - paper * (1 - a[..., None])) / np.maximum(a[..., None], 1e-3), 0, 1)
    ys, xs = np.nonzero(a > 0.5)
    y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
    col, a = col[y0:y1, x0:x1], a[y0:y1, x0:x1]
    h, w = a.shape
    side = max(h, w)
    sq = np.zeros((side, side, 4), np.float32)
    ox = (side - w) // 2
    sq[side - h:, ox:ox + w, :3] = col * a[..., None]          # premultiplied, so it scales down cleanly
    sq[side - h:, ox:ox + w, 3] = a
    im = Image.fromarray((sq * 255).astype(np.uint8), "RGBA").resize((size, size), Image.LANCZOS)
    arr = np.asarray(im).astype(np.float32) / 255
    al = arr[..., 3:4]
    rgb = np.where(al > 0.02, arr[..., :3] / np.maximum(al, 1e-3), 0.03)
    alpha = (al[..., 0] > 0.45).astype(np.float32)                # the game draws items cut out: hard edges
    out = np.dstack([np.clip(rgb, 0, 1), alpha])
    return Image.fromarray((out * 255).astype(np.uint8), "RGBA")


def main():
    path = os.path.join(ROOT, "src", "main", "resources", "vigil", "models.json")
    specs = json.load(open(path))
    os.makedirs(OUT, exist_ok=True)
    for name, (art, height) in BILLBOARDS.items():
        cutout(os.path.join(HERE, "art", art)).save(os.path.join(OUT, name + ".png"))
        tex = "vigil:item/" + name
        model = {"textures": {"p": tex, "particle": tex},
                 "elements": [{"from": [0, 0, 8], "to": [16, 16, 8],
                               "faces": {"south": {"uv": [0, 0, 16, 16], "texture": "#p"},
                                         "north": {"uv": [16, 0, 0, 16], "texture": "#p"}}}]}
        with open(os.path.join(OUT, name + "__body.json"), "w") as f:
            json.dump(model, f, separators=(",", ":"))
        specs[name] = {"scale": height, "billboard": True,
                       "parts": [{"id": name + "__body", "role": "static", "side": 0, "parent": None, "phase": 0.0,
                                  "pivot": [0.0, 0.5, 0.0]}]}
        print(name, "billboard,", height, "blocks")
    with open(path, "w") as f:
        json.dump(specs, f, indent=1, sort_keys=True)
    for name, (art, box) in FACES.items():
        cutout(os.path.join(HERE, "art", art), box, 256).save(os.path.join(OUT, name + "_face.png"))
        print(name, "jumpscare face")


if __name__ == "__main__":
    main()
