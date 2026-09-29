package com.vylorq.anticheat.core.joins;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bot join protection and alt detection (section 9). */
public final class JoinGuard {
    public enum Verdict { OK, TOO_MANY_NEW_ACCOUNTS, SUBNET_WAVE }

    public static final class Data {
        /** ip -> accounts seen on it */
        public Map<String, Set<UUID>> accountsByIp = new LinkedHashMap<>();
        /** account -> ips */
        public Map<UUID, Set<String>> ipsByAccount = new LinkedHashMap<>();
        public Map<UUID, String> names = new LinkedHashMap<>();
        public Set<UUID> known = new LinkedHashSet<>();
    }

    private final Data data;
    private final Deque<Long> newJoins = new ArrayDeque<>();
    private final Map<String, Deque<Long>> subnetJoins = new HashMap<>();

    public JoinGuard(Data data) {
        this.data = data == null ? new Data() : data;
    }

    public Data data() {
        return data;
    }

    public static String subnet(String ip) {
        int i = ip.lastIndexOf('.');
        if (i > 0 && ip.indexOf(':') < 0) {
            return ip.substring(0, i) + ".0/24";
        }
        // IPv6: group by /64
        String[] parts = ip.split(":");
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < Math.min(4, parts.length); p++) {
            sb.append(parts[p]).append(':');
        }
        return sb.append(":/64").toString();
    }

    public synchronized boolean isKnown(UUID id) {
        return data.known.contains(id);
    }

    /**
     * Called before a player is allowed in. Known accounts always pass.
     */
    public synchronized Verdict checkJoin(UUID id, String ip, long now, int maxNewPerMinute, int subnetWaveSize) {
        if (data.known.contains(id)) {
            return Verdict.OK;
        }
        while (!newJoins.isEmpty() && newJoins.peekFirst() < now - 60_000) {
            newJoins.pollFirst();
        }
        if (newJoins.size() >= maxNewPerMinute) {
            return Verdict.TOO_MANY_NEW_ACCOUNTS;
        }
        Deque<Long> d = subnetJoins.computeIfAbsent(subnet(ip), k -> new ArrayDeque<>());
        while (!d.isEmpty() && d.peekFirst() < now - 60_000) {
            d.pollFirst();
        }
        if (d.size() >= subnetWaveSize) {
            return Verdict.SUBNET_WAVE;
        }
        newJoins.addLast(now);
        d.addLast(now);
        return Verdict.OK;
    }

    /** Records a successful join. @return true when this IP is new for the account. */
    public synchronized boolean recordJoin(UUID id, String name, String ip) {
        data.known.add(id);
        data.names.put(id, name);
        data.accountsByIp.computeIfAbsent(ip, k -> new LinkedHashSet<>()).add(id);
        return data.ipsByAccount.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(ip);
    }

    /** Other accounts that shared any IP with this one. */
    public synchronized List<UUID> alts(UUID id) {
        Set<UUID> out = new LinkedHashSet<>();
        for (String ip : data.ipsByAccount.getOrDefault(id, Set.of())) {
            out.addAll(data.accountsByIp.getOrDefault(ip, Set.of()));
        }
        out.remove(id);
        return new ArrayList<>(out);
    }

    public synchronized List<UUID> accountsOn(String ip) {
        return new ArrayList<>(data.accountsByIp.getOrDefault(ip, Set.of()));
    }

    public synchronized Set<String> ipsOf(UUID id) {
        return new LinkedHashSet<>(data.ipsByAccount.getOrDefault(id, Set.of()));
    }

    public synchronized String lastIp(UUID id) {
        String last = null;
        for (String ip : data.ipsByAccount.getOrDefault(id, Set.of())) {
            last = ip;
        }
        return last;
    }

    public synchronized String name(UUID id) {
        return data.names.get(id);
    }

    /** Case-insensitive lookup that also accepts Bedrock names with a prefix (".Steve") and spaces. */
    public synchronized UUID findByName(String name) {
        String n = name.trim();
        for (Map.Entry<UUID, String> e : data.names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(n)) {
                return e.getKey();
            }
        }
        for (Map.Entry<UUID, String> e : data.names.entrySet()) {
            String v = e.getValue();
            if (v.length() > 1 && !Character.isLetterOrDigit(v.charAt(0)) && v.substring(1).equalsIgnoreCase(n)) {
                return e.getKey();
            }
        }
        return null;
    }
}
