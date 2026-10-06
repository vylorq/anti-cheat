"""Minimal Anvil region reader (1.16-1.20 chunk formats): yields (x, y, z, name, props) for every block."""
import io, os, struct, zlib, gzip, math
import nbtlib


def chunks(region_dir):
    for fn in os.listdir(region_dir):
        if not fn.endswith(".mca"):
            continue
        rx, rz = map(int, fn.split(".")[1:3])
        data = open(os.path.join(region_dir, fn), "rb").read()
        if len(data) < 8192:
            continue
        for i in range(1024):
            off = int.from_bytes(data[i * 4:i * 4 + 3], "big")
            cnt = data[i * 4 + 3]
            if off == 0 or cnt == 0:
                continue
            start = off * 4096
            length = struct.unpack(">I", data[start:start + 4])[0]
            comp = data[start + 4]
            raw = data[start + 5:start + 4 + length]
            if comp == 2:
                raw = zlib.decompress(raw)
            elif comp == 1:
                raw = gzip.decompress(raw)
            else:
                continue
            tag = nbtlib.File.parse(io.BytesIO(raw))
            yield tag


def _unpack(longs, bits, count, spanning):
    out = []
    mask = (1 << bits) - 1
    if spanning:
        total = 0
        acc = 0
        big = 0
        for i, l in enumerate(longs):
            big |= (int(l) & 0xFFFFFFFFFFFFFFFF) << (64 * i)
        for i in range(count):
            out.append((big >> (i * bits)) & mask)
        return out
    per = 64 // bits
    for l in longs:
        v = int(l) & 0xFFFFFFFFFFFFFFFF
        for j in range(per):
            out.append((v >> (j * bits)) & mask)
            if len(out) == count:
                return out
    return out


def blocks(region_dir, skip_air=True):
    for tag in chunks(region_dir):
        root = tag.get("Level", tag)
        cx, cz = int(root["xPos"]), int(root["zPos"])
        sections = root.get("sections") or root.get("Sections") or []
        for sec in sections:
            sy = int(sec["Y"])
            if "block_states" in sec:
                bs = sec["block_states"]
                pal = bs["palette"]
                data = bs.get("data")
                spanning = False
            elif "Palette" in sec:
                pal = sec["Palette"]
                data = sec.get("BlockStates")
                spanning = False
            else:
                continue
            names = [(str(p["Name"]).replace("minecraft:", ""), {str(k): str(v) for k, v in p.get("Properties", {}).items()}) for p in pal]
            if data is None or len(names) == 1:
                idx = [0] * 4096
            else:
                bits = max(4, math.ceil(math.log2(len(names))))
                idx = _unpack(data, bits, 4096, spanning)
            for i, k in enumerate(idx):
                n, props = names[k] if k < len(names) else ("air", {})
                if skip_air and n in ("air", "cave_air", "void_air"):
                    continue
                y = sy * 16 + (i >> 8)
                z = cz * 16 + ((i >> 4) & 15)
                x = cx * 16 + (i & 15)
                yield x, y, z, n, props


def block_entities(region_dir, box):
    x0, y0, z0, x1, y1, z1 = box
    for tag in chunks(region_dir):
        root = tag.get("Level", tag)
        for be in root.get("block_entities") or root.get("TileEntities") or []:
            x, y, z = int(be["x"]), int(be["y"]), int(be["z"])
            if x0 <= x <= x1 and y0 <= y <= y1 and z0 <= z <= z1:
                yield be
