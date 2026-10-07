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


# Moving parts, cut out of the picture (pixels of the 512 cut-out): name -> (role, which pixels, pivot pixel).
# Everything else is the body (pivot: the feet). The head looks around and twitches, the arm swings and grabs.
PARTS = {"boiled_one": {"head": ("head", lambda x, y: y < 112 and x > 190, (285, 112)),
                        "arm": ("arm", lambda x, y: x < 250 and y > 395, (255, 455))}}


def labels(name):
    """The part each grid cell belongs to."""
    lab = np.full((GRID, GRID), "body", dtype=object)
    for part, (_, inside, _) in PARTS.get(name, {}).items():
        for gy in range(GRID):
            for gx in range(GRID):
                if inside((gx + 0.5) * SIZE / GRID, (gy + 0.5) * SIZE / GRID):
                    lab[gy, gx] = part
    return lab


def relief(cut, lab=None):
    """Strips for the 3D figure: (x0, x1, y, half_depth, part) in grid cells, y counted from the top."""
    if lab is None:
        lab = np.full((GRID, GRID), "body", dtype=object)
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
            x0, h, part = x, half[y, x], lab[y, x]
            while x < GRID and half[y, x] == h and lab[y, x] == part:
                x += 1
            strips.append((x0, x, y, int(h), part))
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


def java_model(name, strips, part="body", pivot=(8.0, 8.0)):
    """Item model elements of one part (16 units = the whole picture, the figure's front on the south side, which the
    mob faces), placed so the part's pivot sits at the model's centre (8, 8, 8)."""
    c = 16 / GRID
    t = 8 / GRID                        # one grid cell in the texture's uv (the painting is the left half)
    dx, dy = 8 - pivot[0], 8 - pivot[1]
    els = []
    for x0, x1, y, h, prt in strips:
        if prt != part:
            continue
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
        els.append({"from": [round(fx0 + dx, 4), round(bot + dy, 4), round(8 - h * c, 4)],
                    "to": [round(fx1 + dx, 4), round(top + dy, 4), round(8 + h * c, 4)],
                    "faces": {"south": {"uv": front, "texture": "#p"}, "north": {"uv": back, "texture": "#p"},
                              "east": {"uv": right_edge, "texture": "#p"}, "west": {"uv": left_edge, "texture": "#p"},
                              "up": {"uv": row, "texture": "#p"}, "down": {"uv": row, "texture": "#p"}}})
    tex = "vigil:item/" + name
    return {"textures": {"p": tex, "particle": tex}, "elements": els}


BEDROCK_BONES = {"body": "body", "head": "head", "arm": "rightArm"}   # the game's own animations move these


def bedrock_geo(name, strips, tex_size):
    """The same strips as Bedrock cubes (model pixels; Bedrock mobs face north), one bone per part."""
    c = BEDROCK_HEIGHT / GRID
    p = tex_size / 2 / GRID              # one grid cell in texture pixels
    k = BEDROCK_HEIGHT / SIZE            # cut-out pixels -> model pixels
    bones = {"body": {"name": "body", "pivot": [0, 0, 0], "cubes": []}}
    for part, (_, _, (px, py)) in PARTS.get(name, {}).items():
        bones[part] = {"name": BEDROCK_BONES.get(part, part), "parent": "body",
                       "pivot": [round(px * k - BEDROCK_HEIGHT / 2, 4), round((SIZE - py) * k, 4), 0], "cubes": []}
    for x0, x1, y, h, part in strips:
        cubes = bones[part]["cubes"]
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
                                    "bones": list(bones.values())}]}


def footprint(specs):
    """A long, smeared, bloody print of a bare foot: a flat plane lying on the floor."""
    size = 64
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    rng = np.random.default_rng(3)
    a = np.zeros((size, size), np.float32)
    # sole and heel, toes as blots, a smear dragged behind
    for cx, cy, rx, ry in ((32, 30, 9, 15), (32, 46, 8, 7), (25, 11, 3, 3), (30, 8, 3, 3.5), (35, 8, 2.8, 3),
                           (39, 10, 2.5, 2.6), (42, 13, 2.2, 2.2)):
        a = np.maximum(a, np.clip(1.4 - np.sqrt(((xx - cx) / rx) ** 2 + ((yy - cy) / ry) ** 2), 0, 1))
    smear = np.clip(1 - np.abs(xx - 32 - (yy - 52) * 0.15) / 6, 0, 1) * ((yy > 50) & (yy < 63)) * 0.6
    a = np.maximum(a, smear)
    a *= 0.7 + 0.3 * rng.random((size, size))
    alpha = (a > 0.45).astype(np.float32)
    shade = 0.5 + 0.5 * rng.random((size, size))
    rgb = np.dstack([0.32 + 0.22 * shade, 0.01 + 0.02 * shade, 0.02 + 0.02 * shade])
    Image.fromarray((np.dstack([rgb, alpha]) * 255).astype(np.uint8), "RGBA").save(os.path.join(OUT, "boiled_print.png"))
    tex = "vigil:item/boiled_print"
    model = {"textures": {"p": tex, "particle": tex},
             "elements": [{"from": [4, 8, 2], "to": [12, 8.05, 14],
                           "faces": {"up": {"uv": [4, 2, 12, 14], "texture": "#p"}}}]}
    with open(os.path.join(OUT, "boiled_print__body.json"), "w") as f:
        json.dump(model, f, separators=(",", ":"))
    specs["boiled_print"] = {"scale": 1.0, "parts": [{"id": "boiled_print__body", "role": "static", "side": 0,
                                                      "parent": None, "phase": 0.0, "pivot": [0.0, 0.5, 0.0]}]}


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
        strips = relief(cut, labels(name))
        parts = [{"id": name + "__body", "role": "body" if name in PARTS else "static", "side": 0, "parent": None,
                  "phase": 0.0, "pivot": [0.0, 0.0 if name in PARTS else 0.5, 0.0]}]
        body_pivot = (8.0, 0.0) if name in PARTS else (8.0, 8.0)
        with open(os.path.join(OUT, name + "__body.json"), "w") as f:
            json.dump(java_model(name, strips, "body", body_pivot), f, separators=(",", ":"))
        for part, (role, _, (px, py)) in PARTS.get(name, {}).items():
            pv = (px * 16 / SIZE, 16 - py * 16 / SIZE)
            with open(os.path.join(OUT, f"{name}__{part}.json"), "w") as f:
                json.dump(java_model(name, strips, part, pv), f, separators=(",", ":"))
            parts.append({"id": f"{name}__{part}", "role": role, "side": 0, "parent": name + "__body", "phase": 0.0,
                          "pivot": [round((pv[0] - 8) / 16, 4), round(pv[1] / 16, 4), 0.0]})
        with open(os.path.join(OUT, name + ".geo.json"), "w") as f:
            json.dump(bedrock_geo(name, strips, sheet.size[0]), f, separators=(",", ":"))
        print(name, len(strips), "strips")
        specs[name] = {"scale": height, "parts": parts}
        if name in PARTS:
            specs[name]["creepy"] = True
        print(name, height, "blocks")
    with open(path, "w") as f:
        json.dump(specs, f, indent=1, sort_keys=True)
    # A bloody footprint, lying flat (left by it when it breaks in)
    footprint(specs)
    with open(path, "w") as f:
        json.dump(specs, f, indent=1, sort_keys=True)
    for name, (art, box) in FACES.items():
        cutout(os.path.join(HERE, "art", art), box, 256).save(os.path.join(OUT, name + "_face.png"))
        print(name, "jumpscare face")


if __name__ == "__main__":
    main()
