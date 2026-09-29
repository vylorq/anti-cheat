package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.chat.ChatFilter;
import com.vylorq.anticheat.core.item.ItemInfo;
import com.vylorq.anticheat.core.items.DupeWatch;
import com.vylorq.anticheat.core.items.IllegalItems;
import com.vylorq.anticheat.core.joins.JoinGuard;
import com.vylorq.anticheat.core.xray.MiningAnalyzer;
import com.vylorq.anticheat.core.xray.OreAlerts;
import com.vylorq.anticheat.core.xray.XrayTrap;
import com.vylorq.anticheat.core.util.BlockPos3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OtherDetectionTest {
    final UUID p = UUID.randomUUID();

    @Test
    void xrayTrapIsDeterministic() {
        XrayTrap t = new XrayTrap(42);
        List<BlockPos3> a = t.veinsFor("overworld", 3, -7, 1, -58, 16);
        List<BlockPos3> b = t.veinsFor("overworld", 3, -7, 1, -58, 16);
        assertEquals(a, b);
        assertFalse(a.isEmpty());
        assertTrue(t.isTrap("overworld", a.get(0), 1, -58, 16));
        assertNotEquals(a, new XrayTrap(43).veinsFor("overworld", 3, -7, 1, -58, 16), "secret matters");
        for (BlockPos3 pos : a) {
            assertTrue(pos.y() >= -58 && pos.y() < 22);
        }
    }

    @Test
    void miningRatio() {
        MiningAnalyzer legit = new MiningAnalyzer();
        double max = 0;
        for (int i = 0; i < 400; i++) {
            max = Math.max(max, legit.onBreak(i % 150 == 0 ? "minecraft:diamond_ore" : "minecraft:stone", i % 150 == 0, true, i % 150 != 0, 4.0));
        }
        assertEquals(0, max, 1e-9);
        MiningAnalyzer xray = new MiningAnalyzer();
        max = 0;
        for (int i = 0; i < 200; i++) {
            boolean ore = i % 8 == 0;
            max = Math.max(max, xray.onBreak(ore ? "minecraft:diamond_ore" : "minecraft:stone", ore, false, !ore, 4.0));
        }
        assertTrue(max >= 0.5);
    }

    @Test
    void oreAlertsGrouped() {
        OreAlerts a = new OreAlerts();
        for (int i = 0; i < 8; i++) a.onMine(p, "Steve", "diamond_ore", 1000 + i * 100);
        assertTrue(a.flush(5000, 30_000).isEmpty());
        List<OreAlerts.Alert> out = a.flush(40_000, 30_000);
        assertEquals(1, out.size());
        assertEquals(8, out.get(0).count());
    }

    @Test
    void illegalItems() {
        Map<String, Integer> max = Map.of("minecraft:sharpness", 5, "minecraft:mending", 1);
        List<String> banned = List.of("minecraft:barrier", "minecraft:bedrock");
        ItemInfo sword = new ItemInfo("minecraft:diamond_sword", 1);
        sword.maxCount = 1;
        sword.enchantments.put("minecraft:sharpness", 5);
        assertNull(IllegalItems.check(sword, banned, max));
        sword.enchantments.put("minecraft:sharpness", 50);
        assertNotNull(IllegalItems.check(sword, banned, max));
        assertNotNull(IllegalItems.check(new ItemInfo("minecraft:barrier", 1), banned, max));
        ItemInfo stack = new ItemInfo("minecraft:ender_pearl", 64);
        stack.maxCount = 16;
        assertNotNull(IllegalItems.check(stack, banned, max));
        ItemInfo shulker = new ItemInfo("minecraft:shulker_box", 1);
        shulker.contents = List.of(new ItemInfo("minecraft:bedrock", 1));
        assertTrue(IllegalItems.check(shulker, banned, max).startsWith("inside"));
    }

    @Test
    void dupeWatch() {
        DupeWatch d = new DupeWatch();
        Map<String, Integer> w = Map.of("minecraft:diamond", 1);
        int base = DupeWatch.value(List.of(new ItemInfo("minecraft:diamond", 10)), w);
        assertEquals(0, d.sample(p, base, 0, 60_000, 40));
        assertEquals(0, d.sample(p, base + 20, 10_000, 60_000, 40));
        assertTrue(d.sample(p, base + 128, 20_000, 60_000, 40) > 40);
        // Legit source covers the gain
        UUID q = UUID.randomUUID();
        d.sample(q, 0, 0, 60_000, 40);
        d.legitGain(q, 128, 5_000);
        assertEquals(0, d.sample(q, 128, 6_000, 60_000, 40));
    }

    @Test
    void chatFilter() {
        ChatFilter f = new ChatFilter();
        ChatFilter.Settings s = new ChatFilter.Settings(6, 600, 2, 256, 0.7, true, List.of("youtube.com"));
        long t = 0;
        assertEquals(ChatFilter.Verdict.OK, f.check(p, "hello there", t += 1000, s));
        assertEquals(ChatFilter.Verdict.TOO_FAST, f.check(p, "hi", t += 100, s));
        assertEquals(ChatFilter.Verdict.OK, f.check(p, "hi", t += 1000, s));
        assertEquals(ChatFilter.Verdict.OK, f.check(p, "HI!", t += 1000, s));
        assertEquals(ChatFilter.Verdict.REPEATED, f.check(p, "hi", t += 1000, s));
        assertEquals(ChatFilter.Verdict.ADVERTISING, f.check(p, "join play.coolserver.net now", t += 1000, s));
        assertEquals(ChatFilter.Verdict.ADVERTISING, f.check(p, "join 123.45.67.89:25565", t += 1000, s));
        assertEquals(ChatFilter.Verdict.ADVERTISING, f.check(p, "coolserver dot net? no: coolserver(dot)net", t += 1000, s));
        assertEquals(ChatFilter.Verdict.OK, f.check(p, "watch https://youtube.com/watch?v=x", t += 1000, s));
        assertEquals(ChatFilter.Verdict.FLOOD, f.check(p, "aaaaaaaaaaaaaaaaaaaa", t += 1000, s));
        assertEquals(ChatFilter.Verdict.CAPS, f.check(p, "WHY IS EVERYONE SHOUTING", t += 1000, s));
        assertEquals(ChatFilter.Verdict.OK, f.check(p, "ok. fine. i'll stop", t += 1000, s));
    }

    @Test
    void joinGuardAndAlts() {
        JoinGuard g = new JoinGuard(null);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        g.recordJoin(a, "Steve", "1.2.3.4");
        g.recordJoin(b, ".Alex Bedrock", "1.2.3.4");
        assertEquals(List.of(b), g.alts(a));
        assertEquals(b, g.findByName("alex bedrock"), "bedrock prefix + spaces");
        assertEquals(b, g.findByName(".Alex Bedrock"));
        int blocked = 0;
        for (int i = 0; i < 10; i++) {
            if (g.checkJoin(UUID.randomUUID(), "9.9." + i + ".1", i * 100, 5, 4) != JoinGuard.Verdict.OK) blocked++;
        }
        assertEquals(5, blocked);
        assertEquals(JoinGuard.Verdict.OK, g.checkJoin(a, "9.9.9.9", 1000, 5, 4), "known accounts always pass");
        JoinGuard g2 = new JoinGuard(null);
        int wave = 0;
        for (int i = 0; i < 6; i++) {
            if (g2.checkJoin(UUID.randomUUID(), "5.5.5." + i, i * 100, 50, 4) == JoinGuard.Verdict.SUBNET_WAVE) wave++;
        }
        assertEquals(2, wave);
    }
}
