package com.vylorq.anticheat.core.arena;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Arena kit: inventory, armor, effects and rules (section 22). Items are serialized stacks with slot numbers. */
public final class Kit {
    public String name;
    /** slot -> serialized stack (0-35 inventory, 36-39 armor, 40 offhand). */
    public Map<Integer, String> items = new LinkedHashMap<>();
    /** effect id -> amplifier (infinite during the match). */
    public Map<String, Integer> effects = new LinkedHashMap<>();
    public boolean naturalRegen = true;
    public boolean builtIn;
    public List<String> description = new ArrayList<>();

    /** Built-in presets. Their items are filled by the Minecraft layer because they need real item stacks. */
    public static final List<String> PRESETS = List.of("sword", "axe_shield", "mace", "archery", "netherite", "potion", "uhc");
}
