"""Turns a build from a Minecraft world save (1.17-1.20) into a Vigil build file the mod places (Builds.java).

    python3 scripts/builds/convert.py <world dir> <name>

The output (src/main/resources/vigil/builds/<name>.json.gz) holds the whole box, ground included (palette + runs,
air too, so whatever stands where it's placed is cleared), the block entities and entities brought up to 1.21.11,
and the map's functions. Command block commands keep working wherever it's placed: their absolute coordinates become
{X:n}/{Y:n}/{Z:n} and their target selectors {SEL:...}, which the mod fills in with the real spot and with a limit to
the build's own area (so "tp @a" never pulls in anyone outside the map).
"""
import gzip
import json
import os
import re
import sys

import nbtlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import anvil  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "src", "main", "resources", "vigil", "builds")

# The builds: the box to copy (x0 y0 z0 x1 y1 z1), the spot the owner stands on (the map's spawn), and parts to
# leave out (other people's builds in the same world), which become plain flat ground.
BUILDS = {
    "the_prison": dict(box=(-67, 33, -48, 447, 65, 197), spawn=(0, 37, 0),
                       mask=[(290, -48, 447, 100)], flat={36: "grass_block", None: "dirt"}),
    "pvp_arena": dict(box=(-255, 16, 185, -95, 128, 378), spawn=(-291, 64, 280), mask=[], flat=None),
}

DECOR = {"minecraft:armor_stand", "minecraft:painting", "minecraft:item_frame", "minecraft:glow_item_frame",
         "minecraft:text_display", "minecraft:item_display", "minecraft:block_display"}
RENAMED = {"grass": "short_grass", "chain": "iron_chain"}
COLOURS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple",
           "blue", "brown", "green", "red", "black"]
PATTERNS = {"b": "base", "bl": "square_bottom_left", "br": "square_bottom_right", "tl": "square_top_left",
            "tr": "square_top_right", "bs": "stripe_bottom", "ts": "stripe_top", "ls": "stripe_left", "rs": "stripe_right",
            "cs": "stripe_center", "ms": "stripe_middle", "drs": "stripe_downright", "dls": "stripe_downleft",
            "ss": "small_stripes", "cr": "cross", "sc": "straight_cross", "bt": "triangle_bottom", "tt": "triangle_top",
            "bts": "triangles_bottom", "tts": "triangles_top", "ld": "diagonal_left", "rd": "diagonal_up_right",
            "lud": "diagonal_up_left", "rud": "diagonal_right", "mc": "circle", "mr": "rhombus", "vh": "half_vertical",
            "hh": "half_horizontal", "vhr": "half_vertical_right", "hhb": "half_horizontal_bottom", "bo": "border",
            "cbo": "curly_border", "gra": "gradient", "gru": "gradient_up", "bri": "bricks", "glb": "globe",
            "cre": "creeper", "sku": "skull", "flo": "flower", "moj": "mojang", "pig": "piglin"}


# ------------------------------------------------------------------ SNBT

def q(s):
    return '"' + str(s).replace("\\", "\\\\").replace('"', '\\"') + '"'


def snbt(v):
    """Python / nbtlib values as SNBT (keys quoted, typed numbers kept)."""
    if isinstance(v, nbtlib.tag.Byte):
        return f"{int(v)}b"
    if isinstance(v, nbtlib.tag.Short):
        return f"{int(v)}s"
    if isinstance(v, nbtlib.tag.Long):
        return f"{int(v)}L"
    if isinstance(v, nbtlib.tag.Float):
        return f"{float(v)}f"
    if isinstance(v, nbtlib.tag.Double):
        return f"{float(v)}d"
    if isinstance(v, nbtlib.tag.IntArray):
        return "[I;" + ",".join(str(int(x)) for x in v) + "]"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, nbtlib.tag.Int)):
        return str(int(v))
    if isinstance(v, float):
        return repr(v)
    if isinstance(v, Raw):
        return v.s
    if isinstance(v, str):
        return q(v)
    if isinstance(v, dict):
        return "{" + ",".join(f"{q(k)}:{snbt(x)}" for k, x in v.items()) + "}"
    if isinstance(v, (list, tuple)):
        return "[" + ",".join(snbt(x) for x in v) + "]"
    raise TypeError(type(v))


class Raw:
    """SNBT written as is (placeholders the mod fills in)."""

    def __init__(self, s):
        self.s = s


# ------------------------------------------------------------------ text components

def text(js):
    """A 1.20 JSON text component (string) as a 1.21.11 component (Python value, for snbt())."""
    if isinstance(js, str):
        try:
            v = json.loads(js)
        except json.JSONDecodeError:
            return js
    else:
        v = js
    return fix_text(v)


def fix_text(v):
    if isinstance(v, list):
        return [fix_text(x) for x in v]
    if not isinstance(v, dict):
        return v
    out = {}
    for k, x in v.items():
        if k == "clickEvent":
            act = x.get("action")
            ev = {"action": act}
            val = x.get("value", "")
            if act in ("run_command", "suggest_command"):
                ev["command"] = answer(val)
            elif act == "open_url":
                ev["url"] = val
            elif act == "change_page":
                ev["page"] = int(val) if str(val).isdigit() else 1
            else:
                ev["value"] = val
            out["click_event"] = ev
        elif k == "hoverEvent":
            ev = {"action": x.get("action")}
            if "contents" in x:
                ev["value"] = fix_text(x["contents"])
            elif "value" in x:
                ev["value"] = fix_text(x["value"])
            out["hover_event"] = ev
        elif k in ("extra", "with"):
            out[k] = [fix_text(e) for e in x]
        else:
            out[k] = x
    return out


def answer(cmd):
    """Players can't run /function; the map's chat answers go through the mod's /vigilbuild answer instead."""
    m = re.match(r"^/?function\s+(\S+)$", cmd.strip())
    return f"/vigilbuild answer {m.group(1)}" if m else cmd


# ------------------------------------------------------------------ items

def item(it):
    """A 1.17-1.20 item compound as a 1.21.11 one (id, count, components)."""
    if not it or "id" not in it:
        return None
    out = {"id": str(it["id"]), "count": int(it.get("Count", it.get("count", 1)))}
    comps = item_components(it.get("tag"), str(it["id"]))
    if comps:
        out["components"] = comps
    return out


def item_components(tag, item_id=""):
    c = {}
    if not tag:
        return c
    disp = tag.get("display", {})
    if "Name" in disp:
        c["minecraft:custom_name"] = text(str(disp["Name"]))
    if "Lore" in disp:
        c["minecraft:lore"] = [text(str(x)) for x in disp["Lore"]]
    if "color" in disp:
        c["minecraft:dyed_color"] = int(disp["color"])
    if "Enchantments" in tag:
        c["minecraft:enchantments"] = {ench(e["id"]): int(e["lvl"]) for e in tag["Enchantments"]}
    if "StoredEnchantments" in tag:
        c["minecraft:stored_enchantments"] = {ench(e["id"]): int(e["lvl"]) for e in tag["StoredEnchantments"]}
    if int(tag.get("Unbreakable", 0)):
        c["minecraft:unbreakable"] = {}
    if "CanPlaceOn" in tag:
        c["minecraft:can_place_on"] = {"blocks": [str(b) for b in tag["CanPlaceOn"]]}
    if "CanDestroy" in tag:
        c["minecraft:can_break"] = {"blocks": [str(b) for b in tag["CanDestroy"]]}
    if "Damage" in tag and int(tag["Damage"]):
        c["minecraft:damage"] = int(tag["Damage"])
    if "SkullOwner" in tag:
        prof = profile(tag["SkullOwner"])
        if prof:
            c["minecraft:profile"] = prof
    if "Potion" in tag:
        c["minecraft:potion_contents"] = {"potion": str(tag["Potion"])}
    return c


def ench(i):
    i = str(i)
    return i if ":" in i else "minecraft:" + i


def profile(owner):
    if isinstance(owner, str):
        return {"name": owner}
    p = {}
    if "Name" in owner:
        p["name"] = str(owner["Name"])
    if "Id" in owner:
        p["id"] = owner["Id"]
    tex = owner.get("Properties", {}).get("textures")
    if tex:
        p["properties"] = [{"name": "textures", "value": str(tex[0]["Value"])}]
    return p or None


def item_cmd(spec):
    """'stone_button{display:{...},CanPlaceOn:[...]}' (command form) as 'stone_button[custom_name=...,...]'."""
    m = re.match(r"^([a-z0-9_:.]+)(\{.*\})?$", spec, re.S)
    if not m or not m.group(2):
        return spec
    try:
        tag = nbtlib.parse_nbt(m.group(2))
    except Exception:
        return m.group(1)
    comps = item_components(tag, m.group(1))
    return m.group(1) + ("[" + ",".join(f"{k.replace('minecraft:', '')}={snbt(v)}" for k, v in comps.items()) + "]" if comps else "")


# ------------------------------------------------------------------ commands

COORD = re.compile(r"^-?\d+(\.\d+)?$")


def tokens(s):
    """Splits a command at spaces outside quotes, braces and brackets."""
    out, cur, depth, quote = [], "", 0, None
    i = 0
    while i < len(s):
        ch = s[i]
        if quote:
            cur += ch
            if ch == "\\" and i + 1 < len(s):
                cur += s[i + 1]
                i += 1
            elif ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
            cur += ch
        elif ch in "{[(":
            depth += 1
            cur += ch
        elif ch in "}])":
            depth -= 1
            cur += ch
        elif ch == " " and depth == 0:
            if cur:
                out.append(cur)
            cur = ""
        else:
            cur += ch
        i += 1
    if cur:
        out.append(cur)
    return out


def sel(t):
    return "{SEL:" + t + "}" if re.match(r"^@[apre](\[.*\])?$", t, re.S) else t


def xyz(ts, i):
    """Marks the coordinate triple at ts[i:i+3] (absolute numbers only); returns the index after it."""
    for k, axis in enumerate("XYZ"):
        if i + k < len(ts) and COORD.match(ts[i + k]):
            ts[i + k] = "{" + axis + ":" + ts[i + k] + "}"
    return i + 3


def command(cmd):
    """A 1.20 command made placeable: coordinates and selectors marked, items and texts in 1.21.11 form."""
    cmd = cmd.strip()
    if cmd.startswith("/"):
        cmd = cmd[1:]
    if not cmd:
        return cmd
    ts = tokens(cmd)
    return " ".join(convert(ts))


def convert(ts):
    if not ts:
        return ts
    head = ts[0]
    ts = [ts[0]] + [sel(t) for t in ts[1:]]
    if head == "execute":
        out = ["execute"]
        i = 1
        while i < len(ts):
            t = ts[i]
            if t == "run":
                return out + ["run"] + convert(unsel(ts[i + 1:]))
            if t == "positioned" and i + 1 < len(ts) and ts[i + 1] != "as":
                xyz(ts, i + 1)
                out += ts[i:i + 4]
                i += 4
                continue
            if t in ("if", "unless") and i + 1 < len(ts) and ts[i + 1] == "block":
                xyz(ts, i + 2)
                out += ts[i:i + 6]
                i += 6
                continue
            out.append(t)
            i += 1
        return out
    if head in ("tp", "teleport"):
        i = 1
        if i < len(ts) and not COORD.match(ts[i]) and not ts[i].startswith("~"):
            i += 1
        if i < len(ts) and (COORD.match(ts[i]) or ts[i].startswith("~")):
            j = xyz(ts, i)
            if j < len(ts) and ts[j] == "facing" and j + 1 < len(ts) and ts[j + 1] != "entity":
                xyz(ts, j + 1)
    elif head == "fill":
        xyz(ts, 1)
        xyz(ts, 4)
    elif head == "setblock":
        xyz(ts, 1)
    elif head == "clone":
        xyz(ts, 1)
        xyz(ts, 4)
        xyz(ts, 7)
    elif head == "particle":
        xyz(ts, 2)
    elif head == "playsound":
        xyz(ts, 4)
    elif head in ("summon",):
        xyz(ts, 2)
        if len(ts) > 5 and ts[5].startswith("{"):
            ts[5] = entity_nbt_cmd(ts[1], ts[5])
    elif head in ("spawnpoint",):
        xyz(ts, 2)
    elif head == "function" and len(ts) > 1:
        # The map's functions run through the mod (it places their coordinates too).
        return ["vigilbuild", "run", ts[1]]
    elif head == "setworldspawn":
        return ["say", "(setworldspawn skipped)"]
    elif head in ("give",):
        if len(ts) > 2:
            ts[2] = item_cmd(ts[2])
    elif head in ("tellraw",):
        if len(ts) > 2:
            ts = ts[:2] + [txt_arg(" ".join(ts[2:]))]
    elif head in ("title",):
        if len(ts) > 3 and ts[2] in ("title", "subtitle", "actionbar"):
            ts = ts[:3] + [txt_arg(" ".join(ts[3:]))]
    return ts


def unsel(ts):
    return [t[5:-1] if t.startswith("{SEL:") else t for t in ts]


def txt_arg(s):
    s = s.strip()
    try:
        v = json.loads(s)
    except json.JSONDecodeError:
        return s
    return snbt(fix_text(v))


def entity_nbt_cmd(kind, s):
    try:
        e = nbtlib.parse_nbt(s)
    except Exception:
        return s
    e = dict(e)
    e["id"] = kind if ":" in kind else "minecraft:" + kind
    return snbt(entity(e, None))


# ------------------------------------------------------------------ entities and block entities

def equipment(e):
    eq = {}
    for slot, name in zip(e.get("ArmorItems", []), ["feet", "legs", "chest", "head"]):
        it = item(slot)
        if it:
            eq[name] = it
    for slot, name in zip(e.get("HandItems", []), ["mainhand", "offhand"]):
        it = item(slot)
        if it:
            eq[name] = it
    return eq


def entity(e, rel):
    """An entity's data for /summon in 1.21.11 (no position, UUID or motion: the mod summons it in place)."""
    out = {}
    kind = str(e["id"])
    for k in ("NoAI", "Silent", "Invulnerable", "PersistenceRequired", "NoGravity", "Glowing", "CustomNameVisible",
              "Invisible", "ShowArms", "NoBasePlate", "Small", "Marker", "DisabledSlots", "Fixed", "Tags", "Health",
              "CanPickUpLoot", "Age", "Variant", "variant", "Pose", "billboard", "alignment", "line_width",
              "background", "see_through", "shadow", "transformation", "Rotation", "Fire", "Sitting", "CatType",
              "ItemRotation", "ItemDropChance", "VillagerData", "Facing"):
        if k in e:
            out[k] = e[k]
    if "CustomName" in e:
        out["CustomName"] = text(str(e["CustomName"]))
    if "text" in e:
        out["text"] = text(str(e["text"]))
    if "VillagerData" in out:
        vd = dict(out["VillagerData"])
        out["VillagerData"] = {k: str(v) if not isinstance(v, nbtlib.tag.Int) else v for k, v in vd.items()}
    eq = equipment(e)
    if eq:
        out["equipment"] = eq
    drops = {}
    for vals, names in ((e.get("ArmorDropChances"), ["feet", "legs", "chest", "head"]), (e.get("HandDropChances"), ["mainhand", "offhand"])):
        for v, n in zip(vals or [], names):
            drops[n] = nbtlib.tag.Float(float(v))
    if drops:
        out["drop_chances"] = drops
    if "Attributes" in e:
        out["attributes"] = [{"id": "minecraft:" + str(a["Name"]).replace("minecraft:", "").replace("generic.", ""),
                              "base": nbtlib.tag.Double(float(a["Base"]))} for a in e["Attributes"]]
    if "Item" in e:
        it = item(e["Item"])
        if it:
            out["Item"] = it
    if kind.endswith("painting") or kind.endswith("item_frame"):
        if rel is not None and "TileX" in e:
            out["block_pos"] = Raw("[I;{X:%d},{Y:%d},{Z:%d}]" % (int(e["TileX"]), int(e["TileY"]), int(e["TileZ"])))
        if "facing" in e:
            out["facing"] = e["facing"]
        if "Facing" in e and kind.endswith("painting"):
            out["facing"] = e["Facing"]
    if "Motive" in e:
        out["variant"] = str(e["Motive"])
    return out


def sign(b):
    out = {}
    if "front_text" in b:
        for side in ("front_text", "back_text"):
            t = b[side]
            out[side] = {"messages": [text(str(m)) for m in t["messages"]], "color": str(t.get("color", "black")),
                         "has_glowing_text": t.get("has_glowing_text", nbtlib.tag.Byte(0))}
        out["is_waxed"] = b.get("is_waxed", nbtlib.tag.Byte(0))
    else:
        out["front_text"] = {"messages": [text(str(b.get(f"Text{i}", '""'))) for i in range(1, 5)],
                             "color": str(b.get("Color", "black")), "has_glowing_text": b.get("GlowingText", nbtlib.tag.Byte(0))}
    return out


def block_entity(b):
    """(snbt to merge, command) for a block entity, or (None, None) when it needs nothing."""
    kind = str(b["id"]).replace("minecraft:", "")
    if kind in ("sign", "hanging_sign"):
        return snbt(sign(b)), None
    if kind == "banner":
        pats = [{"pattern": "minecraft:" + PATTERNS.get(str(p["Pattern"]), str(p["Pattern"])),
                 "color": COLOURS[int(p["Color"]) % 16]} for p in b.get("Patterns", [])]
        return (snbt({"patterns": pats}) if pats else None), None
    if kind == "skull":
        owner = b.get("SkullOwner") or b.get("ExtraType")
        prof = profile(owner) if owner else None
        return (snbt({"profile": prof}) if prof else None), None
    if kind in ("chest", "barrel", "shulker_box", "dispenser", "dropper", "hopper", "trapped_chest"):
        items = []
        for it in b.get("Items", []):
            x = item(it)
            if x:
                x["Slot"] = nbtlib.tag.Byte(int(it["Slot"]))
                items.append(x)
        return (snbt({"Items": items}) if items else None), None
    if kind == "command_block":
        extra = {"auto": b.get("auto", nbtlib.tag.Byte(0)), "TrackOutput": nbtlib.tag.Byte(0)}
        return snbt(extra), command(str(b.get("Command", "")))
    return None, None


# ------------------------------------------------------------------ the box

def state(name, props):
    name = RENAMED.get(name, name)
    if props:
        return f"minecraft:{name}[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]"
    return f"minecraft:{name}"


def convert_build(world, name):
    cfg = BUILDS[name]
    x0, y0, z0, x1, y1, z1 = cfg["box"]
    sx, sy, sz = x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1
    grid = {}
    for x, y, z, n, p in anvil.blocks(os.path.join(world, "region"), skip_air=False):
        if x0 <= x <= x1 and y0 <= y <= y1 and z0 <= z <= z1:
            grid[(x - x0, y - y0, z - z0)] = state(n, p)

    def masked(x, z):
        return any(mx0 <= x <= mx1 and mz0 <= z <= mz1 for mx0, mz0, mx1, mz1 in cfg["mask"])

    palette, index, runs = [], {}, []
    last, count = None, 0
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                if cfg["flat"] and masked(x + x0, z + z0):
                    ay = y + y0
                    st = "minecraft:" + (cfg["flat"].get(ay) or (cfg["flat"][None] if ay < 36 else "air"))
                else:
                    st = grid.get((x, y, z), "minecraft:air")
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
    for b in anvil.block_entities(os.path.join(world, "region"), (x0, y0, z0, x1, y1, z1)):
        bx, by, bz = int(b["x"]), int(b["y"]), int(b["z"])
        if cfg["flat"] and masked(bx, bz):
            continue
        data, cmd = block_entity(b)
        if data or cmd:
            bes.append([bx - x0, by - y0, bz - z0, data, cmd])

    ents = []
    edir = os.path.join(world, "entities")
    if os.path.isdir(edir):
        for tag in anvil.chunks(edir):
            for e in tag.get("Entities", []):
                kind = str(e["id"])
                # Only the decorations: no mobs (the owner didn't want the map's villagers and animals).
                if kind not in DECOR:
                    continue
                ex, ey, ez = (float(v) for v in e["Pos"])
                if not (x0 <= ex < x1 + 1 and y0 <= ey < y1 + 1 and z0 <= ez < z1 + 1):
                    continue
                if cfg["flat"] and masked(int(ex), int(ez)):
                    continue
                ents.append([round(ex - x0, 3), round(ey - y0, 3), round(ez - z0, 3), kind, snbt(entity(e, True))])

    funcs = {}
    dp = os.path.join(world, "datapacks")
    if os.path.isdir(dp):
        for pack in os.listdir(dp):
            data = os.path.join(dp, pack, "data")
            if not os.path.isdir(data):
                continue
            for ns in os.listdir(data):
                for folder in ("functions", "function"):
                    fdir = os.path.join(data, ns, folder)
                    if not os.path.isdir(fdir):
                        continue
                    for fn in os.listdir(fdir):
                        if fn.endswith(".mcfunction"):
                            lines = [command(line) for line in open(os.path.join(fdir, fn), encoding="utf-8").read().splitlines()
                                     if line.strip() and not line.strip().startswith("#")]
                            funcs[f"{ns}:{fn[:-11]}"] = lines

    sx0, sy0, sz0 = cfg["spawn"]
    out = {"name": name, "size": [sx, sy, sz], "spawn": [sx0 - x0, sy0 - y0, sz0 - z0], "origin": [x0, y0, z0],
           "palette": palette, "runs": runs, "blockEntities": bes, "entities": ents, "functions": funcs,
           "adventure": name == "the_prison"}
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + ".json.gz")
    with gzip.open(path, "wt", encoding="utf-8", compresslevel=9) as f:
        json.dump(out, f, separators=(",", ":"))
    print(name, "size", sx, sy, sz, "palette", len(palette), "runs", len(runs) // 2, "block entities", len(bes),
          "entities", len(ents), "functions", len(funcs), "file", os.path.getsize(path))


if __name__ == "__main__":
    convert_build(sys.argv[1], sys.argv[2])
