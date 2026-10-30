package com.vylorq.anticheat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.team.Team;
import com.vylorq.anticheat.core.team.TeamManager;
import com.vylorq.anticheat.core.team.TeamManager.Result;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.feature.Teams;
import com.vylorq.anticheat.gui.TeamMenu;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ChunkPos;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.literal;

/** /team (everything about teams) and the announcement commands /announce and /notify. */
public final class TeamCommands {
    private TeamCommands() {
    }

    static final List<String> COLORS = List.of("aqua", "blue", "dark_aqua", "dark_blue", "dark_green", "dark_purple", "dark_red",
            "gold", "gray", "green", "light_purple", "red", "white", "yellow");

    private static ServerPlayerEntity self(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p != null) {
            Teams.remember(p);
        }
        if (p == null) {
            Msg.err(ctx.getSource(), "general.players-only");
        } else if (!Teams.enabled()) {
            Msg.err(ctx.getSource(), "team.disabled");
            return null;
        }
        return p;
    }

    /** Tells the player how it went. @return 1 if OK */
    static int result(ServerCommandSource src, Result r, String okKey, Object... args) {
        if (r == Result.OK) {
            if (okKey != null) {
                Msg.ok(src, okKey, args);
            }
            Ac.markDirty("teams");
            Teams.syncTags();
            return 1;
        }
        Msg.err(src, "team.r." + r.name().toLowerCase(Locale.ROOT), args);
        return 0;
    }

    private static TeamManager tm() {
        return Teams.tm();
    }

    private static void tellTeam(Team t, String key, Object... args) {
        for (UUID m : t.members) {
            ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(m);
            if (o != null) {
                Msg.send(o, key, args);
            }
        }
    }

    private static int withPlayer(CommandContext<ServerCommandSource> ctx, PlayerAction a) {
        ServerPlayerEntity p = self(ctx);
        if (p == null) {
            return 0;
        }
        UUID target = Args.known(ctx.getSource(), Args.str(ctx, "player"));
        if (target == null) {
            return 0;
        }
        return a.run(p, target, Args.nameOf(target, Args.str(ctx, "player")));
    }

    interface PlayerAction {
        int run(ServerPlayerEntity p, UUID target, String name);
    }

    /** Takes a command out of the tree (Minecraft's own /team would mix with ours). */
    @SuppressWarnings("unchecked")
    static void removeRoot(CommandDispatcher<ServerCommandSource> d, String name) {
        try {
            for (String field : new String[]{"children", "literals", "arguments"}) {
                java.lang.reflect.Field f = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField(field);
                f.setAccessible(true);
                ((java.util.Map<String, ?>) f.get(d.getRoot())).remove(name);
            }
        } catch (ReflectiveOperationException e) {
            Ac.LOG.warn("Could not replace /{}", name, e);
        }
    }

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        // Vanilla /team (scoreboard teams for map makers) is replaced by player teams.
        removeRoot(d, "team");
        d.register(literal("team")
                .executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null) {
                        TeamMenu.open(p);
                    }
                    return 1;
                })
                .then(literal("create").then(Args.word("name")
                        .executes(ctx -> create(ctx, null))
                        .then(Args.word("tag").executes(ctx -> create(ctx, Args.str(ctx, "tag"))))))
                .then(literal("invite").then(Args.player("player").executes(ctx -> withPlayer(ctx, (p, target, name) -> {
                    Result r = tm().invite(p.getUuid(), target, Teams.limits());
                    if (r == Result.OK) {
                        Team t = tm().teamOf(p.getUuid());
                        ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(target);
                        if (o != null) {
                            MutableText m = Msg.prefixed(Msg.trFor(o, "team.invited-you", Teams.tagText(t) + " §f" + t.name, p.getGameProfile().name()));
                            m.append(" ").append(Msg.button("§a[" + Msg.trFor(o, "team.join") + "]", "/team join " + t.name, ""));
                            o.sendMessage(m);
                            com.vylorq.anticheat.ui.BedrockPrompt.ask(o, Msg.trFor(o, "team.menu.title"),
                                    Msg.trFor(o, "team.invited-you", Teams.tagText(t) + " " + t.name, p.getGameProfile().name()),
                                    List.of(Msg.trFor(o, "team.join")), List.of("team join " + t.name));
                        }
                    }
                    return result(ctx.getSource(), r, "team.invited", name);
                }))))
                .then(literal("join").then(Args.word("team").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    Result r = tm().join(p.getUuid(), Args.str(ctx, "team"), Teams.limits());
                    if (r == Result.OK) {
                        Team t = tm().teamOf(p.getUuid());
                        tellTeam(t, "team.joined", p.getGameProfile().name());
                    }
                    return result(ctx.getSource(), r, null, Args.str(ctx, "team"));
                })))
                .then(literal("leave").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    Team t = tm().teamOf(p.getUuid());
                    Result r = tm().leave(p.getUuid());
                    if (r == Result.OK && t != null) {
                        tellTeam(t, "team.left", p.getGameProfile().name());
                    }
                    return result(ctx.getSource(), r, "team.you-left");
                }))
                .then(literal("kick").then(Args.player("player").executes(ctx -> withPlayer(ctx, (p, target, name) -> {
                    Team t = tm().teamOf(p.getUuid());
                    Result r = tm().kick(p.getUuid(), target);
                    if (r == Result.OK) {
                        tellTeam(t, "team.kicked", name);
                        ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(target);
                        if (o != null) {
                            Msg.send(o, "team.you-were-kicked", t.name);
                        }
                    }
                    return result(ctx.getSource(), r, null, name);
                }))))
                .then(literal("promote").then(Args.player("player").executes(ctx -> withPlayer(ctx, (p, target, name) -> {
                    Result r = tm().setOfficer(p.getUuid(), target, true);
                    if (r == Result.OK) {
                        tellTeam(tm().teamOf(p.getUuid()), "team.promoted", name);
                    }
                    return result(ctx.getSource(), r, null, name);
                }))))
                .then(literal("demote").then(Args.player("player").executes(ctx -> withPlayer(ctx, (p, target, name) -> {
                    Result r = tm().setOfficer(p.getUuid(), target, false);
                    if (r == Result.OK) {
                        tellTeam(tm().teamOf(p.getUuid()), "team.demoted", name);
                    }
                    return result(ctx.getSource(), r, null, name);
                }))))
                .then(literal("leader").then(Args.player("player").executes(ctx -> withPlayer(ctx, (p, target, name) -> {
                    Result r = tm().handOver(p.getUuid(), target);
                    if (r == Result.OK) {
                        tellTeam(tm().teamOf(p.getUuid()), "team.new-leader", name);
                    }
                    return result(ctx.getSource(), r, null, name);
                }))))
                .then(literal("disband").executes(ctx -> {
                    Msg.err(ctx.getSource(), "team.disband-confirm");
                    return 0;
                }).then(literal("confirm").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    Team t = tm().teamOf(p.getUuid());
                    Result r = tm().disband(p.getUuid());
                    if (r == Result.OK) {
                        Teams.forgetVault(t, p);
                        tellTeam(t, "team.disbanded", t.name);
                    }
                    return result(ctx.getSource(), r, null);
                })))
                .then(literal("info").executes(ctx -> info(ctx, null))
                        .then(Args.word("team").executes(ctx -> info(ctx, Args.str(ctx, "team")))))
                .then(literal("list").executes(ctx -> {
                    var all = tm().list();
                    if (all.isEmpty()) {
                        Msg.ok(ctx.getSource(), "team.none-yet");
                    }
                    for (Team t : all) {
                        String line = Teams.tagText(t) + " §f" + t.name + " §7- " + t.members.size() + " "
                                + Msg.tr("team.members") + ", " + t.chunks.size() + " " + Msg.tr("team.chunks");
                        ctx.getSource().sendFeedback(() -> Text.literal(line), false);
                    }
                    return 1;
                }))
                .then(literal("claim").executes(TeamCommands::claim))
                .then(literal("unclaim").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    ChunkPos c = p.getChunkPos();
                    return result(ctx.getSource(), tm().unclaim(p.getUuid(), Mc.worldId(p.getEntityWorld()), c.x, c.z), "team.unclaimed");
                }))
                .then(literal("sethome").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    Team t = tm().teamOf(p.getUuid());
                    if (t == null) {
                        return result(ctx.getSource(), Result.NOT_IN_TEAM, null);
                    }
                    if (!t.role(p.getUuid()).atLeast(Team.Role.OFFICER)) {
                        return result(ctx.getSource(), Result.NOT_ALLOWED, null);
                    }
                    if (!t.chunks.isEmpty() && Teams.at(p.getEntityWorld(), p.getBlockPos()) != t) {
                        Msg.err(ctx.getSource(), "team.home-in-territory");
                        return 0;
                    }
                    t.homeWorld = Mc.worldId(p.getEntityWorld());
                    t.homeX = p.getX();
                    t.homeY = p.getY();
                    t.homeZ = p.getZ();
                    t.homeYaw = p.getYaw();
                    return result(ctx.getSource(), Result.OK, "team.home-set");
                }))
                .then(literal("home").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    return home(p) ? 1 : 0;
                }))
                .then(literal("chat").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    if (tm().teamOf(p.getUuid()) == null) {
                        return result(ctx.getSource(), Result.NOT_IN_TEAM, null);
                    }
                    Msg.ok(ctx.getSource(), Teams.toggleChat(p) ? "team.chat-on" : "team.chat-off");
                    return 1;
                }).then(CommandManager.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    if (!Teams.teamChat(p, StringArgumentType.getString(ctx, "message"))) {
                        return result(ctx.getSource(), Result.NOT_IN_TEAM, null);
                    }
                    return 1;
                })))
                .then(literal("color").then(Args.word("color").suggests((c, b) -> {
                    COLORS.forEach(b::suggest);
                    return b.buildFuture();
                }).executes(ctx -> leaderSet(ctx, t -> {
                    String c = Args.str(ctx, "color").toLowerCase(Locale.ROOT);
                    if (!COLORS.contains(c)) {
                        Msg.err(ctx.getSource(), "team.bad-color", String.join(", ", COLORS));
                        return false;
                    }
                    t.color = c;
                    return true;
                }))))
                .then(literal("tag").then(Args.word("tag").executes(ctx -> leaderSet(ctx, t -> {
                    String tag = Args.str(ctx, "tag").toUpperCase(Locale.ROOT);
                    if (!TeamManager.validTag(tag)) {
                        Msg.err(ctx.getSource(), "team.r.bad_name");
                        return false;
                    }
                    for (Team o : tm().list()) {
                        if (o != t && o.tag.equalsIgnoreCase(tag)) {
                            Msg.err(ctx.getSource(), "team.r.tag_taken");
                            return false;
                        }
                    }
                    t.tag = tag;
                    return true;
                }))))
                .then(literal("open").executes(ctx -> leaderSet(ctx, t -> {
                    t.open = !t.open;
                    Msg.ok(ctx.getSource(), t.open ? "team.now-open" : "team.now-closed");
                    return true;
                })))
                .then(literal("pvp").executes(ctx -> leaderSet(ctx, t -> {
                    t.friendlyFire = !t.friendlyFire;
                    Msg.ok(ctx.getSource(), t.friendlyFire ? "team.ff-on" : "team.ff-off");
                    return true;
                })))
                .then(literal("vault").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null || mine(ctx, p, null) == null) return 0;
                    Teams.openVault(p);
                    return 1;
                }))
                .then(literal("ally").then(Args.word("team").executes(ctx -> ally(ctx, true))))
                .then(literal("unally").then(Args.word("team").executes(ctx -> ally(ctx, false))))
                .then(literal("allies").executes(TeamCommands::allies))
                .then(literal("where").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    where(p);
                    return 1;
                }))
                .then(literal("map").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null || mine(ctx, p, null) == null) return 0;
                    TeamMenu.map(p, null);
                    return 1;
                }))
                .then(literal("mapshare").executes(ctx -> leaderSet(ctx, t -> {
                    t.shareMap = !t.shareMap;
                    tellTeam(t, t.shareMap ? "team.mapshare-on" : "team.mapshare-off");
                    return true;
                })))
                .then(literal("ping").executes(ctx -> ping(ctx, null))
                        .then(CommandManager.argument("note", StringArgumentType.greedyString())
                                .executes(ctx -> ping(ctx, StringArgumentType.getString(ctx, "note")))))
                .then(literal("border").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    Msg.ok(ctx.getSource(), Teams.toggleBorder(p) ? "team.border-on" : "team.border-off");
                    return 1;
                }))
                .then(literal("motd").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    Team t = mine(ctx, p, null);
                    if (t == null) return 0;
                    Msg.ok(ctx.getSource(), t.motd.isEmpty() ? "team.motd-none" : "team.motd-is", t.motd.replace('&', '§'));
                    return 1;
                }).then(literal("clear").executes(ctx -> setMotd(ctx, "")))
                        .then(CommandManager.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> setMotd(ctx, StringArgumentType.getString(ctx, "text")))))
                .then(literal("top").executes(ctx -> {
                    top(ctx.getSource(), "land");
                    return 1;
                }).then(Args.word("by").suggests((c, b) -> {
                    List.of("land", "members", "kills", "level", "wars").forEach(b::suggest);
                    return b.buildFuture();
                }).executes(ctx -> {
                    String by = Args.str(ctx, "by").toLowerCase(Locale.ROOT);
                    top(ctx.getSource(), List.of("members", "kills", "level", "wars").contains(by) ? by : "land");
                    return 1;
                })))
                .then(literal("allypvp").executes(ctx -> leaderSet(ctx, t -> {
                    t.allyFire = !t.allyFire;
                    tellTeam(t, t.allyFire ? "team.allyfire-on" : "team.allyfire-off");
                    return true;
                })))
                .then(literal("level").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    Team t = mine(ctx, p, null);
                    if (t == null) return 0;
                    level(ctx.getSource(), t);
                    return 1;
                }))
                .then(literal("war").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    Team t = mine(ctx, p, null);
                    if (t == null) return 0;
                    TeamManager.War w = tm().warOf(t.id);
                    if (w == null) {
                        Msg.ok(ctx.getSource(), "team.war.none", t.warsWon, t.warsLost);
                    } else {
                        Msg.ok(ctx.getSource(), "team.war.status", Teams.warLine(w),
                                com.vylorq.anticheat.core.util.Durations.format(Math.max(0, w.endsAt - System.currentTimeMillis())));
                    }
                    return 1;
                }).then(Args.word("team").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    Teams.declareWar(p, Args.str(ctx, "team"));
                    return 1;
                })))
                .then(literal("bank").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    Team t = mine(ctx, p, null);
                    if (t == null) return 0;
                    bank(ctx.getSource(), t);
                    return 1;
                }).then(literal("deposit").then(CommandManager.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    int n = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount");
                    if (tm().teamOf(p.getUuid()) == null) {
                        return result(ctx.getSource(), Result.NOT_IN_TEAM, null);
                    }
                    if (!com.vylorq.anticheat.feature.Markets.takeMoney(p, n)) {
                        Msg.err(ctx.getSource(), "market.no-money", com.vylorq.anticheat.feature.Markets.money(n));
                        return 0;
                    }
                    Result r = tm().deposit(p.getUuid(), p.getGameProfile().name(), n);
                    if (r == Result.OK) {
                        tellTeam(tm().teamOf(p.getUuid()), "team.bank.deposited", p.getGameProfile().name(), com.vylorq.anticheat.feature.Markets.money(n));
                    } else {
                        com.vylorq.anticheat.feature.Markets.giveMoney(p, n);
                    }
                    return result(ctx.getSource(), r, null);
                }))).then(literal("withdraw").then(CommandManager.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    int n = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount");
                    Result r = tm().withdraw(p.getUuid(), p.getGameProfile().name(), n);
                    if (r == Result.OK) {
                        com.vylorq.anticheat.feature.Markets.giveMoney(p, n);
                        tellTeam(tm().teamOf(p.getUuid()), "team.bank.withdrew", p.getGameProfile().name(), com.vylorq.anticheat.feature.Markets.money(n));
                    }
                    return result(ctx.getSource(), r, null);
                }))))
                .then(literal("admin").requires(s -> Perms.visible(s, Perm.MANAGE_ADMINS))
                        .then(literal("disband").then(Args.word("team").executes(ctx -> {
                            if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
                            Team t = tm().get(Args.str(ctx, "team"));
                            if (t == null) {
                                return result(ctx.getSource(), Result.NO_SUCH_TEAM, null, Args.str(ctx, "team"));
                            }
                            tellTeam(t, "team.disbanded", t.name);
                            tm().removeTeam(t);
                            Teams.forgetVault(t, ctx.getSource().getPlayer());
                            Staff.log(ctx.getSource().getPlayer(), "team-admin-disband", null, t.name, "");
                            return result(ctx.getSource(), Result.OK, "team.admin-disbanded", t.name);
                        })))
                        .then(literal("unclaim").executes(ctx -> {
                            if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                            if (p == null) return 0;
                            ChunkPos c = p.getChunkPos();
                            Team t = tm().adminUnclaim(Mc.worldId(p.getEntityWorld()), c.x, c.z);
                            if (t == null) {
                                Msg.err(ctx.getSource(), "team.r.chunk_not_yours");
                                return 0;
                            }
                            Staff.log(p, "team-admin-unclaim", null, t.name, c.toString());
                            return result(ctx.getSource(), Result.OK, "team.unclaimed");
                        }))));
        d.register(literal("tc").then(CommandManager.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p == null) return 0;
            if (!Teams.teamChat(p, StringArgumentType.getString(ctx, "message"))) {
                return result(ctx.getSource(), Result.NOT_IN_TEAM, null);
            }
            return 1;
        })));

        d.register(literal("tca").then(CommandManager.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p == null) return 0;
            if (!Teams.allyChat(p, StringArgumentType.getString(ctx, "message"))) {
                return result(ctx.getSource(), Result.NOT_IN_TEAM, null);
            }
            return 1;
        })));

        // ---- Announcements ----
        d.register(literal("announce").requires(s -> Perms.visible(s, Perm.EVENTS))
                .then(CommandManager.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.EVENTS)) return 0;
                    String msg = StringArgumentType.getString(ctx, "message");
                    showOnScreen(Ac.server().getPlayerManager().getPlayerList(), "announce.title", msg);
                    Staff.log(ctx.getSource().getPlayer(), "announce", null, null, msg);
                    return 1;
                })));
        d.register(literal("notify").requires(s -> Perms.visible(s, Perm.EVENTS))
                .then(CommandManager.argument("players", EntityArgumentType.players())
                        .then(CommandManager.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                            if (!Perms.check(ctx.getSource(), Perm.EVENTS)) return 0;
                            Collection<ServerPlayerEntity> targets;
                            try {
                                targets = EntityArgumentType.getPlayers(ctx, "players");
                            } catch (CommandSyntaxException e) {
                                Msg.err(ctx.getSource(), "general.player-not-found");
                                return 0;
                            }
                            String msg = StringArgumentType.getString(ctx, "message");
                            showOnScreen(targets, "announce.message-title", msg);
                            Msg.ok(ctx.getSource(), "announce.sent", targets.size());
                            Staff.log(ctx.getSource().getPlayer(), "notify", null, targets.size() + " players", msg);
                            return 1;
                        }))));
    }

    /**
     * Puts a message in the middle of the screen (and in chat). "Title|Subtitle" picks both lines; &amp; colour
     * codes work.
     */
    public static void showOnScreen(Collection<ServerPlayerEntity> players, String defaultTitleKey, String message) {
        String m = message.replace('&', '§');
        String[] parts = m.split("\\|", 2);
        for (ServerPlayerEntity p : players) {
            String title = parts.length == 2 ? parts[0] : Msg.trFor(p, defaultTitleKey);
            String sub = parts.length == 2 ? parts[1] : parts[0];
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 100, 20));
            p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(sub)));
            p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title).formatted(Formatting.GOLD)));
            p.sendMessage(Text.literal("§6§l" + title.replaceAll("§.", "") + " §r§f" + sub));
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket(SoundEvents.BLOCK_NOTE_BLOCK_PLING,
                    SoundCategory.MASTER, p.getX(), p.getY(), p.getZ(), 1f, 1.2f, p.getRandom().nextLong()));
        }
    }

    private static int ping(CommandContext<ServerCommandSource> ctx, String note) {
        ServerPlayerEntity p = self(ctx);
        if (p == null) return 0;
        if (!Teams.ping(p, note)) {
            return result(ctx.getSource(), Result.NOT_IN_TEAM, null);
        }
        return 1;
    }

    private static int setMotd(CommandContext<ServerCommandSource> ctx, String text) {
        ServerPlayerEntity p = self(ctx);
        if (p == null) return 0;
        Team t = mine(ctx, p, Team.Role.OFFICER);
        if (t == null) return 0;
        t.motd = text.length() > 200 ? text.substring(0, 200) : text;
        if (!t.motd.isEmpty()) {
            tellTeam(t, "team.motd-changed", p.getGameProfile().name());
        }
        return result(ctx.getSource(), Result.OK, t.motd.isEmpty() ? "team.motd-cleared" : "team.motd-set");
    }

    private static int create(CommandContext<ServerCommandSource> ctx, String tag) {
        ServerPlayerEntity p = self(ctx);
        if (p == null) {
            return 0;
        }
        String name = Args.str(ctx, "name");
        Result r = tm().create(name, tag, p.getUuid());
        if (r == Result.OK) {
            for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
                Msg.send(o, "team.founded", p.getGameProfile().name(), name);
            }
        }
        return result(ctx.getSource(), r, "team.created", name);
    }

    private static int claim(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = self(ctx);
        if (p == null) {
            return 0;
        }
        ChunkPos c = p.getChunkPos();
        if (!Teams.claimable((ServerWorld) p.getEntityWorld(), c)) {
            Msg.err(ctx.getSource(), "team.not-claimable");
            return 0;
        }
        Team t = tm().teamOf(p.getUuid());
        Result r = tm().claim(p.getUuid(), Mc.worldId(p.getEntityWorld()), c.x, c.z, Teams.limits());
        return result(ctx.getSource(), r, "team.claimed", t == null ? 0 : t.chunks.size(),
                t == null ? 0 : tm().chunkLimit(t, Teams.limits()));
    }

    private static int info(CommandContext<ServerCommandSource> ctx, String name) {
        Team t = name == null ? (ctx.getSource().getPlayer() == null ? null : tm().teamOf(ctx.getSource().getPlayer().getUuid())) : tm().get(name);
        if (t == null) {
            return result(ctx.getSource(), name == null ? Result.NOT_IN_TEAM : Result.NO_SUCH_TEAM, null, name);
        }
        StringBuilder members = new StringBuilder();
        for (UUID m : t.members) {
            Team.Role r = t.role(m);
            members.append(r == Team.Role.LEADER ? "§6★" : r == Team.Role.OFFICER ? "§e☆" : "§7").append(Args.nameOf(m, "?")).append(" ");
        }
        String head = Teams.tagText(t) + " §f§l" + t.name;
        String line = Msg.tr("team.info", t.members.size(), t.chunks.size(), tm().chunkLimit(t, Teams.limits()),
                Msg.tr(t.open ? "team.open" : "team.invite-only"));
        ctx.getSource().sendFeedback(() -> Text.literal(head), false);
        ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        ctx.getSource().sendFeedback(() -> Text.literal(members.toString().trim()), false);
        return 1;
    }

    /** Leader-only settings. */
    private static int leaderSet(CommandContext<ServerCommandSource> ctx, java.util.function.Predicate<Team> change) {
        ServerPlayerEntity p = self(ctx);
        if (p == null) {
            return 0;
        }
        Team t = tm().teamOf(p.getUuid());
        if (t == null) {
            return result(ctx.getSource(), Result.NOT_IN_TEAM, null);
        }
        if (t.role(p.getUuid()) != Team.Role.LEADER) {
            return result(ctx.getSource(), Result.NOT_ALLOWED, null);
        }
        if (!change.test(t)) {
            return 0;
        }
        return result(ctx.getSource(), Result.OK, "team.updated");
    }

    /** Where the team home is (no teleport: you walk there). */
    public static boolean home(ServerPlayerEntity p) {
        Team t = tm().teamOf(p.getUuid());
        if (t == null || !t.hasHome()) {
            Msg.send(p, t == null ? "team.r.not_in_team" : "team.no-home");
            return false;
        }
        String where = (int) Math.floor(t.homeX) + " " + (int) Math.floor(t.homeY) + " " + (int) Math.floor(t.homeZ);
        if (Mc.worldId(p.getEntityWorld()).equals(t.homeWorld)) {
            int dist = (int) Math.sqrt(p.squaredDistanceTo(t.homeX, t.homeY, t.homeZ));
            Msg.send(p, "team.home-at", where, dist + "m");
        } else {
            Msg.send(p, "team.home-at", where, t.homeWorld);
        }
        return true;
    }

    private static Team mine(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity p, Team.Role need) {
        Team t = tm().teamOf(p.getUuid());
        if (t == null) {
            result(ctx.getSource(), Result.NOT_IN_TEAM, null);
            return null;
        }
        if (need != null && !t.role(p.getUuid()).atLeast(need)) {
            result(ctx.getSource(), Result.NOT_ALLOWED, null);
            return null;
        }
        return t;
    }

    private static int ally(CommandContext<ServerCommandSource> ctx, boolean add) {
        ServerPlayerEntity p = self(ctx);
        if (p == null) {
            return 0;
        }
        String other = Args.str(ctx, "team");
        Team t = tm().teamOf(p.getUuid());
        Team o = tm().get(other);
        Result r = add ? tm().ally(p.getUuid(), other, Teams.cfgAllies()) : tm().unally(p.getUuid(), other);
        if (o != null && t != null) {
            if (r == Result.OK) {
                String key = add ? "team.allied" : "team.unallied";
                tellTeam(t, key, o.name);
                tellTeam(o, key, t.name);
                return result(ctx.getSource(), r, null);
            }
            if (r == Result.ALLY_REQUESTED) {
                for (UUID m : o.members) {
                    ServerPlayerEntity x = Ac.server().getPlayerManager().getPlayer(m);
                    if (x != null && o.role(m).atLeast(Team.Role.OFFICER)) {
                        MutableText msg = Msg.prefixed(Msg.trFor(x, "team.ally-asked", Teams.tagText(t) + " §f" + t.name));
                        msg.append(" ").append(Msg.button("§a[" + Msg.trFor(x, "team.accept") + "]", "/team ally " + t.name, ""));
                        x.sendMessage(msg);
                        com.vylorq.anticheat.ui.BedrockPrompt.ask(x, Msg.trFor(x, "team.menu.allies"),
                                Msg.trFor(x, "team.ally-asked", Teams.tagText(t) + " " + t.name),
                                List.of(Msg.trFor(x, "team.accept")), List.of("team ally " + t.name));
                    }
                }
                Msg.ok(ctx.getSource(), "team.ally-sent", o.name);
                Ac.markDirty("teams");
                return 1;
            }
        }
        return result(ctx.getSource(), r, null, other);
    }

    private static int allies(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = self(ctx);
        if (p == null) return 0;
        Team t = mine(ctx, p, null);
        if (t == null) return 0;
        Msg.ok(ctx.getSource(), "team.allies-list", t.allies.isEmpty() ? "-" : names(t.allies));
        if (!t.allyRequests.isEmpty()) {
            Msg.ok(ctx.getSource(), "team.ally-requests", names(t.allyRequests));
        }
        return 1;
    }

    static String names(java.util.Set<String> ids) {
        StringBuilder b = new StringBuilder();
        for (String id : ids) {
            Team o = tm().get(id);
            if (o != null) {
                b.append(b.length() == 0 ? "" : "§7, ").append(Teams.tagText(o)).append(" §f").append(o.name);
            }
        }
        return b.length() == 0 ? "-" : b.toString();
    }

    /** Teammates' places and health. */
    public static void where(ServerPlayerEntity p) {
        Team t = tm().teamOf(p.getUuid());
        if (t == null) {
            Msg.send(p, "team.r.not_in_team");
            return;
        }
        int n = 0;
        for (UUID m : t.members) {
            ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(m);
            if (o == null || o == p) {
                continue;
            }
            n++;
            var b = o.getBlockPos();
            String place = o.getEntityWorld() == p.getEntityWorld()
                    ? b.getX() + " " + b.getY() + " " + b.getZ() + " §7(" + (int) Math.sqrt(o.squaredDistanceTo(p)) + "m)"
                    : Mc.worldId(o.getEntityWorld());
            p.sendMessage(Text.literal("§f" + o.getGameProfile().name() + " §c❤" + (int) Math.ceil(o.getHealth()) + " §7» §e" + place));
        }
        if (n == 0) {
            Msg.send(p, "team.where-none");
        }
    }

    /** Level, XP to the next level and what the level gives. */
    public static void level(ServerCommandSource src, Team t) {
        int lvl = TeamManager.level(t.xp);
        String next = lvl >= TeamManager.MAX_LEVEL ? Msg.tr("team.level.max") : Msg.tr("team.level.next", TeamManager.xpFor(lvl + 1) - t.xp, lvl + 1);
        Msg.ok(src, "team.level.info", lvl, t.xp, next);
        Msg.ok(src, "team.level-perks", tm().chunkLimit(t, Teams.limits()), Teams.vaultSize(t));
    }

    /** Balance and the last few deposits and withdrawals. */
    public static void bank(ServerCommandSource src, Team t) {
        Msg.ok(src, "team.bank.balance", com.vylorq.anticheat.feature.Markets.money((int) Math.min(Integer.MAX_VALUE, t.bank)));
        int from = Math.max(0, t.bankLog.size() - 8);
        for (int i = t.bankLog.size() - 1; i >= from; i--) {
            String[] parts = t.bankLog.get(i).split("\\|", 3);
            if (parts.length < 3) {
                continue;
            }
            String when = new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(Long.parseLong(parts[0])));
            boolean in = parts[2].startsWith("+");
            int amount = Integer.parseInt(parts[2].substring(1));
            String line = "§7" + when + " §f" + parts[1] + " " + (in ? "§a+" : "§c-") + com.vylorq.anticheat.feature.Markets.money(amount);
            src.sendFeedback(() -> Text.literal(line), false);
        }
    }

    /** Teams ranked by land, members or kills. */
    public static void top(ServerCommandSource src, String what) {
        java.util.List<Team> all = new java.util.ArrayList<>(tm().list());
        java.util.Map<String, Long> score = new java.util.HashMap<>();
        for (Team t : all) {
            long v = switch (what) {
                case "members" -> t.members.size();
                case "kills" -> {
                    long k = 0;
                    for (UUID m : t.members) {
                        var row = com.vylorq.anticheat.feature.PlayerStats.of(m);
                        k += row == null ? 0 : row.playerKills();
                    }
                    yield k;
                }
                case "level" -> t.xp;
                case "wars" -> t.warsWon;
                default -> t.chunks.size();
            };
            score.put(t.id, v);
        }
        all.sort((a, b) -> Long.compare(score.get(b.id), score.get(a.id)));
        Msg.ok(src, "team.top-head", Msg.tr("team.top." + what));
        if (all.isEmpty()) {
            Msg.ok(src, "team.none-yet");
        }
        for (int i = 0; i < Math.min(10, all.size()); i++) {
            Team t = all.get(i);
            String line = "§6#" + (i + 1) + " " + Teams.tagText(t) + " §f" + t.name + " §7- §e" + score.get(t.id);
            src.sendFeedback(() -> Text.literal(line), false);
        }
    }
}
