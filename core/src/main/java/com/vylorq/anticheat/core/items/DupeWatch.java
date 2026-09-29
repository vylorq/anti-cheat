package com.vylorq.anticheat.core.items;

import com.vylorq.anticheat.core.item.ItemInfo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dupe watch (section 9): alerts when the value of a player's valuable items jumps suddenly without a legit
 * source. Legit sources (trades, trader deals, admin gives, death restores, vault/trial spawner loot, crafting)
 * are reported with {@link #legitGain} so they don't count.
 */
public final class DupeWatch {
    private record Sample(long time, int value) {
    }

    private static final class State {
        final Deque<Sample> samples = new ArrayDeque<>();
        final Deque<Sample> legit = new ArrayDeque<>();
        long lastAlert = Long.MIN_VALUE / 2;
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public static int value(List<ItemInfo> items, Map<String, Integer> weights) {
        int v = 0;
        for (ItemInfo i : items) {
            if (i == null || i.isEmpty()) {
                continue;
            }
            v += weights.getOrDefault(i.id, 0) * i.count;
            if (i.contents != null) {
                v += value(i.contents, weights);
            }
        }
        return v;
    }

    public void legitGain(UUID player, int value, long now) {
        State s = states.computeIfAbsent(player, k -> new State());
        synchronized (s) {
            s.legit.addLast(new Sample(now, value));
        }
    }

    /**
     * Feed the current valuable-items value (inventory + ender chest).
     *
     * @return the unexplained jump if it exceeds the threshold, else 0
     */
    public int sample(UUID player, int value, long now, long windowMillis, int threshold) {
        State s = states.computeIfAbsent(player, k -> new State());
        synchronized (s) {
            s.samples.addLast(new Sample(now, value));
            while (!s.samples.isEmpty() && s.samples.peekFirst().time() < now - windowMillis) {
                s.samples.pollFirst();
            }
            while (!s.legit.isEmpty() && s.legit.peekFirst().time() < now - windowMillis) {
                s.legit.pollFirst();
            }
            int min = Integer.MAX_VALUE;
            for (Sample x : s.samples) {
                min = Math.min(min, x.value());
            }
            int credit = 0;
            for (Sample x : s.legit) {
                credit += x.value();
            }
            int jump = value - min - credit;
            if (jump > threshold && now - s.lastAlert > windowMillis) {
                s.lastAlert = now;
                return jump;
            }
            return 0;
        }
    }

    public void forget(UUID player) {
        states.remove(player);
    }
}
