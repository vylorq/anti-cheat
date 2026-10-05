"""Custom blocks for the secret structures, on Java and Bedrock.

A custom block is a note block in a state that never happens on its own (instrument custom_head, note 1-24,
unpowered). The mod keeps those note blocks frozen (no tuning, no sound, no neighbour updates), and:
  * Java: the owner pack retextures those states (scripts/owner-pack/build.py reads what this script writes).
  * Bedrock: Geyser turns those states into real Bedrock custom blocks, using the mappings file and the Bedrock pack
    made here (scripts/oracle-setup.sh copies both into Geyser's folders).

    python3 scripts/blocks/build.py

Writes src/main/resources/vigil/blocks.json, scripts/blocks/built/*.png and resourcepack/bedrock/.
"""
import io
import json
import os
import zipfile

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(HERE, "built")
BEDROCK = os.path.join(ROOT, "resourcepack", "bedrock")
N = 16


def C(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], float)


def rng(name):
    return np.random.default_rng(sum(map(ord, name)) * 7 + 3)


def tones(r, ramp, cell=2, mix=(0.2, 0.6, 0.17, 0.03)):
    """Clustered noise in a 4-tone ramp (dark, base, light, highlight)."""
    g = r.random((N // cell + 2, N // cell + 2))
    big = np.repeat(np.repeat(g, cell, 0), cell, 1)[:N, :N]
    idx = np.digitize(big, np.cumsum(mix)[:-1])
    sp = r.random((N, N))
    idx = np.where(sp < 0.04, np.clip(idx - 1, 0, 3), idx)
    idx = np.where(sp > 0.97, np.clip(idx + 1, 0, 3), idx)
    return np.stack([C(c) for c in ramp])[idx]


def bricks(img, mortar, light, rows=4, width=8):
    """Brick courses: mortar lines, a lit top edge and a shaded bottom edge on every brick."""
    m, l = C(mortar), C(light)
    for y in range(N):
        course = y // rows
        off = (course % 2) * (width // 2)
        for x in range(N):
            if y % rows == rows - 1 or (x + off) % width == width - 1:
                img[y, x] = m
            elif y % rows == 0:
                img[y, x] = img[y, x] * 0.7 + l * 0.3
            elif y % rows == rows - 2:
                img[y, x] *= 0.85
    return img


def tiles(img, line, size=8):
    l = C(line)
    for y in range(N):
        for x in range(N):
            if x % size == size - 1 or y % size == size - 1:
                img[y, x] = l
            elif x % size == 0 or y % size == 0:
                img[y, x] *= 1.12
    return img


def frame(img, col, inner=None):
    c = C(col)
    img[0, :] = img[-1, :] = img[:, 0] = img[:, -1] = c
    if inner:
        i = C(inner)
        img[1, 1:-1] = img[-2, 1:-1] = img[1:-1, 1] = img[1:-1, -2] = i
    return img


def glow(img, pts, core, halo):
    """Glowing lines: a soft halo, then the bright core."""
    c, h = C(core), C(halo)
    s = set(pts)
    for (x, y) in pts:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                xx, yy = x + dx, y + dy
                if 0 <= xx < N and 0 <= yy < N and (xx, yy) not in s:
                    img[yy, xx] = img[yy, xx] * 0.55 + h * 0.45
    for (x, y) in pts:
        img[y, x] = c
    return img


def line(x0, y0, x1, y1):
    n = max(abs(x1 - x0), abs(y1 - y0))
    return [(int(round(x0 + (x1 - x0) * t / n)), int(round(y0 + (y1 - y0) * t / n))) for t in range(n + 1)]


def rune(kind):
    """A few hand-made rune shapes (16x16, centred)."""
    shapes = {
        "eye": line(3, 8, 7, 5) + line(7, 5, 9, 5) + line(9, 5, 12, 8) + line(3, 8, 7, 11) + line(7, 11, 9, 11) + line(9, 11, 12, 8) + [(7, 8), (8, 8), (8, 7), (7, 7)],
        "tide": line(4, 3, 4, 12) + line(11, 3, 11, 12) + line(4, 7, 11, 7) + line(6, 10, 9, 10) + line(7, 4, 8, 4),
        "star": line(8, 2, 8, 13) + line(3, 7, 13, 7) + line(4, 3, 12, 11) + line(12, 3, 4, 11),
        "frost": line(8, 2, 8, 13) + line(3, 5, 13, 10) + line(13, 5, 3, 10) + line(6, 2, 8, 4) + line(10, 2, 8, 4) + line(6, 13, 8, 11) + line(10, 13, 8, 11),
        "ankh": line(8, 7, 8, 13) + line(5, 8, 11, 8) + line(7, 2, 9, 2) + line(6, 3, 6, 5) + line(10, 3, 10, 5) + line(7, 6, 9, 6),
        "flame": line(8, 3, 8, 12) + line(5, 6, 5, 11) + line(11, 6, 11, 11) + line(5, 11, 11, 11) + line(6, 5, 7, 4) + line(10, 5, 9, 4),
    }
    return shapes[kind]


def cracks(img, r, col, n=3, length=6):
    c = C(col)
    for _ in range(n):
        x, y = r.integers(1, N - 1), r.integers(1, N - 1)
        for _ in range(length):
            img[y, x] = c
            x = int(np.clip(x + r.integers(-1, 2), 0, N - 1))
            y = int(np.clip(y + r.choice([-1, 1]), 0, N - 1))
    return img


# ------------------------------------------------------------------ the blocks
# name: (display name, structure, side texture fn, top texture fn or None)

def abyssal_bricks():
    r = rng("abyssal")
    return bricks(tones(r, ["#0f2b2c", "#174042", "#21585a", "#3a7d7a"]), "#081a1b", "#5aa8a0")


def tide_rune():
    r = rng("tide")
    img = frame(tones(r, ["#0d2526", "#143638", "#1c4a4b", "#2c6866"], cell=3), "#081a1b", "#2c6866")
    return glow(img, rune("tide"), "#cffff6", "#2fe6d0")


def vault_stone():
    r = rng("vault")
    return tiles(tones(r, ["#1b1b20", "#26262d", "#33333c", "#4a4a55"]), "#0e0e12")


def vault_lock():
    r = rng("lock")
    img = frame(tones(r, ["#1b1b20", "#26262d", "#33333c", "#4a4a55"], cell=3), "#7a5a10", "#d9a82c")
    gold, dark = C("#e6b833"), C("#0a0a0c")
    for (x, y) in [(6, 4), (7, 4), (8, 4), (9, 4), (5, 5), (10, 5), (5, 6), (10, 6), (5, 7), (10, 7)]:
        img[y, x] = gold
    img[8:13, 5:11] = gold
    img[9, 7:9] = dark
    img[10:12, 7:9] = dark
    img[8, 5:11] = C("#fff08a")
    return img


def cloud_marble():
    r = rng("marble")
    img = tones(r, ["#b9b4ac", "#dcd8d0", "#ece9e3", "#ffffff"], cell=3, mix=(0.1, 0.55, 0.3, 0.05))
    vein = C("#8f8a82")
    x = 2
    for y in range(N):
        img[y, x] = vein
        x = int(np.clip(x + r.choice([0, 1, 1]), 0, N - 1))
    return frame(img, "#c9a032")


def sky_rune():
    img = cloud_marble()
    return glow(img, rune("star"), "#ffffff", "#7fd8ff")


def forge_bricks():
    r = rng("forge")
    img = tones(r, ["#120e14", "#1d181f", "#2a232c", "#3a313d"])
    img = bricks(img, "#0a0709", "#4a3e4c")
    # Molten light leaking through some of the mortar.
    for y in range(N):
        for x in range(N):
            if (img[y, x] == C("#0a0709")).all() and r.random() < 0.35:
                img[y, x] = C("#ff6a10") if r.random() < 0.7 else C("#ffb52a")
    return img


def molten_core():
    r = rng("molten")
    img = tones(r, ["#2a0a04", "#451208", "#5e1a0b", "#7a240f"], cell=2)
    img = cracks(img, r, "#ff8a1f", n=6, length=8)
    img[6:10, 6:10] = C("#ffd25a")
    img[7:9, 7:9] = C("#fff6c0")
    return img


def cursed_sandstone():
    r = rng("cursed")
    img = tones(r, ["#9a8054", "#b89a68", "#cfb380", "#e3cc9a"], cell=3)
    dark = C("#5a4424")
    img[0, :] = img[-1, :] = C("#8a7048")
    img[7, :] = C("#8a7048")
    glyphs = [line(2, 2, 2, 5) + [(3, 2), (4, 3)], [(7, 2), (8, 2), (7, 3), (8, 3), (7, 5), (8, 5)],
              line(12, 2, 14, 5) + line(12, 5, 14, 2), line(2, 9, 5, 9) + line(3, 10, 3, 13),
              line(7, 9, 9, 13) + line(9, 9, 7, 13), [(12, 9), (13, 9), (14, 10), (13, 11), (12, 12), (13, 13), (14, 13)]]
    for g in glyphs:
        for (x, y) in g:
            img[y, x] = dark
    return img


def scarab_tile():
    r = rng("scarab")
    img = frame(tones(r, ["#9a8054", "#b89a68", "#cfb380", "#e3cc9a"], cell=3), "#7a5a10", "#d9a82c")
    gold, lap = C("#e6b833"), C("#2442a6")
    img[4:12, 6:10] = gold
    img[5:11, 5] = gold
    img[5:11, 10] = gold
    img[3, 7:9] = gold
    for (x, y) in [(4, 5), (3, 4), (11, 5), (12, 4), (4, 10), (3, 11), (11, 10), (12, 11)]:
        img[y, x] = gold
    img[7:9, 7:9] = lap
    img[4, 7:9] = C("#fff08a")
    return img


def frost_bricks():
    r = rng("frost")
    img = bricks(tones(r, ["#4372b4", "#6a98d8", "#8fbcef", "#c8e4ff"]), "#2c548e", "#e2f2ff")
    for x in range(N):
        if r.random() < 0.5:
            img[0, x] = C("#ffffff")
    return img


def rune_ice():
    r = rng("runeice")
    img = tones(r, ["#7eaee6", "#a8d0fa", "#c4e0ff", "#eef8ff"], cell=3)
    return glow(img, rune("frost"), "#ffffff", "#4ad8ff")


def mossy_ruin():
    r = rng("ruin")
    img = bricks(tones(r, ["#3c3c42", "#55555c", "#6c6c74", "#85858e"]), "#26262b", "#9a9aa2", width=8)
    moss = [C("#2d4a1e"), C("#3f6328"), C("#557f34")]
    for x in range(N):
        d = r.integers(1, 6)
        for y in range(d):
            img[y, x] = moss[r.integers(0, 3)]
    return cracks(img, r, "#1c1c20", n=2, length=5)


def mossy_ruin_top():
    r = rng("ruintop")
    return tones(r, ["#24401a", "#335a22", "#45752c", "#5a9036"], cell=2)


def thorn_vines():
    r = rng("thorn")
    img = tones(r, ["#10290b", "#1a3f12", "#245418", "#346e22"], cell=2)
    for x0 in (2, 7, 12):
        x = x0
        for y in range(N):
            img[y, x] = C("#3a2a12")
            if r.random() < 0.25 and 0 < x < N - 1:
                img[y, x + r.choice([-1, 1])] = C("#d8d0b0")
            x = int(np.clip(x + r.choice([-1, 0, 0, 1]), 0, N - 1))
    return img


def sculk_bricks():
    r = rng("sculkb")
    img = bricks(tones(r, ["#05060a", "#0b0d14", "#12151f", "#1b1f2e"]), "#020305", "#26304a")
    pts = []
    x, y = 3, 0
    while y < N:
        pts.append((x, y))
        y += 1
        x = int(np.clip(x + r.choice([-1, 0, 1]), 0, N - 1))
    x, y = 12, 0
    while y < N:
        pts.append((x, y))
        y += 1
        x = int(np.clip(x + r.choice([-1, 0, 1]), 0, N - 1))
    return glow(img, pts, "#4ff3ff", "#0d4a57")


def watcher_eye():
    r = rng("eye")
    img = frame(tones(r, ["#05060a", "#0b0d14", "#12151f", "#1b1f2e"], cell=3), "#020305", "#12151f")
    return glow(img, rune("eye"), "#7ff8ff", "#177684")


BLOCKS = {
    "abyssal_bricks": ("Abyssal Bricks", "sunken_vault", abyssal_bricks, None),
    "tide_rune": ("Tide Rune", "sunken_vault", tide_rune, None),
    "vault_stone": ("Vault Stone", "buried_vault", vault_stone, None),
    "vault_lock": ("Ancient Lock", "buried_vault", vault_lock, vault_stone),
    "cloud_marble": ("Cloud Marble", "sky_citadel", cloud_marble, None),
    "sky_rune": ("Sky Rune", "sky_citadel", sky_rune, None),
    "forge_bricks": ("Forge Bricks", "nether_forge", forge_bricks, None),
    "molten_core": ("Molten Core", "nether_forge", molten_core, None),
    "cursed_sandstone": ("Cursed Sandstone", "desert_tomb", cursed_sandstone, None),
    "scarab_tile": ("Scarab Tile", "desert_tomb", scarab_tile, None),
    "frost_bricks": ("Frost Bricks", "frozen_bastion", frost_bricks, None),
    "rune_ice": ("Rune Ice", "frozen_bastion", rune_ice, None),
    "mossy_ruin": ("Mossy Ruin", "overgrown_labyrinth", mossy_ruin, mossy_ruin_top),
    "thorn_vines": ("Thorn Vines", "overgrown_labyrinth", thorn_vines, None),
    "sculk_bricks": ("Sculk Bricks", "watchers_hollow", sculk_bricks, None),
    "watcher_eye": ("Watcher Eye", "watchers_hollow", watcher_eye, None),
}
NOTE_BASE = 1  # notes 1.. are ours (note 0 is what a fresh note block gets)


def state(name):
    return {"instrument": "custom_head", "note": str(NOTE_BASE + list(BLOCKS).index(name)), "powered": "false"}


def png(img):
    b = io.BytesIO()
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(b, "PNG")
    return b.getvalue()


def main():
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(BEDROCK, exist_ok=True)
    spec = {}
    textures = {}
    for name, (display, structure, side, top) in BLOCKS.items():
        textures[name] = png(side())
        if top:
            textures[name + "_top"] = png(top())
        spec[name] = {"display": display, "structure": structure, "note": int(state(name)["note"]), "top": bool(top)}
    for t, data in textures.items():
        with open(os.path.join(OUT, t + ".png"), "wb") as f:
            f.write(data)
    with open(os.path.join(ROOT, "src", "main", "resources", "vigil", "blocks.json"), "w") as f:
        json.dump(spec, f, indent=1)

    # Bedrock: a resource pack with the textures, and Geyser's mapping of the note block states.
    manifest = {"format_version": 2, "header": {"name": "Vigil Blocks", "description": "Custom blocks",
                                                 "uuid": "6b1d6a5e-3c1f-4e9a-9a51-6f0c2d7b8e11", "version": [1, 0, 0],
                                                 "min_engine_version": [1, 21, 0]},
                "modules": [{"type": "resources", "uuid": "b3f2c9a4-8d7e-4b61-a0c5-2e9f17d4c6a2", "version": [1, 0, 0]}]}
    terrain = {"resource_pack_name": "vigil", "texture_name": "atlas.terrain", "padding": 8, "num_mip_levels": 4,
               "texture_data": {f"vigil_{t}": {"textures": f"textures/blocks/vigil_{t}"} for t in textures}}
    pack = io.BytesIO()
    with zipfile.ZipFile(pack, "w", zipfile.ZIP_DEFLATED) as z:
        def add(path, data):
            info = zipfile.ZipInfo(path, (2024, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
        add("manifest.json", json.dumps(manifest, indent=1))
        add("textures/terrain_texture.json", json.dumps(terrain, indent=1))
        for t, data in sorted(textures.items()):
            add(f"textures/blocks/vigil_{t}.png", data)
        add("texts/en_US.lang", "".join(f"tile.vigil:{n}.name={d[0]}\n" for n, d in BLOCKS.items()))
    with open(os.path.join(BEDROCK, "vigil_blocks.mcpack"), "wb") as f:
        f.write(pack.getvalue())
    overrides = {}
    for name, (display, _, _, top) in BLOCKS.items():
        st = state(name)
        key = ",".join(f"{k}={v}" for k, v in sorted(st.items()))
        mats = {"*": {"texture": f"vigil_{name}", "render_method": "opaque", "face_dimming": True, "ambient_occlusion": True}}
        if top:
            mats["up"] = dict(mats["*"], texture=f"vigil_{name}_top")
            mats["down"] = dict(mats["*"], texture=f"vigil_{name}_top")
        overrides[key] = {"display_name": display, "material_instances": mats}
    mappings = {"format_version": 1, "blocks": {"minecraft:note_block": {
        "name": "vigil_block", "display_name": "Vigil Block", "only_override_states": True,
        "included_in_creative_inventory": False, "state_overrides": overrides}}}
    with open(os.path.join(BEDROCK, "vigil_blocks_mappings.json"), "w") as f:
        json.dump(mappings, f, indent=1)
    sheet = Image.new("RGB", (len(textures) * 18 * 4, 18 * 4), (20, 24, 32))
    for i, (t, data) in enumerate(sorted(textures.items())):
        sheet.paste(Image.open(io.BytesIO(data)).resize((64, 64), Image.NEAREST), (i * 72 + 4, 4))
    sheet.save(os.path.join(OUT, "_sheet.png"))
    print("blocks:", len(BLOCKS), "textures:", len(textures))


if __name__ == "__main__":
    main()
