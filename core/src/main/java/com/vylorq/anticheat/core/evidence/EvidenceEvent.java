package com.vylorq.anticheat.core.evidence;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** One entry in an evidence timeline (section 11). */
public final class EvidenceEvent {
    public enum Type { MOVE, HIT, CLICK, BREAK, PLACE, INVENTORY, FLAG, CHAT, COMMAND, OTHER }

    public long time;
    public Type type;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public String text;

    public EvidenceEvent() {
    }

    public EvidenceEvent(long time, Type type, double x, double y, double z, float yaw, float pitch, String text) {
        this.time = time;
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.text = text;
    }

    /** e.g. {@code 12:04:31 — hit Steve from 4.8 blocks, angle 2°, flagged Reach}. */
    public String describe() {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(time));
        if (type == Type.MOVE) {
            return String.format(Locale.ROOT, "%s — at %.2f, %.2f, %.2f (yaw %.1f, pitch %.1f)", t, x, y, z, yaw, pitch);
        }
        return t + " — " + (text == null ? type.name().toLowerCase(Locale.ROOT) : text);
    }
}
