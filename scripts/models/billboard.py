"""Painted 3D mobs (not blocky): a painting cut out of its paper, then given a real, rounded body. The figure's outline
is "inflated" like a balloon (thin parts like the arm and neck come out round, the wide body comes out as a soft
pillow), so its surface curves smoothly; it's built from thin boxes whose front faces show the painting itself, at full
resolution, and whose few visible edges take the painting's colour right there. From the front it looks exactly like the
painting; from the side it has a body. Used for The Boiled One.

    python3 scripts/models/billboard.py     # scripts/models/built/<name>.png, <name>__body.json (Java),
                                            # <name>.geo.json (Bedrock), <name>_face.png; models.json

Run scripts/owner-pack/build.py afterwards to put it in the pack.
"""
import json
import os

import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage
from scipy.sparse import coo_matrix
from scipy.sparse.linalg import spsolve

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(HERE, "built")
SIZE = 1024

# name -> (painting in scripts/models/art, height in blocks of the whole square picture, part of the painting or None,
#          grid cells across, texture pixels across)
BILLBOARDS = {"boiled_one": ("boiled_one.png", 4.6, None, 160, 1024),
              # its clawed hand on its own (pressed against a tunnel roof over you)
              "boiled_hand": ("boiled_one.png", 0.9, (225, 1255, 405, 1435), 48, 256)}
# Jumpscare faces (full-screen pictures shown as a title): name -> (painting, part of it with the face)
FACES = {"boiled_one": ("boiled_one.png", (500, 0, 1000, 470))}
FACE_SIZE = 512

# Parts with their own sharper picture on Java: name -> {part: how many times the painting's own size}
SHARP = {"boiled_one": {"head": 2}}
FRONT = 10.0   # the front of the body (in cells from its middle): flat across, rounding off only at its sides
DEPTH = 0.6    # how far its back bulges for its width (1 would be perfectly round)
STEP = 0.25    # thicknesses are rounded to a quarter of a cell (half at the back), so equal neighbours share one box
SOLID = 0.6    # how much of a cell the figure must cover to be part of its body; less is a thin bit (needle, drip,
               # claw), drawn paper-thin with no sides, so it doesn't turn into a little box

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


GRID = 160          # rows and columns the figure's body is built from
BEDROCK_HEIGHT = 49  # Bedrock model pixels (the server scales the mob 1.5x: 4.6 blocks)


# Moving parts, cut out of the picture (as fractions of it, x from the left, y from the top):
# name -> (role, which part of the picture, pivot). Everything else is the body (pivot: the feet).
# The head looks around and twitches, the arm swings and grabs.
PARTS = {"boiled_one": {"head": ("head", lambda x, y: y < 0.21875 and x > 0.37109, (0.55664, 0.21875)),
                        "arm": ("arm", lambda x, y: x < 0.48828 and y > 0.77148, (0.49805, 0.88867))}}


def labels(name):
    """The part each grid cell belongs to."""
    lab = np.full((GRID, GRID), "body", dtype=object)
    for part, (_, inside, _) in PARTS.get(name, {}).items():
        for gy in range(GRID):
            for gx in range(GRID):
                if inside((gx + 0.5) / GRID, (gy + 0.5) / GRID):
                    lab[gy, gx] = part
    return lab


def coverage(cut):
    """How much of each grid cell the figure covers (0..1), and whether any of it shows there at all."""
    alpha = cut.getchannel("A")
    cover = np.asarray(alpha.resize((GRID, GRID), Image.BOX)).astype(np.float32) / 255
    cell = max(1, round(cut.size[0] / GRID))
    grown = Image.fromarray(ndimage.maximum_filter(np.asarray(alpha), size=cell + 1))
    any_ = np.asarray(grown.resize((GRID, GRID), Image.BOX)) > 8
    return cover, any_ | (cover > 0.02)


def inflate(keep):
    """How far the back bulges at every cell (in cells): the outline blown up like a balloon. Solves
    laplace(h) = -1 inside with h = 0 at the edge; sqrt(2h) is then a circle across any thin bit (arm, neck), and a
    soft, rounded hump over the wide body."""
    ys, xs = np.nonzero(keep)
    index = -np.ones(keep.shape, dtype=np.int64)
    index[ys, xs] = np.arange(len(ys))
    rows, cols, vals = [np.arange(len(ys))], [np.arange(len(ys))], [np.full(len(ys), 4.0)]
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        ny, nx = ys + dy, xs + dx
        ok = (ny >= 0) & (ny < GRID) & (nx >= 0) & (nx < GRID)
        ok[ok] = keep[ny[ok], nx[ok]]
        rows.append(np.arange(len(ys))[ok])
        cols.append(index[ny[ok], nx[ok]])
        vals.append(np.full(ok.sum(), -1.0))
    a = coo_matrix((np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))), shape=(len(ys), len(ys))).tocsr()
    h = np.zeros(keep.shape, np.float64)
    h[ys, xs] = spsolve(a, np.ones(len(ys)))
    return DEPTH * np.sqrt(2 * np.maximum(h, 0))


def front(keep):
    """How far the front stands out at every cell (in cells). Flat across each row, so the painting has no steps in
    it from anywhere a player stands; it only rounds off (a quarter circle) near the left and right edges, and a thin
    bit (the arm, a finger, a needle) is round all the way across."""
    out = np.zeros(keep.shape, np.float64)
    for y in range(GRID):
        x = 0
        while x < GRID:
            if not keep[y, x]:
                x += 1
                continue
            a = x
            while x < GRID and keep[y, x]:
                x += 1
            rad = min((x - a) / 2, FRONT)
            for xx in range(a, x):
                e = min(xx - a + 0.5, x - xx - 0.5)   # cells to the nearest edge (from the cell's middle)
                out[y, xx] = rad if e >= rad else np.sqrt(max(e * (2 * rad - e), 0.0))
    return out


def relief(cut, lab=None):
    """The figure as boxes: runs of cells along a row with the same thickness, (x0, x1, y, front, back, part) in grid
    cells (y from the top), plus what the faces need: every cell's front and back, and how much the figure covers it."""
    if lab is None:
        lab = np.full((GRID, GRID), "body", dtype=object)
    cover, keep = coverage(cut)
    body = keep & (cover >= SOLID)
    f = front(body)
    b = np.maximum(inflate(body), f * 0.8)
    f = np.where(body, np.maximum(np.round(f / STEP) * STEP, STEP), np.where(keep, STEP / 2, 0))
    b = np.where(body, np.maximum(np.round(b / (2 * STEP)) * 2 * STEP, 2 * STEP), np.where(keep, STEP / 2, 0))
    runs = []
    for y in range(GRID):
        x = 0
        while x < GRID:
            if not keep[y, x]:
                x += 1
                continue
            x0, ff, bb, part = x, f[y, x], b[y, x], lab[y, x]
            while x < GRID and keep[y, x] and f[y, x] == ff and b[y, x] == bb and lab[y, x] == part \
                    and body[y, x] == body[y, x0]:
                x += 1
            runs.append((x0, x, y, float(ff), float(bb), part))
    return {"runs": runs, "front": f, "back": b, "cover": cover, "lab": lab, "body": body}


def textures(cut, model):
    """The texture sheet: the painting (the front) on the left; on the right a dim, blurred copy at half size (its
    back), and under that a tiny map with one pixel per grid cell: the painting's colour there, which the sides,
    tops and bottoms of the boxes show (solid, never see-through)."""
    size = cut.size[0]
    back = cut.filter(ImageFilter.GaussianBlur(size / 85)).resize((size // 2, size // 2), Image.LANCZOS)
    arr = np.asarray(back).astype(np.float32)
    arr[..., :3] *= 0.45
    arr[..., 3] = np.asarray(cut.getchannel("A").resize((size // 2, size // 2), Image.NEAREST))
    # The colour of the body around each cell: the painting with its see-through parts filled from the nearest
    # painted pixel, then a median over about three cells, so thin bright bits (needles, drips) don't streak the sides.
    rgb = np.asarray(cut)[..., :3].astype(np.float32)
    painted = np.asarray(cut)[..., 3] > 0
    r_ = max(1, size // 256)
    disk = np.hypot(*np.mgrid[-r_:r_ + 1, -r_:r_ + 1]) <= r_
    # the body, without needles and drips, and a little inside its outline (the outline is blended with the paper)
    inward = max(2, round(1.25 * size / GRID / r_))     # a cell and a bit: past any pale rim the painting has
    solid = ndimage.binary_erosion(ndimage.binary_opening(painted, structure=disk, iterations=2), structure=disk,
                                   iterations=inward)
    if not solid.any():
        solid = painted
    _, (iy, ix) = ndimage.distance_transform_edt(~solid, return_indices=True)
    filled = rgb[iy, ix]
    k = int(round(3 * size / GRID)) | 1
    med = np.dstack([ndimage.median_filter(filled[..., i], size=k) for i in range(3)])
    at = ((np.arange(GRID) + 0.5) * size / GRID).astype(int)
    colour = med[at][:, at]
    tiny = np.dstack([np.clip(colour, 0, 255), np.full((GRID, GRID), 255.0)]).astype(np.uint8)
    sheet = Image.new("RGBA", (size + size // 2, size), (0, 0, 0, 0))
    sheet.paste(cut, (0, 0))
    sheet.paste(Image.fromarray(arr.astype(np.uint8), "RGBA"), (size, 0))
    sheet.paste(Image.fromarray(tiny, "RGBA"), (size, size // 2))
    return sheet


def head_texture(path, box, model, part, scale):
    """The head's own, sharper picture (Java): the painting at its full size, smoothed up {scale} times, cut to the
    cells of that part. It's what fills the screen when it holds you up to its face. Returns the image and the
    cells it spans (x0, y0, x1, y1)."""
    lab = model["lab"]
    ys, xs = np.nonzero((lab == part) & (model["front"] > 0))
    gx0, gy0, gx1, gy1 = xs.min(), ys.min(), xs.max() + 1, ys.max() + 1
    src = Image.open(path).convert("RGB")
    full = max(src.size)                      # about the painting's own size, so nothing is lost
    big = cutout(path, box, full * scale)
    k = big.size[0] / GRID
    crop = big.crop((int(gx0 * k), int(gy0 * k), int(gx1 * k), int(gy1 * k)))
    # Both sides a multiple of 16: anything else makes the game drop mipmaps for every block texture in its atlas.
    crop = crop.resize((max(16, round(crop.size[0] / 16) * 16), max(16, round(crop.size[1] / 16) * 16)), Image.LANCZOS)
    return crop, (int(gx0), int(gy0), int(gx1), int(gy1))


def faces(model, run):
    """Which faces of a run can ever be seen: its front and back always, a side only where its neighbour (of the same
    part) is thinner there, so most are left out. Each side shows the colour map: (side, cell or row it takes)."""
    x0, x1, y, fr, bk, part = run
    f, b, lab = model["front"], model["back"], model["lab"]
    if not model["body"][y, x0]:
        return {}                        # a thin bit: just its painted front and back

    def sticks(yy, xx):
        if 0 <= yy < GRID and 0 <= xx < GRID and lab[yy, xx] == part:
            return f[yy, xx] < fr or b[yy, xx] < bk
        return True

    out = {}
    if sticks(y, x0 - 1):
        out["west"] = x0
    if sticks(y, x1):
        out["east"] = x1 - 1
    if any(sticks(y - 1, xx) for xx in range(x0, x1)):
        out["up"] = y
    if any(sticks(y + 1, xx) for xx in range(x0, x1)):
        out["down"] = y
    return out


def java_model(name, model, size, part="body", pivot=(8.0, 8.0), head=None):
    """Item model elements of one part (16 units = the whole picture, the figure's front on the south side, which the
    mob faces), placed so the part's pivot sits at the model's centre (8, 8, 8). head: (texture, cells it spans) for
    a part with its own sharper picture on the front."""
    c = 16 / GRID
    W, H = size * 1.5, size              # the sheet (see textures())
    cell = size / GRID                   # one grid cell in the painting's pixels
    U = lambda px: px * 16 / W
    V = lambda py: py * 16 / H
    dx, dy = 8 - pivot[0], 8 - pivot[1]
    els = []
    for run in model["runs"]:
        x0, x1, y, fr, bk, prt = run
        if prt != part:
            continue
        # seen from the front (+z), +x is on the viewer's right, like the painting's columns
        if head is not None:
            _, (hx0, hy0, hx1, hy1) = head
            hu = lambda gx: (gx - hx0) * 16 / (hx1 - hx0)
            hv = lambda gy: (gy - hy0) * 16 / (hy1 - hy0)
            south = {"uv": [r(hu(x0)), r(hv(y)), r(hu(x1)), r(hv(y + 1))], "texture": "#h"}
        else:
            south = {"uv": [r(U(x0 * cell)), r(V(y * cell)), r(U(x1 * cell)), r(V((y + 1) * cell))], "texture": "#p"}
        half = cell / 2                  # the back is drawn at half size
        fs = {"south": south,
              "north": {"uv": [r(U(size + x1 * half)), r(V(y * half)), r(U(size + x0 * half)), r(V((y + 1) * half))],
                        "texture": "#p"}}
        for side, at in faces(model, run).items():
            # the colour map: one pixel per cell, at (size + x, size / 2 + y); sampled well inside each pixel
            if side in ("east", "west"):
                uv = [size + at + 0.25, size / 2 + y + 0.25, size + at + 0.75, size / 2 + y + 0.75]
            else:
                uv = [size + x0 + 0.25, size / 2 + at + 0.25, size + x1 - 0.25, size / 2 + at + 0.75]
            fs[side] = {"uv": [r(U(uv[0])), r(V(uv[1])), r(U(uv[2])), r(V(uv[3]))], "texture": "#p"}
        els.append({"from": [r(x0 * c + dx), r(16 - (y + 1) * c + dy), r(8 - bk * c)],
                    "to": [r(x1 * c + dx), r(16 - y * c + dy), r(8 + fr * c)], "faces": fs})
    tex = "vigil:item/" + name
    textures = {"p": tex, "particle": tex}
    if head is not None:
        textures["h"] = tex + "_" + part
    return {"textures": textures, "elements": els}


def r(v):
    return round(v, 4)


BEDROCK_BONES = {"body": "body", "head": "head", "arm": "rightArm"}   # the game's own animations move these


def bedrock_geo(name, model, size):
    """The same boxes as Bedrock cubes (model pixels; Bedrock mobs face north), one bone per part."""
    c = BEDROCK_HEIGHT / GRID
    p = size / GRID                      # one grid cell in texture pixels
    bones = {"body": {"name": "body", "pivot": [0, 0, 0], "cubes": []}}
    for part, (_, _, (px, py)) in PARTS.get(name, {}).items():
        bones[part] = {"name": BEDROCK_BONES.get(part, part), "parent": "body",
                       "pivot": [r(px * BEDROCK_HEIGHT - BEDROCK_HEIGHT / 2), r((1 - py) * BEDROCK_HEIGHT), 0], "cubes": []}
    for run in model["runs"]:
        x0, x1, y, fr, bk, part = run
        w = x1 - x0
        uv = {"north": {"uv": [r(x0 * p), r(y * p)], "uv_size": [r(w * p), r(p)]},
              "south": {"uv": [r(size + x1 * p / 2), r(y * p / 2)], "uv_size": [r(-w * p / 2), r(p / 2)]}}
        for side, at in faces(model, run).items():
            if side in ("east", "west"):
                uv[side] = {"uv": [r(size + at + 0.25), r(size / 2 + y + 0.25)], "uv_size": [0.5, 0.5]}
            else:
                uv[side] = {"uv": [r(size + x0 + 0.25), r(size / 2 + at + 0.25)], "uv_size": [r(w - 0.5), 0.5]}
        bones[part]["cubes"].append({"origin": [r(x0 * c - BEDROCK_HEIGHT / 2), r((GRID - y - 1) * c), r(-fr * c)],
                                     "size": [r(w * c), r(c), r((fr + bk) * c)], "uv": uv})
    return {"format_version": "1.12.0",
            "minecraft:geometry": [{"description": {"identifier": "geometry.vigil." + name,
                                                    "texture_width": size + size // 2, "texture_height": size,
                                                    "visible_bounds_width": 5, "visible_bounds_height": 6,
                                                    "visible_bounds_offset": [0, 3, 0]},
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
    for name, (art, height, box, grid, size) in BILLBOARDS.items():
        GRID = grid
        cut = cutout(os.path.join(HERE, "art", art), box, size)
        model = relief(cut, labels(name))
        textures(cut, model).save(os.path.join(OUT, name + ".png"))
        parts = [{"id": name + "__body", "role": "body" if name in PARTS else "static", "side": 0, "parent": None,
                  "phase": 0.0, "pivot": [0.0, 0.0 if name in PARTS else 0.5, 0.0]}]
        body_pivot = (8.0, 0.0) if name in PARTS else (8.0, 8.0)
        with open(os.path.join(OUT, name + "__body.json"), "w") as f:
            json.dump(java_model(name, model, size, "body", body_pivot), f, separators=(",", ":"))
        for part, (role, _, (px, py)) in PARTS.get(name, {}).items():
            pv = (px * 16, 16 - py * 16)
            head = None
            if part in SHARP.get(name, ()):
                img, cells = head_texture(os.path.join(HERE, "art", art), box, model, part, SHARP[name][part])
                img.save(os.path.join(OUT, f"{name}_{part}.png"))
                head = (img, cells)
            with open(os.path.join(OUT, f"{name}__{part}.json"), "w") as f:
                json.dump(java_model(name, model, size, part, pv, head), f, separators=(",", ":"))
            parts.append({"id": f"{name}__{part}", "role": role, "side": 0, "parent": name + "__body", "phase": 0.0,
                          "pivot": [round((pv[0] - 8) / 16, 4), round(pv[1] / 16, 4), 0.0]})
        with open(os.path.join(OUT, name + ".geo.json"), "w") as f:
            json.dump(bedrock_geo(name, model, size), f, separators=(",", ":"))
        print(name, len(model["runs"]), "boxes")
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
        cutout(os.path.join(HERE, "art", art), box, FACE_SIZE).save(os.path.join(OUT, name + "_face.png"))
        print(name, "jumpscare face")


if __name__ == "__main__":
    main()
