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
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
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

    private record Tab(String id, net.minecraft.item.Item icon, Perm perm) {
    }

    private static final Tab[] TABS = {
            new Tab("overview", Items.PLAYER_HEAD, null),
            new Tab("inventory", Items.CHEST, null),
            new Tab("ender", Items.ENDER_CHEST, null),
            new Tab("effects", Items.POTION, null),
            new Tab("anticheat", Items.IRON_SWORD, null),
            new Tab("location", Items.COMPASS, null),
            new Tab("activity", Items.WRITABLE_BOOK, null),
            new Tab("deaths", Items.SKELETON_SKULL, Perm.DEATHS),
            new Tab("private", Items.TRIPWIRE_HOOK, Perm.INSPECT_PRIVATE)};

    private static void go(ServerPlayerEntity a, UUID target, String id) {
        switch (id) {
            case "overview" -> overview(a, target);
            case "inventory" -> inventory(a, target, false);
            case "ender" -> ender(a, target, false);
            case "effects" -> effects(a, target);
            case "anticheat" -> antiCheat(a, target);
            case "location" -> location(a, target);
            case "activity" -> activity(a, target);
            case "deaths" -> deaths(a, target, 0);
            case "private" -> privateInfo(a, target);
            default -> overview(a, target);
        }
    }

    private static net.minecraft.item.ItemStack tab(Tab t, boolean current) {
        Btn b = Btn.of(t.icon).name(Category.PLAYERS, Msg.tr("in.tab." + t.id)).glint(current);
        if (current) {
            b.status(Theme.GOLD_LIGHT, Theme.Sym.ARROW.sp() + Msg.tr("in.here"));
        } else {
            b.left(Msg.tr("ui.action.open"));
        }
        return b.build();
    }

    /** Page tabs along the top row (slot 4 is the page's info item). */
    private static void nav(Menu m, UUID target, String current) {
        int[] slots = {0, 1, 2, 3, 5, 6, 7, 8};
        for (int i = 0; i < slots.length; i++) {
            Tab t = TABS[i];
            m.set(slots[i], tab(t, t.id.equals(current)), t.perm, (a, c) -> go(a, target, t.id));
        }
    }

    /** Inventory views use every row but the last, so their tabs sit in the bottom row. */
    private static void bottomNav(Menu m, UUID target, String current) {
        for (int i = 45; i < 54; i++) {
            m.icon(i, Btn.pane(Category.PLAYERS.glass));
        }
        for (int i = 0; i < TABS.length; i++) {
            Tab t = TABS[i];
            if (t.id.equals("private") && !Perms.has(m.viewer(), Perm.INSPECT_PRIVATE)) {
                continue;
            }
            m.set(45 + i, tab(t, t.id.equals(current)), t.perm, (a, c) -> go(a, target, t.id));
        }
    }

    private static Menu page(ServerPlayerEntity admin, UUID target, String tab) {
        return Menu.std(Category.PLAYERS, Msg.trFor(admin, "cat.players"), name(target), Msg.trFor(admin, "in.tab." + tab)).perm(Perm.INSPECT);
    }

    // ---- Overview (live) ----

    public static void overview(ServerPlayerEntity admin, UUID target) {
        Menu m = page(admin, target, "overview").live();
        m.renderer(menu -> {
            Ac ac = Ac.get();
            ServerPlayerEntity p = online(target);
            PlayerSession s = Ac.sessionOrNull(target);
            int sus = ac.violations.suspicion(target);
            menu.info(Btn.head(target, name(target)).name(Category.PLAYERS, name(target))
                    .status(p != null ? Theme.GREEN : Theme.SOFT, Theme.Sym.DOT.sp() + Msg.tr(p != null ? "ui.online" : "ui.offline"))
                    .line(Msg.tr(s != null && s.bedrock ? "panel.bedrock" : "panel.java"))
                    .status(ReviewMenu.suspicionColor(sus), Theme.Sym.DOT.sp() + Msg.tr("rv.suspicion", sus))
                    .line(Msg.tr("in.playtime", Joins.formatPlaytime(target)))
                    .line(Msg.tr("in.first-join", date(ac.misc.firstJoin.getOrDefault(target, 0L)))).build());
            if (p != null) {
                ServerWorld w = p.getEntityWorld();
                BlockPos bp = p.getBlockPos();
                String biome = w.getBiome(bp).getKey().map(k -> k.getValue().getPath()).orElse("?");
                String looking = "-";
                HitResult hit = p.raycast(8, 1f, false);
                if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
                    looking = Mc.blockId(w.getBlockState(bh.getBlockPos()).getBlock()).replace("minecraft:", "") + " " + bh.getBlockPos().toShortString();
                }
                menu.icon(19, Btn.of(Items.COMPASS).name(Category.PLAYERS, Msg.tr("in.position"))
                        .line(Mc.vec(p.getEntityPos()).formatExact())
                        .line(Msg.tr("in.dimension", Mc.worldId(w).replace("minecraft:", "")))
                        .line(Msg.tr("in.biome", biome))
                        .line(Msg.tr("in.chunk", bp.getX() >> 4, bp.getZ() >> 4))
                        .line(Msg.tr("in.facing", p.getHorizontalFacing().asString(), String.format(java.util.Locale.ROOT, "%.0f / %.0f", p.getYaw(), p.getPitch())))
                        .line(Msg.tr("in.looking", looking)).build());
                menu.icon(20, Btn.of(Items.GOLDEN_APPLE).name(Category.PLAYERS, Msg.tr("in.status"))
                        .line(Msg.tr("in.gamemode", p.interactionManager.getGameMode().asString()))
                        .line(Msg.tr("in.health", String.format(java.util.Locale.ROOT, "%.1f / %.1f", p.getHealth(), p.getMaxHealth())))
                        .line(Msg.tr("in.hunger", p.getHungerManager().getFoodLevel()))
                        .line(Msg.tr("in.xp", p.experienceLevel))
                        .line(Msg.tr("in.ping", p.networkHandler.getLatency())).build());
            } else {
                Location last = ac.misc.lastLogout.get(target);
                menu.icon(19, Btn.of(Items.COMPASS).name(Category.PLAYERS, Msg.tr("in.last-logout"))
                        .line(last == null ? Msg.tr("in.unknown") : new Vec3(last.x(), last.y(), last.z()).formatExact())
                        .lines(List.of(last == null ? "" : Msg.tr("in.dimension", last.world().replace("minecraft:", "")))).build());
            }
            Watchlist.Entry w = ac.watchlist.get(target);
            Btn wb = Btn.of(w != null ? Items.OBSERVER : Items.ENDER_PEARL).name(Category.WATCHLIST, Msg.tr("cat.watchlist")).onOff(w != null);
            if (w != null) {
                wb.line(Msg.tr("flow.reason", w.reason)).line(w.expiresAt == Durations.PERMANENT ? Msg.tr("ui.permanent") : Msg.tr("in.until", date(w.expiresAt)));
            }
            menu.set(21, wb.left(Msg.tr(w != null ? "panel.action.unwatch" : "rv.action.watch")).build(), Perm.WATCH, (a, c) -> {
                if (w != null) {
                    Confirm.open(a, Category.WATCHLIST, Msg.tr("panel.confirm.unwatch", name(target)), Msg.tr("panel.confirm.unwatch-detail"),
                            Btn.head(target, name(target)).name(Category.WATCHLIST, name(target)).build(), () -> {
                                ac.watchlist.remove(target, a.getGameProfile().name());
                                Staff.log(a, "watch-remove", target, name(target), "");
                                Ac.markDirty("watchlist");
                                overview(a, target);
                            });
                    return;
                }
                ac.watchlist.add(target, name(target), Msg.tr("in.added-from-inspect"), a.getGameProfile().name(), Durations.PERMANENT, false);
                Staff.log(a, "watch-add", target, name(target), "from /inspect");
                Ac.markDirty("watchlist");
                Msg.success(a, "rv.done-watch", name(target));
                menu.refresh();
            });
            // Actions
            if (p != null) {
                menu.set(28, Btn.of(Items.ENDER_EYE).name(Category.PLAYERS, Msg.tr("rv.spectate")).desc(Msg.tr("in.spectate-desc"))
                        .left(Msg.tr("rv.action.spectate")).build(), Perm.SPECTATE, (a, c) -> {
                    a.closeHandledScreen();
                    StaffTools.spectate(a, p);
                });
                boolean invDefault = Ac.get().staff.teleportInvisible(admin.getUuid(), Ac.config().staff.teleportInvisibleByDefault);
                menu.set(29, Btn.of(Items.ENDER_PEARL).name(Category.PLAYERS, Msg.tr("rv.teleport")).desc(Msg.tr("rv.teleport-desc"))
                        .line(Msg.tr("in.tp-default", Msg.tr(invDefault ? "in.invisible" : "in.visible")))
                        .left(Msg.tr("in.tp-as", Msg.tr(invDefault ? "in.invisible" : "in.visible")))
                        .right(Msg.tr("in.tp-as", Msg.tr(invDefault ? "in.visible" : "in.invisible")))
                        .shift(Msg.tr("in.tp-change-default")).build(), Perm.TELEPORT, (a, c) -> {
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
                menu.set(30, Btn.of(Items.PACKED_ICE).name(Category.PLAYERS, Msg.tr("in.freeze")).desc(Msg.tr("in.freeze-desc")).onOff(frozen)
                        .left(Msg.tr(frozen ? "in.action.unfreeze" : "in.action.freeze")).build(), Perm.FREEZE, (a, c) -> {
                    if (com.vylorq.anticheat.feature.Punish.allowedOn(a, target)) {
                        StaffTools.setFrozen(a, p, !frozen);
                    }
                    menu.refresh();
                });
            }
            boolean shadowed = ac.shadow.isShadowed(target);
            menu.set(31, Btn.of(Items.BLACK_DYE).name(Category.PLAYERS, Msg.tr("rv.shadow")).desc(Msg.tr("rv.shadow-desc")).onOff(shadowed)
                    .left(Msg.tr(shadowed ? "ui.action.turn-off" : "ui.action.turn-on")).build(), Perm.SHADOW, (a, c) -> {
                boolean on = ac.shadow.toggle(target);
                Ac.markDirty("shadow");
                Staff.log(a, on ? "shadow-on" : "shadow-off", target, name(target), "");
                menu.refresh();
            });
            menu.set(32, Btn.of(Items.IRON_SWORD).name(Category.PUNISHMENTS, Msg.tr("in.punish")).desc(Msg.tr("in.punish-desc"))
                    .left(Msg.tr("ui.action.open")).build(), Perm.WARN, (a, c) -> punish(a, target));
            ReviewCase open = ac.reviews.openCaseFor(target);
            if (open != null) {
                menu.set(33, Btn.of(Items.WRITABLE_BOOK).name(Category.REVIEW, Msg.tr("in.open-case", open.id)).glint(true)
                        .left(Msg.tr("ui.action.open")).build(), Perm.REVIEW, (a, c) -> ReviewMenu.openCase(a, open.id));
            }
            if (Perms.has(admin, Perm.INSPECT_PRIVATE)) {
                menu.set(43, tab(TABS[8], false), Perm.INSPECT_PRIVATE, (a, c) -> privateInfo(a, target));
            }
            nav(menu, target, "overview");
        });
        m.open(admin);
    }

    /** Warn, mute, kick, tempban, ban, jail: each asks for the time and reason, then confirms. */
    public static void punish(ServerPlayerEntity admin, UUID target) {
        String n = name(target);
        Menu m = Menu.std(Category.PUNISHMENTS, Msg.trFor(admin, "cat.players"), n, Msg.trFor(admin, "in.punish")).perm(Perm.WARN);
        m.renderer(menu -> {
            menu.info(Btn.head(target, n).name(Category.PUNISHMENTS, n).desc(Msg.tr("in.punish-desc")).build());
            String q = Msg.q(n);
            action(menu, 20, Items.BELL, "rv.warn", Perm.WARN, false, 0, (a, r, d) -> Mc.run(a, "warn " + q + " " + r), target, n);
            action(menu, 21, Items.BOOK, "rv.mute", Perm.MUTE, true, Durations.HOUR, (a, r, d) -> Mc.run(a, "mute " + q + " " + Durations.format(d).replace(" ", "") + " " + r), target, n);
            action(menu, 22, Items.LEATHER_BOOTS, "rv.kick", Perm.KICK, false, 0, (a, r, d) -> Mc.run(a, "kick " + q + " " + r), target, n);
            action(menu, 23, Items.IRON_BARS, "rv.jail", Perm.JAIL, true, Durations.HOUR, (a, r, d) -> Mc.run(a, "jail " + q + " " + Durations.format(d).replace(" ", "") + " " + r), target, n);
            action(menu, 24, Items.NETHERITE_AXE, "in.tempban", Perm.BAN, true, Durations.DAY, (a, r, d) -> Mc.run(a, "tempban " + q + " " + Durations.format(d).replace(" ", "") + " " + r), target, n);
            action(menu, 31, Items.WITHER_SKELETON_SKULL, "rv.ban", Perm.BAN, false, 0, (a, r, d) -> Mc.run(a, "ban " + q + " " + r), target, n);
        });
        m.open(admin);
    }

    private interface PunishAction {
        void run(ServerPlayerEntity admin, String reason, long duration);
    }

    private static void action(Menu menu, int slot, net.minecraft.item.Item icon, String key, Perm perm, boolean timed, long defTime,
                               PunishAction run, UUID target, String name) {
        menu.set(slot, Btn.of(icon).name(Category.PUNISHMENTS, Msg.tr(key)).desc(Msg.tr(key + "-desc"))
                .left(Msg.tr(timed ? "rv.action.choose-time" : "panel.action.do")).build(), perm, (a, c) ->
                Flows.punish(a, Category.PUNISHMENTS, target, name, "in.confirm." + key.substring(key.indexOf('.') + 1), timed, defTime,
                        Msg.tr("in.default-reason"), (r, d) -> run.run(a, r, d)));
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
        Menu m = new Menu("", 6).perm(Perm.INSPECT).backedBy(view).live();
        m.titleText = Theme.title(Category.PLAYERS, Msg.trFor(admin, "cat.players"), name(target),
                Msg.trFor(admin, "in.tab.inventory") + (edit ? " (" + Msg.trFor(admin, "in.editing") + ")" : "") + (p == null ? " (" + Msg.trFor(admin, "ui.offline") + ")" : ""));
        Map<Integer, ItemStack> before = snapshot(view);
        if (edit) {
            m.allowPlayerInventory(true).editable(contentSlots(), (a, slot) -> logEdit(a, target, "inventory", view, before));
        } else {
            m.reserved(contentSlots());
        }
        m.renderer(menu -> {
            for (int i = 41; i < 43; i++) {
                menu.icon(i, Btn.of(Items.BLACK_STAINED_GLASS_PANE).color(Theme.SOFT).name(Msg.tr("in.armour-label")).build());
            }
            menu.set(43, Btn.of(Items.LAVA_BUCKET).name(Category.PLAYERS, Msg.tr("invtools.title")).desc(Msg.tr("invtools.button-desc"))
                    .left(Msg.tr("ui.action.open")).build(), Perm.INSPECT_EDIT, (a, c) -> InventoryTools.open(a, target));
            menu.set(44, editButton(edit), edit ? Perm.INSPECT : Perm.INSPECT_EDIT, (a, c) -> inventory(a, target, !edit));
            bottomNav(menu, target, "inventory");
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
        Menu m = new Menu("", 6).perm(Perm.INSPECT).backedBy(view).live();
        m.titleText = Theme.title(Category.PLAYERS, Msg.trFor(admin, "cat.players"), name(target),
                Msg.trFor(admin, "in.tab.ender") + (edit ? " (" + Msg.trFor(admin, "in.editing") + ")" : ""));
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
            for (int i = 27; i < 44; i++) {
                menu.icon(i, Btn.pane(Category.PLAYERS.glass));
            }
            menu.set(44, editButton(edit), edit ? Perm.INSPECT : Perm.INSPECT_EDIT, (a, c) -> ender(a, target, !edit));
            bottomNav(menu, target, "ender");
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

    private static net.minecraft.item.ItemStack editButton(boolean edit) {
        return Btn.of(edit ? Items.LIME_DYE : Items.GRAY_DYE).name(Category.PLAYERS, Msg.tr("in.edit-mode")).desc(Msg.tr("in.edit-mode-desc"))
                .onOff(edit).left(Msg.tr(edit ? "ui.action.turn-off" : "ui.action.turn-on")).glint(edit).build();
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
        Menu m = page(admin, target, "effects").live();
        m.renderer(menu -> {
            ServerPlayerEntity p = online(target);
            List<StatusEffectInstance> list = p == null ? List.of() : new ArrayList<>(p.getStatusEffects());
            menu.info(Btn.of(Items.POTION).name(Category.PLAYERS, Msg.tr("in.tab.effects")).line(Msg.tr("panel.count", list.size())).build());
            menu.list(list, e -> Btn.of(Items.POTION).name(Category.PLAYERS, e.getEffectType().value().getName().getString() + " " + (e.getAmplifier() + 1))
                            .line(Theme.Sym.CLOCK.sp() + (e.isInfinite() ? Msg.tr("ui.permanent") : Msg.tr("ui.left", Durations.format(e.getDuration() * 50L)))).build(),
                    null, e -> e.getEffectType().value().getName().getString(), List.of(),
                    Msg.tr(p == null ? "in.offline-effects" : "in.no-effects"), "");
            nav(menu, target, "effects");
        });
        m.open(admin);
    }

    // ---- Anti-cheat ----

    public static void antiCheat(ServerPlayerEntity admin, UUID target) {
        Menu m = page(admin, target, "anticheat");
        m.renderer(menu -> {
            Ac ac = Ac.get();
            int sus = ac.violations.suspicion(target);
            Btn pts = Btn.of(Items.REDSTONE).name(Category.REVIEW, Msg.tr("rv.suspicion", sus)).desc(Msg.tr("in.points-desc"));
            var snap = ac.violations.snapshot(target);
            if (snap.isEmpty()) {
                pts.line(Msg.tr("rv.none"));
            }
            for (Map.Entry<CheckType, Double> e : snap.entrySet()) {
                pts.line(String.format(java.util.Locale.ROOT, "%s: %.1f", e.getKey().displayName(), e.getValue()));
            }
            menu.info(pts.build());
            Btn counts = Btn.of(Items.PAPER).name(Category.REVIEW, Msg.tr("in.flags-session"));
            var fc = ac.violations.flagCounts(target);
            if (fc.isEmpty()) {
                counts.line(Msg.tr("rv.none"));
            }
            for (Map.Entry<CheckType, Integer> e : fc.entrySet()) {
                counts.line(e.getKey().displayName() + ": " + e.getValue());
            }
            menu.icon(10, counts.build());
            menu.set(11, Btn.of(Items.CLOCK).name(Category.REVIEW, Msg.tr("rv.clips")).desc(Msg.tr("rv.clips-desc"))
                    .left(Msg.tr("ui.action.open")).build(), Perm.REVIEW, (a, c) -> ReviewMenu.clips(a, target));
            int slot = 19;
            for (ReviewCase c : ac.reviews.forPlayer(target)) {
                if (slot > 25) {
                    break;
                }
                menu.set(slot++, Btn.of(c.isOpen() ? Items.WRITABLE_BOOK : Items.BOOK).name(Category.REVIEW, "#" + c.id)
                        .line(Msg.tr("rv.status." + c.status.name().toLowerCase(java.util.Locale.ROOT)))
                        .line(date(c.createdAt)).line(Msg.tr("rv.suspicion", c.suspicion)).line(Msg.tr("rv.warnings", c.warnings.size()))
                        .left(Msg.tr("ui.action.open")).glint(c.isOpen()).build(), Perm.REVIEW, (a, cl) -> ReviewMenu.openCase(a, c.id));
            }
            slot = 28;
            for (Punishment p : ac.punishments.history(target)) {
                if (slot > 43) {
                    break;
                }
                if (slot == 35 || slot == 36) {
                    slot = 37;
                }
                menu.icon(slot++, Btn.of(Items.IRON_BARS).name(Category.PUNISHMENTS, p.type.name().toLowerCase(java.util.Locale.ROOT)
                                + (p.revoked ? " (" + Msg.tr("panel.revoked") + ")" : ""))
                        .desc(p.reason).line(Msg.tr("panel.by", p.by, date(p.at)))
                        .lines(List.of(p.expiresAt == 0 ? "" : p.expiresAt == Durations.PERMANENT ? Msg.tr("ui.permanent") : Msg.tr("in.until", date(p.expiresAt)))).build());
            }
            nav(menu, target, "anticheat");
        });
        m.open(admin);
    }

    // ---- Location ----

    public static void location(ServerPlayerEntity admin, UUID target) {
        Menu m = page(admin, target, "location").live();
        m.renderer(menu -> {
            Ac ac = Ac.get();
            PlayerSession s = Ac.sessionOrNull(target);
            ServerPlayerEntity p = online(target);
            menu.info(Btn.of(Items.COMPASS).name(Category.PLAYERS, Msg.tr("in.tab.location"))
                    .lines(List.of(p == null ? "" : Mc.vec(p.getEntityPos()).formatExact())).build());
            Btn trail = Btn.of(Items.MAP).name(Category.PLAYERS, Msg.tr("in.trail"));
            int n = 0;
            if (s != null) {
                List<String> all = new ArrayList<>(s.trail);
                for (int i = all.size() - 1; i >= 0 && n < 16; i--, n++) {
                    trail.line(all.get(i));
                }
            }
            if (n == 0) {
                trail.line(Msg.tr("in.no-data"));
            }
            menu.icon(20, trail.build());
            List<DeathRecord> deaths = ac.deaths.forPlayer(target, 1);
            if (!deaths.isEmpty()) {
                DeathRecord d = deaths.get(0);
                menu.set(22, Btn.of(Items.SKELETON_SKULL).name(Category.DEATHS, Msg.tr("in.last-death")).line(d.pos.formatExact())
                        .line(d.world.replace("minecraft:", "")).line(date(d.at)).left(Msg.tr("panel.action.teleport")).right(Msg.tr("in.teleport-other-mode")).build(), Perm.TELEPORT, (a, c) -> {
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
                menu.icon(24, Btn.of(Items.RED_BED).name(Category.PLAYERS, Msg.tr("in.spawn-point"))
                        .line(spawn == null ? Msg.tr("in.world-spawn") : spawn.toShortString())
                        .lines(List.of(spawn == null ? "" : respawn.respawnData().getDimension().getValue().getPath())).build());
            }
            Btn claims = Btn.of(Items.GOLDEN_SHOVEL).name(Category.CLAIMS, Msg.tr("cat.claims"));
            if (p != null) {
                Claim in = ac.claims.at(Mc.worldId(p.getEntityWorld()), p.getX(), p.getZ());
                if (in != null) {
                    claims.status(Theme.GREEN, Theme.Sym.DOT.sp() + Msg.tr("in.inside-claim", in.name));
                }
            }
            List<Claim> theirs = ac.claims.claimsOf(target);
            for (Claim c : theirs) {
                ClaimRoleText.add(claims, c, target);
            }
            if (theirs.isEmpty()) {
                claims.line(Msg.tr("rv.none"));
            }
            menu.icon(31, claims.build());
            nav(menu, target, "location");
        });
        m.open(admin);
    }

    private static final class ClaimRoleText {
        static void add(Btn b, Claim c, UUID target) {
            var r = c.roleOf(target, System.currentTimeMillis());
            b.line(c.name + ": " + (r == null ? Msg.tr("cm.no-role") : Msg.tr("cm.role." + r.name().toLowerCase(java.util.Locale.ROOT))));
        }
    }

    // ---- Activity ----

    public static void activity(ServerPlayerEntity admin, UUID target) {
        Menu m = page(admin, target, "activity");
        List<List<String>> data = new ArrayList<>();
        m.renderer(menu -> {
            menu.info(Btn.of(Items.WRITABLE_BOOK).name(Category.PLAYERS, Msg.tr("in.tab.activity")).desc(Msg.tr("in.activity-desc")).build());
            if (data.isEmpty()) {
                menu.icon(22, Btn.of(Items.CLOCK).color(Theme.SOFT).name(Msg.tr("in.loading")).build());
            } else {
                String[] keys = {"in.act.commands", "in.act.chat", "in.act.blocks", "in.act.containers", "in.act.trades", "in.act.joins"};
                net.minecraft.item.Item[] icons = {Items.COMMAND_BLOCK, Items.OAK_SIGN, Items.GRASS_BLOCK, Items.CHEST, Items.EMERALD, Items.OAK_DOOR};
                int[] slots = {20, 21, 22, 23, 24, 31};
                for (int i = 0; i < data.size(); i++) {
                    Btn b = Btn.of(icons[i]).name(Category.PLAYERS, Msg.tr(keys[i]));
                    if (data.get(i).isEmpty()) {
                        b.line(Msg.tr("rv.none"));
                    }
                    for (String l : data.get(i)) {
                        b.line(l);
                    }
                    menu.icon(slots[i], b.build());
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
                for (var r : ac.db.chat(target, "command", 12)) {
                    l.add(f.format(new Date(r.time())) + " " + r.b());
                }
                out.add(l);
                l = new ArrayList<>();
                for (var r : ac.db.chat(target, "chat", 12)) {
                    l.add(f.format(new Date(r.time())) + " " + r.b());
                }
                out.add(l);
                l = new ArrayList<>();
                for (var c : ac.db.blockChanges(target, 0, null, null, null, null, false, 12)) {
                    l.add(f.format(new Date(c.time)) + " " + c.kind + " " + c.x + " " + c.y + " " + c.z);
                }
                out.add(l);
                l = new ArrayList<>();
                for (var r : ac.db.activity(target, 12)) {
                    l.add(f.format(new Date(r.time())) + " " + r.a() + ": " + r.b());
                }
                out.add(l);
                l = new ArrayList<>();
                for (var r : ac.db.trades(target, 10)) {
                    l.add(f.format(new Date(r.time())) + " " + r.a() + " " + r.b() + "/" + r.c() + ": " + r.d());
                }
                out.add(l);
                l = new ArrayList<>();
                for (var r : ac.db.joins(target, 10)) {
                    l.add(f.format(new Date(r.time())) + " " + r.a());
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
        }, "Vigil-Inspect");
        t.setDaemon(true);
        t.start();
    }

    // ---- Deaths ----

    public static void deaths(ServerPlayerEntity admin, UUID target, int page) {
        Menu m = Menu.std(Category.DEATHS, Msg.trFor(admin, "cat.deaths"), name(target)).perm(Perm.DEATHS);
        m.renderer(menu -> {
            List<DeathRecord> list = Ac.get().deaths.forPlayer(target, 200);
            menu.info(Btn.head(target, name(target)).name(Category.DEATHS, name(target)).desc(Msg.tr("panel.desc.deaths"))
                    .line(Msg.tr("panel.count", list.size())).build());
            menu.list(list, d -> Btn.of(d.restored ? Items.BONE : Items.SKELETON_SKULL).name(Category.DEATHS, date(d.at))
                            .desc(Deaths.summary(d)).line(d.pos.formatExact() + " " + d.world.replace("minecraft:", ""))
                            .line(Msg.tr("in.stacks-lost", d.inventory.size()))
                            .lines(List.of(d.restored ? Theme.Sym.CHECK.sp() + Msg.tr("in.restored") : ""))
                            .left(Msg.tr("in.action.details")).build(),
                    d -> (a, c) -> death(a, d.id), d -> Deaths.summary(d),
                    List.of(Menu.Filter.sort(Msg.tr("panel.filter.newest"), java.util.Comparator.comparingLong((DeathRecord d) -> -d.at)),
                            Menu.Filter.of(Msg.tr("in.filter.not-restored"), d -> !d.restored)),
                    Msg.tr("in.no-deaths"), Msg.tr("in.no-deaths-hint"));
            if (Ac.server().getPlayerManager().getPlayer(target) != null || Ac.get().joins.name(target) != null) {
                nav(menu, target, "deaths");
            }
        });
        m.open(admin);
    }

    public static void death(ServerPlayerEntity admin, long id) {
        DeathRecord d = Ac.get().deaths.get(id);
        if (d == null) {
            Msg.send(admin, "deaths.not-found");
            return;
        }
        Menu m = new Menu("", 6).perm(Perm.DEATHS);
        m.titleText = Theme.title(Category.DEATHS, Msg.trFor(admin, "cat.deaths"), d.playerName, "#" + d.id);
        m.renderer(menu -> {
            int slot = 0;
            for (var i : d.inventory) {
                if (slot >= 36) {
                    break;
                }
                menu.icon(slot++, ItemConv.decode(i.serialized));
            }
            Btn hits = Btn.of(Items.REDSTONE).name(Category.DEATHS, Msg.tr("in.last-hits"));
            if (d.lastDamage.isEmpty()) {
                hits.line(Msg.tr("rv.none"));
            }
            for (var h : d.lastDamage) {
                hits.line(String.format(java.util.Locale.ROOT, "%s %s%s -%.1f", new SimpleDateFormat("HH:mm:ss").format(new Date(h.at)), h.source,
                        h.attacker == null ? "" : " (" + h.attacker + ")", h.amount));
            }
            menu.icon(36, Btn.of(Items.PAPER).name(Category.DEATHS, Msg.tr("in.when-where")).line(date(d.at)).line(d.pos.formatExact())
                    .line(d.world.replace("minecraft:", "")).line(Msg.tr("in.biome", d.biome)).build());
            menu.icon(37, Btn.of(Items.IRON_SWORD).name(Category.DEATHS, Msg.tr("in.how")).desc(Deaths.summary(d)).build());
            menu.icon(38, hits.build());
            menu.icon(39, Btn.of(Items.EXPERIENCE_BOTTLE).name(Category.DEATHS, "XP").line(Msg.tr("in.xp", d.xpLevel)).line(Msg.tr("in.xp-total", d.totalXp)).build());
            Btn pick = Btn.of(Items.HOPPER).name(Category.DEATHS, Msg.tr("in.picked-up"));
            if (d.pickups.isEmpty()) {
                pick.line(Msg.tr("in.nobody-yet"));
            }
            for (var p : d.pickups) {
                pick.line(new SimpleDateFormat("HH:mm:ss").format(new Date(p.at)) + " " + p.byName + ": " + p.item);
            }
            menu.icon(40, pick.build());
            menu.set(42, Btn.of(Items.ENDER_PEARL).name(Category.DEATHS, Msg.tr("in.tp-death")).left(Msg.tr("rv.action.tp-default"))
                    .right(Msg.tr("rv.action.tp-other")).build(), Perm.TELEPORT, (a, c) -> {
                ServerWorld w = Mc.world(Ac.server(), d.world);
                if (w != null) {
                    a.closeHandledScreen();
                    boolean inv = Ac.get().staff.teleportInvisible(a.getUuid(), Ac.config().staff.teleportInvisibleByDefault) != c.isRight();
                    StaffTools.teleportTo(a, w, d.pos, inv, "death #" + d.id);
                }
            });
            Btn restore = Btn.of(d.restored ? Items.GRAY_DYE : Items.LIME_DYE).name(Category.DEATHS, Msg.tr("in.restore")).desc(Msg.tr("in.restore-desc"));
            if (d.restored) {
                restore.status(Theme.SOFT, Theme.Sym.CHECK.sp() + Msg.tr("in.restored"));
            } else {
                restore.left(Msg.tr("cm.action.restore"));
            }
            menu.set(43, restore.build(), Perm.DEATH_RESTORE, (a, c) -> {
                if (d.restored) {
                    return;
                }
                Confirm.open(a, Category.DEATHS, Msg.tr("in.confirm-restore", d.playerName), Msg.tr("in.restore-desc"),
                        Btn.head(d.player, d.playerName).name(Category.DEATHS, d.playerName).build(), () -> {
                            Deaths.restore(a, d.id);
                            death(a, d.id);
                        });
            });
            for (int i = 44; i < 54; i++) {
                if (!menu.has(i)) {
                    menu.icon(i, Btn.pane(Category.DEATHS.glass));
                }
            }
            menu.set(45, Menu.back(), (a, c) -> deaths(a, d.player, 0));
            menu.set(53, Menu.close(), (a, c) -> a.closeHandledScreen());
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
        Menu m = page(admin, target, "private");
        m.renderer(menu -> {
            Ac ac = Ac.get();
            Btn ips = Btn.of(Items.NAME_TAG).name(Category.PLAYERS, Msg.tr("in.ips"));
            var list = ac.joins.ipsOf(target);
            if (list.isEmpty()) {
                ips.line(Msg.tr("rv.none"));
            }
            for (String ip : list) {
                ips.line(ip);
            }
            menu.info(ips.build());
            List<UUID> alts = new ArrayList<>(ac.joins.alts(target));
            menu.list(alts, alt -> {
                        boolean banned = ac.punishments.isBanned(alt);
                        return Btn.head(alt, name(alt)).name(Category.PLAYERS, name(alt))
                                .status(banned ? Theme.RED : Theme.SOFT, Theme.Sym.DOT.sp() + Msg.tr(banned ? "in.banned" : "in.not-banned"))
                                .left(Msg.tr("panel.action.inspect")).build();
                    },
                    alt -> (a, c) -> open(a, alt), InspectMenu::name, List.of(), Msg.tr("in.no-alts"), Msg.tr("in.no-alts-hint"));
            nav(menu, target, "private");
        });
        m.open(admin);
    }
}
