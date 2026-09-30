package com.vylorq.anticheat.perm;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.detect.CheckType;
import net.minecraft.server.network.ServerPlayerEntity;

/** Shortcut to flag a player through the detection engine. */
public final class PlayerSessionFlags {
    private PlayerSessionFlags() {
    }

    public static void flag(ServerPlayerEntity p, CheckType check, double points, String detail) {
        Ac ac = Ac.get();
        if (ac == null) {
            return;
        }
        ac.engine.flag(p.getUuid(), p.getGameProfile().name(), check, points, detail, Ac.session(p).bedrock);
    }
}
