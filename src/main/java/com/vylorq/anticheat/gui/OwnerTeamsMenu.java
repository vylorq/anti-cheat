package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.team.Team;
import com.vylorq.anticheat.core.team.TeamManager;
import com.vylorq.anticheat.feature.OwnerPowers;
import com.vylorq.anticheat.feature.Teams;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The owner's view of every team: its vault, its base (home and land) and its members. */
public final class OwnerTeamsMenu {
    private OwnerTeamsMenu() {
    }

    public static void open(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), Msg.trFor(p, "oteams.title"));
        m.renderer(menu -> {
            menu.info(Btn.of(Items.WHITE_BANNER).name(Category.VIGIL, Msg.tr("oteams.title")).desc(Msg.tr("oteams.desc")).build());
            menu.list(Teams.tm().list(), t -> Btn.of(Items.WHITE_BANNER).color(Theme.GOLD_LIGHT).name(Teams.tagText(t) + " §f" + t.name)
                            .line(Msg.tr("oteams.members", t.members.size()))
                            .line(Msg.tr("oteams.level", TeamManager.level(t.xp)))
                            .line(Msg.tr("oteams.home", home(t)))
                            .line(Msg.tr("oteams.land", t.chunks.size()))
                            .left(Msg.tr("owner.open")).build(),
                    t -> (pl, c) -> team(pl, t.id), t -> t.name + " " + t.tag, List.of(), Msg.tr("oteams.none"), "");
        });
        m.open(p);
    }

    private static String home(Team t) {
        return t.hasHome() ? (int) t.homeX + ", " + (int) t.homeY + ", " + (int) t.homeZ + " (" + t.homeWorld + ")" : "-";
    }

    /** The middle of the team's land in a world, or null when it has none there. */
    private static int[] landCenter(Team t) {
        long sx = 0;
        long sz = 0;
        int n = 0;
        String world = null;
        for (String c : t.chunks) {
            String[] parts = c.split("\\|");
            if (parts.length != 3) {
                continue;
            }
            if (world == null) {
                world = parts[0];
            }
            if (!world.equals(parts[0])) {
                continue;
            }
            sx += Integer.parseInt(parts[1]);
            sz += Integer.parseInt(parts[2]);
            n++;
        }
        return n == 0 ? null : new int[]{(int) (sx / n) * 16 + 8, (int) (sz / n) * 16 + 8};
    }

    private static String landWorld(Team t) {
        for (String c : t.chunks) {
            String[] parts = c.split("\\|");
            if (parts.length == 3) {
                return parts[0];
            }
        }
        return null;
    }

    public static void team(ServerPlayerEntity p, String id) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Team t0 = Teams.tm().get(id);
        if (t0 == null) {
            open(p);
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "oteams.title"), t0.name);
        m.renderer(menu -> {
            Team t = Teams.tm().get(id);
            if (t == null) {
                return;
            }
            menu.info(Btn.of(Items.WHITE_BANNER).name(Teams.tagText(t) + " §f" + t.name).desc(t.description)
                    .line(Msg.tr("oteams.level", TeamManager.level(t.xp))).line(Msg.tr("oteams.bank", t.bank))
                    .line(Msg.tr("oteams.wars", t.warsWon, t.warsLost)).build());
            menu.set(11, Btn.of(Items.ENDER_CHEST).color(Theme.GOLD_LIGHT).name(Msg.tr("oteams.vault"))
                    .desc(Msg.tr("oteams.vault.desc")).status(Theme.GOLD_LIGHT, Msg.tr("oteams.vault.items", t.vault.size(), Teams.vaultSize(t)))
                    .left(Msg.tr("owner.open")).build(), null, (pl, c) -> vault(pl, t));
            int[] center = landCenter(t);
            menu.set(13, Btn.of(Items.RED_BED).color(Theme.GOLD_LIGHT).name(Msg.tr("oteams.base"))
                    .line(Msg.tr("oteams.home", home(t)))
                    .line(Msg.tr("oteams.land", t.chunks.size()))
                    .line(center == null ? Msg.tr("oteams.no-land") : Msg.tr("oteams.land-center", center[0], center[1], landWorld(t)))
                    .left(Msg.tr("oteams.visit-home")).right(Msg.tr("oteams.visit-land")).build(), null, (pl, c) -> {
                if (c.isRight()) {
                    if (center != null) {
                        visit(pl, landWorld(t), center[0], -1, center[1]);
                    }
                } else if (t.hasHome()) {
                    visit(pl, t.homeWorld, t.homeX, t.homeY, t.homeZ);
                } else {
                    Msg.send(pl, "oteams.no-home");
                }
            });
            menu.set(15, Btn.of(Items.COMPASS).color(Theme.GREEN).name(Msg.tr("oteams.back"))
                    .desc(Msg.tr("oteams.back.desc")).left(Msg.tr("oteams.back")).build(), null, (pl, c) -> back(pl));
            int slot = 28;
            for (UUID mem : t.members) {
                if (slot > 34) {
                    break;
                }
                String name = InspectMenu.name(mem);
                String role = Msg.tr("oteams.role." + t.role(mem).name().toLowerCase(java.util.Locale.ROOT));
                boolean online = Ac.server().getPlayerManager().getPlayer(mem) != null;
                menu.set(slot++, Btn.head(mem, name).name((online ? "§a" : "§7") + name).line(role)
                        .left(Msg.tr("tracker.inspect")).build(), null, (pl, c) -> InspectMenu.open(pl, mem));
            }
        });
        m.open(p);
    }

    /** Opens the team's real vault: what the owner moves changes it for the team too. */
    private static void vault(ServerPlayerEntity p, Team t) {
        var inv = Teams.vault(t);
        p.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, pl) -> inv.size() == 54
                        ? net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x6(syncId, playerInv, inv)
                        : net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x3(syncId, playerInv, inv),
                Text.literal(Teams.tagText(t) + " §8" + Msg.trFor(p, "team.vault"))));
    }

    private record Was(String world, double x, double y, double z, float yaw, float pitch, GameMode mode) {
    }

    private static final Map<UUID, Was> RETURN = new HashMap<>();

    /** The owner looks around a base in spectator mode; "back" puts them where they were. */
    private static void visit(ServerPlayerEntity p, String worldId, double x, double y, double z) {
        ServerWorld w = Mc.world(Ac.server(), worldId);
        if (w == null) {
            return;
        }
        RETURN.putIfAbsent(p.getUuid(), new Was(Mc.worldId(p.getEntityWorld()), p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(),
                p.interactionManager.getGameMode()));
        if (y < 0) {
            w.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            y = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, (int) x, (int) z);
        }
        p.closeHandledScreen();
        p.changeGameMode(GameMode.SPECTATOR);
        Mc.teleport(p, w, x + 0.5, y + 12, z + 0.5, p.getYaw(), 45);
        Msg.send(p, "oteams.visiting");
    }

    public static void back(ServerPlayerEntity p) {
        Was was = RETURN.remove(p.getUuid());
        if (was == null) {
            Msg.send(p, "oteams.not-visiting");
            return;
        }
        ServerWorld w = Mc.world(Ac.server(), was.world());
        if (w != null) {
            Mc.teleport(p, w, was.x(), was.y(), was.z(), was.yaw(), was.pitch());
        }
        p.changeGameMode(was.mode());
        p.closeHandledScreen();
    }
}
