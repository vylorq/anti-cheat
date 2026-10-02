package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.command.TeamCommands;
import com.vylorq.anticheat.core.team.Team;
import com.vylorq.anticheat.core.team.TeamManager;
import com.vylorq.anticheat.core.team.TeamManager.Result;
import com.vylorq.anticheat.feature.Teams;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.Locale;
import java.util.UUID;

/** /team: your team at a glance, with every action as a button (works the same on Bedrock). */
public final class TeamMenu {
    private TeamMenu() {
    }

    private static final int[] GRID = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private static void say(ServerPlayerEntity p, Result r, String okKey, Object... args) {
        if (r == Result.OK) {
            Ac.markDirty("teams");
            Teams.syncTags();
            if (okKey != null) {
                Msg.send(p, okKey, args);
            }
        } else {
            Msg.send(p, "team.r." + r.name().toLowerCase(Locale.ROOT), args);
        }
    }

    public static void open(ServerPlayerEntity p) {
        Team t = Teams.tm().teamOf(p.getUuid());
        if (t == null) {
            noTeam(p);
        } else {
            team(p);
        }
    }

    /** Not in a team: start one, accept an invite, or see the teams. */
    static void noTeam(ServerPlayerEntity p) {
        Menu m = Menu.std(Theme.Category.PLAYER, 5, Msg.trFor(p, "team.menu.title"));
        m.renderer(menu -> {
            menu.set(11, Btn.of(Items.WHITE_BANNER).name(Msg.tr("team.menu.create")).desc(Msg.tr("team.menu.create-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Msg.sendRaw(pl, Msg.suggest("§b[/team create <name> [TAG]]", "/team create ", ""));
            });
            menu.set(15, Btn.of(Items.BOOK).name(Msg.tr("team.menu.list")).desc(Msg.tr("team.menu.list-desc"))
                    .count(Teams.tm().list().size()).build(), null, (pl, c) -> list(pl, menu));
            int i = 0;
            for (String id : Teams.tm().invitesOf(p.getUuid())) {
                Team t = Teams.tm().get(id);
                if (t == null || i >= GRID.length) {
                    continue;
                }
                menu.set(GRID[i++] + 9, Btn.of(Items.LIME_BANNER).name(Teams.tagText(t) + " §f" + t.name)
                        .desc(Msg.tr("team.menu.invite-from")).left(Msg.tr("team.join")).build(), null, (pl, c) -> {
                    Result r = Teams.tm().join(pl.getUuid(), t.name, Teams.limits());
                    say(pl, r, "team.you-joined", t.name);
                    open(pl);
                });
            }
        });
        m.open(p);
    }

    static void list(ServerPlayerEntity p, Menu parent) {
        Menu m = Menu.std(Theme.Category.PLAYER, 6, Msg.trFor(p, "team.menu.title"), Msg.trFor(p, "team.menu.list"));
        m.parent(parent);
        m.renderer(menu -> {
            int i = 0;
            for (Team t : Teams.tm().list()) {
                if (i >= GRID.length) {
                    break;
                }
                Btn b = Btn.of(Items.WHITE_BANNER).name(Teams.tagText(t) + " §f" + t.name)
                        .line(Msg.tr("team.menu.size", t.members.size(), t.chunks.size()))
                        .line(Msg.tr(t.open ? "team.open" : "team.invite-only"));
                boolean canJoin = t.open && Teams.tm().teamOf(p.getUuid()) == null;
                menu.set(GRID[i++], (canJoin ? b.left(Msg.tr("team.join")) : b).build(), null, (pl, c) -> {
                    if (canJoin) {
                        say(pl, Teams.tm().join(pl.getUuid(), t.name, Teams.limits()), "team.you-joined", t.name);
                        open(pl);
                    }
                });
            }
        });
        m.open(p);
    }

    /** Your team. */
    static void team(ServerPlayerEntity p) {
        Menu m = Menu.std(Theme.Category.PLAYER, 6, Msg.trFor(p, "team.menu.title"));
        m.renderer(menu -> {
            Team t = Teams.tm().teamOf(p.getUuid());
            if (t == null) {
                p.closeHandledScreen();
                return;
            }
            Team.Role me = t.role(p.getUuid());
            TeamManager.Limits l = Teams.limits();
            menu.info(Btn.of(Items.WHITE_BANNER).name(Teams.tagText(t) + " §f§l" + t.name)
                    .line(Msg.tr("team.menu.size", t.members.size(), t.chunks.size()))
                    .line(Msg.tr("team.menu.level", TeamManager.level(t.xp)))
                    .line(Msg.tr("team.menu.land", t.chunks.size(), Teams.tm().chunkLimit(t, l)))
                    .line(Msg.tr("team.menu.your-role", Msg.tr("team.role." + me.name().toLowerCase(Locale.ROOT)))).build());
            menu.set(10, Btn.of(Items.RED_BED).name(Msg.tr("team.menu.home")).desc(Msg.tr(t.hasHome() ? "team.menu.home-desc" : "team.no-home"))
                    .build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                TeamCommands.home(pl);
            });
            menu.set(11, Btn.of(Items.LIME_BANNER).name(Msg.tr("team.menu.claim")).desc(Msg.tr("team.menu.claim-desc"))
                    .left(Msg.tr("team.menu.claim")).right(Msg.tr("team.menu.unclaim")).build(), null, (pl, c) -> {
                ChunkPos cp = pl.getChunkPos();
                if (c.isRight()) {
                    say(pl, Teams.tm().unclaim(pl.getUuid(), Mc.worldId(pl.getEntityWorld()), cp.x, cp.z), "team.unclaimed");
                } else if (!Teams.claimable((ServerWorld) pl.getEntityWorld(), cp)) {
                    Msg.send(pl, "team.not-claimable");
                } else {
                    say(pl, Teams.tm().claim(pl.getUuid(), Mc.worldId(pl.getEntityWorld()), cp.x, cp.z, l), "team.claimed",
                            t.chunks.size() + 1, Teams.tm().chunkLimit(t, l));
                }
                menu.refresh();
            });
            menu.set(12, Btn.of(Items.OAK_SIGN).name(Msg.tr("team.menu.chat")).desc(Msg.tr("team.menu.chat-desc")).build(), null, (pl, c) -> {
                Msg.send(pl, Teams.toggleChat(pl) ? "team.chat-on" : "team.chat-off");
            });
            menu.set(13, Btn.of(Items.WRITABLE_BOOK).name(Msg.tr("team.menu.invite")).desc(Msg.tr("team.menu.invite-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Msg.sendRaw(pl, Msg.suggest("§b[/team invite <player>]", "/team invite ", ""));
            });
            if (me.atLeast(Team.Role.OFFICER)) {
                menu.set(14, Btn.of(Items.COMPASS).name(Msg.tr("team.menu.sethome")).desc(Msg.tr("team.menu.sethome-desc")).build(), null, (pl, c) -> {
                    pl.closeHandledScreen();
                    Mc.run(pl, "team sethome");
                });
            }
            if (me == Team.Role.LEADER) {
                menu.set(15, Btn.of(t.open ? Items.OAK_DOOR : Items.IRON_DOOR).name(Msg.tr("team.menu.open"))
                        .onOff(t.open).build(), null, (pl, c) -> {
                    t.open = !t.open;
                    say(pl, Result.OK, t.open ? "team.now-open" : "team.now-closed");
                    menu.refresh();
                });
                menu.set(16, Btn.of(Items.IRON_SWORD).name(Msg.tr("team.menu.ff")).desc(Msg.tr("team.menu.ff-desc"))
                        .onOff(t.friendlyFire).build(), null, (pl, c) -> {
                    t.friendlyFire = !t.friendlyFire;
                    say(pl, Result.OK, t.friendlyFire ? "team.ff-on" : "team.ff-off");
                    menu.refresh();
                });
            }
            int i = 0;
            for (UUID id : t.members) {
                if (i >= GRID.length) {
                    break;
                }
                Team.Role r = t.role(id);
                String name = com.vylorq.anticheat.command.Args.nameOf(id, "?");
                boolean online = Ac.server().getPlayerManager().getPlayer(id) != null;
                Btn b = Btn.of(Items.PLAYER_HEAD).name((r == Team.Role.LEADER ? "§6★ " : r == Team.Role.OFFICER ? "§e☆ " : "§f") + name)
                        .line(Msg.tr("team.role." + r.name().toLowerCase(Locale.ROOT)))
                        .status(online ? Theme.GREEN : Theme.RED, Msg.tr(online ? "badmin.online" : "badmin.offline"));
                if (me == Team.Role.LEADER && !id.equals(p.getUuid())) {
                    b.left(Msg.tr(r == Team.Role.OFFICER ? "team.menu.demote" : "team.menu.promote"))
                            .right(Msg.tr("team.menu.kick")).shift(Msg.tr("team.menu.make-leader"));
                } else if (me == Team.Role.OFFICER && r == Team.Role.MEMBER) {
                    b.right(Msg.tr("team.menu.kick"));
                }
                menu.set(GRID[i++], b.build(), null, (pl, c) -> {
                    if (id.equals(pl.getUuid())) {
                        return;
                    }
                    if (c.isShift()) {
                        Confirm.open(pl, Theme.Category.PLAYER, Msg.trFor(pl, "team.menu.make-leader"), name, new ItemStack(Items.GOLDEN_HELMET),
                                () -> say(pl, Teams.tm().handOver(pl.getUuid(), id), "team.new-leader", name));
                    } else if (c.isRight()) {
                        Confirm.open(pl, Theme.Category.PLAYER, Msg.trFor(pl, "team.menu.kick"), name, new ItemStack(Items.BARRIER),
                                () -> say(pl, Teams.tm().kick(pl.getUuid(), id), "team.kicked", name));
                    } else {
                        say(pl, Teams.tm().setOfficer(pl.getUuid(), id, r != Team.Role.OFFICER),
                                r == Team.Role.OFFICER ? "team.demoted" : "team.promoted", name);
                        menu.refresh();
                    }
                });
            }
            menu.set(39, Btn.of(Items.BOOK).name(Msg.tr("team.menu.list")).build(), null, (pl, c) -> list(pl, menu));
            menu.set(40, Btn.of(Items.NETHER_STAR).name(Msg.tr("team.menu.more")).desc(Msg.tr("team.menu.more-desc")).glint(true).build(), null,
                    (pl, c) -> more(pl, menu));
            if (me == Team.Role.LEADER) {
                menu.set(41, Btn.of(Items.TNT).color(Theme.RED).name(Msg.tr("team.menu.disband")).desc(Msg.tr("team.menu.disband-desc")).build(), null,
                        (pl, c) -> Confirm.open(pl, Theme.Category.PLAYER, Msg.trFor(pl, "team.menu.disband"), t.name, new ItemStack(Items.TNT),
                                () -> Mc.run(pl, "team disband confirm")));
            } else {
                menu.set(41, Btn.of(Items.OAK_DOOR).color(Theme.RED).name(Msg.tr("team.menu.leave")).build(), null,
                        (pl, c) -> Confirm.open(pl, Theme.Category.PLAYER, Msg.trFor(pl, "team.menu.leave"), t.name, new ItemStack(Items.OAK_DOOR),
                                () -> Mc.run(pl, "team leave")));
            }
        });
        m.open(p);
    }

    /** Vault, allies, map, where, ping, border, top, message of the day and land settings. */
    static void more(ServerPlayerEntity p, Menu parent) {
        Menu m = Menu.std(Theme.Category.PLAYER, 5, Msg.trFor(p, "team.menu.title"), Msg.trFor(p, "team.menu.more"));
        m.parent(parent);
        m.renderer(menu -> {
            Team t = Teams.tm().teamOf(p.getUuid());
            if (t == null) {
                p.closeHandledScreen();
                return;
            }
            Team.Role me = t.role(p.getUuid());
            menu.set(10, Btn.of(Items.ENDER_CHEST).name(Msg.tr("team.menu.vault")).desc(Msg.tr("team.menu.vault-desc")).build(), null,
                    (pl, c) -> Teams.openVault(pl));
            menu.set(11, Btn.of(Items.CYAN_BANNER).name(Msg.tr("team.menu.allies")).desc(Msg.tr("team.menu.allies-desc"))
                    .count(t.allies.size()).glint(!t.allyRequests.isEmpty()).build(), null, (pl, c) -> allies(pl, menu));
            menu.set(12, Btn.of(Items.RECOVERY_COMPASS).name(Msg.tr("team.menu.where")).desc(Msg.tr("team.menu.where-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                TeamCommands.where(pl);
            });
            menu.set(13, Btn.of(Items.BELL).name(Msg.tr("team.menu.ping")).desc(Msg.tr("team.menu.ping-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Teams.ping(pl, null);
            });
            menu.set(14, Btn.of(Items.BLAZE_POWDER).name(Msg.tr("team.menu.border")).desc(Msg.tr("team.menu.border-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Msg.send(pl, Teams.toggleBorder(pl) ? "team.border-on" : "team.border-off");
            });
            menu.set(16, Btn.of(Items.FILLED_MAP).name(Msg.tr("team.menu.map")).desc(Msg.tr("team.menu.map-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Teams.showMap(pl);
            });
            menu.set(15, Btn.of(Items.GOLD_INGOT).name(Msg.tr("team.menu.top")).desc(Msg.tr("team.menu.top-desc"))
                    .left(Msg.tr("team.top.land")).right(Msg.tr("team.top.members")).shift(Msg.tr("team.top.kills")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                TeamCommands.top(pl.getCommandSource(), c.isShift() ? "kills" : c.isRight() ? "members" : "land");
            });
            Btn bank = Btn.of(Items.GOLD_BLOCK).name(Msg.tr("team.menu.bank"))
                    .line(Msg.tr("team.bank.balance", com.vylorq.anticheat.feature.Markets.money((int) Math.min(Integer.MAX_VALUE, t.bank))))
                    .desc(Msg.tr("team.menu.bank-desc")).left(Msg.tr("team.menu.bank-deposit"));
            if (me.atLeast(Team.Role.OFFICER)) {
                bank.right(Msg.tr("team.menu.bank-withdraw"));
            }
            menu.set(20, bank.shift(Msg.tr("team.menu.bank-log")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                if (c.isShift()) {
                    TeamCommands.bank(pl.getCommandSource(), t);
                } else if (c.isRight() && t.role(pl.getUuid()).atLeast(Team.Role.OFFICER)) {
                    Msg.sendRaw(pl, Msg.suggest("§b[/team bank withdraw <amount>]", "/team bank withdraw ", ""));
                } else {
                    Msg.sendRaw(pl, Msg.suggest("§b[/team bank deposit <amount>]", "/team bank deposit ", ""));
                }
            });
            int lvl = TeamManager.level(t.xp);
            long need = lvl >= TeamManager.MAX_LEVEL ? 0 : TeamManager.xpFor(lvl + 1) - TeamManager.xpFor(lvl);
            long into = t.xp - TeamManager.xpFor(lvl);
            int pct = need <= 0 ? 100 : (int) Math.min(100, into * 100 / need);
            menu.set(22, Btn.of(Items.EXPERIENCE_BOTTLE).name(Msg.tr("team.menu.level", lvl))
                    .line("§a" + "■".repeat(pct / 10) + "§8" + "■".repeat(10 - pct / 10) + " §f" + pct + "%")
                    .line(Msg.tr("team.level-perks", Teams.tm().chunkLimit(t, Teams.limits()), Teams.vaultSize(t)))
                    .desc(Msg.tr("team.menu.level-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                TeamCommands.level(pl.getCommandSource(), t);
            });
            TeamManager.War war = Teams.tm().warOf(t.id);
            Btn wb = Btn.of(war != null ? Items.NETHERITE_SWORD : Items.IRON_SWORD).name(Msg.tr("team.menu.war"));
            if (war != null) {
                wb.line(Teams.warLine(war)).glint(true);
            } else {
                wb.line(Msg.tr("team.menu.war-record", t.warsWon, t.warsLost)).desc(Msg.tr("team.menu.war-desc"));
            }
            if (me == Team.Role.LEADER && war == null) {
                wb.left(Msg.tr("team.menu.war-declare"));
            }
            menu.set(24, wb.build(), null, (pl, c) -> {
                if (war == null && t.role(pl.getUuid()) == Team.Role.LEADER) {
                    pl.closeHandledScreen();
                    Msg.sendRaw(pl, Msg.suggest("§b[/team war <team>]", "/team war ", ""));
                }
            });
            Btn motd = Btn.of(Items.NAME_TAG).name(Msg.tr("team.menu.motd"))
                    .line(t.motd.isEmpty() ? Msg.tr("team.menu.motd-none") : "§f" + t.motd.replace('&', '§'));
            if (me.atLeast(Team.Role.OFFICER)) {
                motd.left(Msg.tr("team.menu.motd-edit"));
            }
            menu.set(29, motd.build(), null, (pl, c) -> {
                if (t.role(pl.getUuid()).atLeast(Team.Role.OFFICER)) {
                    pl.closeHandledScreen();
                    Msg.sendRaw(pl, Msg.suggest("§b[/team motd <text>]", "/team motd ", ""));
                }
            });
            if (me == Team.Role.LEADER) {
                menu.set(31, Btn.of(Items.GOLDEN_SWORD).name(Msg.tr("team.menu.allyfire")).desc(Msg.tr("team.menu.allyfire-desc"))
                        .onOff(t.allyFire).build(), null, (pl, c) -> {
                    t.allyFire = !t.allyFire;
                    say(pl, Result.OK, t.allyFire ? "team.allyfire-on" : "team.allyfire-off");
                    menu.refresh();
                });
                menu.set(33, Btn.of(Items.MAP).name(Msg.tr("team.menu.mapshare")).desc(Msg.tr("team.menu.mapshare-desc"))
                        .onOff(t.shareMap).build(), null, (pl, c) -> {
                    t.shareMap = !t.shareMap;
                    say(pl, Result.OK, t.shareMap ? "team.mapshare-on" : "team.mapshare-off");
                    menu.refresh();
                });
            }
        });
        m.open(p);
    }

    /** Allies and requests; officers accept requests or end alliances here. */
    static void allies(ServerPlayerEntity p, Menu parent) {
        Menu m = Menu.std(Theme.Category.PLAYER, 5, Msg.trFor(p, "team.menu.title"), Msg.trFor(p, "team.menu.allies"));
        m.parent(parent);
        m.renderer(menu -> {
            Team t = Teams.tm().teamOf(p.getUuid());
            if (t == null) {
                p.closeHandledScreen();
                return;
            }
            boolean officer = t.role(p.getUuid()).atLeast(Team.Role.OFFICER);
            int i = 0;
            for (String id : new java.util.ArrayList<>(t.allies)) {
                Team o = Teams.tm().get(id);
                if (o == null || i >= GRID.length) {
                    continue;
                }
                Btn b = Btn.of(Items.CYAN_BANNER).name(Teams.tagText(o) + " §f" + o.name).line(Msg.tr("team.menu.ally-now"));
                menu.set(GRID[i++] - 9, (officer ? b.right(Msg.tr("team.menu.unally")) : b).build(), null, (pl, c) -> {
                    if (officer && c.isRight()) {
                        Confirm.open(pl, Theme.Category.PLAYER, Msg.trFor(pl, "team.menu.unally"), o.name, new ItemStack(Items.BARRIER),
                                () -> Mc.run(pl, "team unally " + o.name));
                    }
                });
            }
            for (String id : new java.util.ArrayList<>(t.allyRequests)) {
                Team o = Teams.tm().get(id);
                if (o == null || i >= GRID.length) {
                    continue;
                }
                Btn b = Btn.of(Items.LIME_BANNER).name(Teams.tagText(o) + " §f" + o.name).line(Msg.tr("team.menu.ally-request"));
                menu.set(GRID[i++] - 9, (officer ? b.left(Msg.tr("team.accept")) : b).build(), null, (pl, c) -> {
                    if (officer) {
                        Mc.run(pl, "team ally " + o.name);
                        menu.refresh();
                    }
                });
            }
            if (officer) {
                menu.set(40, Btn.of(Items.WRITABLE_BOOK).name(Msg.tr("team.menu.ally-ask")).desc(Msg.tr("team.menu.ally-ask-desc")).build(), null, (pl, c) -> {
                    pl.closeHandledScreen();
                    Msg.sendRaw(pl, Msg.suggest("§b[/team ally <team>]", "/team ally ", ""));
                });
            }
        });
        m.open(p);
    }
}
