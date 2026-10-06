#!/usr/bin/env python3
"""Builds the secret structures, their loot and the new items' recipes into src/main/resources/data/vigil.

Everything about an item (name, look, lore, cooldown group) is defined once here and reused by its loot table
(chests, /owner secret) and its recipe, so they can't drift apart.

Run from the repo root:  python3 scripts/secrets/build.py
"""
import gzip
import io
import json
import os
import random
import struct

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "vigil")
DATA_VERSION = 3953  # 1.21: the game upgrades older templates when it loads them


def write(rel, content):
    path = os.path.join(DATA, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    mode = "wb" if isinstance(content, bytes) else "w"
    with open(path, mode) as f:
        f.write(content if isinstance(content, bytes) else json.dumps(content, indent=2, ensure_ascii=False) + "\n")


# ------------------------------------------------------------------ items

def code(name):
    """The pack's coded name for a model (same as PackIds.code in the mod and code() in the pack builder)."""
    import hashlib
    return "x" + hashlib.sha256(("vigil-pack:" + name).encode()).hexdigest()[:10]


def text(t, color, italic=None):
    d = {"text": t, "color": color}
    if italic is not None:
        d["italic"] = italic
    return d


def tool_attrs(damage, speed, slow, idv):
    return [
        {"type": "minecraft:attack_damage", "id": "minecraft:base_attack_damage", "amount": damage, "operation": "add_value", "slot": "mainhand"},
        {"type": "minecraft:attack_speed", "id": "minecraft:base_attack_speed", "amount": speed, "operation": "add_value", "slot": "mainhand"},
        {"type": "minecraft:block_break_speed", "id": f"vigil:{idv}_weight", "amount": slow, "operation": "add_multiplied_total", "slot": "mainhand"},
    ]


ITEMS = {
    # Secret: only from the secret structures
    "voidblade": ("diamond_sword", "Voidblade", "#B488FF", "epic", True, None,
                  ["Hits pull your target toward you", "(once every 6 seconds)"]),
    "stormbreaker": ("diamond_axe", "Stormbreaker", "#7FD8FF", "epic", True, None,
                     ["1 in 4 hits calls a small lightning strike", "(then 20 seconds to recharge)"]),
    "tidecaller": ("trident", "Tidecaller", "#3FA8FF", "epic", True, None,
                   ["Sneak + right-click: a wave pushes", "everything in front of you back", "(every 15 seconds)"]),
    "phoenix_feather": ("feather", "Phoenix Feather", "#FF8A1E", "epic", True, 1,
                        ["Keep it in your hotbar or off hand:", "saves you from death once, then burns away",
                         "(only once every 10 minutes)"]),
    "shadow_cloak": ("phantom_membrane", "Shadow Cloak", "#8A73B0", "epic", True, 1,
                     ["Off hand: sneak still for 3 seconds to vanish", "Ends when you attack or get hurt (20s at most)",
                      "(45 seconds to recharge)"]),
    "seeker_compass": ("nautilus_shell", "Seeker Compass", "#7FFFD4", "rare", True, 1,
                       ["Right-click: points to the nearest secret place", "Works once"]),
    # Boss weapons (each boss drops its own)
    "tide_trident": ("trident", "Warden's Tide", "#3FD0FF", "epic", True, None,
                     ["Drowned Warden's trident", "Hits soak the target: Slowness II for 3s", "(every 8 seconds)"]),
    "colossus_maul": ("mace", "Colossus Maul", "#8A93A6", "epic", True, None,
                      ["Deepslate Colossus's maul", "Hits stun the target for 1 second", "(every 10 seconds)"]),
    "storm_fang": ("diamond_sword", "Storm Fang", "#B8E8FF", "epic", True, None,
                   ["Storm Phantom's fang", "Hits jolt up to 3 enemies near the target", "(every 6 seconds)"]),
    "forge_cleaver": ("netherite_axe", "Forge Cleaver", "#FF8A2E", "epic", True, None,
                      ["Forgemaster's cleaver", "Hits set the target ablaze for 4 seconds", "(every 5 seconds)"]),
    "dune_blade": ("diamond_sword", "Dune Blade", "#E8C77A", "epic", True, None,
                   ["Sand Colossus's blade", "Hits blind the target for 2 seconds", "(every 12 seconds)"]),
    "glacier_axe": ("diamond_axe", "Glacier Axe", "#BFF4FF", "epic", True, None,
                    ["Frost Titan's axe", "Hits freeze the target (Slowness III, 2s)", "(every 10 seconds)"]),
    "thornspine": ("diamond_sword", "Thornspine", "#6FD05A", "epic", True, None,
                   ["Thornback Beast's spine", "Hits poison the target (Poison II, 3s)", "(every 8 seconds)"]),
    "hollow_edge": ("netherite_sword", "Hollow Edge", "#9A7BD0", "epic", True, None,
                    ["The Hollow Watcher's blade", "Hits drain life: heal 3 hearts", "(every 10 seconds)"]),
    # Craftable tools
    "hammer": ("iron_pickaxe", "Hammer", "#E0E0E0", "uncommon", False, None,
               ["Mines 3×3 (sneak to mine one block)", "A little slower than a pickaxe"]),
    "lumber_axe": ("iron_axe", "Lumber Axe", "#E0B070", "uncommon", False, None,
                   ["Cuts down a whole tree (sneak for one log)", "A little slower than an axe"]),
    "grappling_hook": ("fishing_rod", "Grappling Hook", "#C0C8D0", "uncommon", False, None,
                       ["Cast at a block, then reel in", "to pull yourself there", "(every 3 seconds)"]),
    "magnet_charm": ("iron_nugget", "Magnet Charm", "#FF6060", "uncommon", False, 1,
                     ["Right-click: on / off", "Hotbar or off hand: pulls in dropped items"]),
    "ender_pouch": ("rabbit_hide", "Ender Pouch", "#B05CFF", "uncommon", False, 1,
                    ["Right-click: open your ender chest", "(not right after a fight)"]),
    "backpack": ("leather", "Backpack", "#C8823C", "uncommon", False, 1,
                 ["Right-click: 27 extra slots", "(can't hold backpacks or shulker boxes)"]),
}

EXTRA_COMPONENTS = {
    "hammer": {"minecraft:attribute_modifiers": tool_attrs(3.0, -2.8, -0.35, "hammer")},
    "lumber_axe": {"minecraft:attribute_modifiers": tool_attrs(8.0, -3.1, -0.3, "lumber_axe")},
}


def components(item_id):
    base, name, color, rarity, glint, stack, lore = ITEMS[item_id]
    c = {
        "minecraft:item_name": text(name, color),
        "minecraft:lore": [text(line, "gray", False) for line in lore],
        "minecraft:custom_model_data": {"strings": ["vigil:" + code(item_id)]},
        "minecraft:item_model": "vigil:" + code(item_id),
        "minecraft:use_cooldown": {"seconds": 0.05, "cooldown_group": f"vigil:{item_id}"},
        "minecraft:rarity": rarity,
    }
    if glint:
        c["minecraft:enchantment_glint_override"] = True
    if stack:
        c["minecraft:max_stack_size"] = stack
    c.update(EXTRA_COMPONENTS.get(item_id, {}))
    return c


def item_loot(item_id):
    base = ITEMS[item_id][0]
    return {"type": "minecraft:chest", "pools": [{"rolls": 1, "entries": [{
        "type": "minecraft:item", "name": f"minecraft:{base}",
        "functions": [{"function": "minecraft:set_components", "components": components(item_id)}]}]}]}


RECIPES = {
    "hammer": (["BBB", " S ", " S "], {"B": "minecraft:iron_block", "S": "minecraft:stick"}),
    "lumber_axe": (["BB ", "BS ", " S "], {"B": "minecraft:iron_block", "S": "minecraft:stick"}),
    "grappling_hook": ([" T ", "IFI", " I "], {"T": "minecraft:tripwire_hook", "I": "minecraft:iron_ingot", "F": "minecraft:fishing_rod"}),
    "magnet_charm": (["R R", "I I", "III"], {"R": "minecraft:redstone", "I": "minecraft:iron_ingot"}),
    "ender_pouch": (["LSL", "LEL", "LLL"], {"L": "minecraft:leather", "S": "minecraft:string", "E": "minecraft:ender_eye"}),
    "backpack": (["SLS", "LCL", "LLL"], {"S": "minecraft:string", "L": "minecraft:leather", "C": "minecraft:chest"}),
}


def recipe(item_id):
    pattern, key = RECIPES[item_id]
    return {"type": "minecraft:crafting_shaped", "category": "equipment", "pattern": pattern, "key": key,
            "result": {"id": f"minecraft:{ITEMS[item_id][0]}", "count": 1, "components": components(item_id)}}


# ------------------------------------------------------------------ chest loot

# Good things in chests are rare: they're a small share of each roll, one at a time, and most rolls are junk or nothing.
VALUABLE = {"diamond", "netherite_scrap", "heart_of_the_sea", "golden_apple", "echo_shard", "emerald", "ender_pearl",
            "gold_ingot", "blaze_rod", "phantom_membrane"}
JUNK = [("rotten_flesh", 14, 1, 4), ("bone", 12, 1, 3), ("string", 12, 1, 3), ("stick", 12, 1, 4), ("coal", 8, 1, 3),
        ("cobweb", 5, 1, 2), ("arrow", 8, 2, 6), ("bread", 6, 1, 2)]


def filler(entries, rolls=(2, 4)):
    out = [{"type": "minecraft:empty", "weight": 40}]
    for name, weight, lo, hi in list(entries) + JUNK:
        if name in VALUABLE:
            weight, lo, hi = 1, 1, 1 if name in ("diamond", "netherite_scrap", "heart_of_the_sea", "golden_apple", "echo_shard") else min(hi, 2)
        e = {"type": "minecraft:item", "name": f"minecraft:{name}", "weight": weight}
        if hi > 1:
            e["functions"] = [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": lo, "max": hi}}]
        out.append(e)
    return {"rolls": {"type": "minecraft:uniform", "min": rolls[0], "max": rolls[1]}, "entries": out}


# How rare each secret item is in a structure chest: 1 in this many per roll, the stronger the rarer.
SECRET_ODDS = {"seeker_compass": 5000, "shadow_cloak": 8000, "tidecaller": 12000,
               "phoenix_feather": 20000, "voidblade": 30000, "stormbreaker": 50000}
SECRET_SCALE = 1_000_000  # loot weights are whole numbers, so odds are spread over this many


def secret(weights, rolls=1):
    """The structure's secret items, each only once in SECRET_ODDS[item] rolls; otherwise nothing."""
    entries = [{"type": "minecraft:loot_table", "value": f"vigil:items/{i}", "weight": SECRET_SCALE // SECRET_ODDS[i]}
               for i, _ in weights]
    empty = SECRET_SCALE - sum(e["weight"] for e in entries)
    return {"rolls": rolls, "entries": [{"type": "minecraft:empty", "weight": empty}] + entries}


CHESTS = {
    "sunken_vault": [secret([("tidecaller", 3), ("seeker_compass", 2)]),
                     filler([("prismarine_shard", 10, 2, 8), ("prismarine_crystals", 8, 2, 6), ("gold_ingot", 8, 1, 5),
                             ("iron_ingot", 8, 2, 6), ("emerald", 5, 1, 3), ("heart_of_the_sea", 1, 1, 1), ("diamond", 2, 1, 3)])],
    "buried_vault": [secret([("voidblade", 3), ("shadow_cloak", 2)]),
                     filler([("amethyst_shard", 10, 2, 6), ("iron_ingot", 10, 2, 6), ("gold_ingot", 6, 1, 4),
                             ("echo_shard", 3, 1, 2), ("diamond", 3, 1, 3), ("candle", 6, 1, 4)])],
    "sky_citadel": [secret([("phoenix_feather", 4), ("seeker_compass", 1)]),
                    filler([("feather", 10, 2, 8), ("golden_apple", 3, 1, 2), ("emerald", 6, 1, 4), ("gold_ingot", 8, 1, 4),
                            ("phantom_membrane", 5, 1, 3), ("diamond", 2, 1, 2)])],
    "nether_forge": [secret([("stormbreaker", 4), ("phoenix_feather", 1)]),
                     filler([("gold_ingot", 10, 2, 6), ("blaze_rod", 8, 1, 4), ("magma_cream", 6, 1, 4), ("quartz", 8, 3, 9),
                             ("iron_ingot", 8, 2, 6), ("netherite_scrap", 2, 1, 1)])],
    "desert_tomb": [secret([("seeker_compass", 3), ("shadow_cloak", 2), ("voidblade", 1)]),
                    filler([("gold_ingot", 10, 2, 6), ("bone", 10, 2, 6), ("emerald", 6, 1, 4), ("rotten_flesh", 8, 2, 6),
                            ("iron_ingot", 6, 1, 4), ("diamond", 2, 1, 2)])],
    "frozen_bastion": [secret([("tidecaller", 2), ("stormbreaker", 2), ("seeker_compass", 1)]),
                       filler([("blue_ice", 8, 2, 6), ("snowball", 10, 4, 12), ("iron_ingot", 8, 2, 6), ("emerald", 5, 1, 3),
                               ("diamond", 2, 1, 2), ("golden_carrot", 6, 2, 6)])],
    "overgrown_labyrinth": [secret([("voidblade", 2), ("phoenix_feather", 2), ("shadow_cloak", 1)]),
                            filler([("cocoa_beans", 8, 2, 8), ("bamboo", 8, 4, 12), ("emerald", 8, 2, 5), ("gold_ingot", 6, 1, 4),
                                    ("iron_ingot", 6, 2, 5), ("diamond", 2, 1, 2), ("melon_seeds", 5, 2, 6)])],
    "watchers_hollow": [secret([("voidblade", 1), ("stormbreaker", 1), ("tidecaller", 1)]), secret([("phoenix_feather", 1)]),
                        filler([("ender_pearl", 8, 1, 3), ("echo_shard", 4, 1, 2), ("sculk", 8, 2, 6), ("emerald", 6, 1, 4),
                                ("iron_ingot", 8, 2, 6)])],
}


# ------------------------------------------------------------------ NBT (structure templates)

class Tag:
    def __init__(self, kind, value):
        self.kind = kind
        self.value = value


def B(v):
    return Tag(1, v)


def I(v):
    return Tag(3, v)


def S(v):
    return Tag(8, v)


def L(kind, items):
    return Tag(9, (kind, items))


def C(d):
    return Tag(10, d)


def _payload(t, out):
    k, v = t.kind, t.value
    if k == 1:
        out.write(struct.pack(">b", v))
    elif k == 2:
        out.write(struct.pack(">h", v))
    elif k == 3:
        out.write(struct.pack(">i", v))
    elif k == 8:
        b = v.encode("utf-8")
        out.write(struct.pack(">H", len(b)))
        out.write(b)
    elif k == 9:
        kind, items = v
        out.write(struct.pack(">bi", kind if items else (kind or 0), len(items)))
        for it in items:
            _payload(it if isinstance(it, Tag) else Tag(kind, it), out)
    elif k == 10:
        for name, val in v.items():
            if not isinstance(val, Tag):
                val = S(val) if isinstance(val, str) else I(val)
            out.write(struct.pack(">b", val.kind))
            nb = name.encode("utf-8")
            out.write(struct.pack(">H", len(nb)))
            out.write(nb)
            _payload(val, out)
        out.write(b"\x00")


def nbt_file(root):
    out = io.BytesIO()
    out.write(b"\x0a\x00\x00")
    _payload(C(root), out)
    return gzip.compress(out.getvalue(), mtime=0)


class Template:
    def __init__(self, sx, sy, sz):
        self.size = (sx, sy, sz)
        self.blocks = {}
        self.nbt = {}

    def set(self, x, y, z, block, **props):
        if 0 <= x < self.size[0] and 0 <= y < self.size[1] and 0 <= z < self.size[2]:
            self.blocks[(x, y, z)] = (block, tuple(sorted((k, str(v).lower()) for k, v in props.items())))
            self.nbt.pop((x, y, z), None)

    def fill(self, x0, y0, z0, x1, y1, z1, block, **props):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    self.set(x, y, z, block, **props)

    def shell(self, x0, y0, z0, x1, y1, z1, wall, inside="air"):
        self.fill(x0, y0, z0, x1, y1, z1, wall)
        if inside:
            self.fill(x0 + 1, y0 + 1, z0 + 1, x1 - 1, y1 - 1, z1 - 1, inside)

    def chest(self, x, y, z, table, facing="north", waterlogged=False):
        self.set(x, y, z, "chest", facing=facing, type="single", waterlogged=waterlogged)
        self.nbt[(x, y, z)] = {"id": S("minecraft:chest"), "LootTable": S(f"vigil:chests/{table}")}

    def sign(self, x, y, z, facing, lines, wood="dark_oak", waterlogged=False):
        self.set(x, y, z, f"{wood}_wall_sign", facing=facing, waterlogged=waterlogged)
        msgs = [S(json.dumps(line)) for line in (lines + ["", "", "", ""])[:4]]
        blank = [S('""')] * 4
        self.nbt[(x, y, z)] = {"id": S("minecraft:sign"), "is_waxed": B(1),
                               "front_text": C({"messages": L(8, msgs), "color": S("black"), "has_glowing_text": B(1)}),
                               "back_text": C({"messages": L(8, blank), "color": S("black"), "has_glowing_text": B(0)})}

    def spawner(self, x, y, z, mob):
        self.set(x, y, z, "spawner")
        self.nbt[(x, y, z)] = {"id": S("minecraft:mob_spawner"),
                               "SpawnData": C({"entity": C({"id": S(f"minecraft:{mob}")})})}

    def dispenser(self, x, y, z, facing, item="arrow", count=16):
        self.set(x, y, z, "dispenser", facing=facing, triggered=False)
        self.nbt[(x, y, z)] = {"id": S("minecraft:dispenser"),
                               "Items": L(10, [C({"Slot": B(0), "id": S(f"minecraft:{item}"), "count": I(count)})])}

    def to_nbt(self):
        palette = []
        index = {}
        blocks = []
        for pos in sorted(self.blocks):
            key = self.blocks[pos]
            if key not in index:
                index[key] = len(palette)
                name, props = key
                entry = {"Name": S(f"minecraft:{name}")}
                if props:
                    entry["Properties"] = C({k: S(v) for k, v in props})
                palette.append(C(entry))
            b = {"pos": L(3, [I(pos[0]), I(pos[1]), I(pos[2])]), "state": I(index[key])}
            if pos in self.nbt:
                b["nbt"] = C(self.nbt[pos])
            blocks.append(C(b))
        return nbt_file({"DataVersion": I(DATA_VERSION), "size": L(3, [I(v) for v in self.size]),
                         "palette": L(10, palette), "blocks": L(10, blocks), "entities": L(10, [])})


# ------------------------------------------------------------------ the structures

def disc(t, cx, cz, r, y, block_fn):
    for x in range(t.size[0]):
        for z in range(t.size[2]):
            if (x - cx) ** 2 + (z - cz) ** 2 <= r * r:
                t.set(x, y, z, block_fn(x, z) if callable(block_fn) else block_fn)


def ring(t, cx, cz, r_in, r_out, y, block_fn):
    for x in range(t.size[0]):
        for z in range(t.size[2]):
            d = ((x - cx) ** 2 + (z - cz) ** 2) ** 0.5
            if r_in <= d <= r_out:
                t.set(x, y, z, block_fn(x, z) if callable(block_fn) else block_fn)


def stairs(t, x, y, z, block, facing, half="bottom", water=False):
    t.set(x, y, z, f"{block}_stairs", facing=facing, half=half, shape="straight", waterlogged=water)


def slab(t, x, y, z, block, kind="bottom", water=False):
    t.set(x, y, z, f"{block}_slab", type=kind, waterlogged=water)


def column(t, x, z, y0, y1, body, base=None, cap=None, **props):
    for y in range(y0, y1 + 1):
        t.set(x, y, z, body, **props)
    if base:
        t.set(x, y0, z, base)
    if cap:
        t.set(x, y1, z, cap)


def hanging_lantern(t, x, z, y_top, length, soul=False):
    """A chain from the ceiling at y_top + 1 down, with a lantern on its end."""
    for y in range(y_top - length + 1, y_top + 1):
        t.set(x, y, z, "chain", axis="y", waterlogged=False)
    t.set(x, y_top - length, z, "soul_lantern" if soul else "lantern", hanging=True, waterlogged=False)


def corners(cx, cz, d):
    return [(cx - d, cz - d), (cx + d, cz - d), (cx - d, cz + d), (cx + d, cz + d)]


# ------------------------------------------------------------------ the structures
# Every boss rises at the centre of its structure, standing on the arena floor, so each centre is a wide open floor
# with plenty of headroom. The Kind's floor (Bosses.java) is the first air layer above that floor.

def sunken_vault():
    """An ancient temple on the sea floor: stepped terrace, a sealed dry hall under a stepped roof, four corner
    towers, a pillared portico and the sealed door with its hatch code."""
    S, c = 29, 14
    t = Template(S, 18, S)
    # Terrace: dark border, a step up, and the temple floor.
    t.fill(0, 0, 0, S - 1, 0, S - 1, "dark_prismarine")
    t.fill(1, 0, 1, S - 2, 0, S - 2, "prismarine_bricks")
    for i in range(2, S - 2):
        for (x, z, f) in [(i, 2, "south"), (i, S - 3, "north"), (2, i, "east"), (S - 3, i, "west")]:
            stairs(t, x, 1, z, "prismarine_brick", f, water=True)
    t.fill(3, 1, 3, S - 4, 1, S - 4, "prismarine_bricks")
    # The hall: walls x/z 5..23, floor y1, air 2..11, ceiling y12.
    a, b = 5, S - 6
    t.fill(a, 1, a, b, 12, b, "prismarine_bricks")
    t.fill(a + 1, 2, a + 1, b - 1, 11, b - 1, "air")
    for i in range(a, b + 1):
        if (i - a) % 3 == 0:
            for (x, z) in [(i, a), (i, b), (a, i), (b, i)]:
                column(t, x, z, 2, 11, "dark_prismarine")
                t.set(x, 7, z, "sea_lantern")
    for i in range(a, b + 1):
        for (x, z) in [(i, a), (i, b), (a, i), (b, i)]:
            t.set(x, 12, z, "dark_prismarine")
    # Windows of blue glass between the pilasters (east, west and back walls).
    for i in range(a + 1, b):
        if (i - a) % 3 in (1, 2) and abs(i - c) > 1:
            for y in (4, 5):
                for (x, z) in [(a, i), (b, i), (i, a)]:
                    t.set(x, y, z, "light_blue_stained_glass")
    # Floor: border, a ring, sea-lantern star points and a centre medallion.
    for x in range(a + 1, b):
        for z in range(a + 1, b):
            dx, dz = abs(x - c), abs(z - c)
            m = max(dx, dz)
            block = "prismarine_bricks"
            if m == 8:
                block = "dark_prismarine"
            elif m in (5, 6) and (dx == dz or dx == 0 or dz == 0):
                block = "sea_lantern"
            elif m == 4:
                block = "dark_prismarine"
            elif m <= 2:
                block = "dark_prismarine" if m == 2 else "prismarine"
            t.set(x, 1, z, block)
    t.set(c, 1, c, "sea_lantern")
    # Four great columns around the arena.
    for (x, z) in corners(c, c, 6):
        column(t, x, z, 2, 11, "prismarine_bricks", base="dark_prismarine", cap="dark_prismarine")
        t.set(x, 6, z, "sea_lantern")
        for (dx, dz, f) in [(1, 0, "east"), (-1, 0, "west"), (0, 1, "south"), (0, -1, "north")]:
            stairs(t, x + dx, 2, z + dz, "dark_prismarine", f)
            stairs(t, x + dx, 11, z + dz, "dark_prismarine", f, half="top")
    # Ceiling lights.
    for (x, z) in corners(c, c, 3):
        t.set(x, 12, z, "sea_lantern")
    t.set(c, 12, c, "sea_lantern")
    # Altar at the back: steps, chests, the conduit.
    for x in range(c - 3, c + 4):
        stairs(t, x, 2, a + 3, "prismarine_brick", "north")
        t.fill(x, 2, a + 1, x, 2, a + 2, "dark_prismarine")
    t.chest(c - 1, 3, a + 1, "sunken_vault", facing="south")
    t.chest(c + 1, 3, a + 1, "sunken_vault", facing="south")
    t.set(c, 3, a + 1, "conduit", waterlogged=False)
    for x in (c - 3, c + 3):
        t.set(x, 3, a + 1, "sea_lantern")
    # Stepped roof.
    for k, y in enumerate(range(13, 17)):
        lo, hi = a + 1 + k * 2, b - 1 - k * 2
        t.fill(lo, y, lo, hi, y, hi, "dark_prismarine" if k % 2 == 0 else "prismarine_bricks")
        for i in range(lo, hi + 1):
            for (x, z, f) in [(i, lo, "south"), (i, hi, "north"), (lo, i, "east"), (hi, i, "west")]:
                stairs(t, x, y, z, "prismarine_brick", f, water=True)
    t.fill(c - 1, 17, c - 1, c + 1, 17, c + 1, "sea_lantern")
    # Corner towers.
    for (x0, z0) in [(a - 1, a - 1), (b - 3, a - 1), (a - 1, b - 3), (b - 3, b - 3)]:
        t.fill(x0, 2, z0, x0 + 4, 13, z0 + 4, "prismarine_bricks")
        for (x, z) in corners(x0 + 2, z0 + 2, 2):
            column(t, x, z, 2, 13, "dark_prismarine")
        t.fill(x0 + 1, 14, z0 + 1, x0 + 3, 14, z0 + 3, "dark_prismarine")
        t.set(x0 + 2, 15, z0 + 2, "sea_lantern")
        for (x, z) in corners(x0 + 2, z0 + 2, 2):
            t.set(x, 14, z, "prismarine_wall", up=True, waterlogged=True)
        t.set(x0 + 2, 9, z0 + 2, "sea_lantern")
    # The door (on the lock marker) in the front wall, a portico before it, the four hatches and the code.
    t.set(c, 1, b, "reinforced_deepslate")
    t.set(c, 2, b, "iron_door", facing="north", half="lower", hinge="left", open=False, powered=False)
    t.set(c, 3, b, "iron_door", facing="north", half="upper", hinge="left", open=False, powered=False)
    for x in (c - 1, c + 1):
        column(t, x, b, 2, 4, "dark_prismarine")
    stairs(t, c, 4, b, "dark_prismarine", "north", half="top")
    for x in (c - 2, c - 1, c + 1, c + 2):
        t.set(x, 5, b + 1, "oak_trapdoor", facing="south", half="top", open=False, powered=False, waterlogged=True)
    t.sign(c, 6, b + 1, "south", ["Open  Shut", "Shut  Open", "(the hatches)", "~ the Vault ~"], waterlogged=True)
    for x in (c - 3, c + 3):
        column(t, x, b + 2, 2, 7, "prismarine_bricks", base="dark_prismarine", cap="sea_lantern")
    for x in range(c - 3, c + 4):
        slab(t, x, 8, b + 2, "dark_prismarine", "bottom", water=True)
    return t


def buried_vault():
    """A buried deepslate cathedral: a pillared nave with pointed vaults, side aisles, hanging soul lanterns and a
    raised altar."""
    X, Y, Z = 25, 15, 31
    c, cz = 12, 15
    t = Template(X, Y, Z)
    t.shell(0, 0, 0, X - 1, Y - 1, Z - 1, "deepslate_bricks")
    # Floor: tiled aisles, a polished nave with a chiseled border, and a medallion at the centre.
    for x in range(1, X - 1):
        for z in range(1, Z - 1):
            nave = 7 <= x <= 17
            block = "deepslate_tiles" if not nave else ("polished_deepslate" if (x + z) % 2 else "deepslate_tiles")
            if x in (7, 17):
                block = "chiseled_deepslate"
            t.set(x, 0, z, block)
    for x in range(c - 3, c + 4):
        for z in range(cz - 3, cz + 4):
            d = max(abs(x - c), abs(z - cz))
            t.set(x, 0, z, "polished_blackstone" if d == 3 else ("chiseled_deepslate" if d == 2 else "polished_deepslate"))
    t.set(c, 0, cz, "crying_obsidian")
    # Vaulted ceiling: low over the aisles, rising to a point over the nave.
    for x in range(1, X - 1):
        d = abs(x - c)
        top = 13 if d <= 1 else 12 if d <= 3 else 11 if d <= 5 else 9
        for y in range(top + 1, Y - 1):
            for z in range(1, Z - 1):
                t.set(x, y, z, "deepslate_bricks")
        if d in (2, 4) or d == 6:
            for z in range(1, Z - 1):
                stairs(t, x, top, z, "deepslate_brick", "east" if x < c else "west", half="top")
    # Pillars with arches between them, along both sides of the nave.
    for z in range(3, Z - 3, 4):
        for x in (6, 18):
            column(t, x, z, 1, 9, "polished_deepslate", base="chiseled_deepslate", cap="chiseled_deepslate")
            t.set(x, 5, z, "chiseled_deepslate")
        if z + 4 < Z - 3:
            for x in (6, 18):
                for zz in (z + 1, z + 3):
                    stairs(t, x, 9, zz, "deepslate_brick", "south" if zz == z + 1 else "north", half="top")
                t.set(x, 9, z + 2, "deepslate_bricks")
    # Wall candles in the aisles and soul lanterns down the nave.
    for z in range(5, Z - 3, 4):
        for x in (1, X - 2):
            t.set(x, 1, z, "polished_deepslate")
            t.set(x, 2, z, "candle", candles=3, lit=True, waterlogged=False)
    for z in (5, 9, 21, 25):
        hanging_lantern(t, c, z, 12, 3, soul=True)
    for (x, z) in corners(c, cz, 4):
        hanging_lantern(t, x, z, 12, 4, soul=True)
    # The altar: three steps, a blackstone altar with the chest, candles and the relic window.
    for k in range(3):
        for x in range(c - 4 + k, c + 5 - k):
            stairs(t, x, 1 + k, Z - 5 + k, "polished_deepslate", "north")
            t.fill(x, 1, Z - 4 + k, x, 1 + k, Z - 4 + k, "polished_deepslate")
        t.fill(c - 4 + k, 1, Z - 2, c + 4 - k, 1 + k, Z - 2, "polished_deepslate")
    t.fill(c - 4, 1, Z - 2, c + 4, 3, Z - 2, "polished_deepslate")
    t.fill(c - 1, 4, Z - 3, c + 1, 4, Z - 3, "polished_blackstone")
    t.chest(c, 5, Z - 3, "buried_vault", facing="north")
    for x in (c - 1, c + 1):
        t.set(x, 5, Z - 3, "candle", candles=4, lit=True, waterlogged=False)
    for y in range(5, 11):
        for x in range(c - 1, c + 2):
            t.set(x, y, Z - 1, "crying_obsidian" if (x + y) % 2 else "obsidian")
    for x in (c - 3, c + 3):
        t.set(x, 4, Z - 2, "soul_lantern", hanging=False, waterlogged=False)
    # A little sculk creeping in from the corners.
    import random as _r
    r = _r.Random(2)
    for _ in range(26):
        x, z = r.choice([r.randint(1, 5), r.randint(19, 23)]), r.randint(1, Z - 2)
        t.set(x, 0, z, "sculk")
    return t


def sky_citadel():
    """A floating island with a quartz temple: a ring of twelve columns holding a halo roof open to the sky, a
    patterned plaza, flower gardens and four golden obelisks."""
    import math
    import random as _r
    S, c = 31, 15
    t = Template(S, 20, S)
    r = _r.Random(3)
    # The island: an upside-down cone of stone, a soil layer, grass on top (y 6).
    for y in range(0, 7):
        rad = 2.5 + y * 2.1
        for x in range(S):
            for z in range(S):
                d = ((x - c) ** 2 + (z - c) ** 2) ** 0.5
                if d <= rad + r.random() * 0.7:
                    if y == 6:
                        block = "grass_block"
                    elif y >= 4:
                        block = "dirt"
                    else:
                        block = r.choice(["stone", "andesite", "tuff", "stone", "calcite"])
                    t.set(x, y, z, block)
    # Plaza (y 6): quartz with eight golden rays and a medallion.
    disc(t, c, c, 10.5, 6, "smooth_quartz")
    ring(t, c, c, 10.0, 10.9, 6, "quartz_bricks")
    for i in range(8):
        a = i * math.pi / 4
        for k in range(3, 10):
            t.set(int(round(c + math.cos(a) * k)), 6, int(round(c + math.sin(a) * k)), "chiseled_quartz_block" if k % 3 else "gold_block")
    disc(t, c, c, 2.5, 6, "chiseled_quartz_block")
    t.set(c, 6, c, "gold_block")
    # Twelve columns and the halo roof.
    for i in range(12):
        a = i * math.pi / 6
        x, z = int(round(c + math.cos(a) * 9)), int(round(c + math.sin(a) * 9))
        column(t, x, z, 7, 13, "quartz_pillar", base="chiseled_quartz_block", cap="chiseled_quartz_block", axis="y")
        t.set(x, 7, z, "chiseled_quartz_block")
    ring(t, c, c, 7.6, 10.4, 14, "smooth_quartz")
    ring(t, c, c, 8.4, 9.6, 15, "quartz_bricks")
    for i in range(24):
        a = i * math.pi / 12
        x, z = int(round(c + math.cos(a) * 8)), int(round(c + math.sin(a) * 8))
        t.set(x, 13, z, "end_rod", facing="down")
    for i in range(12):
        a = (i + 0.5) * math.pi / 6
        x, z = int(round(c + math.cos(a) * 9)), int(round(c + math.sin(a) * 9))
        t.set(x, 16, z, "end_rod", facing="up")
    # Gardens between the plaza and the edge.
    flowers = ["poppy", "dandelion", "cornflower", "oxeye_daisy", "azure_bluet", "allium", "lily_of_the_valley"]
    for x in range(S):
        for z in range(S):
            d = ((x - c) ** 2 + (z - c) ** 2) ** 0.5
            if 11.5 < d < 13.5 and t.blocks.get((x, 6, z), ("",))[0] == "grass_block" and r.random() < 0.55:
                t.set(x, 7, z, r.choice(flowers) if r.random() < 0.75 else "flowering_azalea")
    # Four golden obelisks at the cardinal points.
    for (x, z) in [(c, c - 12), (c, c + 12), (c - 12, c), (c + 12, c)]:
        t.set(x, 6, z, "quartz_bricks")
        column(t, x, z, 7, 11, "quartz_pillar", axis="y")
        t.set(x, 12, z, "gold_block")
        t.set(x, 13, z, "end_rod", facing="up")
    # The chest on a pedestal at the back.
    t.set(c, 7, c + 8, "chiseled_quartz_block")
    t.chest(c, 8, c + 8, "sky_citadel", facing="north")
    return t


def nether_forge():
    """A blackstone forge-hall on the deltas: buttressed walls, a gilded band, lava channels behind bars, a tiled floor,
    a forge with a tall chimney and a great arched gate."""
    S, c = 27, 13
    t = Template(S, 18, S)
    a, b = 2, S - 3
    t.fill(0, 0, 0, S - 1, 0, S - 1, "polished_blackstone")
    ring_sq = [(x, z) for x in range(S) for z in range(S) if min(x, z, S - 1 - x, S - 1 - z) == 0]
    for (x, z) in ring_sq:
        t.set(x, 0, z, "magma_block" if (x + z) % 3 == 0 else "blackstone")
    # Walls with buttresses and a gilded band.
    t.fill(a, 1, a, b, 12, b, "polished_blackstone_bricks")
    t.fill(a + 1, 1, a + 1, b - 1, 11, b - 1, "air")
    for i in range(a, b + 1, 4):
        for (x, z, dx, dz) in [(i, a - 1, 0, -1), (i, b + 1, 0, 1), (a - 1, i, -1, 0), (b + 1, i, 1, 0)]:
            column(t, x, z, 1, 9, "polished_basalt", axis="y")
            t.set(x, 10, z, "polished_blackstone_brick_wall", up=True, waterlogged=False)
    for i in range(a, b + 1):
        for (x, z) in [(i, a), (i, b), (a, i), (b, i)]:
            t.set(x, 6, z, "gilded_blackstone" if i % 2 else "chiseled_polished_blackstone")
    # Floor: tiles with a gold-trimmed border and a medallion.
    for x in range(a + 1, b):
        for z in range(a + 1, b):
            m = max(abs(x - c), abs(z - c))
            block = "polished_blackstone" if (x + z) % 2 else "polished_blackstone_bricks"
            if m == 8:
                block = "gilded_blackstone"
            if m <= 2:
                block = "chiseled_polished_blackstone" if m == 2 else "polished_blackstone"
            t.set(x, 0, z, block)
    t.set(c, 0, c, "crying_obsidian")
    # Lava channels behind iron bars along the east and west walls.
    for z in range(a + 2, b - 1):
        for x in (a + 1, b - 1):
            t.set(x, 0, z, "lava")
            t.set(x, 1, z, "iron_bars", east=(x == a + 1), west=(x == b - 1), north=True, south=True, waterlogged=False)
    # Ceiling: blackstone with shroomlights and hanging lanterns.
    t.fill(a + 1, 12, a + 1, b - 1, 12, b - 1, "polished_blackstone_bricks")
    for (x, z) in corners(c, c, 5) + [(c, c)]:
        t.set(x, 12, z, "shroomlight")
    for (x, z) in corners(c, c, 3):
        hanging_lantern(t, x, z, 11, 3)
    # The forge at the back: chimney with a campfire, furnaces, anvils and the chest.
    for x in range(c - 2, c + 3):
        for z in range(a, a + 3):
            for y in range(1, 17):
                edge = x in (c - 2, c + 2) or z in (a, a + 2)
                if edge or y > 11:
                    t.set(x, y, z, "polished_blackstone_bricks" if y < 16 else "polished_blackstone_brick_wall")
                else:
                    t.set(x, y, z, "air")
    t.set(c, 1, a + 1, "campfire", lit=True, facing="south", signal_fire=True, waterlogged=False)
    t.set(c, 2, a + 2, "air")
    t.set(c, 1, a + 2, "air")
    for x in (c - 1, c + 1):
        t.set(x, 1, a + 2, "magma_block")
    for x in (c - 4, c + 4):
        t.set(x, 1, a + 1, "blast_furnace", facing="south", lit=True)
        t.set(x, 2, a + 1, "blast_furnace", facing="south", lit=True)
    t.set(c - 3, 1, a + 4, "anvil", facing="east")
    t.set(c + 3, 1, a + 4, "anvil", facing="west")
    t.set(c - 6, 1, a + 1, "smithing_table")
    t.set(c + 6, 1, a + 1, "lava_cauldron")
    t.chest(c, 1, a + 4, "nether_forge", facing="south")
    # The gate: an arch in the front wall.
    for y in range(1, 6):
        for x in range(c - 2, c + 3):
            t.set(x, y, b, "air")
    for x in (c - 3, c + 3):
        column(t, x, b + 1, 1, 7, "gilded_blackstone")
    stairs(t, c - 2, 5, b, "polished_blackstone_brick", "east", half="top")
    stairs(t, c + 2, 5, b, "polished_blackstone_brick", "west", half="top")
    for x in range(c - 1, c + 2):
        t.set(x, 6, b, "chiseled_polished_blackstone")
    t.set(c, 7, b + 1, "lantern", hanging=False, waterlogged=False)
    return t


def desert_tomb():
    """A buried tomb under a hidden shaft: a trapped corridor, then a columned burial hall with a terracotta floor,
    painted friezes, golden statues and the sarcophagus."""
    t = Template(23, 16, 23)
    # Surface: a ring of worn cut sandstone around the shaft, mostly buried.
    for (x, z) in [(10, 0), (11, 0), (12, 0), (10, 1), (12, 1), (10, 2), (11, 2), (12, 2)]:
        t.set(x, 15, z, "chiseled_sandstone" if (x + z) % 2 else "cut_sandstone")
    for y in range(1, 16):
        for (x, z) in [(10, 1), (12, 1), (11, 0)]:
            t.set(x, y, z, "cut_sandstone" if y % 4 else "chiseled_sandstone")
        if y > 2:
            t.set(11, y, 2, "cut_sandstone")
        t.set(11, y, 1, "ladder", facing="south")
    t.set(11, 0, 1, "sandstone")
    # The trapped corridor (pressure plates, dispensers in the walls).
    for z in range(2, 8):
        t.set(11, 0, z, "smooth_sandstone")
        t.set(11, 1, z, "air")
        t.set(11, 2, z, "air")
        t.set(11, 3, z, "cut_sandstone")
        for x in (10, 12):
            for y in (1, 2):
                t.set(x, y, z, "cut_sandstone")
    for z in (3, 4, 5, 6):
        t.set(11, 1, z, "stone_pressure_plate", powered=False)
    t.dispenser(10, 1, 3, "east")
    t.dispenser(12, 1, 4, "west")
    t.dispenser(10, 1, 5, "east")
    t.dispenser(12, 1, 6, "west")
    # The burial hall: x 2..20, z 7..22, floor y0, air 1..8, ceiling y9.
    t.shell(2, 0, 7, 20, 9, 22, "cut_sandstone")
    t.set(11, 1, 7, "air")
    t.set(11, 2, 7, "air")
    for x in (10, 12):
        column(t, x, 7, 1, 3, "chiseled_sandstone")
    t.set(11, 3, 7, "chiseled_sandstone")
    # Floor: a diamond pattern of orange and white, a blue medallion with a gold heart.
    for x in range(3, 20):
        for z in range(8, 22):
            k = (abs(x - 11) + abs(z - 15)) % 4
            t.set(x, 0, z, "orange_terracotta" if k == 0 else "smooth_sandstone")
    for x in range(8, 15):
        for z in range(12, 19):
            d = abs(x - 11) + abs(z - 15)
            if d <= 3:
                t.set(x, 0, z, "blue_terracotta" if d >= 2 else "light_blue_terracotta")
    t.set(11, 0, 15, "gold_block")
    # Painted frieze around the walls and a ceiling grid with lights.
    for x in range(2, 21):
        for z in range(7, 23):
            if x in (2, 20) or z in (7, 22):
                t.set(x, 6, z, "blue_terracotta" if (x + z) % 2 else "orange_terracotta")
                t.set(x, 7, z, "chiseled_sandstone")
    for x in range(3, 20):
        for z in range(8, 22):
            t.set(x, 9, z, "glowstone" if (x - 3) % 4 == 0 and (z - 8) % 4 == 2 else "smooth_sandstone")
    # Columns down both sides, with lanterns.
    for z in (9, 13, 17, 21):
        for x in (5, 17):
            column(t, x, z, 1, 8, "cut_sandstone", base="chiseled_sandstone", cap="chiseled_sandstone")
            t.set(x, 4, z, "chiseled_sandstone")
        for x in (4, 18):
            t.set(x, 5, z, "lantern", hanging=False, waterlogged=False)
    # Golden statues guarding the sarcophagus.
    for x in (8, 14):
        t.set(x, 1, 20, "chiseled_sandstone")
        t.set(x, 2, 20, "gold_block")
        t.set(x, 3, 20, "skeleton_skull", rotation=0)
    # The sarcophagus: a raised step, the chest, sandstone ends.
    for x in range(9, 14):
        stairs(t, x, 1, 19, "smooth_sandstone", "north")
        t.set(x, 1, 20, "smooth_sandstone")
        t.set(x, 1, 21, "smooth_sandstone")
    t.chest(11, 2, 21, "desert_tomb", facing="north")
    for x in (10, 12):
        t.set(x, 2, 21, "chiseled_sandstone")
        t.set(x, 3, 21, "candle", candles=3, lit=True, waterlogged=False)
    return t


def frozen_bastion():
    """An ice castle: thick walls with a blue band and snowy battlements, a wall walk, four round towers with ice
    roofs, a gate and a patterned courtyard."""
    S, c = 25, 12
    t = Template(S, 20, S)
    t.fill(0, 0, 0, S - 1, 0, S - 1, "packed_ice")
    # Courtyard floor: snow with ice paths in a cross and a ring, blue ice centre.
    for x in range(1, S - 1):
        for z in range(1, S - 1):
            d = ((x - c) ** 2 + (z - c) ** 2) ** 0.5
            block = "snow_block"
            if abs(x - c) <= 1 or abs(z - c) <= 1:
                block = "packed_ice"
            if 5.5 <= d <= 6.5:
                block = "blue_ice"
            if d <= 2.2:
                block = "blue_ice"
            t.set(x, 0, z, block)
    t.set(c, 0, c, "sea_lantern")
    # Walls: two thick, 9 high, blue band, battlements.
    for x in range(S):
        for z in range(S):
            edge = min(x, z, S - 1 - x, S - 1 - z)
            if edge <= 1:
                for y in range(1, 10):
                    t.set(x, y, z, "blue_ice" if y == 5 else "packed_ice")
                t.set(x, 10, z, "snow_block" if edge == 0 and (x + z) % 2 == 0 else ("air" if edge == 0 else "spruce_planks"))
    for i in range(2, S - 2):
        for (x, z) in [(i, 2), (i, S - 3), (2, i), (S - 3, i)]:
            slab(t, x, 9, z, "spruce", "top")
    # Round towers at the corners.
    for (tx, tz) in corners(c, c, c - 1):
        for x in range(tx - 3, tx + 4):
            for z in range(tz - 3, tz + 4):
                if not (0 <= x < S and 0 <= z < S):
                    continue
                d = ((x - tx) ** 2 + (z - tz) ** 2) ** 0.5
                if d <= 3.2:
                    for y in range(1, 14):
                        t.set(x, y, z, "packed_ice" if d > 2.2 else "air")
                    t.set(x, 14, z, "blue_ice")
                    if d <= 2.4:
                        t.set(x, 15, z, "blue_ice")
                    if d <= 1.5:
                        t.set(x, 16, z, "blue_ice")
                    if d <= 0.5:
                        t.set(x, 17, z, "packed_ice")
                        t.set(x, 18, z, "lantern", hanging=False, waterlogged=False)
        for y in (4, 8, 12):
            for (dx, dz) in [(3, 0), (-3, 0), (0, 3), (0, -3)]:
                if 0 <= tx + dx < S and 0 <= tz + dz < S:
                    t.set(tx + dx, y, tz + dz, "blue_ice")
        hanging_lantern(t, tx, tz, 13, 2)
    # The gate.
    for x in range(c - 1, c + 2):
        for y in range(1, 5):
            for z in (S - 2, S - 1):
                t.set(x, y, z, "air")
    for x in (c - 2, c + 2):
        column(t, x, S - 1, 1, 6, "blue_ice")
        t.set(x, 7, S - 1, "lantern", hanging=False, waterlogged=False)
    for x in range(c - 1, c + 2):
        t.set(x, 5, S - 1, "spruce_fence", waterlogged=False)
    # Ice spikes and lantern posts in the courtyard corners.
    for (x, z) in corners(c, c, 7):
        column(t, x, z, 1, 6, "packed_ice")
        t.set(x, 7, z, "blue_ice")
    for (x, z) in [(c - 4, c - 8), (c + 4, c - 8), (c - 4, c + 8), (c + 4, c + 8)]:
        column(t, x, z, 1, 2, "spruce_fence", waterlogged=False)
        t.set(x, 3, z, "lantern", hanging=False, waterlogged=False)
    # The chest under an ice canopy at the back.
    t.chest(c, 1, 3, "frozen_bastion", facing="south")
    for x in (c - 1, c + 1):
        column(t, x, 3, 1, 3, "blue_ice")
    t.fill(c - 1, 4, 3, c + 1, 4, 3, "packed_ice")
    t.set(c, 1, 4, "powder_snow")
    return t


def overgrown_labyrinth():
    """A mossy maze around a ruined round temple: banded stone walls capped with leaves and lanterns, overgrown paths,
    a grand entrance and a ring of broken columns in the middle."""
    import random as _r
    r = _r.Random(7)
    n = 7
    cell = 4
    size = n * cell + 1
    t = Template(size, 9, size)
    for x in range(size):
        for z in range(size):
            t.set(x, 0, z, r.choice(["moss_block", "moss_block", "coarse_dirt", "mossy_cobblestone", "rooted_dirt"]))
    walls = [[True] * size for _ in range(size)]
    seen = {(0, 0)}
    stack = [(0, 0)]

    def open_cell(cx, cz):
        for x in range(cx * cell + 1, cx * cell + cell):
            for z in range(cz * cell + 1, cz * cell + cell):
                walls[x][z] = False
    open_cell(0, 0)
    while stack:
        cx, cz = stack[-1]
        nbrs = [(cx + dx, cz + dz) for dx, dz in [(1, 0), (-1, 0), (0, 1), (0, -1)]
                if 0 <= cx + dx < n and 0 <= cz + dz < n and (cx + dx, cz + dz) not in seen]
        if not nbrs:
            stack.pop()
            continue
        nx, nz = r.choice(nbrs)
        seen.add((nx, nz))
        open_cell(nx, nz)
        if cx != nx:
            x = max(cx, nx) * cell
            for z in range(cz * cell + 1, cz * cell + cell):
                walls[x][z] = False
        else:
            z = max(cz, nz) * cell
            for x in range(cx * cell + 1, cx * cell + cell):
                walls[x][z] = False
        stack.append((nx, nz))
    for x in range(2 * cell + 1, 5 * cell):
        for z in range(2 * cell + 1, 5 * cell):
            walls[x][z] = False
    for z in range(1, cell):
        walls[0][z] = False
    # Walls: a cobbled base, mossy courses, a chiseled band, leaves on top, a lantern now and then.
    for x in range(size):
        for z in range(size):
            if walls[x][z]:
                t.set(x, 1, z, "mossy_cobblestone")
                for y in range(2, 5):
                    t.set(x, y, z, r.choice(["mossy_stone_bricks", "mossy_stone_bricks", "stone_bricks", "cracked_stone_bricks"]))
                t.set(x, 5, z, "chiseled_stone_bricks" if (x % 4 == 0 and z % 4 == 0) else "stone_bricks")
                t.set(x, 6, z, "jungle_leaves", persistent=True, distance=1, waterlogged=False)
                if x % 4 == 0 and z % 4 == 0 and r.random() < 0.35:
                    t.set(x, 7, z, "lantern", hanging=False, waterlogged=False)
            else:
                for y in range(1, 9):
                    t.set(x, y, z, "air")
                if r.random() < 0.08:
                    t.set(x, 1, z, r.choice(["fern", "short_grass", "moss_carpet"]))
    # The ruined temple in the middle.
    c = size // 2
    for x in range(c - 5, c + 6):
        for z in range(c - 5, c + 6):
            d = ((x - c) ** 2 + (z - c) ** 2) ** 0.5
            if d <= 5.3:
                t.set(x, 0, z, "chiseled_stone_bricks" if 4.4 <= d else ("mossy_stone_bricks" if d > 1.5 else "chiseled_stone_bricks"))
    import math
    for i in range(8):
        a = i * math.pi / 4
        x, z = int(round(c + math.cos(a) * 4.5)), int(round(c + math.sin(a) * 4.5))
        h = [6, 3, 5, 2, 6, 4, 6, 3][i]
        column(t, x, z, 1, h, "stone_bricks", base="chiseled_stone_bricks")
        if h == 6:
            t.set(x, 7, z, "lantern", hanging=False, waterlogged=False)
        else:
            slab(t, x, h + 1, z, "mossy_stone_brick")
    t.chest(c, 1, c + 5, "overgrown_labyrinth", facing="north")
    t.chest(size - 3, 1, size - 3, "overgrown_labyrinth", facing="north")
    # The entrance: a chiseled arch with lanterns.
    for (z, y) in [(0, 1), (0, 2), (0, 3), (cell, 1), (cell, 2), (cell, 3)]:
        t.set(0, y, z, "chiseled_stone_bricks")
    for z in range(0, cell + 1):
        t.set(0, 4, z, "chiseled_stone_bricks")
        if z in (0, cell):
            t.set(0, 5, z, "stone_brick_wall", up=True, waterlogged=False)
        else:
            t.set(0, 5, z, "stone_bricks")
    t.set(0, 6, 0, "lantern", hanging=False, waterlogged=False)
    t.set(0, 6, cell, "lantern", hanging=False, waterlogged=False)
    return t


def watchers_hollow():
    """A ritual grove in the dark forest: a ring of dark oak pillars under a leafy dome, rings of sculk and mud,
    soul lanterns on posts, candles, and a black obelisk crowned with a wither skull."""
    import math
    import random as _r
    r = _r.Random(6)
    S, c = 23, 11
    t = Template(S, 14, S)
    for x in range(S):
        for z in range(S):
            d = ((x - c) ** 2 + (z - c) ** 2) ** 0.5
            if d <= 10.5:
                # Dark moss and mud, sculk creeping out from a tiled ring around the centre.
                block = "mud" if r.random() < 0.35 else "moss_block"
                if r.random() < max(0.0, 0.6 - d * 0.07):
                    block = "sculk"
                if 9.4 <= d:
                    block = "deepslate_tiles"
                if 2.4 <= d <= 3.4:
                    block = "deepslate_tiles"
                if d < 2.4:
                    block = "sculk" if d > 0.5 else "sculk_catalyst"
                t.set(x, 0, z, block)
                for y in range(1, 11):
                    t.set(x, y, z, "air")
    # Pillars, beams and the leafy dome.
    for i in range(12):
        a = i * math.pi / 6
        x, z = int(round(c + math.cos(a) * 9)), int(round(c + math.sin(a) * 9))
        column(t, x, z, 1, 8, "dark_oak_log", axis="y")
        t.set(x, 9, z, "stripped_dark_oak_log", axis="y")
    ring(t, c, c, 8.4, 9.6, 9, "dark_oak_planks")
    for y, (ri, ro) in [(10, (7.5, 10.2)), (11, (5.5, 8.6)), (12, (2.5, 6.4))]:
        ring(t, c, c, ri, ro, y, lambda x, z: "dark_oak_leaves")
    for pos, (blk, props) in list(t.blocks.items()):
        if blk == "dark_oak_leaves":
            t.set(*pos, "dark_oak_leaves", persistent=True, distance=1, waterlogged=False)
    # Soul lanterns on posts and candles around the inner ring.
    for i in range(6):
        a = (i + 0.5) * math.pi / 3
        x, z = int(round(c + math.cos(a) * 6)), int(round(c + math.sin(a) * 6))
        column(t, x, z, 1, 2, "dark_oak_fence", waterlogged=False)
        t.set(x, 3, z, "soul_lantern", hanging=False, waterlogged=False)
    for i in range(10):
        a = i * math.pi / 5
        x, z = int(round(c + math.cos(a) * 4)), int(round(c + math.sin(a) * 4))
        t.set(x, 1, z, "black_candle", candles=r.randint(1, 4), lit=True, waterlogged=False)
    # The obelisk at the back, crowned with a wither skull.
    ox, oz = c, c + 8
    t.fill(ox - 1, 1, oz - 1, ox + 1, 1, oz + 1, "polished_blackstone")
    t.fill(ox - 1, 2, oz - 1, ox + 1, 2, oz + 1, "polished_blackstone_bricks")
    column(t, ox, oz, 3, 7, "black_concrete")
    t.set(ox, 5, oz - 1, "crying_obsidian")
    t.set(ox, 8, oz, "wither_skeleton_skull", rotation=8)
    for (x, z) in [(ox - 1, oz - 1), (ox + 1, oz - 1)]:
        t.set(x, 3, z, "soul_lantern", hanging=False, waterlogged=False)
    t.chest(ox, 1, oz - 2, "watchers_hollow", facing="north")
    for (x, z) in [(c - 5, c - 5), (c + 5, c - 5), (c - 5, c + 5), (c + 5, c + 5)]:
        t.set(x, 1, z, "sculk_sensor", sculk_sensor_phase="inactive", power=0, waterlogged=False)
    # The way in, from the north.
    for x in range(c - 1, c + 2):
        for y in range(1, 4):
            t.set(x, y, 1, "air")
            t.set(x, y, 2, "air")
    return t


# ------------------------------------------------------------------ rooms (jigsaw pieces)
# Each structure is its boss arena (the start piece, in the middle) plus a random spread of rooms joined by doors.
# Every door is a jigsaw block named vigil:door in the floor at the middle of a 3-wide doorway on the edge of a
# piece; any door can join any other. When the spread stops (too deep, or no room fits), the door gets a wall plug
# (the fallback "caps" pool). Guard and treasure rooms have spawners of the structure's own mobs that work in any
# light, so the way to the boss is always fought through.

THEMES = {
    "sunken_vault": dict(wall="prismarine_bricks", accent="dark_prismarine", floor=("prismarine_bricks", "dark_prismarine"),
                         light="sea_lantern", ceil="prismarine_bricks", trim="dark_prismarine", decor=["prismarine", "sea_lantern"],
                         mobs=[("drowned", 3), ("zombie", 1)], lantern="sea_lantern"),
    "buried_vault": dict(wall="deepslate_bricks", accent="polished_deepslate", floor=("deepslate_tiles", "polished_deepslate"),
                         light="pearlescent_froglight", ceil="deepslate_bricks", trim="deepslate_brick", decor=["bookshelf", "chiseled_deepslate"],
                         mobs=[("zombie", 3), ("skeleton", 2), ("cave_spider", 1)], lantern="soul_lantern"),
    "sky_citadel": dict(wall="quartz_bricks", accent="quartz_pillar", floor=("smooth_quartz", "quartz_bricks"),
                        light="sea_lantern", ceil=None, trim="quartz", decor=["flowering_azalea", "gold_block"],
                        mobs=[("skeleton", 3), ("stray", 1)], lantern="lantern", open=True),
    "nether_forge": dict(wall="polished_blackstone_bricks", accent="chiseled_polished_blackstone",
                         floor=("polished_blackstone", "blackstone"), light="shroomlight", ceil="polished_blackstone_bricks",
                         trim="polished_blackstone_brick", decor=["gilded_blackstone", "blast_furnace"],
                         mobs=[("blaze", 2), ("wither_skeleton", 2), ("magma_cube", 1)], lantern="lantern"),
    "desert_tomb": dict(wall="cut_sandstone", accent="chiseled_sandstone", floor=("smooth_sandstone", "orange_terracotta"),
                        light="glowstone", ceil="smooth_sandstone", trim="sandstone", decor=["bookshelf", "chiseled_sandstone"],
                        mobs=[("husk", 3), ("skeleton", 1), ("spider", 1)], lantern="lantern"),
    "frozen_bastion": dict(wall="packed_ice", accent="blue_ice", floor=("snow_block", "packed_ice"), light="sea_lantern",
                           ceil="packed_ice", trim="spruce", decor=["blue_ice", "spruce_planks"],
                           mobs=[("stray", 3), ("skeleton", 1)], lantern="lantern"),
    "overgrown_labyrinth": dict(wall="mossy_stone_bricks", accent="chiseled_stone_bricks", floor=("moss_block", "mossy_cobblestone"),
                                light="lantern", ceil="jungle_leaves", trim="mossy_stone_brick", decor=["mossy_cobblestone", "moss_block"],
                                mobs=[("spider", 2), ("cave_spider", 2), ("zombie", 1)], lantern="lantern"),
    "watchers_hollow": dict(wall="dark_oak_planks", accent="dark_oak_log", floor=("deepslate_tiles", "mud_bricks"),
                            light="ochre_froglight", ceil="dark_oak_planks", trim="dark_oak", decor=["bookshelf", "dark_oak_log"],
                            mobs=[("wither_skeleton", 2), ("skeleton", 2), ("zombie", 1)], lantern="soul_lantern"),
}

LIGHT_ANY = {"block_light_limit": {"min_inclusive": 0, "max_inclusive": 15},
             "sky_light_limit": {"min_inclusive": 0, "max_inclusive": 15}}


def _range(d):
    return C({k: C({"min_inclusive": I(v["min_inclusive"]), "max_inclusive": I(v["max_inclusive"])}) for k, v in d.items()})


def mob_spawner(t, x, y, z, mobs, structure):
    """A spawner of the structure's mobs (weighted), in any light, a few at a time. Each mob is tagged with the
    structure, so the mod gives it that structure's armour and weapons (StructureMobs.java)."""
    t.set(x, y, z, "spawner")

    def entity(m):
        return C({"id": S(f"minecraft:{m}"), "Tags": L(8, [S(f"vigil_structure_mob:{structure}")]),
                  "DeathLootTable": S("minecraft:empty")})
    entries = [C({"weight": I(w), "data": C({"entity": entity(m), "custom_spawn_rules": _range(LIGHT_ANY)})}) for m, w in mobs]
    t.nbt[(x, y, z)] = {"id": S("minecraft:mob_spawner"),
                        "SpawnData": C({"entity": entity(mobs[0][0]), "custom_spawn_rules": _range(LIGHT_ANY)}),
                        "SpawnPotentials": L(10, entries), "SpawnCount": Tag(2, 4), "MaxNearbyEntities": Tag(2, 8),
                        "RequiredPlayerRange": Tag(2, 20), "MinSpawnDelay": Tag(2, 100), "MaxSpawnDelay": Tag(2, 260),
                        "SpawnRange": Tag(2, 4), "Delay": Tag(2, 20)}


def door(t, x, y, z, facing, structure, final):
    """A jigsaw door in the floor on the edge of a piece, facing out."""
    t.set(x, y, z, "jigsaw", orientation=f"{facing}_up")
    t.nbt[(x, y, z)] = {"id": S("minecraft:jigsaw"), "name": S("vigil:door"), "target": S("vigil:door"),
                        "pool": S(f"vigil:{structure}/rooms"), "final_state": S(f"minecraft:{final}"),
                        "joint": S("rollable"), "placement_priority": I(0), "selection_priority": I(0)}


def _put(t, x, y, z, block):
    if block == "dark_oak_log" or block == "quartz_pillar":
        t.set(x, y, z, block, axis="y")
    elif block in ("jungle_leaves",):
        t.set(x, y, z, block, persistent=True, distance=1, waterlogged=False)
    else:
        t.set(x, y, z, block)


SIDES_AT = {
    "n": lambda w, d: (w // 2, 0, "north", (1, 0)),
    "s": lambda w, d: (w // 2, d - 1, "south", (1, 0)),
    "w": lambda w, d: (0, d // 2, "west", (0, 1)),
    "e": lambda w, d: (w - 1, d // 2, "east", (0, 1)),
}

ROOMS = {
    # kind: (width, depth, doors, weight)
    "corridor": (7, 11, "ns", 5),
    "hall": (13, 13, "nsew", 2),
    "guard_room": (15, 15, "nwe", 3),
    "shrine": (11, 15, "ns", 2),
    "crossroad": (9, 9, "nsew", 2),
    "treasure": (11, 11, "n", 2),
    "great_hall": (17, 21, "nsew", 2),
    "barracks": (15, 11, "ns", 3),
    "prison": (13, 13, "nse", 2),
    "library": (13, 11, "ns", 2),
    "gauntlet": (7, 17, "ns", 3),
    "armory": (11, 11, "nw", 2),
    "throne_room": (19, 19, "sew", 1),
    "crypt": (13, 17, "ns", 2),
    "ritual_chamber": (15, 15, "nsew", 2),
    "storage_vault": (11, 13, "n", 2),
    "courtyard": (17, 17, "nsew", 2),
    "mess_hall": (15, 11, "nse", 2),
    "mine_tunnel": (7, 19, "ns", 2),
}
H = 9  # floor y0, air 1..7, ceiling y8


def room(structure, kind):
    th = THEMES[structure]
    w, d, doors, _ = ROOMS[kind]
    t = Template(w, H, d)
    open_air = th.get("open", False)
    fa, fb = th["floor"]
    cx, cz = w // 2, d // 2
    if open_air:
        _floating_platform(t, th, w, d)
    else:
        t.fill(0, 0, 0, w - 1, H - 1, d - 1, th["wall"])
        t.fill(1, 1, 1, w - 2, H - 2, d - 2, "air")
        for x in range(w):
            for z in range(d):
                if x in (0, w - 1) or z in (0, d - 1):
                    _put(t, x, H - 1, z, th["wall"])
                else:
                    _put(t, x, H - 1, z, th["ceil"])
    # Floor: a border, a checked field and a centre stone.
    for x in range(1, w - 1):
        for z in range(1, d - 1):
            edge = x in (1, w - 2) or z in (1, d - 2)
            t.set(x, 0, z, th["accent"] if edge and th["accent"] not in ("dark_oak_log", "quartz_pillar") else (fa if (x + z) % 2 else fb))
            if edge and th["accent"] in ("dark_oak_log", "quartz_pillar"):
                t.set(x, 0, z, fb)
    t.set(cx, 0, cz, th["light"] if not open_air else "gold_block")
    # Pilasters with lights along the walls.
    if not open_air:
        for x in range(2, w - 2, 3):
            for z in (1, d - 2):
                for y in range(1, H - 1):
                    _put(t, x, y, z, th["accent"])
                t.set(x, 4, z, th["light"])
        for z in range(2, d - 2, 3):
            for x in (1, w - 2):
                for y in range(1, H - 1):
                    _put(t, x, y, z, th["accent"])
                t.set(x, 4, z, th["light"])
        for x in range(2, w - 2, 4):
            for z in range(2, d - 2, 4):
                if th["ceil"] != "jungle_leaves":
                    t.set(x, H - 1, z, th["light"])
                else:
                    t.set(x, H - 2, z, "lantern", hanging=True, waterlogged=False)
    # Doorways.
    for side in doors:
        x, z, facing, (ax, az) = SIDES_AT[side](w, d)
        for k in (-1, 0, 1):
            for y in range(1, 5):
                xx, zz = x + ax * k, z + az * k
                t.set(xx, y, zz, "air")
                # through the pilaster row behind the wall too
                ix, iz = xx + (1 if facing == "west" else -1 if facing == "east" else 0), zz + (1 if facing == "north" else -1 if facing == "south" else 0)
                t.set(ix, y, iz, "air")
        door(t, x, 0, z, facing, structure, fa)
    _furnish(t, th, structure, kind, w, d)
    if open_air:
        t = _lift_onto_rock(t, 4)
    return t


def _lift_onto_rock(t, lift):
    """Moves a sky room up and hangs an upside-down cone of rock under its floor."""
    import random as _r
    w, h, d = t.size
    out = Template(w, h + lift, d)
    for (x, y, z), (b, props) in t.blocks.items():
        out.blocks[(x, y + lift, z)] = (b, props)
    for (x, y, z), n in t.nbt.items():
        out.nbt[(x, y + lift, z)] = n
    r = _r.Random(w * 31 + d)
    cx, cz = (w - 1) / 2, (d - 1) / 2
    for x in range(w):
        for z in range(d):
            e = max(abs(x - cx) / (w / 2), abs(z - cz) / (d / 2))
            depth = int((1 - e) * (lift + 1)) + r.randint(0, 1)
            for k in range(1, min(depth, lift) + 1):
                out.set(x, lift - k, z, r.choice(["stone", "andesite", "calcite", "tuff"]))
    return out


def _floating_platform(t, th, w, d):
    """Sky rooms: an island with a railing and no roof."""
    for x in range(w):
        for z in range(d):
            t.set(x, 0, z, th["floor"][0])
    for x in range(w):
        for z in range(d):
            if x in (0, w - 1) or z in (0, d - 1):
                t.set(x, 1, z, "diorite_wall", up=True, waterlogged=False)
    for (x, z) in [(0, 0), (w - 1, 0), (0, d - 1), (w - 1, d - 1)]:
        t.set(x, 1, z, "quartz_pillar", axis="y")
        t.set(x, 2, z, "quartz_pillar", axis="y")
        t.set(x, 3, z, "lantern", hanging=False, waterlogged=False)


def _furnish(t, th, structure, kind, w, d):
    cx, cz = w // 2, d // 2
    mobs = th["mobs"]
    acc = th["accent"]
    if kind == "corridor":
        if not th.get("open"):
            for z in (3, d - 4):
                t.set(cx, H - 2, z, th["lantern"], hanging=True, waterlogged=False)
        mob_spawner(t, 1 if not th.get("open") else 2, 1, cz, mobs, structure)
    elif kind == "hall":
        for y in range(1, H - 1):
            _put(t, cx, y, cz, acc)
        for (x, z) in [(cx - 1, cz), (cx + 1, cz), (cx, cz - 1), (cx, cz + 1)]:
            stairs(t, x, 1, z, th["trim"], {(cx - 1, cz): "east", (cx + 1, cz): "west", (cx, cz - 1): "south", (cx, cz + 1): "north"}[(x, z)])
        t.set(cx, 4, cz, th["light"])
        for (x, z) in corners(cx, cz, 4):
            _put(t, x, 1, z, acc)
            t.set(x, 2, z, th["lantern"], hanging=False, waterlogged=False)
        mob_spawner(t, cx, 1, cz + 3, mobs, structure)
    elif kind == "guard_room":
        for x in (cx - 4, cx + 4):
            _put(t, x, 1, cz, acc)
            mob_spawner(t, x, 2, cz, mobs, structure)
            for (dx, dz) in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                t.set(x + dx, 1, cz + dz, th["light"])
        for x in range(2, w - 2, 2):
            t.set(x, 1, d - 3, "barrel", facing="up", open=False)
        t.chest(cx, 1, d - 3, f"{structure}_room", facing="north")
    elif kind == "shrine":
        for z in range(3, d - 3):
            if z != cz:
                for x in (2, w - 3):
                    for y in (1, 2, 3):
                        _put(t, x, y, z, th["decor"][0] if y < 3 else th["decor"][1])
        for z in (4, d - 5):
            _put(t, cx, 1, z, acc)
            t.set(cx, 2, z, th["lantern"], hanging=False, waterlogged=False)
        mob_spawner(t, cx, 1, cz, mobs, structure)
    elif kind == "crossroad":
        _put(t, cx, 1, cz, acc)
        t.set(cx, 2, cz, th["light"])
        _put(t, cx, 3, cz, acc)
        mob_spawner(t, 2, 1, 2, mobs, structure)
        mob_spawner(t, w - 3, 1, d - 3, mobs, structure)
    elif kind in NEW_ROOMS:
        NEW_ROOMS[kind](t, th, structure, w, d)
    elif kind == "treasure":
        for x in range(cx - 2, cx + 3):
            for z in range(d - 4, d - 1):
                _put(t, x, 1, z, acc)
        t.chest(cx, 2, d - 3, f"{structure}_room", facing="north")
        for x in (cx - 2, cx + 2):
            t.set(x, 2, d - 3, "gold_block")
            t.set(x, 3, d - 3, th["lantern"], hanging=False, waterlogged=False)
        mob_spawner(t, cx - 3, 1, cz - 1, mobs, structure)
        mob_spawner(t, cx + 3, 1, cz - 1, mobs, structure)


# ------------------------------------------------------------------ the newer rooms (harder: more spawners, traps)

def _bars(t, x, y, z, along_x):
    """Iron bars joined into a straight run (along x or along z)."""
    t.set(x, y, z, "iron_bars", east=along_x, west=along_x, north=not along_x, south=not along_x, waterlogged=False)


def great_hall(t, th, structure, w, d):
    """A long hall of columns with hanging lights, a carpet of the trim down the middle and three spawners."""
    cx, cz = w // 2, d // 2
    acc = th["accent"]
    for z in range(3, d - 3, 4):
        for x in (4, w - 5):
            for y in range(1, H - 1):
                _put(t, x, y, z, acc)
            stairs(t, x + 1, 1, z, th["trim"], "west")
            stairs(t, x - 1, 1, z, th["trim"], "east")
            t.set(x, 5, z, th["light"])
    for z in range(2, d - 2):
        if z % 4 == 1 and not th.get("open"):
            t.set(cx - 3, H - 2, z, th["lantern"], hanging=True, waterlogged=False)
            t.set(cx + 3, H - 2, z, th["lantern"], hanging=True, waterlogged=False)
    for (x, z) in [(2, cz - 4), (w - 3, cz + 4), (cx, 3)]:
        mob_spawner(t, x, 1, z, th["mobs"], structure)
    for x in (2, w - 3):
        for z in (2, d - 3):
            _put(t, x, 1, z, th["decor"][0])
            t.set(x, 2, z, th["lantern"], hanging=False, waterlogged=False)


def barracks(t, th, structure, w, d):
    """Rows of bunks (slabs over barrels) along both walls, a weapon rack, two spawners and a footlocker chest."""
    cx, cz = w // 2, d // 2
    for x in range(2, w - 2, 3):
        if abs(x - cx) <= 1:
            continue
        for z in (2, d - 3):
            t.set(x, 1, z, "barrel", facing="up", open=False)
            slab(t, x, 2, z, th["trim"])
            slab(t, x + 1 if x + 1 < w - 2 else x, 3, z, th["trim"], "top")
    for z in range(3, d - 3):
        if z != cz:
            t.set(2, 1, z, "smithing_table" if z % 2 else "fletching_table")
    mob_spawner(t, cx - 3, 1, cz, th["mobs"], structure)
    mob_spawner(t, cx + 3, 1, cz, th["mobs"], structure)
    t.chest(w - 3, 1, cz, f"{structure}_room", facing="west")


def prison(t, th, structure, w, d):
    """Four barred cells in the corners, chains from the ceiling and spawners locked in two of the cells."""
    for (x0, z0, fx) in [(1, 1, False), (w - 5, 1, True), (1, d - 5, False), (w - 5, d - 5, True)]:
        for k in range(4):
            for y in (1, 2, 3):
                bz = z0 + 3 if z0 == 1 else z0
                if k != 1:
                    _bars(t, x0 + k, y, bz, True)
                bx = x0 + 3 if not fx else x0
                if k != 2:
                    _bars(t, bx, y, z0 + k, False)
        t.set(x0 + 1 + (1 if fx else 0), 1, z0 + 1 + (1 if z0 != 1 else 0), "cobweb")
    mob_spawner(t, 2, 1, 2, th["mobs"], structure)
    mob_spawner(t, w - 3, 1, d - 3, th["mobs"], structure)
    cx, cz = w // 2, d // 2
    if not th.get("open"):
        for (x, z) in [(cx - 2, cz), (cx + 2, cz), (cx, cz - 2), (cx, cz + 2)]:
            for y in range(H - 4, H - 1):
                t.set(x, y, z, "chain", axis="y", waterlogged=False)


def library(t, th, structure, w, d):
    """Shelves in rows with an aisle down the middle, a reading desk, a spawner and a chest."""
    cx, cz = w // 2, d // 2
    for z in (3, d - 4):
        for x in range(2, w - 2):
            if abs(x - cx) <= 1:
                continue
            for y in (1, 2, 3):
                t.set(x, y, z, "bookshelf")
    t.set(cx - 2, 1, cz, "lectern", facing="east", has_book=False, powered=False)
    t.set(cx + 2, 1, cz, "enchanting_table")
    mob_spawner(t, 2, 1, cz, th["mobs"], structure)
    mob_spawner(t, w - 3, 1, cz, th["mobs"], structure)
    t.chest(w - 3, 1, 1 if th.get("open") else 2, f"{structure}_room", facing="south")


def gauntlet(t, th, structure, w, d):
    """A trapped corridor: pressure plates that fire arrows from the walls, and spawners in nooks."""
    cx = w // 2
    for z in range(3, d - 3, 3):
        x = cx - 1 if (z // 3) % 2 else cx + 1
        t.set(x, 1, z, "stone_pressure_plate", powered=False)
        if x < cx:
            t.set(x - 1, 1, z, "dispenser", facing="east", triggered=False)
            t.nbt[(x - 1, 1, z)] = {"id": S("minecraft:dispenser"),
                                    "Items": L(10, [C({"Slot": B(0), "id": S("minecraft:arrow"), "count": I(64)})])}
        else:
            t.set(x + 1, 1, z, "dispenser", facing="west", triggered=False)
            t.nbt[(x + 1, 1, z)] = {"id": S("minecraft:dispenser"),
                                    "Items": L(10, [C({"Slot": B(0), "id": S("minecraft:arrow"), "count": I(64)})])}
    mob_spawner(t, 1, 2, 4, th["mobs"], structure)
    mob_spawner(t, w - 2, 2, d - 5, th["mobs"], structure)


def armory(t, th, structure, w, d):
    """Anvils, a grindstone and a blast furnace round the walls, two spawners and a weapons chest."""
    cx, cz = w // 2, d // 2
    t.set(w - 3, 1, 2, "anvil", facing="north")
    t.set(w - 3, 1, 4, "grindstone", face="floor", facing="west")
    t.set(w - 3, 1, 6, "blast_furnace", facing="west", lit=False)
    t.set(w - 3, 1, 8 if d > 9 else d - 3, "smithing_table")
    for x in range(3, w - 4, 2):
        t.set(x, 1, d - 3, "barrel", facing="up", open=False)
    mob_spawner(t, cx, 1, cz + 2, th["mobs"], structure)
    mob_spawner(t, cx + 2, 1, 2, th["mobs"], structure)
    t.chest(cx - 1, 1, d - 3, f"{structure}_room", facing="north")


def throne_room(t, th, structure, w, d):
    """A raised dais at the far wall with a throne, columns down both sides and four spawners."""
    cx = w // 2
    acc, trim = th["accent"], th["trim"]
    for x in range(cx - 4, cx + 5):
        for z in range(1, 5):
            _put(t, x, 1, z, acc if (x in (cx - 4, cx + 4) or z == 4) else th["floor"][1])
    for x in range(cx - 4, cx + 5):
        stairs(t, x, 1, 5, trim, "south")
    for x in range(cx - 2, cx + 3):
        for z in range(1, 4):
            _put(t, x, 2, z, acc)
    stairs(t, cx, 3, 2, trim, "south")
    stairs(t, cx - 1, 3, 2, trim, "east")
    stairs(t, cx + 1, 3, 2, trim, "west")
    for y in (3, 4, 5):
        _put(t, cx, y, 1, acc)
    t.set(cx, 6, 1, th["light"])
    for x in (cx - 3, cx + 3):
        t.set(x, 3, 2, th["lantern"], hanging=False, waterlogged=False)
    for z in range(7, d - 2, 4):
        for x in (3, w - 4):
            for y in range(1, H - 1):
                _put(t, x, y, z, acc)
            t.set(x, 5, z, th["light"])
    for (x, z) in [(2, 8), (w - 3, 8), (2, d - 4), (w - 3, d - 4)]:
        mob_spawner(t, x, 1, z, th["mobs"], structure)
    t.chest(cx + 3, 2, 3, f"{structure}_room", facing="south")


def crypt(t, th, structure, w, d):
    """Rows of stone coffins under hanging soul lights, with spawners at both ends."""
    cx = w // 2
    for z in range(3, d - 3, 3):
        for x0 in (2, w - 5):
            for dx in range(3):
                slab(t, x0 + dx, 1, z, th["trim"], "bottom" if dx != 1 else "top")
            t.set(x0 + 1, 2, z, "candle", candles=3, lit=True, waterlogged=False)
    if not th.get("open"):
        for z in range(2, d - 2, 4):
            t.set(cx, H - 2, z, "soul_lantern", hanging=True, waterlogged=False)
    t.set(cx, 1, 2, "cobweb")
    t.set(cx, 1, d - 3, "cobweb")
    mob_spawner(t, 1, 1, 4, th["mobs"], structure)
    mob_spawner(t, w - 2, 1, d - 5, th["mobs"], structure)
    mob_spawner(t, cx, 1, d // 2, th["mobs"], structure)


def ritual_chamber(t, th, structure, w, d):
    """A ring of candles round a dark altar holding a spawner, with four more in the corners."""
    cx, cz = w // 2, d // 2
    for x in range(w):
        for z in range(d):
            r = ((x - cx) ** 2 + (z - cz) ** 2) ** 0.5
            if 4.5 <= r < 5.5 and abs(x - cx) > 1 and abs(z - cz) > 1:
                t.set(x, 1, z, "candle", candles=4, lit=True, waterlogged=False)
            elif 2.5 <= r < 3.5:
                t.set(x, 0, z, "crying_obsidian" if (x + z) % 2 else "obsidian")
    for (dx, dz, f) in [(-1, 0, "east"), (1, 0, "west"), (0, -1, "south"), (0, 1, "north")]:
        stairs(t, cx + dx, 1, cz + dz, th["trim"], f)
    mob_spawner(t, cx, 1, cz, th["mobs"], structure)
    for (x, z) in [(2, 2), (w - 3, 2), (2, d - 3), (w - 3, d - 3)]:
        mob_spawner(t, x, 1, z, th["mobs"], structure)
    t.set(cx, 3, cz, th["light"])


def storage_vault(t, th, structure, w, d):
    """A dead end stacked with barrels and crates; one of the chests holds loot, two spawners guard it."""
    cx = w // 2
    for z in range(3, d - 1, 2):
        for x in (1, 2, w - 3, w - 2):
            for y in range(1, 3 + (z % 3)):
                t.set(x, y, z, "barrel", facing="up", open=False)
    for x in range(3, w - 3):
        if x != cx:
            t.set(x, 1, d - 2, "barrel", facing="up", open=False)
            t.set(x, 2, d - 2, "hay_block" if x % 2 else "barrel", **({"axis": "y"} if x % 2 else {"facing": "up", "open": False}))
    t.chest(cx, 1, d - 2, f"{structure}_room", facing="north")
    mob_spawner(t, cx - 2, 1, d // 2, th["mobs"], structure)
    mob_spawner(t, cx + 2, 1, d // 2, th["mobs"], structure)


def courtyard(t, th, structure, w, d):
    """An overgrown yard: moss and flowers, four small trees, a well in the middle and spawners in the hedges."""
    cx, cz = w // 2, d // 2
    for x in range(2, w - 2):
        for z in range(2, d - 2):
            if abs(x - cx) > 1 and abs(z - cz) > 1 and (x * 7 + z * 3) % 5 == 0:
                t.set(x, 0, z, "moss_block")
                t.set(x, 1, z, "moss_carpet")
    for (x, z) in corners(cx, cz, 5):
        for y in (1, 2, 3):
            t.set(x, y, z, "oak_log", axis="y")
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                for y in (4, 5):
                    if (dx, dz) != (0, 0) or y == 5:
                        t.set(x + dx, y, z + dz, "azalea_leaves", persistent=True, distance=1, waterlogged=False)
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            if (dx, dz) != (0, 0):
                _put(t, cx + dx, 1, cz + dz, th["accent"])
    t.set(cx, 1, cz, th["light"])
    for (x, z) in [(cx, 3), (cx, d - 4), (3, cz + 3), (w - 4, cz - 3)]:
        mob_spawner(t, x, 1, z, th["mobs"], structure)


def mess_hall(t, th, structure, w, d):
    """Long tables with benches, a kitchen corner and two spawners."""
    cx, cz = w // 2, d // 2
    for z in (3, d - 4):
        for x in range(2, w - 2):
            if abs(x - cx) <= 1:
                continue
            slab(t, x, 1, z, th["trim"], "top")
            stairs(t, x, 1, z - 1, th["trim"], "south")
            stairs(t, x, 1, z + 1, th["trim"], "north")
    t.set(1, 1, cz - 1, "smoker", facing="east", lit=False)
    t.set(1, 1, cz + 1, "barrel", facing="east", open=False)
    t.set(1, 2, cz, "cauldron")
    mob_spawner(t, cx, 1, 2, th["mobs"], structure)
    mob_spawner(t, cx, 1, d - 3, th["mobs"], structure)


def mine_tunnel(t, th, structure, w, d):
    """A narrow old dig: timber frames, a broken rail line, cobwebs and spawners in the side pockets."""
    cx = w // 2
    for z in range(2, d - 2, 4):
        for x in (1, w - 2):
            for y in (1, 2, 3):
                t.set(x, y, z, "spruce_log", axis="y")
        for x in range(1, w - 1):
            t.set(x, 4, z, "spruce_planks")
        t.set(cx, 3, z + 1 if z + 1 < d - 1 else z, "lantern", hanging=True, waterlogged=False)
    for z in range(1, d - 1):
        if z % 5 != 3:
            t.set(cx, 1, z, "rail", shape="north_south", waterlogged=False)
    for (x, z) in [(1, 5), (w - 2, 11), (1, 15)]:
        if z < d - 1:
            t.set(x, 2, z, "cobweb")
    mob_spawner(t, 1, 1, 7, th["mobs"], structure)
    mob_spawner(t, w - 2, 1, 13, th["mobs"], structure)


NEW_ROOMS = {"great_hall": great_hall, "barracks": barracks, "prison": prison, "library": library,
             "gauntlet": gauntlet, "armory": armory, "throne_room": throne_room, "crypt": crypt,
             "ritual_chamber": ritual_chamber, "storage_vault": storage_vault, "courtyard": courtyard,
             "mess_hall": mess_hall, "mine_tunnel": mine_tunnel}


def cap(structure):
    """The wall plug a door gets when nothing more is built behind it."""
    th = THEMES[structure]
    t = Template(5, 6, 1)
    if th.get("open"):
        for x in range(5):
            t.set(x, 0, 0, th["floor"][0])
            t.set(x, 1, 0, "diorite_wall", up=True, waterlogged=False)
        t.set(2, 2, 0, "lantern", hanging=False, waterlogged=False)
    else:
        for x in range(5):
            for y in range(6):
                _put(t, x, y, 0, th["wall"] if th["wall"] != "dark_oak_log" else "dark_oak_planks")
        _put(t, 2, 2, 0, th["accent"])
    door(t, 2, 0, 0, "north", structure, th["floor"][0])
    return t


# The ways out of each arena: (side, centre along that side, floor y, how deep to dig in, sealed tunnel?).
ARENA_DOORS = {
    "sunken_vault": [("w", 14, 1, 6, True), ("e", 14, 1, 6, True)],
    "buried_vault": [("w", 15, 0, 2, True), ("e", 15, 0, 2, True), ("n", 12, 0, 2, True)],
    "sky_citadel": [("w", 15, 6, 7, False), ("e", 15, 6, 7, False), ("n", 15, 6, 7, False)],
    "nether_forge": [("w", 13, 0, 4, False), ("e", 13, 0, 4, False)],
    "desert_tomb": [("w", 15, 0, 3, True), ("e", 15, 0, 3, True)],
    "frozen_bastion": [("w", 12, 0, 2, False), ("e", 12, 0, 2, False), ("n", 7, 0, 2, False)],
    "overgrown_labyrinth": [("e", 14, 0, 1, False), ("n", 14, 0, 1, False), ("s", 14, 0, 1, False)],
    "watchers_hollow": [("w", 11, 0, 3, False), ("e", 11, 0, 3, False), ("n", 11, 0, 3, False)],
}


def arena_doors(structure, t):
    th = THEMES[structure]
    X, _, Z = t.size
    for side, along, fy, depth, sealed in ARENA_DOORS[structure]:
        for k in range(depth):
            if side == "w":
                cells = [(k, along + j) for j in (-1, 0, 1)]
                wallc = [(k, along - 2), (k, along + 2)]
            elif side == "e":
                cells = [(X - 1 - k, along + j) for j in (-1, 0, 1)]
                wallc = [(X - 1 - k, along - 2), (X - 1 - k, along + 2)]
            elif side == "n":
                cells = [(along + j, k) for j in (-1, 0, 1)]
                wallc = [(along - 2, k), (along + 2, k)]
            else:
                cells = [(along + j, Z - 1 - k) for j in (-1, 0, 1)]
                wallc = [(along - 2, Z - 1 - k), (along + 2, Z - 1 - k)]
            for (x, z) in cells:
                t.set(x, fy, z, th["floor"][1] if th["floor"][1] != "mud_bricks" else "deepslate_tiles")
                for y in range(fy + 1, fy + 5):
                    t.set(x, y, z, "air")
                if sealed:
                    _put(t, x, fy + 5, z, th["wall"])
            if sealed:
                for (x, z) in wallc:
                    for y in range(fy, fy + 6):
                        _put(t, x, y, z, th["wall"])
        if side == "w":
            door(t, 0, fy, along, "west", structure, th["floor"][0])
        elif side == "e":
            door(t, X - 1, fy, along, "east", structure, th["floor"][0])
        elif side == "n":
            door(t, along, fy, 0, "north", structure, th["floor"][0])
        else:
            door(t, along, fy, Z - 1, "south", structure, th["floor"][0])
    return t


STRUCTURES = {
    # name: (builder, biomes, step, start_height, heightmap, spacing, separation, salt, terrain)
    "sunken_vault": (sunken_vault, "#minecraft:is_ocean", "surface_structures", 0, "OCEAN_FLOOR_WG", 110, 50, 31170801, "none"),
    "buried_vault": (buried_vault, "#minecraft:is_overworld", "underground_structures", -40, None, 120, 60, 31170802, "none"),
    "sky_citadel": (sky_citadel, "#minecraft:is_overworld", "surface_structures", 210, None, 130, 60, 31170803, "none"),
    "nether_forge": (nether_forge, "minecraft:basalt_deltas", "surface_structures", 45, None, 60, 30, 31170804, "beard_box"),
    "desert_tomb": (desert_tomb, "minecraft:desert", "underground_structures", -15, "WORLD_SURFACE_WG", 100, 45, 31170805, "none"),
    "frozen_bastion": (frozen_bastion, ["minecraft:snowy_slopes", "minecraft:frozen_peaks", "minecraft:jagged_peaks", "minecraft:snowy_plains",
                                        "minecraft:ice_spikes", "minecraft:grove"], "surface_structures", 0, "WORLD_SURFACE_WG", 110, 50, 31170807, "beard_thin"),
    "overgrown_labyrinth": (overgrown_labyrinth, "#minecraft:is_jungle", "surface_structures", -1, "WORLD_SURFACE_WG", 110, 50, 31170808, "beard_thin"),
    "watchers_hollow": (watchers_hollow, "minecraft:dark_forest", "surface_structures", 0, "WORLD_SURFACE_WG", 160, 80, 31170806, "beard_thin"),
}


def pool(locations, fallback="minecraft:empty"):
    elements = []
    for loc in locations:
        loc, weight = loc if isinstance(loc, tuple) else (loc, 1)
        elements.append({"weight": weight, "element": {"element_type": "minecraft:single_pool_element", "location": f"vigil:{loc}",
                                                       "processors": "minecraft:empty", "projection": "rigid"}})
    return {"fallback": fallback, "elements": elements}


def build():
    for item_id in ITEMS:
        write(f"loot_table/items/{item_id}.json", item_loot(item_id))
    for item_id in RECIPES:
        write(f"recipe/{item_id}.json", recipe(item_id))
    for name, pools in CHESTS.items():
        write(f"loot_table/chests/{name}.json", {"type": "minecraft:chest", "pools": pools})
    for name, (fn, biomes, step, height, heightmap, spacing, sep, salt, terrain) in STRUCTURES.items():
        arena = arena_doors(name, fn())
        # The old single-piece name stays, so structures generated before still find their template.
        write(f"structure/{name}.nbt", arena.to_nbt())
        write(f"worldgen/template_pool/{name}.json", pool([name]))
        write(f"structure/{name}/arena.nbt", arena.to_nbt())
        for kind in ROOMS:
            write(f"structure/{name}/{kind}.nbt", room(name, kind).to_nbt())
        write(f"structure/{name}/cap.nbt", cap(name).to_nbt())
        write(f"worldgen/template_pool/{name}/start.json", pool([f"{name}/arena"]))
        write(f"worldgen/template_pool/{name}/rooms.json",
              pool([(f"{name}/{k}", ROOMS[k][3]) for k in ROOMS], fallback=f"vigil:{name}/caps"))
        write(f"worldgen/template_pool/{name}/caps.json", pool([f"{name}/cap"]))
        # Room chests: the structure's everyday loot, now and then one of its secret items.
        write(f"loot_table/chests/{name}_room.json", {"type": "minecraft:chest", "pools": [
            CHESTS[name][-1], CHESTS[name][0]]})
        s = {"type": "minecraft:jigsaw", "biomes": biomes, "step": step, "spawn_overrides": {}, "terrain_adaptation": terrain,
             "start_pool": f"vigil:{name}/start", "size": 12, "start_height": {"absolute": height}, "max_distance_from_center": 116,
             "use_expansion_hack": False}
        if heightmap:
            s["project_start_to_heightmap"] = heightmap
        write(f"worldgen/structure/{name}.json", s)
        write(f"worldgen/structure_set/{name}.json", {"structures": [{"structure": f"vigil:{name}", "weight": 1}],
                                                      "placement": {"type": "minecraft:random_spread", "spacing": spacing,
                                                                    "separation": sep, "salt": salt}})
    write("tags/worldgen/structure/secret.json", {"values": [f"vigil:{n}" for n in STRUCTURES]})
    print("items", len(ITEMS), "recipes", len(RECIPES), "structures", len(STRUCTURES))


if __name__ == "__main__":
    build()
