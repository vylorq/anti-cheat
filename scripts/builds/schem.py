"""Turns a Sponge schematic (.schem, version 2 or 3, made in 1.21) into a Vigil build file the mod places (Builds.java).

    python3 scripts/builds/schem.py <file.schem> <name> [spawn x y z]

The spawn is the spot (inside the schematic) that lands on the owner's feet. Without one: the schematic's own offset
when it has one (where its maker stood), else the middle of its bottom.
"""
import gzip
import json
import os
import sys

import nbtlib

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "src", "main", "resources", "vigil", "builds")
RENAMED = {"minecraft:grass": "minecraft:short_grass", "minecraft:chain": "minecraft:iron_chain"}
# Block entity data the game fills in by itself (left out so the file stays small).
SKIP = {"Id", "id", "Pos", "x", "y", "z", "keepPacked", "CookingTimes", "CookingTotalTimes"}


def varints(data):
    out, i, n = [], 0, len(data)
    while i < n:
        v = s = 0
        while True:
            b = data[i]
            i += 1
            v |= (b & 0x7F) << s
            s += 7
            if not b & 0x80:
                break
        out.append(v)
    return out


def empty(v):
    return isinstance(v, (nbtlib.tag.List, nbtlib.tag.Compound, list, dict)) and len(v) == 0


def state(name):
    base, _, props = name.partition("[")
    base = RENAMED.get(base, base)
    return base + ("[" + props if props else "")


def convert(path, name, spawn=None):
    root = nbtlib.load(path)
    r = root.get("Schematic", root)
    w, h, l = int(r["Width"]), int(r["Height"]), int(r["Length"])
    blocks = r.get("Blocks", r)  # version 3 keeps them under Blocks
    pal = {int(v): state(str(k)) for k, v in blocks["Palette"].items()}
    raw = blocks["Data"] if "Data" in blocks and "Palette" in blocks and blocks is not r else r["BlockData"]
    vals = varints(bytes(int(b) & 0xFF for b in raw))
    assert len(vals) == w * h * l, (len(vals), w * h * l)

    # Same order the mod reads: y, then z, then x (the schematic's own order too).
    palette, index, runs = [], {}, []
    last, count = None, 0
    for v in vals:
        st = pal[v]
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
    for b in blocks.get("BlockEntities", []):
        x, y, z = (int(c) for c in b["Pos"])
        data = b.get("Data", b)
        keep = {k: v for k, v in data.items() if k not in SKIP and not empty(v)}
        if keep:
            bes.append([x, y, z, nbtlib.Compound(keep).snbt(), None])

    if spawn is None:
        off = [int(c) for c in r.get("Offset", [0, 0, 0])]
        spawn = [-off[0], -off[1], -off[2]] if any(off) else [w // 2, 0, l // 2]
    out = {"name": name, "size": [w, h, l], "spawn": list(spawn), "origin": [0, 0, 0], "palette": palette,
           "runs": runs, "blockEntities": bes, "entities": [], "functions": {}, "adventure": False}
    os.makedirs(OUT, exist_ok=True)
    dest = os.path.join(OUT, name + ".json.gz")
    with gzip.open(dest, "wt", encoding="utf-8", compresslevel=9) as f:
        json.dump(out, f, separators=(",", ":"))
    print(name, "size", w, h, l, "palette", len(palette), "runs", len(runs) // 2, "block entities", len(bes),
          "spawn", spawn, "file", os.path.getsize(dest))


if __name__ == "__main__":
    convert(sys.argv[1], sys.argv[2], [int(v) for v in sys.argv[3:6]] if len(sys.argv) >= 6 else None)
