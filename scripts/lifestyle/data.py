"""Writes the recipes for feature/Lifestyle.java (Climbing Claws, Sleeping Bag, the new foods, furniture) into
src/main/resources/data/vigil/recipe.

    python3 scripts/lifestyle/data.py
"""
import hashlib
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RECIPES = os.path.join(ROOT, "src", "main", "resources", "data", "vigil", "recipe")


def code(n):
    return "x" + hashlib.sha256(("vigil-pack:" + n).encode()).hexdigest()[:10]


def look(name):
    return {"minecraft:item_model": "vigil:" + code(name), "minecraft:custom_model_data": {"strings": ["vigil:" + code(name)]}}


def crop(what, base):
    return {"fabric:type": "fabric:custom_data", "base": f"minecraft:{base}", "nbt": "{vigil_crop:\"%s\"}" % what}


def write(name, obj):
    os.makedirs(RECIPES, exist_ok=True)
    with open(os.path.join(RECIPES, name + ".json"), "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def shaped(pattern, key, result, category="misc"):
    return {"type": "minecraft:crafting_shaped", "category": category, "pattern": pattern, "key": key, "result": result}


def named(item, title, color, extra):
    c = {"minecraft:item_name": {"text": title, "color": color}}
    c.update(extra)
    return {"id": f"minecraft:{item}", "count": 1, "components": c}


def food(effect, seconds):
    return {"minecraft:consumable": {"on_consume_effects": [{"type": "minecraft:apply_effects", "effects": [
        {"id": f"minecraft:{effect}", "duration": seconds * 20, "amplifier": 0}]}]}}


def main():
    write("gear_climbing_claws", shaped(["F F", "FIF", " L "], {"F": "minecraft:flint", "I": "minecraft:iron_ingot", "L": "minecraft:leather"},
                                        named("iron_nugget", "Climbing Claws", "white", {"minecraft:custom_data": {"vigil_make": "gear:claws"},
                                                                                        **look("climbing_claws")}), "equipment"))
    write("gear_sleeping_bag", shaped(["WWW", "LLL"], {"W": "#minecraft:wool", "L": "minecraft:leather"},
                                      named("leather", "Sleeping Bag", "red", {"minecraft:custom_data": {"vigil_make": "gear:sleeping_bag"},
                                                                              **look("sleeping_bag")})))
    write("food_spicy_stew", {"type": "minecraft:crafting_shapeless", "category": "misc",
                              "ingredients": ["minecraft:bowl", crop("pepper", "beetroot"), crop("rice", "wheat"), "minecraft:cooked_beef"],
                              "result": named("rabbit_stew", "Spicy Stew", "#ff6a2a", {**food("fire_resistance", 60), **look("spicy_stew")})})
    write("food_rice_bowl", {"type": "minecraft:crafting_shapeless", "category": "misc",
                             "ingredients": ["minecraft:bowl", crop("rice", "wheat"), crop("rice", "wheat")],
                             "result": named("mushroom_stew", "Rice Bowl", "#f4eee0", {**food("regeneration", 5), **look("rice_bowl")})})
    write("decor_table", shaped(["P", "F"], {"P": "minecraft:oak_pressure_plate", "F": "minecraft:oak_fence"},
                                named("oak_fence", "Table", "#d6b07a", {"minecraft:custom_data": {"vigil_decor": "table"}})))
    write("decor_lamp", shaped(["L", "F"], {"L": "minecraft:lantern", "F": "minecraft:oak_fence"},
                               named("oak_fence", "Lamp", "#ffd24a", {"minecraft:custom_data": {"vigil_decor": "lamp"}})))
    write("decor_chair", shaped(["P  ", "PPP", "F F"], {"P": "minecraft:oak_planks", "F": "minecraft:oak_fence"},
                                {"id": "minecraft:oak_stairs", "count": 2, "components": {"minecraft:item_name": {"text": "Chair", "color": "#d6b07a"}}}))
    print("recipes 7")


if __name__ == "__main__":
    main()
