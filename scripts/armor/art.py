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
    "voidstone": ["#0a0214", "#25094a", "#4a1890", "#8040e0", "#e0c0ff"],
    "bloodstone": ["#100000", "#3d0000", "#7a0606", "#bf1717", "#ff6a6a"],
    "worldbreaker": ["#07060a", "#1e1a24", "#5a4318", "#c9952a", "#fff0a8"],
    "emberite": ["#2a0500", "#7a1a00", "#d4480a", "#ff9a2a", "#ffe9a0"],
    "frostshard": ["#0b2a4a", "#1f5f94", "#4fa6e0", "#a6e4ff", "#f2fdff"],
    "starmetal": ["#1a1630", "#3b3a6e", "#7d84c9", "#c6ccf5", "#ffffff"],
    "soulstone": ["#021417", "#06363c", "#0f6f73", "#3ec6c0", "#c8fff6"],
    "ember": ["#1c0400", "#5e1400", "#b23a06", "#f07a1a", "#ffd27a"],
    "silk": ["#2a2338", "#544a73", "#8f86b5", "#cfc8ee", "#fbf8ff"],
    "tidecrown": ["#03243a", "#0b4f6e", "#1f8ea3", "#6fd8d0", "#e6fff6"],
    "golem": ["#2a2a2a", "#55534f", "#8b877e", "#c7c1b2", "#f4efe2"],
    "shadowstep": ["#020203", "#0e0d14", "#22202e", "#3d3a52", "#7a76a0"],
}
# Which of the game's armour each set is drawn from.
SHAPE = {"abyssal": "netherite", "colossus": "netherite", "tempest": "netherite", "inferno": "netherite",
         "dune": "netherite", "glacier": "netherite", "thornback": "netherite", "hollow": "netherite",
         "worldbreaker": "netherite", "ember": "netherite", "golem": "netherite", "shadowstep": "netherite",
         "silk": "chainmail", "tidecrown": "diamond"}
ARMOR_SETS = ["emerald", "obsidian", "phantom", "tide", "abyssal", "colossus", "tempest", "inferno", "dune", "glacier",
              "thornback", "hollow", "ruby", "sapphire", "worldbreaker",
              "ember", "silk", "tidecrown", "golem", "shadowstep"]
PIECES = ["helmet", "chestplate", "leggings", "boots"]
GEMS = {"ruby": "emerald", "sapphire": "diamond", "topaz": "quartz", "voidstone": "echo_shard", "bloodstone": "amethyst_shard",
        "emberite": "blaze_powder", "frostshard": "prismarine_crystals", "starmetal": "iron_ingot", "soulstone": "amethyst_shard"}
# Raw chunk and ore each gem is drawn from (ore: the game's ore, its spots recoloured; deepslate or stone).
RAWS = {"ruby": "raw_copper", "sapphire": "raw_iron", "topaz": "raw_gold", "voidstone": "raw_iron", "bloodstone": "raw_copper",
        "emberite": "raw_gold", "frostshard": "raw_iron", "starmetal": "raw_iron", "soulstone": "raw_copper"}
ORE_FROM = {"ruby": ("emerald_ore", True), "sapphire": ("lapis_ore", False), "topaz": ("gold_ore", False),
            "voidstone": ("diamond_ore", True), "bloodstone": ("redstone_ore", False),
            "frostshard": ("diamond_ore", False), "soulstone": ("lapis_ore", True)}
# Newer ores: (ore texture, the rock it sits in): their spots are what differs from the plain rock.
ORE_ON = {"emberite": ("nether_gold_ore", "netherrack"), "starmetal": ("emerald_ore", "blackstone")}
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
    "worldbreaker": ["#0a4a6b", "#3fd0ff", "#e0faff"],
    "ember": ["#3a1a00", "#ffb000", "#fff3a0"], "silk": ["#3a2a5a", "#b78cff", "#f2e6ff"],
    "tidecrown": ["#6b4a00", "#ffd04a", "#fff6c8"], "golem": ["#3a1600", "#c05a1a", "#ffb27a"],
    "shadowstep": ["#2a0040", "#a020ff", "#e6b0ff"],
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


def bolts(img, layer):
    """Worldbreaker: cyan lightning cracks across the worn armour (chest, arms, legs and boots)."""
    img = img.convert("RGBA")
    px = img.load()
    glow, core = hexc("#3fd0ff"), hexc("#e0faff")
    paths = {"humanoid": [[(23, 20), (22, 22), (24, 24), (23, 26), (25, 28), (24, 31)],
                          [(44, 20), (45, 23), (44, 26), (46, 29)], [(4, 20), (5, 23), (4, 26), (6, 29)],
                          [(10, 8), (11, 10), (9, 12), (11, 14)]],
             "humanoid_leggings": [[(5, 20), (4, 23), (6, 26), (5, 29)], [(22, 20), (23, 22), (21, 24)]]}
    for line in paths[layer]:
        for (x0, y0), (x1, y1) in zip(line, line[1:]):
            n = max(abs(x1 - x0), abs(y1 - y0), 1)
            for k in range(n + 1):
                x, y = round(x0 + (x1 - x0) * k / n), round(y0 + (y1 - y0) * k / n)
                if 0 <= x < img.width and 0 <= y < img.height and px[x, y][3] > 0:
                    px[x, y] = core + (255,) if k in (0, n) else glow + (255,)
    return img


def main(src):
    os.makedirs(OUT, exist_ok=True)
    for s in ARMOR_SETS:
        shape = SHAPE.get(s, "diamond")
        for layer in ("humanoid", "humanoid_leggings"):
            img = Image.open(os.path.join(src, "entity", "equipment", layer, shape + ".png"))
            img = recolor(img, RAMPS[s])
            if s == "worldbreaker":
                img = bolts(img, layer)
            img.save(os.path.join(OUT, f"{s}_{layer}.png"))
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
                a_ = po[x, y]
                hi_, lo_ = max(a_[:3]), min(a_[:3])
                # only the ore's coloured pixels (the stone around them is grey)
                if hi_ > 0 and (hi_ - lo_) / hi_ > 0.28 and hi_ > 40:
                    ps[x, y] = a_
        out = bg.copy()
        # a dark rim round each spot (the gem sits in the rock), then the recoloured spots
        pout = out.load()
        dark = hexc(RAMPS[g][0])
        for y in range(o.height):
            for x in range(o.width):
                if ps[x, y][3] == 0 and any(0 <= x + dx < o.width and 0 <= y + dy < o.height and ps[x + dx, y + dy][3] > 0
                                            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    r_, g_, b_, a_ = pout[x, y]
                    pout[x, y] = ((r_ + dark[0]) // 3, (g_ + dark[1]) // 3, (b_ + dark[2]) // 3, 255)
        out.alpha_composite(recolor(spots, RAMPS[g]))
        out.save(os.path.join(OUT, f"ore_{g}.png"))
        recolor(Image.open(os.path.join(src, "items", RAWS[g] + ".png")), RAMPS[g]).save(os.path.join(OUT, f"raw_{g}.png"))
    for g, (ore, rock) in ORE_ON.items():
        o = Image.open(os.path.join(src, "blocks", ore + ".png")).convert("RGBA")
        plain = Image.open(os.path.join(src, "blocks", ("netherrack" if ore.startswith("nether") else "stone") + ".png")).convert("RGBA")
        bg = Image.open(os.path.join(src, "blocks", rock + ".png")).convert("RGBA")
        po, pp = o.load(), plain.load()
        spots = Image.new("RGBA", o.size, (0, 0, 0, 0))
        ps = spots.load()
        for y in range(o.height):
            for x in range(o.width):
                if sum(abs(po[x, y][k] - pp[x, y][k]) for k in range(3)) > 60:
                    ps[x, y] = po[x, y]
        out = bg.copy()
        pout = out.load()
        dark = hexc(RAMPS[g][0])
        for y in range(o.height):
            for x in range(o.width):
                if ps[x, y][3] == 0 and any(0 <= x + dx < o.width and 0 <= y + dy < o.height and ps[x + dx, y + dy][3] > 0
                                            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    r_, g_, b_, a_ = pout[x, y]
                    pout[x, y] = ((r_ + dark[0]) // 3, (g_ + dark[1]) // 3, (b_ + dark[2]) // 3, 255)
        out.alpha_composite(recolor(spots, RAMPS[g]))
        out.save(os.path.join(OUT, f"ore_{g}.png"))
        recolor(Image.open(os.path.join(src, "items", RAWS[g] + ".png")), RAMPS[g]).save(os.path.join(OUT, f"raw_{g}.png"))
    for g, base in GEMS.items():
        recolor(Image.open(os.path.join(src, "items", base + ".png")), RAMPS[g]).save(os.path.join(OUT, f"gem_{g}.png"))
    for tool in TOOLS:
        img = Image.open(os.path.join(src, "items", f"diamond_{tool}.png"))
        recolor(img, RAMPS["ruby"], cyan).save(os.path.join(OUT, f"ruby_{tool}.png"))
    # Sapphire (3x3 mining) and Topaz (auto-smelting) pickaxes and shovels.
    for g in ("sapphire", "topaz"):
        for tool in ("pickaxe", "shovel"):
            img = Image.open(os.path.join(src, "items", f"diamond_{tool}.png"))
            recolor(img, RAMPS[g], cyan).save(os.path.join(OUT, f"{g}_{tool}.png"))
    print("made", len(os.listdir(OUT)), "textures in", OUT)


if __name__ == "__main__":
    main(sys.argv[1])
