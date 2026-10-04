package com.vylorq.anticheat.core.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A saved window of a player's activity, attached to review cases. */
public final class EvidenceClip {
    public String id;
    public UUID player;
    public String playerName;
    public long createdAt;
    public String trigger;
    /** World it was recorded in (for the in-game replay); null for older clips. */
    public String world;
    /** Pinned clips (open case / ban) are never deleted by retention. */
    public boolean pinned;
    public List<EvidenceEvent> events = new ArrayList<>();

    /** Timeline without per-tick movement spam: keeps every non-move event and one move per second. */
    public List<EvidenceEvent> condensedTimeline() {
        List<EvidenceEvent> out = new ArrayList<>();
        long lastMove = Long.MIN_VALUE;
        for (EvidenceEvent e : events) {
            if (e.type == EvidenceEvent.Type.MOVE) {
                if (e.time - lastMove < 1000) {
                    continue;
                }
                lastMove = e.time;
            }
            out.add(e);
        }
        return out;
    }

    /** Short plain-text summary (used for Discord). */
    public String summary(int maxLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("Evidence ").append(id).append(" for ").append(playerName).append(" (trigger: ").append(trigger).append(")\n");
        int n = 0;
        for (EvidenceEvent e : events) {
            if (e.type == EvidenceEvent.Type.MOVE) {
                continue;
            }
            sb.append(e.describe()).append('\n');
            if (++n >= maxLines) {
                sb.append("...");
                break;
            }
        }
        return sb.toString();
    }
}
