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

def filler(entries, rolls=(4, 7)):
    out = []
    for name, weight, lo, hi in entries:
        e = {"type": "minecraft:item", "name": f"minecraft:{name}", "weight": weight}
        if hi > 1:
            e["functions"] = [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": lo, "max": hi}}]
        out.append(e)
    return {"rolls": {"type": "minecraft:uniform", "min": rolls[0], "max": rolls[1]}, "entries": out}


def secret(weights, rolls=1):
    return {"rolls": rolls, "entries": [{"type": "minecraft:loot_table", "value": f"vigil:items/{i}", "weight": w} for i, w in weights]}


CHESTS = {
    "sunken_vault": [secret([("tidecaller", 3), ("seeker_compass", 2)]),
                     filler([("prismarine_shard", 10, 2, 8), ("prismarine_crystals", 8, 2, 6), ("gold_ingot", 8, 1, 5),
                             ("iron_ingot", 8, 2, 6), ("emerald", 5, 1, 3), ("heart_of_the_sea", 1, 1, 1)])],
    "buried_shrine": [secret([("voidblade", 3), ("shadow_cloak", 2)]),
                      filler([("amethyst_shard", 10, 2, 6), ("iron_ingot", 10, 2, 6), ("gold_ingot", 6, 1, 4),
                              ("echo_shard", 2, 1, 2), ("diamond", 2, 1, 2), ("candle", 6, 1, 4)])],
    "sky_altar": [secret([("phoenix_feather", 4), ("seeker_compass", 1)]),
                  filler([("feather", 10, 2, 8), ("golden_apple", 3, 1, 1), ("emerald", 6, 1, 4), ("gold_ingot", 8, 1, 4),
                          ("phantom_membrane", 5, 1, 3), ("cherry_sapling", 4, 1, 2)])],
    "nether_forge": [secret([("stormbreaker", 4), ("phoenix_feather", 1)]),
                     filler([("gold_ingot", 10, 2, 6), ("blaze_rod", 8, 1, 4), ("magma_cream", 6, 1, 4), ("quartz", 8, 3, 9),
                             ("iron_ingot", 8, 2, 6), ("netherite_scrap", 1, 1, 1)])],
    "desert_tomb": [secret([("seeker_compass", 3), ("shadow_cloak", 2), ("voidblade", 1)]),
                    filler([("gold_ingot", 10, 2, 6), ("bone", 10, 2, 6), ("emerald", 6, 1, 4), ("rotten_flesh", 8, 2, 6),
                            ("iron_ingot", 6, 1, 4), ("diamond", 1, 1, 2)])],
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

def sunken_vault():
    r = random.Random(1)
    t = Template(13, 9, 13)
    for x in range(13):
        for z in range(13):
            if (x - 6) ** 2 + (z - 6) ** 2 <= 46:
                t.set(x, 0, z, r.choice(["prismarine_bricks", "prismarine_bricks", "dark_prismarine", "prismarine"]))
    # Broken pillars around
    for (x, z, h) in [(0, 6, 4), (12, 6, 2), (6, 0, 3), (2, 2, 5), (10, 2, 3), (2, 10, 2)]:
        for y in range(1, h):
            t.set(x, y, z, "prismarine_bricks")
        t.set(x, h, z, "prismarine_brick_slab", type="bottom", waterlogged=True)
    # The vault: sealed and dry inside
    t.shell(3, 0, 3, 9, 6, 9, "dark_prismarine")
    t.fill(4, 6, 4, 8, 6, 8, "prismarine_bricks")
    t.set(6, 6, 6, "sea_lantern")
    for (x, z) in [(4, 4), (8, 4), (4, 8), (8, 8)]:
        t.set(x, 1, z, "sea_lantern")
    t.chest(6, 1, 5, "sunken_vault", facing="south")
    t.set(6, 1, 7, "wet_sponge")
    # The door (on the lock marker) and its four trapdoors + the code
    t.set(6, 0, 9, "reinforced_deepslate")
    t.set(6, 1, 9, "iron_door", facing="north", half="lower", hinge="left", open=False, powered=False)
    t.set(6, 2, 9, "iron_door", facing="north", half="upper", hinge="left", open=False, powered=False)
    for x in (4, 5, 7, 8):
        t.set(x, 3, 10, "oak_trapdoor", facing="south", half="top", open=False, powered=False, waterlogged=True)
    t.sign(6, 4, 10, "south", ["Open  Shut", "Shut  Open", "(the hatches)", "~ the Vault ~"], waterlogged=True)
    return t


def buried_shrine():
    r = random.Random(2)
    t = Template(11, 7, 11)
    t.shell(0, 0, 0, 10, 6, 10, "deepslate_tiles")
    for x in range(1, 10):
        for z in range(1, 10):
            t.set(x, 0, z, "polished_deepslate" if (x + z) % 2 else "deepslate_tiles")
            if r.random() < 0.12:
                t.set(x, 1, z, "sculk")
    for (x, z) in [(1, 1), (9, 1), (1, 9), (9, 9)]:
        t.fill(x, 1, z, x, 5, z, "polished_deepslate")
        t.set(x, 6, z, "chiseled_deepslate")
    t.set(5, 0, 5, "chiseled_deepslate")
    t.set(5, 1, 5, "polished_blackstone")
    t.chest(5, 2, 5, "buried_shrine", facing="south")
    for (x, z) in [(4, 4), (6, 4), (4, 6), (6, 6)]:
        t.set(x, 1, z, "candle", candles=3, lit=True)
    for (x, z) in [(3, 3), (7, 3), (3, 7), (7, 7)]:
        t.set(x, 5, z, "soul_lantern", hanging=True)
    t.set(5, 5, 5, "chain", axis="y")
    return t


def sky_altar():
    r = random.Random(3)
    t = Template(13, 9, 13)
    for y in range(0, 4):
        rad = 1.5 + y * 1.4
        for x in range(13):
            for z in range(13):
                d = ((x - 6) ** 2 + (z - 6) ** 2) ** 0.5
                if d <= rad + r.random() * 0.6:
                    block = "grass_block" if y == 3 else ("dirt" if y == 2 else r.choice(["stone", "andesite", "stone", "dirt"]))
                    t.set(x, y, z, block)
    for x in range(4, 9):
        for z in range(4, 9):
            if (x - 6) ** 2 + (z - 6) ** 2 <= 5:
                t.set(x, 3, z, "smooth_quartz")
    t.set(6, 4, 6, "chiseled_quartz_block")
    t.chest(6, 5, 6, "sky_altar", facing="south")
    for (x, z, h) in [(3, 3, 3), (9, 3, 2), (3, 9, 1), (9, 9, 3)]:
        for y in range(4, 4 + h):
            t.set(x, y, z, "quartz_pillar", axis="y")
        t.set(x, 4 + h, z, "end_rod", facing="up")
    for _ in range(10):
        x, z = r.randint(1, 11), r.randint(1, 11)
        if (x, 3, z) in t.blocks and t.blocks[(x, 3, z)][0] == "grass_block" and (x, 4, z) not in t.blocks:
            t.set(x, 4, z, r.choice(["poppy", "dandelion", "cornflower", "oxeye_daisy"]))
    return t


def nether_forge():
    t = Template(13, 9, 13)
    t.shell(0, 0, 0, 12, 8, 12, "polished_blackstone_bricks")
    for x in range(13):
        for z in range(13):
            if (x + z) % 3 == 0:
                t.set(x, 0, z, "magma_block")
    t.fill(1, 1, 1, 11, 7, 11, "air")
    t.fill(5, 1, 0, 7, 3, 0, "air")
    t.set(5, 1, 0, "nether_brick_fence")
    t.set(7, 1, 0, "nether_brick_fence")
    for (x, z) in [(2, 2), (10, 2), (2, 10), (10, 10)]:
        t.fill(x, 1, z, x, 7, z, "gilded_blackstone")
    t.set(6, 1, 9, "anvil", facing="east")
    t.set(4, 1, 10, "blast_furnace", facing="south", lit=True)
    t.set(8, 1, 10, "blast_furnace", facing="south", lit=True)
    t.set(6, 1, 11, "smithing_table")
    t.set(3, 1, 6, "lava_cauldron")
    t.chest(9, 1, 6, "nether_forge", facing="west")
    t.spawner(6, 6, 6, "blaze")
    for (x, z) in [(4, 4), (8, 4), (4, 8), (8, 8)]:
        t.set(x, 7, z, "shroomlight")
    return t


def desert_tomb():
    t = Template(11, 11, 11)
    # Entrance at the surface, a ladder shaft down
    for (x, z) in [(4, 0), (5, 0), (6, 0), (4, 1), (6, 1), (4, 2), (5, 2), (6, 2)]:
        t.set(x, 10, z, "chiseled_sandstone" if (x + z) % 2 else "cut_sandstone")
    for y in range(1, 11):
        t.set(5, y, 1, "air")
        for (x, z) in [(4, 1), (6, 1), (5, 0)]:
            t.set(x, y, z, "sandstone")
        if y > 2:
            t.set(5, y, 2, "sandstone")
        t.set(5, y, 1, "ladder", facing="south")
    t.set(5, 0, 1, "sandstone")
    # Corridor with two arrow traps
    for z in range(2, 6):
        t.set(5, 0, z, "smooth_sandstone")
        t.set(5, 1, z, "air")
        t.set(5, 2, z, "air")
        t.set(5, 3, z, "cut_sandstone")
        for x in (4, 6):
            for y in (1, 2):
                t.set(x, y, z, "sandstone")
    t.set(5, 1, 3, "stone_pressure_plate", powered=False)
    t.set(5, 1, 4, "stone_pressure_plate", powered=False)
    t.dispenser(4, 1, 3, "east")
    t.dispenser(6, 1, 4, "west")
    # Treasure room
    t.shell(1, 0, 6, 9, 5, 10, "sandstone")
    t.set(5, 1, 6, "air")
    t.set(5, 2, 6, "air")
    for x in range(2, 9):
        for z in range(7, 10):
            t.set(x, 0, z, "orange_terracotta" if (x + z) % 2 else "smooth_sandstone")
    t.set(5, 0, 8, "blue_terracotta")
    t.chest(5, 1, 9, "desert_tomb", facing="north")
    for (x, z) in [(2, 7), (8, 7), (2, 9), (8, 9)]:
        t.set(x, 1, z, "chiseled_sandstone")
        t.set(x, 2, z, "lantern")
    t.set(3, 1, 9, "skeleton_skull", rotation=8)
    t.set(7, 1, 9, "skeleton_skull", rotation=8)
    return t


def watchers_hollow():
    r = random.Random(6)
    t = Template(9, 9, 9)
    for y in range(0, 8):
        for x in range(9):
            for z in range(9):
                d = ((x - 4) ** 2 + (z - 4) ** 2) ** 0.5
                if y == 0 and d <= 4.2:
                    t.set(x, y, z, "sculk" if r.random() < 0.4 else "mud")
                elif 1 <= y <= 6:
                    if 3.0 < d <= 4.2:
                        t.set(x, y, z, "dark_oak_wood", axis="y")
                    elif d <= 3.0:
                        t.set(x, y, z, "air")
                elif y == 7 and d <= 4.0:
                    t.set(x, y, z, "dark_oak_leaves", persistent=True, distance=1)
    t.set(4, 1, 0, "air")
    t.set(4, 2, 0, "air")
    t.set(4, 1, 7, "black_concrete")
    t.set(4, 2, 7, "black_concrete")
    t.set(4, 3, 7, "wither_skeleton_skull", rotation=0)
    t.set(3, 1, 7, "soul_lantern", hanging=False)
    t.set(5, 1, 7, "soul_lantern", hanging=False)
    t.chest(4, 1, 5, "watchers_hollow", facing="north")
    t.set(2, 1, 3, "sculk_sensor")
    t.set(6, 1, 3, "sculk_sensor")
    return t


STRUCTURES = {
    # name: (builder, biomes, step, start_height, heightmap, spacing, separation, salt, terrain)
    "sunken_vault": (sunken_vault, "#minecraft:is_ocean", "surface_structures", 0, "OCEAN_FLOOR_WG", 40, 14, 31170801, "none"),
    "buried_shrine": (buried_shrine, "#minecraft:is_overworld", "underground_structures", -30, None, 36, 12, 31170802, "none"),
    "sky_altar": (sky_altar, "#minecraft:is_overworld", "surface_structures", 200, None, 48, 16, 31170803, "none"),
    "nether_forge": (nether_forge, "minecraft:basalt_deltas", "surface_structures", 40, None, 30, 10, 31170804, "beard_box"),
    "desert_tomb": (desert_tomb, "minecraft:desert", "underground_structures", -10, "WORLD_SURFACE_WG", 40, 14, 31170805, "none"),
    "watchers_hollow": (watchers_hollow, "minecraft:dark_forest", "surface_structures", 0, "WORLD_SURFACE_WG", 64, 24, 31170806, "beard_thin"),
}


def build():
    for item_id in ITEMS:
        write(f"loot_table/items/{item_id}.json", item_loot(item_id))
    for item_id in RECIPES:
        write(f"recipe/{item_id}.json", recipe(item_id))
    for name, pools in CHESTS.items():
        write(f"loot_table/chests/{name}.json", {"type": "minecraft:chest", "pools": pools})
    for name, (fn, biomes, step, height, heightmap, spacing, sep, salt, terrain) in STRUCTURES.items():
        write(f"structure/{name}.nbt", fn().to_nbt())
        s = {"type": "minecraft:jigsaw", "biomes": biomes, "step": step, "spawn_overrides": {}, "terrain_adaptation": terrain,
             "start_pool": f"vigil:{name}", "size": 1, "start_height": {"absolute": height}, "max_distance_from_center": 80,
             "use_expansion_hack": False}
        if heightmap:
            s["project_start_to_heightmap"] = heightmap
        write(f"worldgen/structure/{name}.json", s)
        write(f"worldgen/template_pool/{name}.json", {"fallback": "minecraft:empty", "elements": [{"weight": 1, "element": {
            "element_type": "minecraft:single_pool_element", "location": f"vigil:{name}", "processors": "minecraft:empty",
            "projection": "rigid"}}]})
        write(f"worldgen/structure_set/{name}.json", {"structures": [{"structure": f"vigil:{name}", "weight": 1}],
                                                      "placement": {"type": "minecraft:random_spread", "spacing": spacing,
                                                                    "separation": sep, "salt": salt}})
    write("tags/worldgen/structure/secret.json", {"values": [f"vigil:{n}" for n in STRUCTURES]})
    print("items", len(ITEMS), "recipes", len(RECIPES), "structures", len(STRUCTURES))


if __name__ == "__main__":
    build()
