package com.vylorq.anticheat.core.detect;

import java.util.Locale;

/** Every check the anti-cheat runs. The id is used in config and logs. */
public enum CheckType {
    // Movement (section 7)
    FLY(Category.MOVEMENT, 1.0),
    SPEED(Category.MOVEMENT, 1.0),
    SPIDER(Category.MOVEMENT, 1.0),
    JESUS(Category.MOVEMENT, 1.0),
    NOFALL(Category.MOVEMENT, 1.0),
    STEP(Category.MOVEMENT, 1.0),
    PHASE(Category.MOVEMENT, 1.5),
    TIMER(Category.MOVEMENT, 1.0),
    VEHICLE(Category.MOVEMENT, 0.8),
    ELYTRA(Category.MOVEMENT, 0.8),
    // Combat (section 8)
    REACH(Category.COMBAT, 1.5),
    WALL_HIT(Category.COMBAT, 1.5),
    AIM(Category.COMBAT, 1.2),
    MULTI_TARGET(Category.COMBAT, 2.0),
    ATTACK_COOLDOWN(Category.COMBAT, 1.0),
    INVALID_ACTION(Category.COMBAT, 1.0),
    AUTOCLICKER(Category.COMBAT, 1.5),
    // World / other (section 9)
    XRAY(Category.WORLD, 1.0),
    XRAY_TRAP(Category.WORLD, 5.0),
    FAST_BREAK(Category.WORLD, 1.0),
    SCAFFOLD(Category.WORLD, 1.0),
    ILLEGAL_ITEM(Category.WORLD, 5.0),
    DUPE(Category.WORLD, 3.0),
    // Packets / exploits
    BAD_PACKET(Category.PACKET, 2.0),
    UNAUTHORIZED(Category.PACKET, 3.0),
    TRADE_MACRO(Category.PACKET, 1.5),
    BARRIER_ESCAPE(Category.PACKET, 3.0);

    public enum Category { MOVEMENT, COMBAT, WORLD, PACKET }

    private final Category category;
    private final double weight;

    CheckType(Category category, double weight) {
        this.category = category;
        this.weight = weight;
    }

    public Category category() {
        return category;
    }

    /** How much each point from this check counts toward suspicion. */
    public double weight() {
        return weight;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        String s = name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static CheckType byId(String id) {
        for (CheckType t : values()) {
            if (t.id().equalsIgnoreCase(id) || t.name().equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }
}
