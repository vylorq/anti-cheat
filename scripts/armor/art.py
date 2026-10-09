"""Makes the armour, gem and Ruby tool textures by recolouring the game's own (diamond / netherite / leather) art
with each set's colour ramp, the way most mods do it. The results are kept in scripts/armor/built and the pack
builder (scripts/owner-pack/build.py) puts them in the resource pack.

    python3 scripts/armor/art.py <folder with the game's textures, e.g. minecraft-assets data/1.21.9>
"""
import os
import sys

from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "built")

RAMPS = {
    "emerald": ["#0b3d1e", "#127a35", "#1fb04c", "#5be37f", "#d2ffdc"],
    "obsidian": ["#07040c", "#1a0e2b", "#32204f", "#563c86", "#a587d8"],
    "phantom": ["#24374a", "#456985", "#76a3c0", "#b3d8e8", "#f2fcff"],
    "tide": ["#06303a", "#0e5866", "#1c8a8f", "#4fc2b5", "#c2f5e8"],
    "abyssal": ["#03081b", "#0a1b47", "#15347b", "#2d63b8", "#86bdff"],
    "colossus": ["#111316", "#2a2e34", "#484f58", "#727b86", "#b2bac4"],
    "tempest": ["#0a2130", "#134e67", "#2189a8", "#58c8ea", "#dcf8ff"],
    "inferno": ["#260600", "#661800", "#b03a00", "#f07b12", "#ffda74"],
    "dune": ["#3a280b", "#77571d", "#b68f3c", "#e3c574", "#fff3c8"],
    "glacier": ["#1a3350", "#386b95", "#6ea8d0", "#b4e3f6", "#ffffff"],
    "thornback": ["#0d2008", "#214813", "#3a7821", "#6aaf39", "#c6f08a"],
    "hollow": ["#08050e", "#1c112e", "#392061", "#6a3fa8", "#c9a2ff"],
    "ruby": ["#36000a", "#77091c", "#bb1530", "#ec4a5c", "#ffc6cc"],
    "sapphire": ["#000d36", "#0a2878", "#1445bd", "#3f7ff0", "#c2dbff"],
    "topaz": ["#3a1d00", "#874800", "#d68812", "#ffc94a", "#fff4c9"],
    "voidstone": ["#000000", "#10031f", "#280a4b", "#58209b", "#c890ff"],
    "bloodstone": ["#100000", "#3d0000", "#7a0606", "#bf1717", "#ff6a6a"],
}
# Which of the game's armour each set is drawn from.
SHAPE = {"abyssal": "netherite", "colossus": "netherite", "tempest": "netherite", "inferno": "netherite",
         "dune": "netherite", "glacier": "netherite", "thornback": "netherite", "hollow": "netherite"}
ARMOR_SETS = ["emerald", "obsidian", "phantom", "tide", "abyssal", "colossus", "tempest", "inferno", "dune", "glacier",
              "thornback", "hollow", "ruby", "sapphire"]
PIECES = ["helmet", "chestplate", "leggings", "boots"]
GEMS = {"ruby": "emerald", "sapphire": "diamond", "topaz": "quartz", "voidstone": "echo_shard", "bloodstone": "amethyst_shard"}
# Raw chunk and ore each gem is drawn from (ore: the game's ore, its spots recoloured; deepslate or stone).
RAWS = {"ruby": "raw_copper", "sapphire": "raw_iron", "topaz": "raw_gold", "voidstone": "raw_iron", "bloodstone": "raw_copper"}
ORE_FROM = {"ruby": ("emerald_ore", True), "sapphire": ("diamond_ore", False), "topaz": ("gold_ore", False),
            "voidstone": ("diamond_ore", True), "bloodstone": ("redstone_ore", False)}
# Which gems grow in deepslate (the rest in stone).
ORES = {"ruby": True, "sapphire": False, "topaz": False, "voidstone": True, "bloodstone": False}
TOOLS = ["sword", "pickaxe", "axe", "shovel", "hoe"]


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def lum(p):
    return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]


def recolor(img, ramp, only=None):
    """Maps every pixel's brightness (stretched over the picture's own range) onto the ramp; keeps alpha."""
    img = img.convert("RGBA")
    px = img.load()
    stops = [hexc(c) for c in ramp]
    pick = [(x, y) for y in range(img.height) for x in range(img.width)
            if px[x, y][3] > 0 and (only is None or only(px[x, y]))]
    if not pick:
        return img
    ls = [lum(px[x, y]) for x, y in pick]
    lo, hi = min(ls), max(ls)
    span = max(1.0, hi - lo)
    out = img.copy()
    po = out.load()
    for (x, y), l in zip(pick, ls):
        t = (l - lo) / span * (len(stops) - 1)
        i = min(len(stops) - 2, int(t))
        f = t - i
        a, b = stops[i], stops[i + 1]
        c = tuple(round(a[k] + (b[k] - a[k]) * f) for k in range(3))
        po[x, y] = c + (px[x, y][3],)
    return out


# Each set's second colour: its trim (the edges of every plate) and its gem.
ACCENT = {
    "emerald": ["#6b4a00", "#c89211", "#ffe066"], "obsidian": ["#5a0a6e", "#c23cff", "#f2b8ff"],
    "phantom": ["#4b4f63", "#a8b0c8", "#ffffff"], "tide": ["#7a5200", "#e0a521", "#fff0a0"],
    "abyssal": ["#00505a", "#18d6e8", "#bafcff"], "colossus": ["#6b1600", "#ff6a00", "#ffd27a"],
    "tempest": ["#7a6200", "#ffe14a", "#fffbd0"], "inferno": ["#1a0a00", "#3b1f0c", "#ffd84a"],
    "dune": ["#0a3a6b", "#2f7fd6", "#a8dcff"], "glacier": ["#0a2a66", "#2a6bd6", "#9fd0ff"],
    "thornback": ["#3a1f0a", "#7a4a1f", "#d6a35a"], "hollow": ["#6b4a00", "#d6a821", "#fff0a0"],
    "ruby": ["#6b4a00", "#d6a821", "#fff0a0"], "sapphire": ["#4a4f5c", "#c4ccd8", "#ffffff"],
}


def trim(img, accent, piece=None):
    """Outlines darker, a ring of the accent colour just inside, and the set's gem on the chest / eyes on the helm."""
    img = img.convert("RGBA")
    w, h = img.size
    px = img.load()
    a = [hexc(c) for c in accent]

    def solid(x, y):
        return 0 <= x < w and 0 <= y < h and px[x, y][3] > 0

    edge = {(x, y) for y in range(h) for x in range(w) if solid(x, y)
            and any(not solid(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))}
    ring = {(x, y) for y in range(h) for x in range(w) if solid(x, y) and (x, y) not in edge
            and any((x, y + dy) in edge for dy in (1, -1)) and (x, y - 1) in edge}
    out = img.copy()
    po = out.load()
    for x, y in edge:
        r, g, b, al = px[x, y]
        po[x, y] = (r * 45 // 100, g * 45 // 100, b * 45 // 100, al)
    for x, y in ring:
        l = lum(px[x, y]) / 255
        c = a[0] if l < 0.35 else a[1] if l < 0.7 else a[2]
        po[x, y] = c + (px[x, y][3],)
    cx = sum(x for x, _ in ring) // max(1, len(ring)) if ring else w // 2
    if piece == "chestplate":
        # a gem in the middle of the chest
        gy = h // 2 - 2
        for dx, dy, k in ((0, 0, 2), (1, 0, 1), (0, 1, 1), (1, 1, 0)):
            if solid(cx - 1 + dx, gy + dy):
                po[cx - 1 + dx, gy + dy] = a[k] + (255,)
    if piece == "helmet":
        # two glowing eyes in the visor
        for y in range(h // 2, h - 3):
            row = [x for x in range(w) if solid(x, y) and (x, y) not in edge]
            if len(row) >= 8:
                for ex in (row[0] + 2, row[-1] - 2):
                    po[ex, y] = a[2] + (255,)
                break
    return out


def cyan(p):
    """The gem part of a diamond tool (not the wooden handle)."""
    return p[2] > p[0] + 10


def main(src):
    os.makedirs(OUT, exist_ok=True)
    for s in ARMOR_SETS:
        shape = SHAPE.get(s, "diamond")
        for layer in ("humanoid", "humanoid_leggings"):
            img = Image.open(os.path.join(src, "entity", "equipment", layer, shape + ".png"))
            recolor(img, RAMPS[s]).save(os.path.join(OUT, f"{s}_{layer}.png"))
        for piece in PIECES:
            img = Image.open(os.path.join(src, "items", f"{shape}_{piece}.png"))
            trim(recolor(img, RAMPS[s]), ACCENT[s], piece).save(os.path.join(OUT, f"{s}_{piece}.png"))
    for g, (ore, deep) in ORE_FROM.items():
        o = Image.open(os.path.join(src, "blocks", ("deepslate_" if deep else "") + ore + ".png")).convert("RGBA")
        bg = Image.open(os.path.join(src, "blocks", "deepslate.png" if deep else "stone.png")).convert("RGBA")
        po, pb = o.load(), bg.load()
        spots = Image.new("RGBA", o.size, (0, 0, 0, 0))
        ps = spots.load()
        for y in range(o.height):
            for x in range(o.width):
                a_, b_ = po[x, y], pb[x, y]
                if abs(a_[0] - b_[0]) + abs(a_[1] - b_[1]) + abs(a_[2] - b_[2]) > 40:
                    ps[x, y] = a_
        out = bg.copy()
        out.alpha_composite(recolor(spots, RAMPS[g]))
        out.save(os.path.join(OUT, f"ore_{g}.png"))
        recolor(Image.open(os.path.join(src, "items", RAWS[g] + ".png")), RAMPS[g]).save(os.path.join(OUT, f"raw_{g}.png"))
    for g, base in GEMS.items():
        recolor(Image.open(os.path.join(src, "items", base + ".png")), RAMPS[g]).save(os.path.join(OUT, f"gem_{g}.png"))
    for tool in TOOLS:
        img = Image.open(os.path.join(src, "items", f"diamond_{tool}.png"))
        recolor(img, RAMPS["ruby"], cyan).save(os.path.join(OUT, f"ruby_{tool}.png"))
    print("made", len(os.listdir(OUT)), "textures in", OUT)


if __name__ == "__main__":
    main(sys.argv[1])
