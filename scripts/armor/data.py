"""Writes the recipes (armour sets, gems, Ruby tools, Bloodstone) and the ore world generation for
feature/ArmorSets.java and feature/Gems.java into src/main/resources/data/vigil.

    python3 scripts/armor/data.py
"""
import hashlib
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "vigil")
GEMS = {"ruby": ("Ruby", "#ff5566", 1), "sapphire": ("Sapphire", "#5588ff", 2), "topaz": ("Topaz", "#ffaa22", 3),
        "voidstone": ("Voidstone", "#aa55ff", 4), "bloodstone": ("Bloodstone", "#bb1111", 5),
        "emberite": ("Emberite", "#ff7a1a", 6), "frostshard": ("Frostshard", "#7fd4ff", 7),
        "starmetal": ("Starmetal", "#b9c0ff", 8), "soulstone": ("Soulstone", "#3fd6c8", 9)}
USE = {"ruby": "Makes Ruby armor and tools.", "sapphire": "Makes Sapphire armor.", "topaz": "Burns 4x longer than coal.",
       "voidstone": "Use it with netherite gear in your other hand.", "bloodstone": "Makes the Lantern of Dawn and Boiled Armor.",
       "emberite": "Makes Ember Armor.", "frostshard": "Makes Tidecrown Armor.",
       "starmetal": "Makes Golem Plate. Only found where meteors fell.", "soulstone": "Makes Shadowstep Armor."}
PIECES = ["helmet", "chestplate", "leggings", "boots"]
SHAPES = {"helmet": ["XXX", "X X"], "chestplate": ["X X", "XXX", "XXX"], "leggings": ["XXX", "X X", "X X"], "boots": ["X X", "X X"]}
TOOLS = {"sword": ["X", "X", "S"], "pickaxe": ["XXX", " S ", " S "], "axe": ["XX", "XS", " S"], "shovel": ["X", "S", "S"],
         "hoe": ["XX", " S", " S"]}


def code(n):
    return "x" + hashlib.sha256(("vigil-pack:" + n).encode()).hexdigest()[:10]


def look(name):
    return {"minecraft:item_model": "vigil:" + code(name), "minecraft:custom_model_data": {"strings": ["vigil:" + code(name)]}}


def gem_ingredient(g, kind="vigil_gem"):
    return {"fabric:type": "fabric:custom_data", "base": "minecraft:disc_fragment_5", "nbt": "{%s:\"%s\"}" % (kind, g)}


def made(item, what, title, color, model):
    c = {"minecraft:custom_data": {"vigil_make": what}, "minecraft:item_name": {"text": title, "color": color}}
    c.update(look(model))
    return {"id": item, "count": 1, "components": c}


def write(path, obj):
    full = os.path.join(DATA, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def main():
    # old generated files out first
    rdir = os.path.join(DATA, "recipe")
    for f in os.listdir(rdir):
        if f.startswith(("gem_", "armor_", "ruby_", "bloodstone_", "sapphire_", "topaz_", "gemset_", "block_")):
            os.remove(os.path.join(rdir, f))
    n = 0
    # Raw chunk -> gem (furnace and blast furnace)
    for g, (title, color, _) in GEMS.items():
        result = {"id": "minecraft:disc_fragment_5", "count": 1, "components": {
            "minecraft:custom_data": {"vigil_gem": g}, "minecraft:custom_name": {"text": title, "color": color, "italic": False},
            "minecraft:lore": [{"text": USE[g], "color": "gray", "italic": False}], "minecraft:rarity": "rare", **look("gem_" + g)}}
        for kind, t in (("smelting", 200), ("blasting", 100)):
            write(f"recipe/gem_{g}_{kind}.json", {"type": "minecraft:" + kind, "category": "misc",
                                                 "ingredient": gem_ingredient(g, "vigil_raw"), "result": result, "experience": 1.0,
                                                 "cookingtime": t})
            n += 1
    # Ruby and Sapphire armour; Ruby tools
    for g in ("ruby", "sapphire"):
        for i, p in enumerate(PIECES):
            write(f"recipe/armor_{g}_{p}.json", {"type": "minecraft:crafting_shaped", "category": "equipment", "pattern": SHAPES[p],
                                                "key": {"X": gem_ingredient(g)},
                                                "result": made(f"minecraft:diamond_{p}", f"armor:{g}:{i}", f"{GEMS[g][0]} {p.capitalize()}",
                                                               GEMS[g][1], f"armor_{g}_{p}")})
            n += 1
    for t, pattern in TOOLS.items():
        write(f"recipe/ruby_{t}.json", {"type": "minecraft:crafting_shaped", "category": "equipment", "pattern": pattern,
                                       "key": {"X": gem_ingredient("ruby"), "S": "minecraft:stick"},
                                       "result": made(f"minecraft:diamond_{t}", f"ruby_tool:{t}", f"Ruby {t.capitalize()}", GEMS["ruby"][1],
                                                      f"ruby_{t}")})
        n += 1
    # Sapphire tools mine 3x3; Topaz tools smelt what they dig (Gems.gemTool).
    for g in ("sapphire", "topaz"):
        for t in ("pickaxe", "shovel"):
            write(f"recipe/{g}_{t}.json", {"type": "minecraft:crafting_shaped", "category": "equipment", "pattern": TOOLS[t],
                                         "key": {"X": gem_ingredient(g), "S": "minecraft:stick"},
                                         "result": made(f"minecraft:diamond_{t}", f"gem_tool:{g}:{t}", f"{GEMS[g][0]} {t.capitalize()}",
                                                        GEMS[g][1], f"{g}_{t}")})
            n += 1
    # Crafted sets: the armour piece in the middle of eight of the material.
    crafted = {"emerald": ("minecraft:emerald", "iron", "iron", "Emerald", "#55ff77"),
               "obsidian": ("minecraft:obsidian", "diamond", "diamond", "Obsidian", "#aa66ff"),
               "phantom": ("minecraft:phantom_membrane", "iron", "chainmail", "Phantom", "#aaddee"),
               "tide": ("minecraft:prismarine_shard", "iron", "iron", "Tide", "#33ccbb")}
    for s, (mat, base, out, title, color) in crafted.items():
        for i, p in enumerate(PIECES):
            write(f"recipe/armor_{s}_{p}.json", {"type": "minecraft:crafting_shaped", "category": "equipment", "pattern": ["MMM", "MAM", "MMM"],
                                                "key": {"M": mat, "A": f"minecraft:{base}_{p}"},
                                                "result": made(f"minecraft:{out}_{p}", f"armor:{s}:{i}", f"{title} {p.capitalize()}", color,
                                                               f"armor_{s}_{p}")})
            n += 1
    # The new gems' sets: the armour piece in the middle of eight of the gem (Phantom Silk: of phantom membrane).
    gemsets = {"ember": ("emberite", "diamond", "netherite", "Ember", "#ff8a2a"),
               "tidecrown": ("frostshard", "diamond", "diamond", "Tidecrown", "#4fd6d0"),
               "golem": ("starmetal", "netherite", "netherite", "Golem", "#d8d2c2"),
               "shadowstep": ("soulstone", "diamond", "netherite", "Shadowstep", "#9a6aff")}
    for s, (g, base, out, title, color) in gemsets.items():
        for i, p in enumerate(PIECES):
            write(f"recipe/gemset_{s}_{p}.json", {"type": "minecraft:crafting_shaped", "category": "equipment", "pattern": ["MMM", "MAM", "MMM"],
                                                 "key": {"M": gem_ingredient(g), "A": f"minecraft:{base}_{p}"},
                                                 "result": made(f"minecraft:{out}_{p}", f"armor:{s}:{i}", f"{title} {p.capitalize()}", color,
                                                                f"armor_{s}_{p}")})
            n += 1
    for i, p in enumerate(PIECES):
        write(f"recipe/gemset_silk_{p}.json", {"type": "minecraft:crafting_shaped", "category": "equipment", "pattern": ["MMM", "MAM", "MMM"],
                                              "key": {"M": "minecraft:phantom_membrane", "A": f"minecraft:diamond_{p}"},
                                              "result": made(f"minecraft:chainmail_{p}", f"armor:silk:{i}", f"Phantom Silk {p.capitalize()}",
                                                             "#cfc8ee", f"armor_silk_{p}")})
        n += 1
    # Special blocks (SpecialBlocks.java): the Elevator, the Night Lamp and the Trophy Pedestal.
    blocks = {"elevator": (["WWW", "WEW", "WWW"], {"W": "#minecraft:wool", "E": "minecraft:ender_pearl"}, "minecraft:chiseled_tuff_bricks",
                           "Elevator", "aqua", 2),
              "night_lamp": ([" G ", "GLG", " D "], {"G": "minecraft:glowstone_dust", "L": "minecraft:redstone_lamp",
                                                     "D": "minecraft:daylight_detector"}, "minecraft:redstone_lamp", "Night Lamp", "yellow", 1),
              "pedestal": (["QGQ", " Q ", "QQQ"], {"Q": "minecraft:quartz_block", "G": "minecraft:gold_ingot"},
                           "minecraft:chiseled_quartz_block", "Trophy Pedestal", "gold", 1),
              # Machines (Machines.java)
              "miner": (["IPI", "RBR", "III"], {"I": "minecraft:iron_ingot", "P": "minecraft:diamond_pickaxe", "R": "minecraft:redstone",
                                                "B": "minecraft:blast_furnace"}, "minecraft:blast_furnace", "Auto-Miner", "gold", 1),
              "crusher": (["IAI", "IBI", "III"], {"I": "minecraft:iron_ingot", "A": "minecraft:anvil", "B": "minecraft:barrel"},
                          "minecraft:barrel", "Ore Crusher", "gray", 1),
              "farm": ([" H ", "WCW", "BBB"], {"H": "minecraft:diamond_hoe", "W": "minecraft:water_bucket", "C": "minecraft:composter",
                                               "B": "minecraft:bone_block"}, "minecraft:composter", "Auto-Farm", "green", 1),
              "grinder": (["SSS", "SGS", "IRI"], {"S": "minecraft:iron_sword", "G": "minecraft:grindstone", "I": "minecraft:iron_block",
                                                  "R": "minecraft:redstone_block"}, "minecraft:grindstone", "Mob Grinder", "red", 1),
              "pad": (["EEE", "EOE", "EEE"], {"E": "minecraft:ender_pearl", "O": "minecraft:crying_obsidian"},
                      "minecraft:lodestone", "Teleport Pad", "light_purple", 1),
              "sorter": (["CHC", "HOH", "CHC"], {"C": "minecraft:copper_ingot", "H": "minecraft:hopper", "O": "minecraft:observer"},
                         "minecraft:chiseled_copper", "Item Sorter", "dark_aqua", 1)}
    for b, (pattern, key, item, title, color, count) in blocks.items():
        write(f"recipe/block_{b}.json", {"type": "minecraft:crafting_shaped", "category": "building", "pattern": pattern, "key": key,
                                         "result": {"id": item, "count": count, "components": {
                                             "minecraft:custom_data": {"vigil_make": f"block:{b}"},
                                             "minecraft:item_name": {"text": title, "color": color}}}})
        n += 1
    # Bloodstone: the Lantern of Dawn, and Boiled Armor
    write("recipe/bloodstone_lantern.json", {"type": "minecraft:crafting_shapeless", "category": "misc",
                                             "ingredients": ["minecraft:lantern"] + [gem_ingredient("bloodstone")] * 4,
                                             "result": {"id": "minecraft:lantern", "count": 1, "components": {
                                                 "minecraft:custom_data": {"vigil_make": "dawn_lantern"},
                                                 "minecraft:item_name": {"text": "Lantern of Dawn", "color": "gold"}}}})
    n += 1
    for i, p in enumerate(PIECES):
        write(f"recipe/bloodstone_boiled_{p}.json", {"type": "minecraft:crafting_shaped", "category": "equipment", "pattern": ["MMM", "MAM", "MMM"],
                                                    "key": {"M": gem_ingredient("bloodstone"), "A": f"minecraft:netherite_{p}"},
                                                    "result": {"id": f"minecraft:netherite_{p}", "count": 1, "components": {
                                                        "minecraft:custom_data": {"vigil_make": f"boiled:{i}"},
                                                        "minecraft:item_name": {"text": "Boiled " + p.capitalize(), "color": "dark_red"}}}})
        n += 1
    # Ore world generation (new chunks only)
    ores = {"ruby": ("deepslate", 4, 4, -64, 0, "trapezoid"), "sapphire": ("stone", 4, 3, -16, 48, "uniform"),
            "topaz": ("stone", 5, 6, 0, 128, "uniform"), "voidstone": ("deepslate", 2, 1, -64, -40, "uniform"),
            "bloodstone": ("stone", 3, 2, -16, 64, "uniform"),
            # Emberite: in the Nether's netherrack, near lava. Frostshard: in snowy lands (Gems.java picks the biomes).
            # Soulstone: deep in the Deep Dark. Starmetal never grows: meteors bring it (Meteors.java).
            "emberite": ("netherrack", 5, 7, 10, 110, "uniform"), "frostshard": ("stone", 4, 5, 0, 120, "uniform"),
            "soulstone": ("deepslate", 3, 6, -64, -20, "uniform")}
    for g, (rock, size, count, lo, hi, shape) in ores.items():
        state = {"Name": "minecraft:note_block", "Properties": {"instrument": "dragon", "note": str(GEMS[g][2]), "powered": "false"}}
        target = ({"predicate_type": "minecraft:block_match", "block": "minecraft:netherrack"} if rock == "netherrack"
                  else {"predicate_type": "minecraft:tag_match", "tag": f"minecraft:{rock}_ore_replaceables"})
        write(f"worldgen/configured_feature/ore_{g}.json", {"type": "minecraft:ore", "config": {
            "size": size, "discard_chance_on_air_exposure": 0.0, "targets": [{"target": target, "state": state}]}})
        write(f"worldgen/placed_feature/ore_{g}.json", {"feature": f"vigil:ore_{g}", "placement": [
            {"type": "minecraft:count", "count": count}, {"type": "minecraft:in_square"},
            {"type": "minecraft:height_range", "height": {"type": "minecraft:" + shape, "min_inclusive": {"absolute": lo},
                                                           "max_inclusive": {"absolute": hi}}},
            {"type": "minecraft:biome"}]})
    print("recipes", n, "ores", len(ores))


if __name__ == "__main__":
    main()
