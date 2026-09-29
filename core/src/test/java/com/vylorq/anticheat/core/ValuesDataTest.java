package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.trader.ItemValues;
import com.vylorq.anticheat.core.trader.Specialty;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValuesDataTest {
    @Test
    void bundledDataValuesEveryPoolItem() {
        ItemValues v = ItemValues.loadDefaults(Map.of());
        assertTrue(v.value("minecraft:diamond_sword") > 100);
        assertTrue(v.value("minecraft:diamond_chestplate") > v.value("minecraft:iron_chestplate"));
        assertTrue(v.value("minecraft:iron_block") > 70);
        assertTrue(v.value("minecraft:stone_bricks") < 1);
        int unknown = 0;
        StringBuilder missing = new StringBuilder();
        for (Specialty s : Specialty.values()) {
            for (Specialty.Entry e : s.pool()) {
                if (v.value(e.id()) == ItemValues.UNKNOWN && !e.id().equals("minecraft:potion") && !e.id().equals("minecraft:tipped_arrow")) {
                    unknown++;
                    missing.append(e.id()).append(' ');
                }
            }
        }
        assertEquals(0, unknown, "no value for: " + missing);
    }

    @Test
    void overridesWin() {
        ItemValues v = ItemValues.loadDefaults(Map.of("minecraft:diamond", 100.0));
        assertEquals(100.0, v.value("minecraft:diamond"));
    }
}
