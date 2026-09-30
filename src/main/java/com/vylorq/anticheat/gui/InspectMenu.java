package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.claims.Claim;
import com.vylorq.anticheat.core.deaths.DeathRecord;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.detect.Watchlist;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.perm.PermissionPolicy;
import com.vylorq.anticheat.core.review.ReviewCase;
import com.vylorq.anticheat.core.staff.Punishment;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Location;
import com.vylorq.anticheat.core.util.Vec3;
import com.vylorq.anticheat.feature.DetectionListener;
import com.vylorq.anticheat.feature.Deaths;
import com.vylorq.anticheat.feature.Joins;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.feature.StaffTools;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Icons;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** /inspect (section 14). Every page re-checks permissions on click; every action is logged. */
public final class InspectMenu {
    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private InspectMenu() {
    }

    private static ServerPlayerEntity online(UUID id) {
        return Ac.server().getPlayerManager().getPlayer(id);
    }

    private static String name(UUID id) {
        ServerPlayerEntity p = online(id);
        if (p != null) {
            return p.getGameProfile().name();
        }
        String n = Ac.get().joins.name(id);
        return n == null ? id.toString() : n;
    }

    private static String date(long t) {
        return t <= 0 ? "-" : DATE.format(new Date(t));
    }

    public static void open(ServerPlayerEntity admin, UUID target) {
        Staff.log(admin, "inspect", target, name(target), "");
        overview(admin, target);
    }

    /** Standard bottom row of page buttons. */
    private static void nav(Menu m, UUID target, String current) {
        int b = 45;
        for (int i = b; i < 54; i++) {
            m.icon(i, Icons.filler());
        }
        m.set(b, Icons.of(Items.PLAYER_HEAD, "§fOverview"), (a, c) -> overview(a, target));
        m.set(b + 1, Icons.of(Items.CHEST, "§fInventory"), (a, c) -> inventory(a, target, false));
        m.set(b + 2, Icons.of(Items.ENDER_CHEST, "§fEnder chest"), (a, c) -> ender(a, target, false));
        m.set(b + 3, Icons.of(Items.POTION, "§fEffects"), (a, c) -> effects(a, target));
        m.set(b + 4, Icons.of(Items.IRON_SWORD, "§fAnti-cheat"), (a, c) -> antiCheat(a, target));
        m.set(b + 5, Icons.of(Items.COMPASS, "§fLocation"), (a, c) -> location(a, target));
        m.set(b + 6, Icons.of(Items.WRITABLE_BOOK, "§fActivity"), (a, c) -> activity(a, target));
        m.set(b + 7, Icons.of(Items.SKELETON_SKULL, "§fDeaths"), (a, c) -> deaths(a, target, 0));
        m.set(b + 8, Icons.of(Items.TRIPWIRE_HOOK, "§fPrivate info", "Owner only by default"), Perm.INSPECT_PRIVATE,
                (a, c) -> privateInfo(a, target));
    }

    // ---- Overview (live) ----

    public static void overview(ServerPlayerEntity admin, UUID target) {
        Menu m = new Menu("§8Inspect: " + name(target), 6).perm(Perm.INSPECT).live();
        m.renderer(menu -> {
            Ac ac = Ac.get();
            ServerPlayerEntity p = online(target);
            PlayerSession s = Ac.sessionOrNull(target);
            int sus = ac.violations.suspicion(target);
            String color = DetectionListener.color(sus);
            List<String> info = new ArrayList<>();
            info.add("§7Platform: §f" + (s != null && s.bedrock ? "Bedrock" : p != null ? "Java" : "offline"));
            info.add("§7Suspicion: " + color + sus);
            info.add("§7Playtime: §f" + Joins.formatPlaytime(target));
            info.add("§7First join: §f" + date(ac.misc.firstJoin.getOrDefault(target, 0L)));
            menu.icon(4, Icons.head(target, name(target), color + name(target), info.toArray(new String[0])));
            if (p != null) {
                ServerWorld w = p.getEntityWorld();
                BlockPos bp = p.getBlockPos();
                String biome = w.getBiome(bp).getKey().map(k -> k.getValue().toString()).orElse("?");
                String looking = "-";
                HitResult hit = p.raycast(8, 1f, false);
                if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
                    looking = Mc.blockId(w.getBlockState(bh.getBlockPos()).getBlock()).replace("minecraft:", "") + " at " + bh.getBlockPos().toShortString();
                }
                menu.icon(19, Icons.of(Items.COMPASS, "§ePosition", Mc.vec(p.getEntityPos()).formatExact(),
                        "§7Dimension: §f" + Mc.worldId(w), "§7Biome: §f" + biome,
                        "§7Chunk: §f" + (bp.getX() >> 4) + ", " + (bp.getZ() >> 4),
                        "§7Facing: §f" + p.getHorizontalFacing().asString() + String.format(" (yaw %.1f, pitch %.1f)", p.getYaw(), p.getPitch()),
                        "§7Looking at: §f" + looking));
                menu.icon(20, Icons.of(Items.GOLDEN_APPLE, "§eStatus",
                        "§7Gamemode: §f" + p.interactionManager.getGameMode().asString(),
                        String.format("§7Health: §f%.1f / %.1f", p.getHealth(), p.getMaxHealth()),
                        "§7Hunger: §f" + p.getHungerManager().getFoodLevel(),
                        "§7XP level: §f" + p.experienceLevel,
                        "§7Ping: §f" + p.networkHandler.getLatency() + " ms"));
            } else {
                Location last = ac.misc.lastLogout.get(target);
                menu.icon(19, Icons.of(Items.COMPASS, "§eLast logout",
                        last == null ? "unknown" : new Vec3(last.x(), last.y(), last.z()).formatExact(),
                        last == null ? "" : "§7Dimension: §f" + last.world()));
            }
            Watchlist.Entry w = ac.watchlist.get(target);
            menu.icon(21, Icons.of(w != null ? Items.ENDER_EYE : Items.ENDER_PEARL, w != null ? "§dWatched" : "§7Not watched",
                    w == null ? "" : "Reason: " + w.reason, w == null ? "" : "Until: " + (w.expiresAt == Durations.PERMANENT ? "permanent" : date(w.expiresAt))));
            // Actions
            if (p != null) {
                menu.set(28, Icons.of(Items.ENDER_EYE, "§bSpectate", "Follow unseen in spectator mode", "/inspect leave to return"), Perm.SPECTATE,
                        (a, c) -> {
                            a.closeHandledScreen();
                            StaffTools.spectate(a, p);
                        });
                boolean invDefault = Ac.get().staff.teleportInvisible(admin.getUuid(), Ac.config().staff.teleportInvisibleByDefault);
                menu.set(29, Icons.of(Items.ENDER_PEARL, "§bTeleport",
                        "Left-click: " + (invDefault ? "§7invisible" : "§fvisible") + " §7(your default)",
                        "Right-click: " + (invDefault ? "§fvisible" : "§7invisible"),
                        "Shift-click: change your default"), Perm.TELEPORT, (a, c) -> {
                    if (c.isShift()) {
                        Ac.get().staff.setTeleportInvisible(a.getUuid(), !invDefault);
                        Ac.markDirty("staff");
                        menu.refresh();
                        return;
                    }
                    boolean invisible = c.isRight() != invDefault;
                    a.closeHandledScreen();
                    StaffTools.teleportTo(a, p.getEntityWorld(), Mc.vec(p.getEntityPos()), invisible, p.getGameProfile().name());
                });
                boolean frozen = ac.staff.isFrozen(target);
                menu.set(30, Icons.of(Items.PACKED_ICE, frozen ? "§bUnfreeze" : "§bFreeze"), Perm.FREEZE, (a, c) -> {
                    if (com.vylorq.anticheat.feature.Punish.allowedOn(a, target)) {
                        StaffTools.setFrozen(a, p, !frozen);
                    }
                    menu.refresh();
                });
            }
            boolean shadowed = ac.shadow.isShadowed(target);
            menu.set(31, Icons.of(Items.BLACK_DYE, shadowed ? "§8Shadow mode: §aON" : "§8Shadow mode: §cOFF",
                    "Their hits do no damage to players", "and their block changes are hidden.", "They are never told."), Perm.SHADOW, (a, c) -> {
                boolean on = ac.shadow.toggle(target);
                Ac.markDirty("shadow");
                Staff.log(a, on ? "shadow-on" : "shadow-off", target, name(target), "");
                menu.refresh();
            });
            menu.set(32, Icons.of(Items.SPYGLASS, w != null ? "§dRemove from watchlist" : "§dAdd to watchlist"), Perm.WATCH, (a, c) -> {
                if (w != null) {
                    ac.watchlist.remove(target, a.getGameProfile().name());
                    Staff.log(a, "watch-remove", target, name(target), "");
                } else {
                    ac.watchlist.add(target, name(target), "Added from /inspect", a.getGameProfile().name(), Durations.PERMANENT, false);
                    Staff.log(a, "watch-add", target, name(target), "from /inspect");
                }
                Ac.markDirty("watchlist");
                menu.refresh();
            });
            ReviewCase open = ac.reviews.openCaseFor(target);
            if (open != null) {
                menu.set(33, Icons.of(Items.BOOK, "§6Open review case #" + open.id), Perm.REVIEW, (a, c) -> ReviewMenu.openCase(a, open.id));
            }
            nav(menu, target, "overview");
        });
        m.open(admin);
    }

    // ---- Inventory / ender chest (live, optional edit mode) ----

    private static Set<Integer> contentSlots() {
        Set<Integer> s = new HashSet<>();
        for (int i = 0; i < 54; i++) {
            if (PlayerInventoryView.isContent(i)) {
                s.add(i);
            }
        }
        return s;
    }

    public static void inventory(ServerPlayerEntity admin, UUID target, boolean edit) {
        if (edit && !Perms.require(admin, Perm.INSPECT_EDIT)) {
            return;
        }
        ServerPlayerEntity p = online(target);
        OfflineInventory offline = p == null ? OfflineInventory.load(target) : null;
        if (p == null && offline == null) {
            Msg.send(admin, "inspect.no-data");
            return;
        }
        Inventory backing = p != null ? p.getInventory() : offline.inventory;
        PlayerInventoryView view = new PlayerInventoryView(backing);
        Menu m = new Menu("§8" + (edit ? "§cEDIT §8" : "") + "Inventory: " + name(target) + (p == null ? " (offline)" : ""), 6)
                .perm(Perm.INSPECT).backedBy(view).live();
        Map<Integer, ItemStack> before = snapshot(view);
        if (edit) {
            m.allowPlayerInventory(true).editable(contentSlots(), (a, slot) -> logEdit(a, target, "inventory", view, before));
        } else {
            m.reserved(contentSlots());
        }
        m.renderer(menu -> {
            for (int i = 41; i < 45; i++) {
                menu.icon(i, PlayerInventoryView.label("§7← armour / offhand"));
            }
            menu.set(44, Icons.of(edit ? Items.LIME_DYE : Items.GRAY_DYE, edit ? "§cEdit mode: ON" : "§7Edit mode: OFF",
                    "Every change is logged"), edit ? Perm.INSPECT : Perm.INSPECT_EDIT, (a, c) -> inventory(a, target, !edit));
            nav(menu, target, "inventory");
        });
        if (offline != null) {
            OfflineInventory.EDITING.put(target, admin.getUuid());
            m.onClose(a -> {
                OfflineInventory.EDITING.remove(target);
                if (edit && offline.save()) {
                    Msg.send(a, "inspect.offline-saved");
                }
            });
        }
        if (edit) {
            Staff.log(admin, "inventory-edit-open", target, name(target), p == null ? "offline" : "online");
        }
        m.open(admin);
    }

    public static void ender(ServerPlayerEntity admin, UUID target, boolean edit) {
        if (edit && !Perms.require(admin, Perm.INSPECT_EDIT)) {
            return;
        }
        ServerPlayerEntity p = online(target);
        OfflineInventory offline = p == null ? OfflineInventory.load(target) : null;
        if (p == null && offline == null) {
            Msg.send(admin, "inspect.no-data");
            return;
        }
        Inventory ec = p != null ? p.getEnderChestInventory() : offline.ender;
        EnderView view = new EnderView(ec);
        Menu m = new Menu("§8" + (edit ? "§cEDIT §8" : "") + "Ender chest: " + name(target), 6).perm(Perm.INSPECT).backedBy(view).live();
        Set<Integer> content = new HashSet<>();
        for (int i = 0; i < 27; i++) {
            content.add(i);
        }
        Map<Integer, ItemStack> before = snapshot(view);
        if (edit) {
            m.allowPlayerInventory(true).editable(content, (a, slot) -> logEdit(a, target, "ender chest", view, before));
        } else {
            m.reserved(content);
        }
        m.renderer(menu -> {
            for (int i = 27; i < 45; i++) {
                menu.icon(i, Icons.filler());
            }
            menu.set(44, Icons.of(edit ? Items.LIME_DYE : Items.GRAY_DYE, edit ? "§cEdit mode: ON" : "§7Edit mode: OFF",
                    "Every change is logged"), edit ? Perm.INSPECT : Perm.INSPECT_EDIT, (a, c) -> ender(a, target, !edit));
            nav(menu, target, "ender");
        });
        if (offline != null) {
            OfflineInventory.EDITING.put(target, admin.getUuid());
            m.onClose(a -> {
                OfflineInventory.EDITING.remove(target);
                if (edit && offline.save()) {
                    Msg.send(a, "inspect.offline-saved");
                }
            });
        }
        m.open(admin);
    }

    /** 54-slot view of a 27-slot ender chest. */
    static final class EnderView extends net.minecraft.inventory.SimpleInventory {
        private final Inventory target;

        EnderView(Inventory target) {
            super(54);
            this.target = target;
        }

        @Override
        public ItemStack getStack(int slot) {
            return slot < 27 ? target.getStack(slot) : super.getStack(slot);
        }

        @Override
        public void setStack(int slot, ItemStack stack) {
            if (slot < 27) {
                target.setStack(slot, stack);
            } else {
                super.setStack(slot, stack);
            }
        }

        @Override
        public ItemStack removeStack(int slot, int amount) {
            return slot < 27 ? target.removeStack(slot, amount) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeStack(int slot) {
            return slot < 27 ? target.removeStack(slot) : ItemStack.EMPTY;
        }

        @Override
        public void markDirty() {
            target.markDirty();
        }
    }

    private static Map<Integer, ItemStack> snapshot(Inventory inv) {
        Map<Integer, ItemStack> m = new HashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            m.put(i, inv.getStack(i).copy());
        }
        return m;
    }

    private static void logEdit(ServerPlayerEntity admin, UUID target, String where, Inventory inv, Map<Integer, ItemStack> before) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack now = inv.getStack(i);
            ItemStack old = before.get(i);
            if (old == null) {
                continue;
            }
            if (!ItemStack.areEqual(now, old)) {
                String detail = where + " slot " + i + ": " + (old.isEmpty() ? "empty" : ItemConv.info(old).describe())
                        + " -> " + (now.isEmpty() ? "empty" : ItemConv.info(now).describe());
                Staff.log(admin, "inventory-edit", target, name(target), detail);
                before.put(i, now.copy());
            }
        }
    }

    // ---- Effects ----

    public static void effects(ServerPlayerEntity admin, UUID target) {
        Menu m = new Menu("§8Effects: " + name(target), 6).perm(Perm.INSPECT).live();
        m.renderer(menu -> {
            ServerPlayerEntity p = online(target);
            int slot = 0;
            if (p != null) {
                for (StatusEffectInstance e : p.getStatusEffects()) {
                    if (slot >= 45) {
                        break;
                    }
                    String left = e.isInfinite() ? "infinite" : Durations.format(e.getDuration() * 50L);
                    menu.icon(slot++, Icons.of(Items.POTION, "§d" + e.getEffectType().value().getName().getString() + " " + (e.getAmplifier() + 1),
                            "§7Time left: §f" + left));
                }
            }
            if (slot == 0) {
                menu.icon(22, Icons.of(Items.GLASS_BOTTLE, "§7No active effects"));
            }
            nav(menu, target, "effects");
        });
        m.open(admin);
    }

    // ---- Anti-cheat ----

    public static void antiCheat(ServerPlayerEntity admin, UUID target) {
        Menu m = new Menu("§8Anti-cheat: " + name(target), 6).perm(Perm.INSPECT);
        m.renderer(menu -> {
            Ac ac = Ac.get();
            int sus = ac.violations.suspicion(target);
            List<String> pts = new ArrayList<>();
            for (Map.Entry<CheckType, Double> e : ac.violations.snapshot(target).entrySet()) {
                pts.add(String.format("§7%s: §f%.1f", e.getKey().displayName(), e.getValue()));
            }
            menu.icon(0, Icons.of(Items.REDSTONE, DetectionListener.color(sus) + "Suspicion " + sus, pts.isEmpty() ? List.of("§7No current points") : pts));
            List<String> counts = new ArrayList<>();
            for (Map.Entry<CheckType, Integer> e : ac.violations.flagCounts(target).entrySet()) {
                counts.add("§7" + e.getKey().displayName() + ": §f" + e.getValue());
            }
            menu.icon(1, Icons.of(Items.PAPER, "§eFlags this session", counts.isEmpty() ? List.of("§7None") : counts));
            List<ReviewCase> cases = ac.reviews.forPlayer(target);
            int slot = 9;
            for (ReviewCase c : cases) {
                if (slot >= 27) {
                    break;
                }
                menu.set(slot++, Icons.of(c.isOpen() ? Items.BOOK : Items.ENCHANTED_BOOK, "§6Case #" + c.id + " §7(" + c.status + ")",
                        "Opened " + date(c.createdAt), "Suspicion " + c.suspicion, "Warnings sent: " + c.warnings.size()),
                        Perm.REVIEW, (a, cl) -> ReviewMenu.openCase(a, c.id));
            }
            slot = 27;
            for (Punishment p : ac.punishments.history(target)) {
                if (slot >= 45) {
                    break;
                }
                menu.icon(slot++, Icons.of(Items.IRON_BARS, "§c" + p.type + (p.revoked ? " §8(revoked)" : ""),
                        "Reason: " + p.reason, "By: " + p.by, "When: " + date(p.at),
                        p.expiresAt == 0 ? "" : "Until: " + (p.expiresAt == Durations.PERMANENT ? "permanent" : date(p.expiresAt))));
            }
            menu.set(8, Icons.of(Items.CLOCK, "§bEvidence clips", "Recorded timelines"), Perm.REVIEW, (a, c) -> ReviewMenu.clips(a, target));
            nav(menu, target, "anticheat");
        });
        m.open(admin);
    }

    // ---- Location ----

    public static void location(ServerPlayerEntity admin, UUID target) {
        Menu m = new Menu("§8Location: " + name(target), 6).perm(Perm.INSPECT).live();
        m.renderer(menu -> {
            Ac ac = Ac.get();
            PlayerSession s = Ac.sessionOrNull(target);
            ServerPlayerEntity p = online(target);
            List<String> trail = new ArrayList<>();
            if (s != null) {
                List<String> all = new ArrayList<>(s.trail);
                for (int i = all.size() - 1; i >= 0 && trail.size() < 20; i--) {
                    trail.add("§7" + all.get(i));
                }
            }
            menu.icon(10, Icons.of(Items.MAP, "§eMovement trail (last 10 min)", trail.isEmpty() ? List.of("§7No data") : trail));
            List<DeathRecord> deaths = ac.deaths.forPlayer(target, 1);
            if (!deaths.isEmpty()) {
                DeathRecord d = deaths.get(0);
                menu.set(12, Icons.of(Items.SKELETON_SKULL, "§cLast death", d.pos.formatExact(), d.world, date(d.at), "§eClick to teleport"),
                        Perm.TELEPORT, (a, c) -> {
                            ServerWorld w = Mc.world(ac.server, d.world);
                            if (w != null) {
                                a.closeHandledScreen();
                                StaffTools.teleportTo(a, w, d.pos, Ac.get().staff.teleportInvisible(a.getUuid(), Ac.config().staff.teleportInvisibleByDefault) != c.isRight(), "death of " + name(target));
                            }
                        });
            }
            if (p != null) {
                var respawn = p.getRespawn();
                BlockPos spawn = respawn == null ? null : respawn.respawnData().getPos();
                menu.icon(14, Icons.of(Items.RED_BED, "§eSpawn point", spawn == null ? "World spawn" : spawn.toShortString(),
                        spawn == null ? "" : respawn.respawnData().getDimension().getValue().toString()));
            }
            List<String> claims = new ArrayList<>();
            for (Claim c : ac.claims.claimsOf(target)) {
                claims.add("§7" + c.name + ": §f" + c.roleOf(target, System.currentTimeMillis()));
            }
            if (p != null) {
                Claim in = ac.claims.at(Mc.worldId(p.getEntityWorld()), p.getX(), p.getZ());
                if (in != null) {
                    claims.add(0, "§aInside: " + in.name);
                }
            }
            menu.icon(16, Icons.of(Items.GOLDEN_SHOVEL, "§eClaims", claims.isEmpty() ? List.of("§7None") : claims));
            nav(menu, target, "location");
        });
        m.open(admin);
    }

    // ---- Activity ----

    public static void activity(ServerPlayerEntity admin, UUID target) {
        Menu m = new Menu("§8Activity: " + name(target), 6).perm(Perm.INSPECT);
        List<List<String>> data = new ArrayList<>();
        m.renderer(menu -> {
            if (data.isEmpty()) {
                menu.icon(22, Icons.of(Items.CLOCK, "§7Loading..."));
            } else {
                String[] titles = {"§eCommands", "§eChat", "§eBlocks", "§eContainers & other", "§eTrades", "§eJoins"};
                net.minecraft.item.Item[] icons = {Items.COMMAND_BLOCK, Items.OAK_SIGN, Items.GRASS_BLOCK, Items.CHEST, Items.EMERALD, Items.OAK_DOOR};
                for (int i = 0; i < data.size(); i++) {
                    menu.icon(10 + i, Icons.of(icons[i], titles[i], data.get(i).isEmpty() ? List.of("§7None") : data.get(i)));
                }
            }
            nav(menu, target, "activity");
        });
        m.open(admin);
        Ac ac = Ac.get();
        ac.logs.flush();
        Thread t = new Thread(() -> {
            try {
                SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm");
                List<List<String>> out = new ArrayList<>();
                List<String> l = new ArrayList<>();
                for (var r : ac.db.chat(target, "command", 15)) {
                    l.add("§7" + f.format(new Date(r.time())) + " §f" + r.b());
                }
                out.add(l);
                l = new ArrayList<>();
                for (var r : ac.db.chat(target, "chat", 15)) {
                    l.add("§7" + f.format(new Date(r.time())) + " §f" + r.b());
                }
                out.add(l);
                l = new ArrayList<>();
                for (var c : ac.db.blockChanges(target, 0, null, null, null, null, false, 15)) {
                    l.add("§7" + f.format(new Date(c.time)) + " §f" + c.kind + " " + c.x + " " + c.y + " " + c.z);
                }
                out.add(l);
                l = new ArrayList<>();
                for (var r : ac.db.activity(target, 15)) {
                    l.add("§7" + f.format(new Date(r.time())) + " §f" + r.a() + ": " + r.b());
                }
                out.add(l);
                l = new ArrayList<>();
                for (var r : ac.db.trades(target, 10)) {
                    l.add("§7" + f.format(new Date(r.time())) + " §f" + r.a() + " " + r.b() + "/" + r.c() + ": " + r.d());
                }
                out.add(l);
                l = new ArrayList<>();
                for (var r : ac.db.joins(target, 10)) {
                    l.add("§7" + f.format(new Date(r.time())) + " §f" + r.a());
                }
                out.add(l);
                ac.server.execute(() -> {
                    data.addAll(out);
                    if (m.isOpenFor(admin)) {
                        m.refresh();
                    }
                });
            } catch (Exception e) {
                Ac.LOG.error("Activity query failed", e);
            }
        }, "AntiCheat-Inspect");
        t.setDaemon(true);
        t.start();
    }

    // ---- Deaths ----

    public static void deaths(ServerPlayerEntity admin, UUID target, int page) {
        Menu m = new Menu("§8Deaths: " + name(target), 6).perm(Perm.DEATHS);
        m.renderer(menu -> {
            List<DeathRecord> list = Ac.get().deaths.forPlayer(target, 200);
            menu.page(list, page, d -> Icons.of(d.restored ? Items.BONE : Items.SKELETON_SKULL,
                    "§c" + date(d.at), Deaths.summary(d), d.pos.formatExact(), d.world,
                    d.inventory.size() + " stacks lost" + (d.restored ? " §a(restored)" : ""), "§eClick for details"),
                    d -> (a, c) -> death(a, d.id), pg -> deaths(admin, target, pg));
            menu.set(45, Menu.back(), (a, c) -> overview(a, target));
        });
        m.open(admin);
    }

    public static void death(ServerPlayerEntity admin, long id) {
        DeathRecord d = Ac.get().deaths.get(id);
        if (d == null) {
            Msg.send(admin, "deaths.not-found");
            return;
        }
        Menu m = new Menu("§8Death #" + d.id + ": " + d.playerName, 6).perm(Perm.DEATHS);
        m.renderer(menu -> {
            int slot = 0;
            for (var i : d.inventory) {
                if (slot >= 36) {
                    break;
                }
                menu.icon(slot++, ItemConv.decode(i.serialized));
            }
            List<String> hits = new ArrayList<>();
            for (var h : d.lastDamage) {
                hits.add(String.format("§7%s §f%s%s §c-%.1f", new SimpleDateFormat("HH:mm:ss").format(new Date(h.at)), h.source,
                        h.attacker == null ? "" : " (" + h.attacker + ")", h.amount));
            }
            menu.icon(36, Icons.of(Items.PAPER, "§eWhen & where", date(d.at), d.pos.formatExact(), d.world, "Biome: " + d.biome));
            menu.icon(37, Icons.of(Items.IRON_SWORD, "§eHow", Deaths.summary(d)));
            menu.icon(38, Icons.of(Items.REDSTONE, "§eLast 10 seconds", hits.isEmpty() ? List.of("§7No hits recorded") : hits));
            menu.icon(39, Icons.of(Items.EXPERIENCE_BOTTLE, "§eXP", "Level " + d.xpLevel, "Total " + d.totalXp));
            List<String> pick = new ArrayList<>();
            for (var p : d.pickups) {
                pick.add("§7" + new SimpleDateFormat("HH:mm:ss").format(new Date(p.at)) + " §f" + p.byName + ": " + p.item);
            }
            menu.icon(40, Icons.of(Items.HOPPER, "§ePicked up by", pick.isEmpty() ? List.of("§7Nobody yet") : pick));
            menu.set(42, Icons.of(Items.ENDER_PEARL, "§bTeleport to death spot", "Left: your default, right: the other"), Perm.TELEPORT, (a, c) -> {
                ServerWorld w = Mc.world(Ac.server(), d.world);
                if (w != null) {
                    a.closeHandledScreen();
                    boolean inv = Ac.get().staff.teleportInvisible(a.getUuid(), Ac.config().staff.teleportInvisibleByDefault) != c.isRight();
                    StaffTools.teleportTo(a, w, d.pos, inv, "death #" + d.id);
                }
            });
            menu.set(43, Icons.of(d.restored ? Items.GRAY_DYE : Items.LIME_DYE, d.restored ? "§7Already restored" : "§aRestore items",
                    "Only once per death;", "blocked if the items were picked back up"), Perm.DEATH_RESTORE, (a, c) -> {
                Deaths.restore(a, d.id);
                menu.refresh();
            });
            menu.set(45, Menu.back(), (a, c) -> deaths(a, d.player, 0));
            for (int i = 46; i < 54; i++) {
                menu.icon(i, Icons.filler());
            }
        });
        m.open(admin);
    }

    // ---- Private info ----

    public static void privateInfo(ServerPlayerEntity admin, UUID target) {
        if (!PermissionPolicy.canSeePrivateInfo(Perms.effectiveRole(admin), Ac.config().permissions.adminsSeePrivateInfo)) {
            Perms.unauthorized(admin, "private info");
            return;
        }
        Staff.log(admin, "inspect-private", target, name(target), "");
        Menu m = new Menu("§8Private: " + name(target), 6).perm(Perm.INSPECT);
        m.renderer(menu -> {
            Ac ac = Ac.get();
            List<String> ips = new ArrayList<>();
            for (String ip : ac.joins.ipsOf(target)) {
                ips.add("§f" + ip);
            }
            menu.icon(10, Icons.of(Items.NAME_TAG, "§eIP addresses", ips.isEmpty() ? List.of("§7None") : ips));
            int slot = 19;
            for (UUID alt : ac.joins.alts(target)) {
                if (slot >= 44) {
                    break;
                }
                boolean banned = ac.punishments.isBanned(alt);
                menu.set(slot++, Icons.head(alt, name(alt), (banned ? "§c" : "§f") + name(alt), banned ? "§cBanned" : "", "§eClick to inspect"),
                        (a, c) -> open(a, alt));
            }
            menu.icon(12, Icons.of(Items.PLAYER_HEAD, "§eAlt accounts (same IP)", (slot - 19) + " found"));
            nav(menu, target, "private");
        });
        m.open(admin);
    }
}
