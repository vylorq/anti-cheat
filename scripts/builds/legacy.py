"""Turns an old MCEdit/WorldEdit .schematic (numeric block ids, before 1.13) into a Vigil build file (Builds.java).

    python3 scripts/builds/legacy.py <file.schematic> <name> <legacy.json>

legacy.json is the id:data -> block table from minecraft-data (data/pc/common/legacy.json). Blocks are renamed to
1.21.11, and fences, walls, panes and bars are joined to their neighbours (the old format didn't store that). Signs
keep their text; mobs are left out. The owner stands in the middle, level with its ground.
"""
import gzip
import json
import os
import sys

import nbtlib
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import convert  # noqa: E402  (text and snbt helpers)

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "src", "main", "resources", "vigil", "builds")
# 1.13 names that changed since
RENAMED = {"minecraft:grass": "minecraft:short_grass", "minecraft:sign": "minecraft:oak_sign",
           "minecraft:wall_sign": "minecraft:oak_wall_sign", "minecraft:stone_slab": "minecraft:smooth_stone_slab",
           "minecraft:chain": "minecraft:iron_chain"}
JOINS = ("_fence", "_wall", "glass_pane", "iron_bars")


def split(state):
    base, _, rest = state.partition("[")
    props = dict(kv.split("=") for kv in rest.rstrip("]").split(",")) if rest else {}
    return base, props


def join(base, props):
    return base + ("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else "")


def main(path, name, legacy):
    r = nbtlib.load(path)
    r = r.get("Schematic", r)
    w, h, l = int(r["Width"]), int(r["Height"]), int(r["Length"])
    ids = np.array(r["Blocks"], dtype=np.int32) & 0xFF
    if "AddBlocks" in r:
        add = np.array(r["AddBlocks"], dtype=np.int32) & 0xFF
        hi = np.empty(len(ids), dtype=np.int32)
        hi[0::2] = (add >> 4) & 0xF
        hi[1::2] = add[: (len(ids) + 1) // 2][: len(hi[1::2])] & 0xF
        ids |= hi << 8
    data = np.array(r["Data"], dtype=np.int32) & 0xF
    table = json.load(open(legacy))["blocks"]
    cache = {}
    grid = np.empty(len(ids), dtype=object)
    for i, (b, d) in enumerate(zip(ids.tolist(), data.tolist())):
        key = (b, d)
        if key not in cache:
            st = table.get(f"{b}:{d}") or table.get(f"{b}:0") or "minecraft:air"
            base, props = split(st)
            cache[key] = (RENAMED.get(base, base), props)
        grid[i] = cache[key]
    grid = grid.reshape(h, l, w)

    def solid(y, z, x):
        if not (0 <= y < h and 0 <= z < l and 0 <= x < w):
            return False
        base = grid[y, z, x][0]
        return base not in ("minecraft:air", "minecraft:water", "minecraft:lava") and not any(
            k in base for k in ("grass", "flower", "torch", "sign", "button", "lever", "rail", "carpet", "dandelion",
                                "poppy", "bluet", "daisy", "sunflower", "sugar_cane", "fire", "tall_grass", "slab",
                                "stairs", "trapdoor"))

    def joins(y, z, x):
        if not (0 <= y < h and 0 <= z < l and 0 <= x < w):
            return False
        base = grid[y, z, x][0]
        return any(j in base for j in JOINS)

    palette, index, runs = [], {}, []
    last, count = None, 0
    for y in range(h):
        for z in range(l):
            for x in range(w):
                base, props = grid[y, z, x]
                if any(j in base for j in JOINS):
                    props = dict(props)
                    wall = base.endswith("_wall")
                    for side, dz, dx in (("north", -1, 0), ("south", 1, 0), ("west", 0, -1), ("east", 0, 1)):
                        on = joins(y, z + dz, x + dx) or solid(y, z + dz, x + dx)
                        props[side] = ("low" if on else "none") if wall else ("true" if on else "false")
                    if wall:
                        props["up"] = "true"
                st = join(base, props)
                if st not in index:
                    index[st] = len(palette)
                    palette.append(st)
                i = index[st]
                if i == last:
                    count += 1
                else:
                    if last is not None:
                        runs += [count, last]
                    last, count = i, 1
    runs += [count, last]

    bes = []
    for t in r.get("TileEntities", []):
        if str(t["id"]) in ("Sign", "minecraft:sign"):
            t = dict(t)
            for k in ("Text1", "Text2", "Text3", "Text4"):
                v = str(t.get(k, ""))
                if v in ("", "null"):
                    t[k] = '""'
                elif not v.startswith(("{", "[", '"')):
                    t[k] = json.dumps(v)  # plain text from very old signs
            bes.append([int(t["x"]), int(t["y"]), int(t["z"]), convert.snbt(convert.sign(t)), None])

    # The owner stands in the middle, level with the build's ground (its surface at the corner, outside any walls).
    cx, cz = w // 2, l // 2
    cy = 0
    for y in range(h - 1, -1, -1):
        if grid[y, 0, 0][0] not in ("minecraft:air", "minecraft:short_grass", "minecraft:tall_grass"):
            cy = y + 1
            break
    out = {"name": name, "size": [w, h, l], "spawn": [cx, cy, cz], "origin": [0, 0, 0], "palette": palette,
           "runs": runs, "blockEntities": bes, "entities": [], "functions": {}, "adventure": False}
    dest = os.path.join(OUT, name + ".json.gz")
    with gzip.open(dest, "wt", encoding="utf-8", compresslevel=9) as f:
        json.dump(out, f, separators=(",", ":"))
    print(name, "size", w, h, l, "palette", len(palette), "runs", len(runs) // 2, "signs", len(bes), "spawn", [cx, cy, cz],
          "file", os.path.getsize(dest))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
