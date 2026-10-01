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
                .then(literal("admin").requires(s -> Perms.visible(s, Perm.MANAGE_ADMINS))
                        .then(literal("disband").then(Args.word("team").executes(ctx -> {
                            if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
                            Team t = tm().get(Args.str(ctx, "team"));
                            if (t == null) {
                                return result(ctx.getSource(), Result.NO_SUCH_TEAM, null, Args.str(ctx, "team"));
                            }
                            tellTeam(t, "team.disbanded", t.name);
                            tm().removeTeam(t);
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
            p.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.MASTER, 1f, 1.2f);
        }
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

    public static boolean home(ServerPlayerEntity p) {
        Team t = tm().teamOf(p.getUuid());
        if (t == null || !t.hasHome()) {
            Msg.send(p, t == null ? "team.r.not_in_team" : "team.no-home");
            return false;
        }
        ServerWorld w = Mc.world(Ac.server(), t.homeWorld);
        if (w == null) {
            Msg.send(p, "team.no-home");
            return false;
        }
        Mc.teleport(p, w, t.homeX, t.homeY, t.homeZ, t.homeYaw, 0);
        Msg.send(p, "team.home-tp");
        return true;
    }
}
