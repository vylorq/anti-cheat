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
# The custom blocks (made by scripts/blocks/build.py): a block "vigil:<name>" is a frozen note block state.
BLOCKS = json.load(open(os.path.join(ROOT, "src", "main", "resources", "vigil", "blocks.json")))


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


def block_components(name):
    b = BLOCKS[name]
    place = b["structure"].replace("_", " ").title()
    return {"minecraft:item_name": text(b["display"], "#C9B6FF"),
            "minecraft:lore": [text(f"A block from the {place}", "gray", False)],
            "minecraft:custom_model_data": {"strings": ["vigil:" + code(name + "_block")]},
            "minecraft:custom_data": {"vigil_block": name}}


def block_pool(structure):
    """A stack or two of the structure's own custom blocks."""
    return {"rolls": {"type": "minecraft:uniform", "min": 1, "max": 2}, "entries": [
        {"type": "minecraft:item", "name": "minecraft:note_block", "functions": [
            {"function": "minecraft:set_components", "components": block_components(n)},
            {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 6, "max": 16}}]}
        for n, b in BLOCKS.items() if b["structure"] == structure]}


def secret(weights, rolls=1):
    return {"rolls": rolls, "entries": [{"type": "minecraft:loot_table", "value": f"vigil:items/{i}", "weight": w} for i, w in weights]}


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
        if block.startswith("vigil:"):
            props = {"instrument": "custom_head", "note": BLOCKS[block[6:]]["note"], "powered": False}
            block = "note_block"
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


def sunken_vault():
    r = random.Random(1)
    t = Template(21, 12, 21)
    disc(t, 10, 10, 10.4, 0, lambda x, z: r.choice(["prismarine_bricks", "prismarine_bricks", "dark_prismarine", "prismarine"]))
    for (x, z, h) in [(0, 10, 6), (20, 10, 3), (10, 0, 4), (2, 2, 7), (18, 2, 4), (2, 18, 3), (18, 18, 5)]:
        for y in range(1, h):
            t.set(x, y, z, "prismarine_bricks")
        t.set(x, h, z, "prismarine_brick_slab", type="bottom", waterlogged=True)
    # The vault: a big sealed hall, dry inside.
    t.shell(3, 0, 3, 17, 11, 17, "vigil:abyssal_bricks")
    t.fill(4, 0, 4, 16, 0, 16, "prismarine_bricks")
    for x in range(4, 17):
        for z in range(4, 17):
            if (x + z) % 4 == 0:
                t.set(x, 0, z, "sea_lantern")
    for (x, z) in [(5, 5), (15, 5), (5, 15), (15, 15)]:
        t.fill(x, 1, z, x, 10, z, "vigil:abyssal_bricks")
        t.set(x, 5, z, "sea_lantern")
        t.set(x, 3, z, "vigil:tide_rune")
        t.set(x, 8, z, "vigil:tide_rune")
    t.chest(10, 1, 4, "sunken_vault", facing="south")
    t.chest(9, 1, 4, "sunken_vault", facing="south")
    t.set(11, 1, 4, "conduit", waterlogged=False)
    # The door (on the lock marker), its four hatches and the code
    t.set(10, 0, 17, "reinforced_deepslate")
    t.set(10, 1, 17, "iron_door", facing="north", half="lower", hinge="left", open=False, powered=False)
    t.set(10, 2, 17, "iron_door", facing="north", half="upper", hinge="left", open=False, powered=False)
    for x in (8, 9, 11, 12):
        t.set(x, 3, 18, "oak_trapdoor", facing="south", half="top", open=False, powered=False, waterlogged=True)
    t.sign(10, 4, 18, "south", ["Open  Shut", "Shut  Open", "(the hatches)", "~ the Vault ~"], waterlogged=True)
    return t


def buried_vault():
    r = random.Random(2)
    t = Template(23, 12, 23)
    t.shell(0, 0, 0, 22, 11, 22, "vigil:vault_stone")
    for x in range(1, 22):
        for z in range(1, 22):
            t.set(x, 0, z, "polished_deepslate" if (x // 2 + z // 2) % 2 else "deepslate_tiles")
            if r.random() < 0.08:
                t.set(x, 1, z, "sculk")
    for (x, z) in [(4, 4), (18, 4), (4, 18), (18, 18), (11, 3), (11, 19), (3, 11), (19, 11)]:
        t.fill(x, 1, z, x, 10, z, "polished_deepslate")
        t.set(x, 7, z, "chiseled_deepslate")
        t.set(x, 10, z, "chiseled_deepslate")
    t.fill(9, 0, 9, 13, 0, 13, "chiseled_deepslate")
    t.set(11, 0, 11, "crying_obsidian")
    t.set(11, 1, 20, "vigil:vault_lock")
    for (x, y) in [(10, 1), (12, 1), (10, 2), (12, 2), (11, 3)]:
        t.set(x, y, 21, "vigil:vault_lock")
    t.chest(11, 2, 20, "buried_vault", facing="north")
    for (x, z) in [(7, 7), (15, 7), (7, 15), (15, 15), (11, 11)]:
        t.set(x, 10, z, "chain", axis="y")
        t.set(x, 9, z, "soul_lantern", hanging=True)
    for (x, z) in [(10, 19), (12, 19)]:
        t.set(x, 1, z, "candle", candles=4, lit=True)
    return t


def sky_citadel():
    r = random.Random(3)
    t = Template(25, 14, 25)
    for y in range(0, 5):
        rad = 2 + y * 2.3
        for x in range(25):
            for z in range(25):
                d = ((x - 12) ** 2 + (z - 12) ** 2) ** 0.5
                if d <= rad + r.random() * 0.8:
                    block = "grass_block" if y == 4 else ("dirt" if y == 3 else r.choice(["stone", "andesite", "stone", "calcite"]))
                    t.set(x, y, z, block)
    disc(t, 12, 12, 8.5, 4, lambda x, z: "vigil:cloud_marble" if (x + z) % 3 else "smooth_quartz")
    disc(t, 12, 12, 3.2, 4, "vigil:sky_rune")
    for i in range(8):
        import math
        a = i * math.pi / 4
        x, z = int(round(12 + math.cos(a) * 8)), int(round(12 + math.sin(a) * 8))
        h = 4 + (i % 3) * 2
        for y in range(5, 5 + h):
            t.set(x, y, z, "quartz_pillar", axis="y")
        t.set(x, 5 + h, z, "end_rod", facing="up")
    t.chest(12, 5, 20, "sky_citadel", facing="north")
    t.set(12, 4, 20, "vigil:sky_rune")
    for _ in range(14):
        x, z = r.randint(1, 23), r.randint(1, 23)
        if t.blocks.get((x, 4, z), ("",))[0] == "grass_block" and (x, 5, z) not in t.blocks:
            t.set(x, 5, z, r.choice(["poppy", "dandelion", "cornflower", "oxeye_daisy", "azure_bluet"]))
    return t


def nether_forge():
    t = Template(23, 12, 23)
    t.shell(0, 0, 0, 22, 11, 22, "vigil:forge_bricks")
    for x in range(23):
        for z in range(23):
            if (x + z) % 4 == 0:
                t.set(x, 0, z, "magma_block")
    t.fill(1, 1, 1, 21, 10, 21, "air")
    t.fill(10, 1, 0, 12, 4, 0, "air")
    # Lava channels behind iron bars along the walls
    for z in range(2, 21):
        for x in (1, 21):
            t.set(x, 0, z, "lava")
            t.set(x, 1, z, "iron_bars", east=(x == 1), west=(x == 21), north=False, south=False, waterlogged=False)
    for (x, z) in [(5, 5), (17, 5), (5, 17), (17, 17)]:
        t.fill(x, 1, z, x, 10, z, "gilded_blackstone")
        t.set(x, 5, z, "vigil:molten_core")
    t.set(11, 1, 19, "anvil", facing="east")
    t.set(8, 1, 20, "blast_furnace", facing="north", lit=True)
    t.set(14, 1, 20, "blast_furnace", facing="north", lit=True)
    t.set(11, 1, 21, "smithing_table")
    t.fill(10, 0, 10, 12, 0, 12, "vigil:molten_core")
    t.set(6, 1, 11, "lava_cauldron")
    t.set(16, 1, 11, "lava_cauldron")
    t.chest(11, 1, 20, "nether_forge", facing="north")
    for (x, z) in [(7, 7), (15, 7), (7, 15), (15, 15), (11, 11)]:
        t.set(x, 10, z, "shroomlight")
    return t


def desert_tomb():
    t = Template(23, 16, 23)
    # Surface entrance and the ladder shaft
    for (x, z) in [(10, 0), (11, 0), (12, 0), (10, 1), (12, 1), (10, 2), (11, 2), (12, 2)]:
        t.set(x, 15, z, "chiseled_sandstone" if (x + z) % 2 else "cut_sandstone")
    for y in range(1, 16):
        for (x, z) in [(10, 1), (12, 1), (11, 0)]:
            t.set(x, y, z, "sandstone")
        if y > 2:
            t.set(11, y, 2, "sandstone")
        t.set(11, y, 1, "ladder", facing="south")
    t.set(11, 0, 1, "sandstone")
    # Trapped corridor
    for z in range(2, 8):
        t.set(11, 0, z, "smooth_sandstone")
        t.set(11, 1, z, "air")
        t.set(11, 2, z, "air")
        t.set(11, 3, z, "cut_sandstone")
        for x in (10, 12):
            for y in (1, 2):
                t.set(x, y, z, "sandstone")
    for z in (3, 4, 5, 6):
        t.set(11, 1, z, "stone_pressure_plate", powered=False)
    t.dispenser(10, 1, 3, "east")
    t.dispenser(12, 1, 4, "west")
    t.dispenser(10, 1, 5, "east")
    t.dispenser(12, 1, 6, "west")
    # The burial hall (the boss arena)
    t.shell(2, 0, 7, 20, 9, 22, "vigil:cursed_sandstone")
    t.set(11, 1, 7, "air")
    t.set(11, 2, 7, "air")
    for x in range(3, 20):
        for z in range(8, 22):
            t.set(x, 0, z, "orange_terracotta" if (x + z) % 2 else "smooth_sandstone")
    t.fill(9, 0, 13, 13, 0, 17, "blue_terracotta")
    t.fill(10, 0, 14, 12, 0, 16, "vigil:scarab_tile")
    t.chest(11, 1, 21, "desert_tomb", facing="north")
    for (x, z) in [(4, 9), (18, 9), (4, 20), (18, 20), (4, 15), (18, 15)]:
        t.fill(x, 1, z, x, 8, z, "chiseled_sandstone")
        t.set(x, 4, z, "lantern")
    for x in (9, 13):
        t.set(x, 1, 21, "skeleton_skull", rotation=8)
    return t


def frozen_bastion():
    t = Template(21, 14, 21)
    t.fill(0, 0, 0, 20, 0, 20, "packed_ice")
    # Walls with crenellations, an open courtyard (the arena)
    for x in range(21):
        for z in range(21):
            edge = x in (0, 20) or z in (0, 20)
            if edge:
                for y in range(1, 9):
                    t.set(x, y, z, "vigil:frost_bricks" if y % 3 else "blue_ice")
                if (x + z) % 2 == 0:
                    t.set(x, 9, z, "snow_block")
            else:
                for y in range(1, 13):
                    t.set(x, y, z, "air")
                t.set(x, 0, z, "snow_block" if (x * 7 + z * 3) % 5 else "blue_ice")
    for (x, z) in [(0, 0), (20, 0), (0, 20), (20, 20)]:
        for y in range(1, 13):
            for dx in (0, 1, -1):
                for dz in (0, 1, -1):
                    if 0 <= x + dx < 21 and 0 <= z + dz < 21:
                        t.set(x + dx, y, z + dz, "blue_ice" if y > 9 else "packed_ice")
    t.fill(9, 1, 0, 11, 4, 0, "air")
    for (x, z) in [(5, 5), (15, 5), (5, 15), (15, 15)]:
        for y in range(1, 6):
            t.set(x, y, z, "vigil:rune_ice" if y in (2, 4) else "blue_ice")
        t.set(x, 6, z, "sea_lantern")
    t.chest(10, 1, 19, "frozen_bastion", facing="north")
    t.set(10, 1, 18, "powder_snow")
    return t


def overgrown_labyrinth():
    r = random.Random(7)
    n = 7
    cell = 4
    size = n * cell + 1
    t = Template(size, 8, size)
    t.fill(0, 0, 0, size - 1, 0, size - 1, "mossy_cobblestone")
    walls = [[True] * size for _ in range(size)]
    seen = set()
    stack = [(0, 0)]
    seen.add((0, 0))

    def open_cell(cx, cz):
        for x in range(cx * cell + 1, cx * cell + cell):
            for z in range(cz * cell + 1, cz * cell + cell):
                walls[x][z] = False
    open_cell(0, 0)
    while stack:
        cx, cz = stack[-1]
        nbrs = [(cx + dx, cz + dz) for dx, dz in [(1, 0), (-1, 0), (0, 1), (0, -1)] if 0 <= cx + dx < n and 0 <= cz + dz < n and (cx + dx, cz + dz) not in seen]
        if not nbrs:
            stack.pop()
            continue
        nx, nz = r.choice(nbrs)
        seen.add((nx, nz))
        open_cell(nx, nz)
        # knock down the wall between
        if cx != nx:
            x = max(cx, nx) * cell
            for z in range(cz * cell + 1, cz * cell + cell):
                walls[x][z] = False
        else:
            z = max(cz, nz) * cell
            for x in range(cx * cell + 1, cx * cell + cell):
                walls[x][z] = False
        stack.append((nx, nz))
    # Central arena (3x3 cells) and the entrance
    for x in range(2 * cell + 1, 5 * cell):
        for z in range(2 * cell + 1, 5 * cell):
            walls[x][z] = False
    for z in range(1, cell):
        walls[0][z] = False
    for x in range(size):
        for z in range(size):
            if walls[x][z]:
                for y in range(1, 6):
                    t.set(x, y, z, r.choice(["vigil:mossy_ruin", "vigil:mossy_ruin", "mossy_stone_bricks", "cracked_stone_bricks"]))
                if r.random() < 0.3:
                    t.set(x, 6, z, "vigil:thorn_vines")
                else:
                    t.set(x, 6, z, "jungle_leaves", persistent=True, distance=1)
            else:
                for y in range(1, 7):
                    t.set(x, y, z, "air")
    c = size // 2
    t.fill(c - 2, 0, c - 2, c + 2, 0, c + 2, "chiseled_stone_bricks")
    t.chest(c, 1, c + 5, "overgrown_labyrinth", facing="north")
    # A second chest at a far corner of the maze
    t.chest(size - 3, 1, size - 3, "overgrown_labyrinth", facing="north")
    return t


def watchers_hollow():
    r = random.Random(6)
    t = Template(17, 12, 17)
    for y in range(0, 11):
        for x in range(17):
            for z in range(17):
                d = ((x - 8) ** 2 + (z - 8) ** 2) ** 0.5
                if y == 0 and d <= 8.2:
                    t.set(x, y, z, "sculk" if r.random() < 0.4 else "mud")
                elif 1 <= y <= 9:
                    if 6.6 < d <= 8.2:
                        t.set(x, y, z, "dark_oak_wood", axis="y")
                    elif d <= 6.6:
                        t.set(x, y, z, "air")
                elif y == 10 and d <= 8.0:
                    t.set(x, y, z, "dark_oak_leaves", persistent=True, distance=1)
    t.fill(8, 1, 0, 8, 2, 1, "air")
    for y in (1, 2, 3):
        t.set(8, y, 14, "vigil:sculk_bricks")
    t.set(8, 4, 14, "vigil:watcher_eye")
    for (x, z) in [(3, 3), (13, 3), (3, 13), (13, 13)]:
        t.set(x, 1, z, "vigil:sculk_bricks")
        t.set(x, 2, z, "vigil:watcher_eye")
    t.set(8, 5, 14, "wither_skeleton_skull", rotation=0)
    t.set(6, 1, 14, "soul_lantern", hanging=False)
    t.set(10, 1, 14, "soul_lantern", hanging=False)
    t.chest(8, 1, 12, "watchers_hollow", facing="north")
    for (x, z) in [(4, 5), (12, 5), (4, 11), (12, 11)]:
        t.set(x, 1, z, "sculk_sensor")
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


def build():
    for item_id in ITEMS:
        write(f"loot_table/items/{item_id}.json", item_loot(item_id))
    for item_id in RECIPES:
        write(f"recipe/{item_id}.json", recipe(item_id))
    for name, pools in CHESTS.items():
        write(f"loot_table/chests/{name}.json", {"type": "minecraft:chest", "pools": pools + [block_pool(name)]})
    # A broken (or blown up) custom block drops itself; every other note block drops a note block as usual.
    drops = [{"type": "minecraft:item", "name": "minecraft:note_block",
              "conditions": [{"condition": "minecraft:block_state_property", "block": "minecraft:note_block",
                              "properties": {"instrument": "custom_head", "note": str(b["note"]), "powered": "false"}}],
              "functions": [{"function": "minecraft:set_components", "components": block_components(name)}]}
             for name, b in BLOCKS.items()]
    drops.append({"type": "minecraft:item", "name": "minecraft:note_block"})
    path = os.path.join(os.path.dirname(DATA), "minecraft", "loot_table", "blocks", "note_block.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump({"type": "minecraft:block", "random_sequence": "minecraft:blocks/note_block", "pools": [{
            "rolls": 1, "bonus_rolls": 0, "conditions": [{"condition": "minecraft:survives_explosion"}],
            "entries": [{"type": "minecraft:alternatives", "children": drops}]}]}, f, indent=2)
        f.write("\n")
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
