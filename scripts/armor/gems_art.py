"""Hand-drawn gem art: each gem its own cut (not a recoloured diamond), its raw chunk, and its ore.

    python3 scripts/armor/gems_art.py <folder with the game's textures (for stone / deepslate)>

Writes gem_*.png, raw_*.png and ore_*.png into scripts/armor/built.
"""
import os
import sys

from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "built")

# Letters: O outline, D dark, d dark-mid, M mid, m light-mid, L light, H highlight, W white glint; anything else
# per-gem (see PAL).
PAL = {
    "ruby": {"O": "#2a0008", "D": "#6e0618", "d": "#960c22", "M": "#c4142e", "m": "#e0303f", "L": "#f2626a", "H": "#ffa3a8", "W": "#ffffff"},
    "sapphire": {"O": "#000a2e", "D": "#0a2366", "d": "#123494", "M": "#1c4fc4", "m": "#2f6ee6", "L": "#5b95ff", "H": "#a9c9ff", "W": "#ffffff"},
    "topaz": {"O": "#3a1a00", "D": "#7a3d00", "d": "#a55800", "M": "#d67e0a", "m": "#f0a020", "L": "#ffc24a", "H": "#ffe49a", "W": "#ffffff"},
    "voidstone": {"O": "#000000", "D": "#0c0418", "d": "#1b0a33", "M": "#2d1257", "m": "#4a1f8a", "L": "#7b3fd6", "H": "#c08cff", "W": "#f0e0ff"},
    "bloodstone": {"O": "#050a06", "D": "#0f2414", "d": "#18361e", "M": "#22482a", "m": "#2f5e37", "L": "#467a4e", "H": "#7fae86", "W": "#ff3b3b",
                   "R": "#b31212", "r": "#7a0a0a"},
}

# Ruby: a cushion cut, wider than tall, with its table and facets.
RUBY = [
    "................",
    "................",
    "....OOOOOOOO....",
    "...OHWLLLmmMO...",
    "..OHWLLLmmMMdO..",
    ".OLLHLLmmMMMddO.",
    ".OmLLmmmMMMdddO.",
    ".OmmmMMMMMddddO.",
    ".OMmMMMMMMdddDO.",
    ".OMMMMMMMddddDO.",
    "..OdMMMMMdddDO..",
    "...OddMMdddDO...",
    "....OdddddDO....",
    ".....OODDOO.....",
    "................",
    "................",
]
# Sapphire: a round brilliant, its star of facets.
SAPPHIRE = [
    "................",
    ".....OOOOOO.....",
    "...OOHLLLmMOO...",
    "..OHWHLLmmMMdO..",
    "..OHHLLmmMMMdO..",
    ".OLLLmmLMMMMddO.",
    ".OLLmmLLLMMddDO.",
    ".OmmmLLWLLMddDO.",
    ".OmmMMLLLMdddDO.",
    ".OMMMMMLMddddDO.",
    "..OMMMdMddddDO..",
    "..OdMddMdddDDO..",
    "...OOdddddDOO...",
    ".....OOOOOO.....",
    "................",
    "................",
]
# Topaz: a tall step-cut octagon.
TOPAZ = [
    "................",
    "......OOOO......",
    ".....OHWLmO.....",
    "....OHLLmmMO....",
    "....OLHLmmMO....",
    "....OLLmmMMO....",
    "....OLLmMMdO....",
    "....OmLmMMdO....",
    "....OmmMMddO....",
    "....OmMMMddO....",
    "....OMMMddDO....",
    "....OMMdddDO....",
    ".....OdddDO.....",
    "......OOOO......",
    "................",
    "................",
]
# Voidstone: jagged shards with a glowing core.
VOIDSTONE = [
    "................",
    "..........OO....",
    ".........OHmO...",
    "...OO...OHmMO...",
    "..OLmO..OLmdO...",
    "..OHmMOOLmMdO...",
    "...OmMLHWLmdO...",
    "...OdmLWHLmdO...",
    "....OdmLLmMdDO..",
    "...OOdMmmMddDO..",
    "..OLmdMMMdddO...",
    "..OHmdOdMddO....",
    "...OdO.OddO.....",
    "....O...OO......",
    "................",
    "................",
]
# Bloodstone: a polished dark green oval with red flecks.
BLOODSTONE = [
    "................",
    "................",
    ".....OOOOOO.....",
    "...OOHLLmmMOO...",
    "..OHWLmmRmMMdO..",
    "..OLLmmMMMMRdO..",
    ".OLmRmMMMMMddDO.",
    ".OmmMMMRrMMddDO.",
    ".OmMMMMMMMRddDO.",
    ".OMMRMMMdMddDDO.",
    "..OMMMMddRddDO..",
    "..OdMdddddDDDO..",
    "...OOdddDDDOO...",
    ".....OOOOOO.....",
    "................",
    "................",
]
# A raw chunk: rough rock with uncut crystals growing out of it (crystal letters, rock in grey/black).
RAW = [
    "................",
    "................",
    ".....OOO........",
    "....OLmMO.OOO...",
    "...OHLmMdOmMdO..",
    "..OHLmMMddMMddO.",
    "..OLmMMMdddMddO.",
    ".OmmMMMdddddDDO.",
    ".OmMMdddddDDDO..",
    ".OMMddDdddDDO...",
    "..OdddDDdDDDO...",
    "...ODDDODDDO....",
    "....OOO.OOO.....",
    "................",
    "................",
    "................",
]
ROCK = {"k": "#6b6b70", "K": "#8a8a90", "o": "#3e3e44"}
SHAPES = {"ruby": RUBY, "sapphire": SAPPHIRE, "topaz": TOPAZ, "voidstone": VOIDSTONE, "bloodstone": BLOODSTONE}
# Ore spots: small crystals (3 to 5 pixels) at these places on the stone, a highlight on each.
SPOTS = {
    "ruby": [(2, 3), (9, 2), (12, 8), (4, 10), (9, 12)],
    "sapphire": [(3, 2), (10, 4), (6, 8), (12, 11), (2, 12)],
    "topaz": [(2, 4), (8, 2), (11, 9), (5, 11), (13, 13)],
    "voidstone": [(4, 4), (11, 6), (6, 12)],
    "bloodstone": [(3, 3), (10, 3), (7, 8), (2, 12), (12, 12)],
}
CRYSTAL = ["..O..", ".OHO.", "OLmMO", ".OMd.", "..O.."]
DEEP = {"ruby": True, "voidstone": True}


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def draw(grid, pal):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(grid):
        for x, c in enumerate(row):
            if c in pal:
                px[x, y] = hexc(pal[c])
    return img


def ore(g, src):
    base = Image.open(os.path.join(src, "blocks", "deepslate.png" if DEEP.get(g) else "stone.png")).convert("RGBA")
    pal = dict(PAL[g])
    pal["O"] = "#1a1a1e" if not DEEP.get(g) else "#0b0b0e"
    px = base.load()
    for sx, sy in SPOTS[g]:
        for y, row in enumerate(CRYSTAL):
            for x, c in enumerate(row):
                X, Y = sx + x - 2, sy + y - 2
                if c in pal and 0 <= X < 16 and 0 <= Y < 16:
                    px[X, Y] = hexc(pal[c])
    return base


def main(src):
    os.makedirs(OUT, exist_ok=True)
    for g, shape in SHAPES.items():
        draw(shape, PAL[g]).save(os.path.join(OUT, f"gem_{g}.png"))
        draw(RAW, PAL[g]).save(os.path.join(OUT, f"raw_{g}.png"))
        ore(g, src).save(os.path.join(OUT, f"ore_{g}.png"))
    print("gems, raw chunks and ores drawn")


if __name__ == "__main__":
    main(sys.argv[1])
