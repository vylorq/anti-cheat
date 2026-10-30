"""Writes the new biomes and underground features into src/main/resources/data/vigil (Worldgen.java places them).

    python3 scripts/worldgen/biomes.py <folder with the game's own biome JSONs (forest.json, plains.json, ...)>

Each new biome starts from one of the game's (its trees, grass, mobs, caves) and gets its own colours and extras;
Worldgen.java turns patches of that game biome into the new one.
"""
import copy
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "vigil")

# The underground features every overworld cave gets (and the new biomes list themselves).
CAVE = ["vigil:cave_lights", "vigil:cave_spore_blossoms", "vigil:cave_moss", "vigil:cave_vines", "vigil:cave_lanterns"]
# The mod's ores and sights, which the game only adds to its own biomes by itself.
OVERWORLD_ORES = ["vigil:ore_ruby", "vigil:ore_sapphire", "vigil:ore_voidstone", "vigil:ore_bloodstone"]

BIOMES = {
    # name: (game biome it starts from, colours, extra surface features, extra ores)
    "autumn_forest": ("forest", {"foliage_color": "#e0782a", "grass_color": "#b8a24a", "dry_foliage_color": "#a0522d"},
                      {"minecraft:visual/sky_color": "#8fb0ff"}, ["vigil:autumn_pumpkins"], []),
    "lavender_fields": ("plains", {"foliage_color": "#9a7fd0", "grass_color": "#a993d6"},
                        {"minecraft:visual/sky_color": "#a6b6ff"}, ["vigil:lavender_flowers"], []),
    "frost_pines": ("taiga", {"foliage_color": "#5f9fc8", "grass_color": "#7fb0b8", "water_color": "#3d6ee0"},
                    {"minecraft:visual/sky_color": "#9ec8ff"}, [], ["vigil:ore_frostshard"]),
    "glowshroom_marsh": ("swamp", {"foliage_color": "#3a8f7a", "grass_color": "#4a9e8a", "water_color": "#2fd6c6"},
                         {"minecraft:visual/sky_color": "#7fb8c8", "minecraft:visual/water_fog_color": "#1d6f6a"},
                         ["vigil:glow_grove_red_mushroom", "vigil:glow_grove_brown_mushroom", "vigil:glow_grove_lichen"], []),
}


def write(rel, obj):
    path = os.path.join(DATA, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def ceiling(count, lo=-56, hi=40):
    return [{"type": "minecraft:count", "count": count}, {"type": "minecraft:in_square"},
            {"type": "minecraft:height_range", "height": {"type": "minecraft:uniform", "min_inclusive": {"absolute": lo},
                                                          "max_inclusive": {"absolute": hi}}},
            {"type": "minecraft:environment_scan", "allowed_search_condition": {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"},
             "direction_of_search": "up", "max_steps": 12, "target_condition": {"type": "minecraft:solid"}},
            {"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": -1}, {"type": "minecraft:biome"}]


def floor(count, lo=-56, hi=40):
    return [{"type": "minecraft:count", "count": count}, {"type": "minecraft:in_square"},
            {"type": "minecraft:height_range", "height": {"type": "minecraft:uniform", "min_inclusive": {"absolute": lo},
                                                          "max_inclusive": {"absolute": hi}}},
            {"type": "minecraft:environment_scan", "allowed_search_condition": {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"},
             "direction_of_search": "down", "max_steps": 12, "target_condition": {"type": "minecraft:solid"}},
            {"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": 1}, {"type": "minecraft:biome"}]


def caves():
    # Glowing lights hanging from cave roofs: shroomlight and the three froglights.
    lights = {"weight": 1}
    write("worldgen/configured_feature/cave_lights.json", {"type": "minecraft:simple_block", "config": {"to_place": {
        "type": "minecraft:weighted_state_provider", "entries": [
            {"weight": 3, "data": {"Name": "minecraft:shroomlight"}},
            {"weight": 1, "data": {"Name": "minecraft:ochre_froglight", "Properties": {"axis": "y"}}},
            {"weight": 1, "data": {"Name": "minecraft:pearlescent_froglight", "Properties": {"axis": "y"}}},
            {"weight": 1, "data": {"Name": "minecraft:verdant_froglight", "Properties": {"axis": "y"}}}]}}})
    write("worldgen/placed_feature/cave_lights.json", {"feature": "vigil:cave_lights", "placement": ceiling(3)})
    # Old miners' lanterns: chains hanging from the roof with a lantern at the end.
    write("worldgen/configured_feature/cave_lanterns.json", {"type": "minecraft:block_column", "config": {
        "direction": "down", "allowed_placement": {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"},
        "prioritize_tip": True, "layers": [
            {"height": {"type": "minecraft:uniform", "min_inclusive": 1, "max_inclusive": 4},
             "provider": {"type": "minecraft:simple_state_provider", "state": {"Name": "minecraft:chain", "Properties": {"axis": "y", "waterlogged": "false"}}}},
            {"height": 1, "provider": {"type": "minecraft:simple_state_provider",
                                       "state": {"Name": "minecraft:lantern", "Properties": {"hanging": "true", "waterlogged": "false"}}}}]}})
    write("worldgen/placed_feature/cave_lanterns.json", {"feature": "vigil:cave_lanterns", "placement": ceiling(2, -40, 50)})
    # Lush touches in every cave: spore blossoms, moss carpets and glow berry vines.
    write("worldgen/placed_feature/cave_spore_blossoms.json", {"feature": "minecraft:spore_blossom", "placement": ceiling(3)})
    write("worldgen/placed_feature/cave_moss.json", {"feature": "minecraft:moss_patch", "placement": floor(4)})
    write("worldgen/placed_feature/cave_vines.json", {"feature": "minecraft:cave_vine", "placement": ceiling(12)})
    # The new biomes' own extras.
    write("worldgen/placed_feature/autumn_pumpkins.json", {"feature": "minecraft:patch_pumpkin", "placement": [
        {"type": "minecraft:rarity_filter", "chance": 6}, {"type": "minecraft:in_square"},
        {"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING"}, {"type": "minecraft:biome"}]})
    write("worldgen/placed_feature/lavender_flowers.json", {"feature": "minecraft:flower_meadow", "placement": [
        {"type": "minecraft:count", "count": 6}, {"type": "minecraft:in_square"},
        {"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING"}, {"type": "minecraft:biome"}]})


def main(src):
    caves()
    for name, (base, effects, attributes, extra, ores) in BIOMES.items():
        with open(os.path.join(src, base + ".json")) as f:
            b = json.load(f)
        b = copy.deepcopy(b)
        b["effects"].update(effects)
        b.setdefault("attributes", {}).update(attributes)
        feats = b["features"]
        while len(feats) < 11:
            feats.append([])
        # (Each list keeps the order the mod adds them to the game's biomes in, or the game refuses to start.)
        feats[2] += ["vigil:crystal_cave"]
        feats[6] += OVERWORLD_ORES + ores
        feats[7] += CAVE
        feats[9] += extra
        write(f"worldgen/biome/{name}.json", b)
    # The Nether's new biome: Ember Wastes, from the basalt deltas, glowing orange, rich in Emberite.
    with open(os.path.join(src, "basalt_deltas.json")) as f:
        b = json.load(f)
    b["attributes"]["minecraft:visual/fog_color"] = "#8a3a12"
    b["attributes"]["minecraft:visual/ambient_particles"] = [{"particle": {"type": "minecraft:small_flame"}, "probability": 0.004},
                                                             {"particle": {"type": "minecraft:white_ash"}, "probability": 0.05}]
    # (The plain Emberite ore is added to every Nether biome by Gems.java, this one too; the rich vein is its own.)
    b["features"][6] += ["vigil:ore_emberite_rich"]
    write("worldgen/biome/ember_wastes.json", b)
    with open(os.path.join(DATA, "worldgen", "placed_feature", "ore_emberite.json")) as f:
        rich = json.load(f)
    for p in rich["placement"]:
        if p["type"] == "minecraft:count":
            p["count"] = p["count"] * 2
    write("worldgen/placed_feature/ore_emberite_rich.json", rich)
    # The new biomes count as overworld (and forest/plains/taiga...) for structures and mobs.
    tags = {"is_overworld": list(BIOMES), "is_forest": ["autumn_forest"], "is_taiga": ["frost_pines"],
            "is_nether": ["ember_wastes"]}
    for tag, names in tags.items():
        write(f"../minecraft/tags/worldgen/biome/{tag}.json", {"replace": False, "values": [f"vigil:{n}" for n in names]})
    print("biomes", len(BIOMES) + 1)


if __name__ == "__main__":
    main(sys.argv[1])
