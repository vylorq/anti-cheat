package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.claims.Claim;
import com.vylorq.anticheat.core.claims.ClaimAction;
import com.vylorq.anticheat.core.claims.ClaimManager;
import com.vylorq.anticheat.core.claims.ClaimRole;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.time.LocalTime;
import java.util.UUID;

/** Claim rules applied to real players and positions (section 17). */
public final class Claims {
    private Claims() {
    }

    public static boolean anyMemberOnline(Claim c) {
        for (UUID id : c.members.keySet()) {
            if (Ac.server().getPlayerManager().getPlayer(id) != null) {
                return true;
            }
        }
        return false;
    }

    public static boolean protectionOn(Claim c, World w) {
        if (c.schedule == Claim.Schedule.ALWAYS) {
            return c.isActive();
        }
        return ClaimManager.protectionOn(c, w.getTimeOfDay() % 24000L, anyMemberOnline(c), LocalTime.now().getHour());
    }

    public static boolean can(ServerPlayerEntity p, Claim c, ClaimAction a) {
        if (c == null) {
            return true;
        }
        boolean staff = Perms.isActiveStaff(p);
        return Ac.get().claims.can(c, p.getUuid(), staff, a, protectionOn(c, p.getEntityWorld()));
    }

    public static Claim at(World w, BlockPos pos) {
        return Ac.get().claims.at(Mc.worldId(w), pos.getX(), pos.getZ());
    }

    /**
     * Full check for an action at a block, with the denial message and grief alerts.
     *
     * @return true if allowed
     */
    public static boolean check(ServerPlayerEntity p, World w, BlockPos pos, ClaimAction a) {
        if (!Teams.allowed(p, w, pos, a)) {
            return false;
        }
        Claim c = at(w, pos);
        if (c == null || can(p, c, a)) {
            return true;
        }
        Msg.actionBar(p, Msg.tr(c.eventLocked ? "claim.locked" : "claim.denied", c.name));
        var cfg = Ac.config().claims;
        if (a.isChange() && Ac.get().claims.griefAttempt(p.getUuid(), c, cfg.griefAlertAttempts, cfg.griefAlertWindowSeconds * 1000L)) {
            Staff.broadcast(Msg.prefixed(Msg.tr("claim.grief-alert", p.getGameProfile().name(), c.name,
                    pos.getX() + " " + pos.getY() + " " + pos.getZ())).append(Text.literal(" "))
                    .append(Msg.button("§b[TP]", "/inspect " + Msg.q(p.getGameProfile().name()) + " tp", "Teleport")));
            Discord.send("grief", "Grief attempt in " + c.name, p.getGameProfile().name() + " at " + pos.toShortString(), 0xC0392B);
            Ac.get().logs.activity(System.currentTimeMillis(), p.getUuid(), "grief-attempt", c.name + " " + pos.toShortString());
        }
        return false;
    }

    public static boolean canEnter(ServerPlayerEntity p, Claim c) {
        return can(p, c, ClaimAction.ENTER);
    }

    public static void denyEntry(ServerPlayerEntity p, Claim c) {
        Msg.actionBar(p, Msg.tr("claim.private", c.name));
        if (c.settings.alerts) {
            alertMembers(c, Msg.tr("claim.alert.tried", p.getGameProfile().name(), c.name));
        }
    }

    private static void alertMembers(Claim c, String text) {
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            ClaimRole r = c.roleOf(o.getUuid(), System.currentTimeMillis());
            if (r == ClaimRole.MANAGER || Perms.isActiveStaff(o)) {
                o.sendMessage(Msg.prefixed(text));
            }
        }
    }

    public static void onTransition(ServerPlayerEntity p, ClaimManager.Transition t) {
        PlayerSession s = Ac.session(p);
        if (t.entered() != null) {
            Claim c = t.entered();
            s.lastClaimId = c.id;
            if (c.settings.entryMessages) {
                String left = c.expiresAt == Durations.PERMANENT ? Msg.trFor(p, "claim.permanent")
                        : Durations.format(c.remaining(System.currentTimeMillis()));
                String bar = Msg.trFor(p, "claim.bar", c.name, left);
                if (!com.vylorq.anticheat.ui.BossBars.show(p, com.vylorq.anticheat.ui.BossBars.Kind.CLAIM,
                        com.vylorq.anticheat.ui.Theme.c(bar, com.vylorq.anticheat.ui.Theme.GREEN), 1f, 4)) {
                    Msg.actionBar(p, Msg.trFor(p, "claim.enter", c.name, left));
                }
                Mc.title(p, "", "§a" + c.name, 5, 30, 10);
            }
            boolean member = c.roleOf(p.getUuid(), System.currentTimeMillis()) != null;
            if (c.settings.alerts && !member && !Perms.isActiveStaff(p)) {
                alertMembers(c, Msg.tr("claim.alert.entered", p.getGameProfile().name(), c.name));
            }
        } else if (t.left() != null) {
            s.lastClaimId = null;
            if (t.left().settings.entryMessages) {
                com.vylorq.anticheat.ui.BossBars.hide(p, com.vylorq.anticheat.ui.BossBars.Kind.CLAIM);
                Msg.actionBar(p, Msg.trFor(p, "claim.leave", t.left().name));
            }
        }
    }

    /** Timer tick (every second): warnings, expiry, removal of every permission. */
    public static void tick() {
        Ac ac = Ac.get();
        for (ClaimManager.TimerEvent e : ac.claims.tick(Ac.config().claims.archiveOnExpiry)) {
            Claim c = e.claim();
            Ac.markDirty("claims");
            switch (e.kind()) {
                case WARN_1H, WARN_10M -> {
                    String when = e.kind() == ClaimManager.TimerEvent.Kind.WARN_1H ? "1 hour" : "10 minutes";
                    String msg = Msg.tr("claim.expiring", c.name, when);
                    for (ServerPlayerEntity o : ac.server.getPlayerManager().getPlayerList()) {
                        if (c.members.containsKey(o.getUuid()) || Perms.isActiveStaff(o)) {
                            o.sendMessage(Msg.prefixed(msg));
                        }
                    }
                    Discord.send("claimExpiry", "Claim expiring: " + c.name, "Expires in " + when, 0xF1C40F);
                }
                case EXPIRED, DELETED -> {
                    String msg = Msg.tr("claim.expired", c.name);
                    for (ServerPlayerEntity o : ac.server.getPlayerManager().getPlayerList()) {
                        boolean inside = c.contains(Mc.worldId(o.getEntityWorld()), o.getX(), o.getZ());
                        boolean wasMember = c.pendingExpiryNotice.remove(o.getUuid());
                        if (inside || wasMember || Perms.isActiveStaff(o)) {
                            o.sendMessage(Msg.prefixed(msg));
                        }
                    }
                    Staff.log("System", null, e.kind() == ClaimManager.TimerEvent.Kind.EXPIRED ? "claim-expired" : "claim-deleted",
                            null, c.name, "protection ended; all member permissions removed");
                    Discord.send("claimExpiry", "Claim expired: " + c.name, "Protection is off; permissions removed.", 0x95A5A6);
                }
            }
        }
    }

    public static void onJoin(ServerPlayerEntity p) {
        for (Claim c : Ac.get().claims.pollExpiryNotices(p.getUuid())) {
            Msg.send(p, "claim.expired", c.name);
            Ac.markDirty("claims");
        }
    }

    /** Particle outline of a rectangle at the player's height (only that player sees it). */
    public static void outline(ServerPlayerEntity p, int minX, int minZ, int maxX, int maxZ, boolean selection) {
        double y = p.getY() + 1;
        int step = Math.max(1, Math.max(maxX - minX, maxZ - minZ) / 40);
        var effect = selection ? ParticleTypes.END_ROD : ParticleTypes.HAPPY_VILLAGER;
        for (int x = minX; x <= maxX + 1; x += step) {
            if (Math.abs(x - p.getX()) > 48) {
                continue;
            }
            if (Math.abs(minZ - p.getZ()) < 48) {
                Mc.particle(p, effect, x, y, minZ);
            }
            if (Math.abs(maxZ + 1 - p.getZ()) < 48) {
                Mc.particle(p, effect, x, y, maxZ + 1);
            }
        }
        for (int z = minZ; z <= maxZ + 1; z += step) {
            if (Math.abs(z - p.getZ()) > 48) {
                continue;
            }
            if (Math.abs(minX - p.getX()) < 48) {
                Mc.particle(p, effect, minX, y, z);
            }
            if (Math.abs(maxX + 1 - p.getX()) < 48) {
                Mc.particle(p, effect, maxX + 1, y, z);
            }
        }
    }

    /** Every second for players holding the Claim Stick: their selection and nearby claim borders. */
    public static void showBorders(ServerPlayerEntity p) {
        PlayerSession s = Ac.session(p);
        if (s.corner1 != null && s.corner2 != null && Mc.worldId(p.getEntityWorld()).equals(s.cornerWorld)) {
            outline(p, Math.min(s.corner1.getX(), s.corner2.getX()), Math.min(s.corner1.getZ(), s.corner2.getZ()),
                    Math.max(s.corner1.getX(), s.corner2.getX()), Math.max(s.corner1.getZ(), s.corner2.getZ()), true);
        }
        for (Claim c : Ac.get().claims.near(Mc.worldId(p.getEntityWorld()), p.getBlockX(), p.getBlockZ(), 48)) {
            outline(p, c.minX, c.minZ, c.maxX, c.maxZ, false);
        }
    }

    /** Pushes a player out of a private claim they ended up inside (pearls, chorus fruit, teleports, other mods). */
    public static void enforceInside(ServerPlayerEntity p) {
        Claim c = Ac.get().claims.at(Mc.worldId(p.getEntityWorld()), p.getX(), p.getZ());
        if (c == null || canEnter(p, c)) {
            return;
        }
        ServerWorld w = (ServerWorld) p.getEntityWorld();
        // Nearest point outside the claim along the shortest axis.
        double x = p.getX();
        double z = p.getZ();
        double dW = x - c.minX;
        double dE = c.maxX + 1 - x;
        double dN = z - c.minZ;
        double dS = c.maxZ + 1 - z;
        double min = Math.min(Math.min(dW, dE), Math.min(dN, dS));
        if (min == dW) {
            x = c.minX - 1.5;
        } else if (min == dE) {
            x = c.maxX + 2.5;
        } else if (min == dN) {
            z = c.minZ - 1.5;
        } else {
            z = c.maxZ + 2.5;
        }
        int topY = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
        Mc.teleport(p, w, x, Math.max(p.getY(), topY), z, p.getYaw(), p.getPitch());
        denyEntry(p, c);
    }
}
