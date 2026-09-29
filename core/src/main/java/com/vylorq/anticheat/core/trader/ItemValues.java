package com.vylorq.anticheat.core.trader;

import com.vylorq.anticheat.core.item.ItemInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Item value system (section 23.4): raw materials have base values (config); crafted items are valued from
 * their cheapest recipe. Enchantments, damage and potions adjust the value.
 */
public final class ItemValues {
    /** A crafting/smelting recipe: ingredients (item id -> count) make {@code output} items. */
    public record Recipe(Map<String, Integer> ingredients, int output) {
    }

    public static final double UNKNOWN = 5.0;
    private static final double CRAFT_PREMIUM = 1.05;

    private final Map<String, Double> base;
    private final Map<String, List<Recipe>> recipes;
    private final Map<String, Double> memo = new HashMap<>();

    public ItemValues(Map<String, Double> base, Map<String, List<Recipe>> recipes) {
        this.base = base;
        this.recipes = recipes;
    }

    public synchronized double value(String id) {
        return value(id, new HashSet<>());
    }

    private double value(String id, Set<String> visiting) {
        Double m = memo.get(id);
        if (m != null) {
            return m;
        }
        Double b = base.get(id);
        if (b != null) {
            memo.put(id, b);
            return b;
        }
        if (!visiting.add(id)) {
            // Recipe cycle (e.g. iron block <-> ingots): no value along this path.
            return Double.NaN;
        }
        double best = Double.NaN;
        for (Recipe r : recipes.getOrDefault(id, List.of())) {
            double sum = 0;
            boolean ok = true;
            for (Map.Entry<String, Integer> e : r.ingredients().entrySet()) {
                double v = value(e.getKey(), visiting);
                if (Double.isNaN(v)) {
                    ok = false;
                    break;
                }
                sum += v * e.getValue();
            }
            if (ok && r.output() > 0) {
                double v = sum / r.output() * CRAFT_PREMIUM;
                if (Double.isNaN(best) || v < best) {
                    best = v;
                }
            }
        }
        visiting.remove(id);
        if (Double.isNaN(best)) {
            if (visiting.isEmpty()) {
                memo.put(id, UNKNOWN);
                return UNKNOWN;
            }
            return Double.NaN;
        }
        if (visiting.isEmpty()) {
            memo.put(id, best);
        }
        return best;
    }

    public static double enchantValue(String id, int level) {
        double base = Enchants.TOP.contains(id) ? 35 : 22;
        if (id.equals("minecraft:mending")) {
            base = 320;
        } else if (Enchants.TREASURE.contains(id)) {
            base *= 2;
        }
        if (Enchants.CURSES.contains(id)) {
            return -20;
        }
        return base * Math.pow(level, 1.6);
    }

    /** Value of one unit of a stack (count ignored). */
    public double unitValue(ItemInfo item) {
        double v = value(item.id);
        for (Map.Entry<String, Integer> e : item.enchantments.entrySet()) {
            v += enchantValue(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Integer> e : item.storedEnchantments.entrySet()) {
            v += enchantValue(e.getKey(), e.getValue());
        }
        if (item.maxDamage > 0 && item.damage > 0) {
            double left = 1.0 - item.damage / (double) item.maxDamage;
            v *= Math.max(0.05, left);
        }
        return Math.max(0, v);
    }

    /**
     * Loads the bundled base values and recipe table, then applies config overrides.
     */
    public static ItemValues loadDefaults(Map<String, Double> overrides) {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        Map<String, Double> base = new HashMap<>();
        Map<String, List<Recipe>> recipes = new HashMap<>();
        try (java.io.InputStream in = ItemValues.class.getResourceAsStream("/anticheat/trader_values.json")) {
            if (in != null) {
                Map<String, Double> m = gson.fromJson(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
                        new com.google.gson.reflect.TypeToken<Map<String, Double>>() { }.getType());
                base.putAll(m);
            }
        } catch (java.io.IOException ignored) {
            // no bundled values
        }
        try (java.io.InputStream in = ItemValues.class.getResourceAsStream("/anticheat/trader_recipes.json")) {
            if (in != null) {
                com.google.gson.JsonObject o = gson.fromJson(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
                        com.google.gson.JsonObject.class);
                for (String id : o.keySet()) {
                    List<Recipe> list = new java.util.ArrayList<>();
                    for (com.google.gson.JsonElement e : o.getAsJsonArray(id)) {
                        com.google.gson.JsonObject r = e.getAsJsonObject();
                        Map<String, Integer> ing = new HashMap<>();
                        for (String k : r.getAsJsonObject("in").keySet()) {
                            ing.put(k, r.getAsJsonObject("in").get(k).getAsInt());
                        }
                        list.add(new Recipe(ing, r.get("out").getAsInt()));
                    }
                    recipes.put(id, list);
                }
            }
        } catch (java.io.IOException ignored) {
            // no bundled recipes
        }
        if (overrides != null) {
            base.putAll(overrides);
        }
        return new ItemValues(base, recipes);
    }

    public synchronized void clearCache() {
        memo.clear();
    }
}
