"""Painted 3D mobs (not blocky): a painting cut out of its paper, then given real depth. Every row of the figure is
cut into strips; each strip is as thick as the figure is wide there (round, like a body), so it's solid from every
side and looks like the painting from the front. Used for The Boiled One.

    python3 scripts/models/billboard.py     # scripts/models/built/<name>.png, <name>__body.json (Java),
                                            # <name>.geo.json (Bedrock), <name>_face.png; models.json

Run scripts/owner-pack/build.py afterwards to put it in the pack.
"""
import json
import os

import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(HERE, "built")
SIZE = 512

# name -> (painting in scripts/models/art, height in blocks of the whole square picture)
BILLBOARDS = {"boiled_one": ("boiled_one.png", 4.6, None, 112),
              # its clawed hand on its own (pressed against a tunnel roof over you): fewer, bigger strips
              "boiled_hand": ("boiled_one.png", 0.9, (225, 1255, 405, 1435), 40)}
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


GRID = 112          # rows and columns the figure is cut into
BEDROCK_HEIGHT = 49  # Bedrock model pixels (the server scales the mob 1.5x: 4.6 blocks)


def relief(cut):
    """Strips for the 3D figure: (x0, x1, y, half_depth) in grid cells, y counted from the top."""
    a = np.asarray(cut.resize((GRID, GRID), Image.BOX))[..., 3] > 110
    d = ndimage.distance_transform_edt(a)
    half = np.where(a, np.clip(np.minimum(2.7 * np.sqrt(d), 1.3 * d), 1, None), 0).round().astype(int)
    strips = []
    for y in range(GRID):
        x = 0
        while x < GRID:
            if half[y, x] == 0:
                x += 1
                continue
            x0, h = x, half[y, x]
            while x < GRID and half[y, x] == h:
                x += 1
            strips.append((x0, x, y, int(h)))
    return strips


def textures(cut):
    """One square texture: the painting on the left (front), a dim blurred copy on the right (its back)."""
    size = cut.size[0]
    back = cut.filter(ImageFilter.GaussianBlur(6))
    arr = np.asarray(back).astype(np.float32)
    arr[..., :3] *= 0.45
    arr[..., 3] = np.asarray(cut)[..., 3]
    sheet = Image.new("RGBA", (size * 2, size * 2), (0, 0, 0, 0))
    sheet.paste(cut, (0, 0))
    sheet.paste(Image.fromarray(arr.astype(np.uint8), "RGBA"), (size, 0))
    return sheet


def java_model(name, strips):
    """Item model elements (0..16 units, the figure's front on the south side, which the mob faces)."""
    c = 16 / GRID
    t = 8 / GRID                        # one grid cell in the texture's uv (the painting is the left half)
    els = []
    for x0, x1, y, h in strips:
        # seen from the front (+z), +x is on the viewer's right, like the painting's columns
        fx0, fx1 = x0 * c, x1 * c
        top, bot = 16 - y * c, 16 - (y + 1) * c
        front = [x0 * t, y * t, x1 * t, (y + 1) * t]
        back = [8 + x1 * t, y * t, 8 + x0 * t, (y + 1) * t]
        # the sides take their colour from a little inside the edge (the very edge is half paper)
        li, ri = x0 + min(4, (x1 - x0) // 2), x1 - 1 - min(4, (x1 - x0) // 2)
        left_edge = [li * t, y * t, (li + 1) * t, (y + 1) * t]
        right_edge = [ri * t, y * t, (ri + 1) * t, (y + 1) * t]
        row = [x0 * t, y * t, x1 * t, (y + 1) * t]
        els.append({"from": [round(fx0, 4), round(bot, 4), round(8 - h * c, 4)],
                    "to": [round(fx1, 4), round(top, 4), round(8 + h * c, 4)],
                    "faces": {"south": {"uv": front, "texture": "#p"}, "north": {"uv": back, "texture": "#p"},
                              "east": {"uv": right_edge, "texture": "#p"}, "west": {"uv": left_edge, "texture": "#p"},
                              "up": {"uv": row, "texture": "#p"}, "down": {"uv": row, "texture": "#p"}}})
    tex = "vigil:item/" + name
    return {"textures": {"p": tex, "particle": tex}, "elements": els}


def bedrock_geo(name, strips, tex_size):
    """The same strips as Bedrock cubes (model pixels; Bedrock mobs face north)."""
    c = BEDROCK_HEIGHT / GRID
    p = tex_size / 2 / GRID              # one grid cell in texture pixels
    cubes = []
    for x0, x1, y, h in strips:
        w = (x1 - x0)
        uvf = {"uv": [x0 * p, y * p], "uv_size": [w * p, p]}
        cubes.append({"origin": [round(x0 * c - BEDROCK_HEIGHT / 2, 4), round((GRID - y - 1) * c, 4), round(-h * c, 4)],
                      "size": [round(w * c, 4), round(c, 4), round(2 * h * c, 4)],
                      "uv": {"north": uvf,
                             "south": {"uv": [tex_size / 2 + x1 * p, y * p], "uv_size": [-w * p, p]},
                             "east": {"uv": [(x1 - 1 - min(4, w // 2)) * p, y * p], "uv_size": [p, p]},
                             "west": {"uv": [(x0 + min(4, w // 2)) * p, y * p], "uv_size": [p, p]},
                             "up": uvf, "down": uvf}})
    return {"format_version": "1.12.0",
            "minecraft:geometry": [{"description": {"identifier": "geometry.vigil." + name, "texture_width": tex_size,
                                                    "texture_height": tex_size, "visible_bounds_width": 5,
                                                    "visible_bounds_height": 6, "visible_bounds_offset": [0, 3, 0]},
                                    "bones": [{"name": "figure", "pivot": [0, 0, 0], "cubes": cubes}]}]}


def main():
    path = os.path.join(ROOT, "src", "main", "resources", "vigil", "models.json")
    specs = json.load(open(path))
    os.makedirs(OUT, exist_ok=True)
    global GRID
    for name, (art, height, box, grid) in BILLBOARDS.items():
        GRID = grid
        cut = cutout(os.path.join(HERE, "art", art), box)
        sheet = textures(cut)
        sheet.save(os.path.join(OUT, name + ".png"))
        strips = relief(cut)
        with open(os.path.join(OUT, name + "__body.json"), "w") as f:
            json.dump(java_model(name, strips), f, separators=(",", ":"))
        with open(os.path.join(OUT, name + ".geo.json"), "w") as f:
            json.dump(bedrock_geo(name, strips, sheet.size[0]), f, separators=(",", ":"))
        print(name, len(strips), "strips")
        specs[name] = {"scale": height,
                       "parts": [{"id": name + "__body", "role": "static", "side": 0, "parent": None, "phase": 0.0,
                                  "pivot": [0.0, 0.5, 0.0]}]}
        print(name, height, "blocks")
    with open(path, "w") as f:
        json.dump(specs, f, indent=1, sort_keys=True)
    for name, (art, box) in FACES.items():
        cutout(os.path.join(HERE, "art", art), box, 256).save(os.path.join(OUT, name + "_face.png"))
        print(name, "jumpscare face")


if __name__ == "__main__":
    main()
