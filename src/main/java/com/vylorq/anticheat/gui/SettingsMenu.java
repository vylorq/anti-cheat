package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.perm.PermissionPolicy;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.watcher.WatcherEffect;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Sounds;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.Difficulty;
import net.minecraft.world.rule.GameRule;
import net.minecraft.world.rule.GameRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * /settings (34.8): a hub of pages. Every setting shows what it does, its current and default value, how to
 * change it, and a gold "Changed" marker when it differs from the default. Shift + right-click resets one setting,
 * the page button resets a whole page (both ask first). Changes apply at once, play a sound and go in the staff log.
 */
public final class SettingsMenu {
    private SettingsMenu() {
    }

    public enum Page {
        GENERAL(Items.NAME_TAG, Perm.SETTINGS),
        WORLD(Items.GRASS_BLOCK, Perm.SETTINGS),
        ANTICHEAT(Items.SHIELD, Perm.SETTINGS),
        PROTECTION(Items.STICK, Perm.SETTINGS),
        LOBBY_JAIL(Items.IRON_BARS, Perm.SETTINGS),
        WAITING(Items.BLACK_CANDLE, Perm.SETTINGS),
        ARENAS(Items.DIAMOND_SWORD, Perm.SETTINGS),
        TRADERS(Items.EMERALD, Perm.SETTINGS),
        ECONOMY(Items.GOLD_INGOT, Perm.SETTINGS),
        WATCHER(Items.ENDER_EYE, Perm.WATCHER),
        STYLE(Items.PAINTING, Perm.SETTINGS);

        final Item icon;
        final Perm perm;

        Page(Item icon, Perm perm) {
            this.icon = icon;
            this.perm = perm;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    // ---- setting types ----

    /** One setting button. */
    abstract static class S {
        final String id;
        final Page page;
        final Item icon;
        Perm perm;

        S(String id, Page page, Item icon) {
            this.id = id;
            this.page = page;
            this.icon = icon;
            this.perm = page.perm;
        }

        S owner() {
            this.perm = Perm.MANAGE_ADMINS;
            return this;
        }

        String name() {
            return Msg.tr("set." + id);
        }

        String desc() {
            return Msg.tr("set." + id + ".desc");
        }

        /** Current value as shown. */
        abstract String now();

        /** Default value as shown, or null when there's no single default. */
        abstract String def();

        boolean changed() {
            String d = def();
            return d != null && !d.equals(now());
        }

        Item icon() {
            return icon;
        }

        abstract void hints(Btn b);

        /** Handles a click (not reset). */
        abstract void click(ServerPlayerEntity p, Menu.Click c, Menu menu);

        abstract void reset();
    }

    static AcConfig cfg() {
        return Ac.config();
    }

    static final AcConfig DEFAULTS = new AcConfig().normalize();

    static String onOff(boolean b) {
        return Msg.tr(b ? "ui.status.on" : "ui.status.off");
    }

    static final class Toggle extends S {
        final Function<AcConfig, Boolean> get;
        final BiConsumer<AcConfig, Boolean> set;

        Toggle(String id, Page page, Function<AcConfig, Boolean> get, BiConsumer<AcConfig, Boolean> set) {
            super(id, page, Items.LIME_DYE);
            this.get = get;
            this.set = set;
        }

        @Override
        Item icon() {
            return get.apply(cfg()) ? Items.LIME_DYE : Items.GRAY_DYE;
        }

        @Override
        String now() {
            return onOff(get.apply(cfg()));
        }

        @Override
        String def() {
            return onOff(get.apply(DEFAULTS));
        }

        @Override
        void hints(Btn b) {
            b.left(Msg.tr(get.apply(cfg()) ? "ui.action.turn-off" : "ui.action.turn-on"));
        }

        @Override
        void click(ServerPlayerEntity p, Menu.Click c, Menu menu) {
            boolean v = !get.apply(cfg());
            set.accept(cfg(), v);
            applied(p, this, onOff(v));
        }

        @Override
        void reset() {
            set.accept(cfg(), get.apply(DEFAULTS));
        }
    }

    static final class Num extends S {
        final Function<AcConfig, Integer> get;
        final BiConsumer<AcConfig, Integer> set;
        final int min;
        final int max;
        final int step;

        Num(String id, Page page, Item icon, Function<AcConfig, Integer> get, BiConsumer<AcConfig, Integer> set, int min, int max, int step) {
            super(id, page, icon);
            this.get = get;
            this.set = set;
            this.min = min;
            this.max = max;
            this.step = step;
        }

        @Override
        String now() {
            return String.valueOf(get.apply(cfg()));
        }

        @Override
        String def() {
            return String.valueOf(get.apply(DEFAULTS));
        }

        @Override
        void hints(Btn b) {
            b.line(Msg.tr("settings.range", min, max));
            b.left("+" + step).right("-" + step).shift("+" + step * 10);
        }

        @Override
        void click(ServerPlayerEntity p, Menu.Click c, Menu menu) {
            int d = c.isShift() ? step * 10 : c.isRight() ? -step : step;
            int v = Math.max(min, Math.min(max, get.apply(cfg()) + d));
            set.accept(cfg(), v);
            applied(p, this, String.valueOf(v));
        }

        @Override
        void reset() {
            set.accept(cfg(), get.apply(DEFAULTS));
        }
    }

    static final class Dbl extends S {
        final Function<AcConfig, Double> get;
        final BiConsumer<AcConfig, Double> set;
        final double min;
        final double max;
        final double step;

        Dbl(String id, Page page, Item icon, Function<AcConfig, Double> get, BiConsumer<AcConfig, Double> set, double min, double max, double step) {
            super(id, page, icon);
            this.get = get;
            this.set = set;
            this.min = min;
            this.max = max;
            this.step = step;
        }

        static String fmt(double v) {
            String s = String.format(Locale.ROOT, "%.2f", v);
            return s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }

        @Override
        String now() {
            return fmt(get.apply(cfg()));
        }

        @Override
        String def() {
            return fmt(get.apply(DEFAULTS));
        }

        @Override
        void hints(Btn b) {
            b.line(Msg.tr("settings.range", fmt(min), fmt(max)));
            b.left("+" + fmt(step)).right("-" + fmt(step)).shift("+" + fmt(step * 10));
        }

        @Override
        void click(ServerPlayerEntity p, Menu.Click c, Menu menu) {
            double d = c.isShift() ? step * 10 : c.isRight() ? -step : step;
            double v = Math.max(min, Math.min(max, Math.round((get.apply(cfg()) + d) * 1000) / 1000.0));
            set.accept(cfg(), v);
            applied(p, this, fmt(v));
        }

        @Override
        void reset() {
            set.accept(cfg(), get.apply(DEFAULTS));
        }
    }

    static final class Choice extends S {
        final List<String> values;
        final Function<String, String> label;
        final Function<AcConfig, String> get;
        final BiConsumer<AcConfig, String> set;

        Choice(String id, Page page, Item icon, List<String> values, Function<String, String> label, Function<AcConfig, String> get,
               BiConsumer<AcConfig, String> set) {
            super(id, page, icon);
            this.values = values;
            this.label = label;
            this.get = get;
            this.set = set;
        }

        @Override
        String now() {
            return label.apply(get.apply(cfg()));
        }

        @Override
        String def() {
            return label.apply(get.apply(DEFAULTS));
        }

        @Override
        void hints(Btn b) {
            String cur = get.apply(cfg());
            for (String v : values) {
                b.status(v.equals(cur) ? Theme.GOLD_LIGHT : Theme.SOFT, (v.equals(cur) ? Theme.Sym.ARROW.sp() : "  ") + label.apply(v));
            }
            b.left(Msg.tr("settings.next-option")).right(Msg.tr("settings.prev-option"));
        }

        @Override
        void click(ServerPlayerEntity p, Menu.Click c, Menu menu) {
            int i = Math.max(0, values.indexOf(get.apply(cfg())));
            String v = values.get(Math.floorMod(i + (c.isRight() ? -1 : 1), values.size()));
            set.accept(cfg(), v);
            applied(p, this, label.apply(v));
        }

        @Override
        void reset() {
            set.accept(cfg(), get.apply(DEFAULTS));
        }
    }

    /** A length of time: presets on left-click, any time typed on right-click. */
    static final class Time extends S {
        static final String[] PRESETS = {"30m", "1h", "6h", "1d", "7d", "permanent"};
        final Function<AcConfig, Long> get;
        final BiConsumer<AcConfig, Long> set;

        Time(String id, Page page, Item icon, Function<AcConfig, Long> get, BiConsumer<AcConfig, Long> set) {
            super(id, page, icon);
            this.get = get;
            this.set = set;
        }

        static String show(long v) {
            return v == Durations.PERMANENT ? Msg.tr("ui.permanent") : Durations.format(v);
        }

        @Override
        String now() {
            return show(get.apply(cfg()));
        }

        @Override
        String def() {
            return show(get.apply(DEFAULTS));
        }

        @Override
        void hints(Btn b) {
            b.line(Msg.tr("settings.presets", String.join(", ", PRESETS)));
            b.left(Msg.tr("settings.next-preset")).right(Msg.tr("settings.type-time"));
        }

        @Override
        void click(ServerPlayerEntity p, Menu.Click c, Menu menu) {
            if (c.isRight()) {
                Input.text(p, name(), now(), txt -> {
                    OptionalLong d = Durations.parse(txt == null ? "" : txt.trim());
                    if (d.isEmpty()) {
                        Msg.error(p, "general.bad-duration");
                    } else {
                        set.accept(cfg(), d.getAsLong());
                        applied(p, this, show(d.getAsLong()));
                    }
                    reopen(p, menu);
                });
                return;
            }
            long cur = get.apply(cfg());
            int next = 0;
            for (int i = 0; i < PRESETS.length; i++) {
                if (Durations.parse(PRESETS[i]).orElse(-1) == cur) {
                    next = (i + 1) % PRESETS.length;
                }
            }
            long v = Durations.parse(PRESETS[next]).orElse(Durations.PERMANENT);
            set.accept(cfg(), v);
            applied(p, this, show(v));
        }

        @Override
        void reset() {
            set.accept(cfg(), get.apply(DEFAULTS));
        }
    }

    static final class TextS extends S {
        final Function<AcConfig, String> get;
        final BiConsumer<AcConfig, String> set;

        TextS(String id, Page page, Item icon, Function<AcConfig, String> get, BiConsumer<AcConfig, String> set) {
            super(id, page, icon);
            this.get = get;
            this.set = set;
        }

        @Override
        String now() {
            String v = get.apply(cfg());
            return v == null || v.isEmpty() ? Msg.tr("settings.empty") : v;
        }

        @Override
        String def() {
            String v = get.apply(DEFAULTS);
            return v == null || v.isEmpty() ? Msg.tr("settings.empty") : v;
        }

        @Override
        void hints(Btn b) {
            b.left(Msg.tr("settings.type-text"));
        }

        @Override
        void click(ServerPlayerEntity p, Menu.Click c, Menu menu) {
            Input.text(p, name(), get.apply(cfg()), txt -> {
                if (txt != null) {
                    set.accept(cfg(), txt);
                    applied(p, this, txt);
                }
                reopen(p, menu);
            });
        }

        @Override
        void reset() {
            set.accept(cfg(), get.apply(DEFAULTS));
        }
    }

    /** A list of entries (e.g. never-sell items): opens a page to add and remove them. */
    static final class ListS extends S {
        final Function<AcConfig, List<String>> get;
        final BiConsumer<AcConfig, List<String>> set;

        ListS(String id, Page page, Item icon, Function<AcConfig, List<String>> get, BiConsumer<AcConfig, List<String>> set) {
            super(id, page, icon);
            this.get = get;
            this.set = set;
        }

        @Override
        String now() {
            return Msg.tr("settings.entries", get.apply(cfg()).size());
        }

        @Override
        String def() {
            return Msg.tr("settings.entries", get.apply(DEFAULTS).size());
        }

        @Override
        boolean changed() {
            return !get.apply(cfg()).equals(get.apply(DEFAULTS));
        }

        @Override
        void hints(Btn b) {
            List<String> l = get.apply(cfg());
            for (int i = 0; i < Math.min(4, l.size()); i++) {
                b.status(Theme.SOFT, "  " + l.get(i));
            }
            if (l.size() > 4) {
                b.status(Theme.SOFT, "  …");
            }
            b.left(Msg.tr("settings.edit-list"));
        }

        @Override
        void click(ServerPlayerEntity p, Menu.Click c, Menu parent) {
            Menu m = Menu.std(Category.SETTINGS, Msg.tr("cat.settings"), name());
            ListS self = this;
            m.renderer(menu -> {
                menu.info(Btn.of(icon).name(Category.SETTINGS, name()).desc(desc()).build());
                menu.set(Menu.INFO + 4, Btn.of(Items.LIME_DYE).color(Theme.GREEN).name(Msg.tr("settings.add")).left(Msg.tr("settings.add")).build(),
                        perm, (pl, ck) -> Input.text(pl, Msg.tr("settings.add"), "", txt -> {
                            if (txt != null && !txt.isBlank()) {
                                List<String> l = new ArrayList<>(get.apply(cfg()));
                                l.add(txt.trim());
                                set.accept(cfg(), l);
                                applied(pl, self, "+ " + txt.trim());
                            }
                            reopen(pl, menu);
                        }));
                menu.list(get.apply(cfg()), e -> Btn.of(Items.PAPER).color(Theme.WHITE).name(e).shift(Msg.tr("settings.remove")).build(),
                        e -> (pl, ck) -> {
                            if (!ck.isShift()) {
                                return;
                            }
                            List<String> l = new ArrayList<>(get.apply(cfg()));
                            l.remove(e);
                            set.accept(cfg(), l);
                            applied(pl, self, "- " + e);
                            menu.refresh();
                        },
                        e -> e, List.of(), Msg.tr("settings.list-empty"), Msg.tr("settings.list-empty-hint"));
            });
            m.open(p);
        }

        @Override
        void reset() {
            set.accept(cfg(), new ArrayList<>(get.apply(DEFAULTS)));
        }
    }

    /** Anything that isn't a plain config value (game rules, maintenance, links to other menus). */
    static class Custom extends S {
        final Supplier<String> now;
        final Supplier<String> def;
        final BiConsumer<ServerPlayerEntity, Menu.Click> click;
        final Runnable reset;
        final String hint;
        Supplier<Item> iconFn;

        Custom(String id, Page page, Item icon, Supplier<String> now, Supplier<String> def, String hintKey,
               BiConsumer<ServerPlayerEntity, Menu.Click> click, Runnable reset) {
            super(id, page, icon);
            this.now = now;
            this.def = def;
            this.hint = hintKey;
            this.click = click;
            this.reset = reset;
        }

        Custom icon(Supplier<Item> f) {
            this.iconFn = f;
            return this;
        }

        @Override
        Item icon() {
            return iconFn != null ? iconFn.get() : icon;
        }

        @Override
        String now() {
            return now.get();
        }

        @Override
        String def() {
            return def == null ? null : def.get();
        }

        @Override
        void hints(Btn b) {
            b.left(Msg.tr(hint));
        }

        @Override
        void click(ServerPlayerEntity p, Menu.Click c, Menu menu) {
            click.accept(p, c);
        }

        @Override
        void reset() {
            if (reset != null) {
                reset.run();
            }
        }
    }

    // ---- registry ----

    private static boolean rule(GameRule<Boolean> key) {
        return Ac.server().getOverworld().getGameRules().getValue(key);
    }

    private static void setRule(ServerPlayerEntity p, GameRule<Boolean> key, boolean v) {
        MinecraftServer s = Ac.server();
        for (var w : s.getWorlds()) {
            w.getGameRules().setValue(key, v, s);
        }
    }

    private static Custom gameRule(String id, GameRule<Boolean> key, boolean inverted) {
        Custom c = new Custom(id, Page.WORLD, Items.LIME_DYE,
                () -> onOff(rule(key) != inverted), () -> onOff(key.getDefaultValue() != inverted), null,
                (p, ck) -> {
                    setRule(p, key, !rule(key));
                    applied(p, null, Msg.trFor(p, "set." + id) + ": " + onOff(rule(key) != inverted));
                },
                () -> setRule(null, key, key.getDefaultValue()));
        return c.icon(() -> rule(key) != inverted ? Items.LIME_DYE : Items.GRAY_DYE);
    }

    private static boolean fireSpreads() {
        return Ac.server().getOverworld().getGameRules().getValue(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER) > 0;
    }

    private static void setFireSpread(boolean on) {
        MinecraftServer s = Ac.server();
        int radius = on ? GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER.getDefaultValue() : 0;
        for (var w : s.getWorlds()) {
            w.getGameRules().setValue(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, radius, s);
        }
    }

    static String langName(String code) {
        return code.equals("ar_sa") ? "العربية" : "English";
    }

    private static List<S> build() {
        List<S> l = new ArrayList<>();
        // General
        l.add(new TextS("server-name", Page.GENERAL, Items.NAME_TAG, c -> c.general.serverName, (c, v) -> c.general.serverName = v));
        l.add(new Choice("language", Page.GENERAL, Items.GLOBE_BANNER_PATTERN, List.of("en_us", "ar_sa"), SettingsMenu::langName,
                c -> c.general.language, (c, v) -> c.general.language = v));
        l.add(new Custom("maintenance", Page.GENERAL, Items.REDSTONE_TORCH, () -> onOff(Ac.get().staff.maintenance()), () -> onOff(false),
                "ui.action.turn-on", (p, ck) -> {
            boolean on = Ac.get().staff.maintenance();
            if (on) {
                com.vylorq.anticheat.util.Mc.run(p, "maintenance off");
            } else {
                Confirm.open(p, Category.SETTINGS, Msg.tr("panel.confirm.maintenance"), Msg.tr("panel.confirm.maintenance-detail"), null,
                        () -> com.vylorq.anticheat.util.Mc.run(p, "maintenance on"));
            }
        }, () -> {
            Ac.get().staff.setMaintenance(false);
            Ac.markDirty("staff");
        }).icon(() -> Ac.get().staff.maintenance() ? Items.LIME_DYE : Items.GRAY_DYE));
        l.add(new Toggle("restarts", Page.GENERAL, c -> c.restarts.enabled, (c, v) -> c.restarts.enabled = v));
        l.add(new ListS("restart-times", Page.GENERAL, Items.CLOCK, c -> c.restarts.times, (c, v) -> c.restarts.times = v));
        l.add(new Toggle("restart-backup", Page.GENERAL, c -> c.restarts.backupBeforeRestart, (c, v) -> c.restarts.backupBeforeRestart = v));
        l.add(new Toggle("chat-on", Page.GENERAL, c -> c.chat.chatEnabled, (c, v) -> c.chat.chatEnabled = v));
        l.add(new Toggle("daily-backups", Page.GENERAL, c -> c.storage.dailyBackups, (c, v) -> c.storage.dailyBackups = v));
        l.add(new Num("snapshots-kept", Page.GENERAL, Items.CHEST, c -> c.staff.snapshotsKept, (c, v) -> c.staff.snapshotsKept = v, 1, 200, 5));
        l.add(new Num("auto-snapshot", Page.GENERAL, Items.CLOCK, c -> c.staff.autoSnapshotMinutes, (c, v) -> c.staff.autoSnapshotMinutes = v, 0, 1440, 15));
        l.add(new Toggle("item-blacklist", Page.PROTECTION, c -> c.itemBlacklist.enabled, (c, v) -> c.itemBlacklist.enabled = v));
        l.add(new ListS("blacklist-items", Page.PROTECTION, Items.BARRIER, c -> c.itemBlacklist.items, (c, v) -> c.itemBlacklist.items = v));
        l.add(new Toggle("blacklist-ender", Page.PROTECTION, c -> c.itemBlacklist.includeEnderChest, (c, v) -> c.itemBlacklist.includeEnderChest = v));
        l.add(new Num("log-days", Page.GENERAL, Items.BOOK, c -> c.storage.logRetentionDays, (c, v) -> c.storage.logRetentionDays = v, 1, 365, 1));
        l.add(new Toggle("admins-settings", Page.GENERAL, c -> c.permissions.adminsUseSettings, (c, v) -> c.permissions.adminsUseSettings = v).owner());
        l.add(new Toggle("admins-private", Page.GENERAL, c -> c.permissions.adminsSeePrivateInfo, (c, v) -> c.permissions.adminsSeePrivateInfo = v).owner());
        l.add(new Toggle("staff-pin", Page.GENERAL, c -> c.staff.requirePin, (c, v) -> c.staff.requirePin = v).owner());
        // PvP & world
        l.add(gameRule("pvp", GameRules.PVP, false));
        l.add(gameRule("keep-inventory", GameRules.KEEP_INVENTORY, false));
        l.add(gameRule("natural-regen", GameRules.NATURAL_HEALTH_REGENERATION, false));
        l.add(new Custom("difficulty", Page.WORLD, Items.ZOMBIE_HEAD,
                () -> Ac.server().getSaveProperties().getDifficulty().getName(), () -> Difficulty.NORMAL.getName(), "settings.next-option",
                (p, ck) -> {
                    MinecraftServer s = Ac.server();
                    Difficulty next = Difficulty.byId((s.getSaveProperties().getDifficulty().getId() + (ck.isRight() ? 3 : 1)) % 4);
                    s.setDifficulty(next, true);
                    applied(p, null, Msg.trFor(p, "set.difficulty") + ": " + next.getName());
                }, () -> Ac.server().setDifficulty(Difficulty.NORMAL, true)));
        l.add(gameRule("mob-griefing", GameRules.DO_MOB_GRIEFING, false));
        l.add(new Custom("fire-spread", Page.WORLD, Items.FLINT_AND_STEEL, () -> onOff(fireSpreads()), () -> onOff(true), "ui.action.turn-on",
                (p, ck) -> {
                    setFireSpread(!fireSpreads());
                    applied(p, null, Msg.trFor(p, "set.fire-spread") + ": " + onOff(fireSpreads()));
                }, () -> setFireSpread(true)).icon(() -> fireSpreads() ? Items.LIME_DYE : Items.GRAY_DYE));
        l.add(new Custom("explosions", Page.WORLD, Items.TNT, () -> onOff(Ac.get().misc.explosionsEnabled), () -> onOff(true), "ui.action.turn-on",
                (p, ck) -> {
                    Ac.get().misc.explosionsEnabled = !Ac.get().misc.explosionsEnabled;
                    Ac.markDirty("misc");
                    applied(p, null, Msg.trFor(p, "set.explosions") + ": " + onOff(Ac.get().misc.explosionsEnabled));
                }, () -> {
            Ac.get().misc.explosionsEnabled = true;
            Ac.markDirty("misc");
        }).icon(() -> Ac.get().misc.explosionsEnabled ? Items.LIME_DYE : Items.GRAY_DYE));
        l.add(gameRule("time-lock", GameRules.ADVANCE_TIME, true));
        l.add(gameRule("weather-lock", GameRules.ADVANCE_WEATHER, true));
        l.add(new Toggle("ban-effects", Page.WORLD, c -> c.fun.banEffects, (c, v) -> c.fun.banEffects = v));
        l.add(new Toggle("caught-counter", Page.WORLD, c -> c.fun.caughtCounter, (c, v) -> c.fun.caughtCounter = v));
        l.add(new Toggle("the-end", Page.WORLD, c -> c.general.endOpen, (c, v) -> c.general.endOpen = v));
        // Anti-cheat
        l.add(new Custom("sensitivity", Page.ANTICHEAT, Items.COMPARATOR, () -> Msg.tr("settings.checks", CheckType.values().length), null,
                "ui.action.open", (p, ck) -> sensitivity(p), null));
        l.add(new Num("review-threshold", Page.ANTICHEAT, Items.WRITABLE_BOOK, c -> c.detection.reviewThreshold, (c, v) -> c.detection.reviewThreshold = v, 5, 100, 5));
        l.add(new Toggle("warnings", Page.ANTICHEAT, c -> c.warnings.enabled, (c, v) -> c.warnings.enabled = v));
        l.add(new Num("warn-score", Page.ANTICHEAT, Items.BELL, c -> c.warnings.warnScore, (c, v) -> c.warnings.warnScore = v, 5, 100, 5));
        l.add(new Toggle("auto-watch", Page.ANTICHEAT, c -> c.detection.autoWatchEnabled, (c, v) -> c.detection.autoWatchEnabled = v));
        l.add(new Num("auto-watch-score", Page.ANTICHEAT, Items.OBSERVER, c -> c.detection.autoWatchScore, (c, v) -> c.detection.autoWatchScore = v, 5, 100, 5));
        l.add(new Num("alert-score", Page.ANTICHEAT, Items.BELL, c -> c.detection.alertScore, (c, v) -> c.detection.alertScore = v, 5, 100, 5));
        l.add(new Toggle("movement-checks", Page.ANTICHEAT, c -> c.movement.enabled, (c, v) -> c.movement.enabled = v));
        l.add(new Toggle("setbacks", Page.ANTICHEAT, c -> c.movement.setbacks, (c, v) -> c.movement.setbacks = v));
        l.add(new Toggle("combat-checks", Page.ANTICHEAT, c -> c.combat.enabled, (c, v) -> c.combat.enabled = v));
        l.add(new Toggle("ore-hiding", Page.ANTICHEAT, c -> c.xray.oreHiding, (c, v) -> c.xray.oreHiding = v));
        l.add(new Toggle("xray-traps", Page.ANTICHEAT, c -> c.xray.trapEnabled, (c, v) -> c.xray.trapEnabled = v));
        l.add(new Toggle("ore-alerts", Page.ANTICHEAT, c -> c.xray.oreAlerts, (c, v) -> c.xray.oreAlerts = v));
        l.add(new Toggle("illegal-items", Page.ANTICHEAT, c -> c.illegalItems.enabled, (c, v) -> c.illegalItems.enabled = v));
        l.add(new Toggle("dupe-watch", Page.ANTICHEAT, c -> c.dupeWatch.enabled, (c, v) -> c.dupeWatch.enabled = v));
        l.add(new Toggle("chat-protection", Page.ANTICHEAT, c -> c.chat.enabled, (c, v) -> c.chat.enabled = v));
        l.add(new Toggle("block-links", Page.ANTICHEAT, c -> c.chat.blockLinks, (c, v) -> c.chat.blockLinks = v));
        l.add(new Dbl("watch-multiplier", Page.ANTICHEAT, Items.SPYGLASS, c -> c.watchlist.pointMultiplier, (c, v) -> c.watchlist.pointMultiplier = v, 1, 3, 0.1));
        // Protection
        l.add(new Custom("spawn-protection", Page.PROTECTION, Items.BEACON, () -> {
            var sp = Ac.get().claims.get("spawn");
            return onOff(sp != null && sp.isActive());
        }, null, "ui.action.open", (p, ck) -> {
            var sp = Ac.get().claims.get("spawn");
            if (sp != null) {
                ClaimMenu.claim(p, sp);
            } else {
                p.closeHandledScreen();
                Msg.info(p, "settings.spawn-how");
            }
        }, null));
        l.add(new Num("claim-gap", Page.PROTECTION, Items.STICK, c -> c.claims.minGap, (c, v) -> c.claims.minGap = v, 0, 100, 1));
        l.add(new Num("claim-area", Page.PROTECTION, Items.MAP, c -> c.claims.maxClaimArea, (c, v) -> c.claims.maxClaimArea = v, 10_000, 100_000_000, 10_000));
        l.add(new Toggle("claim-archive", Page.PROTECTION, c -> c.claims.archiveOnExpiry, (c, v) -> c.claims.archiveOnExpiry = v));
        l.add(new Num("grief-alert", Page.PROTECTION, Items.BELL, c -> c.claims.griefAlertAttempts, (c, v) -> c.claims.griefAlertAttempts = v, 1, 50, 1));
        l.add(new Num("max-tnt", Page.PROTECTION, Items.TNT, c -> c.redstone.maxPrimedTntPerChunk, (c, v) -> c.redstone.maxPrimedTntPerChunk = v, 1, 500, 5));
        l.add(new Toggle("tnt-dupers", Page.PROTECTION, c -> c.redstone.blockTntDupers, (c, v) -> c.redstone.blockTntDupers = v));
        l.add(new Num("max-entities", Page.PROTECTION, Items.ZOMBIE_HEAD, c -> c.redstone.maxEntitiesPerChunk, (c, v) -> c.redstone.maxEntitiesPerChunk = v, 10, 2000, 10));
        l.add(new Num("max-items", Page.PROTECTION, Items.HOPPER, c -> c.redstone.maxItemsPerChunk, (c, v) -> c.redstone.maxItemsPerChunk = v, 10, 5000, 10));
        l.add(new Num("max-minecarts", Page.PROTECTION, Items.MINECART, c -> c.redstone.maxMinecartsPerChunk, (c, v) -> c.redstone.maxMinecartsPerChunk = v, 1, 500, 5));
        l.add(new Num("max-armor-stands", Page.PROTECTION, Items.ARMOR_STAND, c -> c.redstone.maxArmorStandsPerChunk, (c, v) -> c.redstone.maxArmorStandsPerChunk = v, 1, 500, 5));
        l.add(new Toggle("alt-detection", Page.PROTECTION, c -> c.joins.altDetection, (c, v) -> c.joins.altDetection = v));
        l.add(new Num("new-accounts", Page.PROTECTION, Items.PLAYER_HEAD, c -> c.joins.maxNewAccountsPerMinute, (c, v) -> c.joins.maxNewAccountsPerMinute = v, 1, 100, 1));
        // Lobby & jail
        l.add(new Toggle("lobby-hunger", Page.LOBBY_JAIL, c -> c.lobby.noHunger, (c, v) -> c.lobby.noHunger = v));
        l.add(new Toggle("lobby-fall", Page.LOBBY_JAIL, c -> c.lobby.noFallDamage, (c, v) -> c.lobby.noFallDamage = v));
        l.add(new Toggle("lobby-pvp", Page.LOBBY_JAIL, c -> c.lobby.noPvp, (c, v) -> c.lobby.noPvp = v));
        l.add(new Num("lobby-refill", Page.LOBBY_JAIL, Items.CHEST, c -> c.lobby.lootChestRefillMinutes, (c, v) -> c.lobby.lootChestRefillMinutes = v, 1, 1440, 5));
        l.add(new Toggle("jail-online", Page.LOBBY_JAIL, c -> c.jail.onlineTimeOnly, (c, v) -> c.jail.onlineTimeOnly = v));
        l.add(new Toggle("jail-chat", Page.LOBBY_JAIL, c -> c.jail.allowChat, (c, v) -> c.jail.allowChat = v));
        l.add(new Toggle("jail-announce", Page.LOBBY_JAIL, c -> c.jail.announce, (c, v) -> c.jail.announce = v));
        l.add(new TextS("jail-release", Page.LOBBY_JAIL, Items.OAK_SIGN, c -> c.jail.releaseMessage, (c, v) -> c.jail.releaseMessage = v));
        // Waiting room
        l.add(new Toggle("waiting-room", Page.WAITING, c -> c.waitingRoom.enabled, (c, v) -> c.waitingRoom.enabled = v));
        l.add(new Time("deny-ban", Page.WAITING, Items.CLOCK, c -> c.waitingRoom.denyBanMillis, (c, v) -> c.waitingRoom.denyBanMillis = v));
        l.add(new Toggle("deny-escalate", Page.WAITING, c -> c.waitingRoom.escalate, (c, v) -> c.waitingRoom.escalate = v));
        l.add(new ListS("deny-steps", Page.WAITING, Items.LADDER, c -> c.waitingRoom.escalationSteps, (c, v) -> c.waitingRoom.escalationSteps = v));
        // Arenas
        l.add(new Custom("arena-list", Page.ARENAS, Items.DIAMOND_SWORD, () -> Msg.tr("settings.entries", Ac.get().arenas.data().arenas.size()), null,
                "ui.action.open", (p, ck) -> ArenaMenu.list(p), null));
        l.add(new Num("arena-countdown", Page.ARENAS, Items.CLOCK, c -> c.arenas.countdownSeconds, (c, v) -> c.arenas.countdownSeconds = v, 1, 30, 1));
        l.add(new Num("arena-queue", Page.ARENAS, Items.HOPPER, c -> c.arenas.queueTimeoutSeconds, (c, v) -> c.arenas.queueTimeoutSeconds = v, 30, 3600, 30));
        l.add(new Num("duel-request", Page.ARENAS, Items.IRON_SWORD, c -> c.arenas.duelRequestSeconds, (c, v) -> c.arenas.duelRequestSeconds = v, 5, 300, 5));
        // Traders
        l.add(new Num("trader-rotation", Page.TRADERS, Items.CLOCK, c -> c.traders.rotationMinutes, (c, v) -> c.traders.rotationMinutes = v, 30, 1440, 30));
        l.add(new Dbl("min-markup", Page.TRADERS, Items.GOLD_NUGGET, c -> c.traders.minMarkup, (c, v) -> c.traders.minMarkup = Math.min(v, c.traders.maxMarkup), 1, 5, 0.05));
        l.add(new Dbl("max-markup", Page.TRADERS, Items.GOLD_INGOT, c -> c.traders.maxMarkup, (c, v) -> c.traders.maxMarkup = Math.max(v, c.traders.minMarkup), 1, 5, 0.05));
        l.add(new Num("min-offers", Page.TRADERS, Items.PAPER, c -> c.traders.minOffers, (c, v) -> c.traders.minOffers = Math.min(v, c.traders.maxOffers), 1, 20, 1));
        l.add(new Num("max-offers", Page.TRADERS, Items.PAPER, c -> c.traders.maxOffers, (c, v) -> c.traders.maxOffers = Math.max(v, c.traders.minOffers), 1, 20, 1));
        l.add(new Num("rare-cap", Page.TRADERS, Items.DIAMOND, c -> c.traders.weeklyRareCap, (c, v) -> c.traders.weeklyRareCap = v, 0, 100, 1));
        l.add(new Num("legendary-cap", Page.TRADERS, Items.NETHER_STAR, c -> c.traders.weeklyLegendaryCap, (c, v) -> c.traders.weeklyLegendaryCap = v, 0, 50, 1));
        l.add(new Num("player-rare-cap", Page.TRADERS, Items.DIAMOND, c -> c.traders.perPlayerRarePerWeek, (c, v) -> c.traders.perPlayerRarePerWeek = v, 0, 50, 1));
        l.add(new Num("player-legendary-cap", Page.TRADERS, Items.NETHER_STAR, c -> c.traders.perPlayerLegendaryPerWeek, (c, v) -> c.traders.perPlayerLegendaryPerWeek = v, 0, 20, 1));
        l.add(new Num("rare-odds", Page.TRADERS, Items.DIAMOND, c -> c.traders.rareOdds, (c, v) -> c.traders.rareOdds = v, 1, 100_000, 100));
        l.add(new Num("legendary-odds", Page.TRADERS, Items.NETHER_STAR, c -> c.traders.legendaryOdds, (c, v) -> c.traders.legendaryOdds = v, 1, 100_000, 100));
        l.add(new Num("buys-per-day", Page.TRADERS, Items.EMERALD, c -> c.traders.buysPerDay, (c, v) -> c.traders.buysPerDay = v, 0, 1000, 1));
        l.add(new Num("sell-cap", Page.TRADERS, Items.WHEAT, c -> c.traders.sellDailyCapPerPlayer, (c, v) -> c.traders.sellDailyCapPerPlayer = v, 16, 10_000, 16));
        l.add(new ListS("never-sell", Page.TRADERS, Items.BARRIER, c -> c.traders.neverSell, (c, v) -> c.traders.neverSell = v));
        l.add(new Choice("trader-payment", Page.TRADERS, Items.GOLD_INGOT, List.of("items", "emeralds", "cash"), v -> Msg.tr("tr.pay." + v),
                c -> c.traders.defaultPayment, (c, v) -> c.traders.defaultPayment = v));
        l.add(new Num("price-change", Page.TRADERS, Items.CLOCK, c -> c.traders.priceChangeMinutes, (c, v) -> c.traders.priceChangeMinutes = v, 5, 600, 5));
        l.add(new Toggle("only-obtained", Page.TRADERS, c -> c.traders.onlyObtainedItems, (c, v) -> c.traders.onlyObtainedItems = v));
        // Economy
        l.add(new Toggle("market", Page.ECONOMY, c -> c.market.enabled, (c, v) -> c.market.enabled = v));
        l.add(new Choice("market-currency", Page.ECONOMY, Items.GOLD_NUGGET, List.of("cash", "minecraft:emerald"),
                v -> Msg.tr(v.equals("cash") ? "tr.pay.cash" : "tr.pay.emeralds"), c -> c.market.currency, (c, v) -> c.market.currency = v));
        l.add(new Num("starting-cash", Page.ECONOMY, Items.GOLD_INGOT, c -> c.market.startingCash, (c, v) -> c.market.startingCash = v, 0, 100_000, 50));
        l.add(new Dbl("cash-per-value", Page.ECONOMY, Items.GOLD_BLOCK, c -> c.market.cashPerValue, (c, v) -> c.market.cashPerValue = v, 0.1, 10, 0.1));
        l.add(new Dbl("sell-rate", Page.ECONOMY, Items.HOPPER, c -> c.market.sellRate, (c, v) -> c.market.sellRate = v, 0, 1, 0.05));
        l.add(new Toggle("shops", Page.ECONOMY, c -> c.market.shops, (c, v) -> c.market.shops = v));
        l.add(new Toggle("shops-lobby-only", Page.ECONOMY, c -> c.market.shopsOnlyInLobby, (c, v) -> c.market.shopsOnlyInLobby = v));
        l.add(new Toggle("booths", Page.ECONOMY, c -> c.market.booths, (c, v) -> c.market.booths = v));
        l.add(new Num("booth-slots", Page.ECONOMY, Items.CHEST, c -> c.market.boothSlots, (c, v) -> c.market.boothSlots = v, 1, 28, 1));
        l.add(new Num("offer-hours", Page.ECONOMY, Items.CLOCK, c -> c.market.offerHours, (c, v) -> c.market.offerHours = v, 1, 336, 1));
        l.add(new Num("max-shops", Page.ECONOMY, Items.CHEST, c -> c.market.maxShopsPerPlayer, (c, v) -> c.market.maxShopsPerPlayer = v, 0, 50, 1));
        l.add(new Num("auction-hours", Page.ECONOMY, Items.CLOCK, c -> c.market.auctionHours, (c, v) -> c.market.auctionHours = v, 1, 168, 1));
        l.add(new Num("max-auctions", Page.ECONOMY, Items.GOLD_BLOCK, c -> c.market.maxAuctionsPerPlayer, (c, v) -> c.market.maxAuctionsPerPlayer = v, 0, 50, 1));
        l.add(new Num("max-orders", Page.ECONOMY, Items.HOPPER, c -> c.market.maxOrdersPerPlayer, (c, v) -> c.market.maxOrdersPerPlayer = v, 0, 50, 1));
        l.add(new Dbl("deal-discount", Page.ECONOMY, Items.EMERALD, c -> c.market.dailyDealDiscount, (c, v) -> c.market.dailyDealDiscount = v, 0, 0.9, 0.05));
        l.add(new Toggle("bounties", Page.ECONOMY, c -> c.market.bounties, (c, v) -> c.market.bounties = v));
        l.add(new Num("war-minutes", Page.ECONOMY, Items.IRON_SWORD, c -> c.teams.warMinutes, (c, v) -> c.teams.warMinutes = v, 10, 600, 10));
        l.add(new Num("war-cooldown", Page.ECONOMY, Items.CLOCK, c -> c.teams.warCooldownMinutes, (c, v) -> c.teams.warCooldownMinutes = v, 0, 1440, 30));
        l.add(new Num("chunks-per-level", Page.ECONOMY, Items.GRASS_BLOCK, c -> c.teams.chunksPerLevel, (c, v) -> c.teams.chunksPerLevel = v, 0, 20, 1));
        // Watcher (owner only)
        l.add(new Toggle("watcher", Page.WATCHER, c -> c.watcher.enabled, (c, v) -> {
            c.watcher.enabled = v;
            if (!v) {
                com.vylorq.anticheat.feature.Watcher.stopAll();
            }
        }));
        l.add(new Num("watcher-min", Page.WATCHER, Items.CLOCK, c -> c.watcher.minMinutes, (c, v) -> c.watcher.minMinutes = Math.min(v, c.watcher.maxMinutes), 5, 600, 5));
        l.add(new Num("watcher-max", Page.WATCHER, Items.CLOCK, c -> c.watcher.maxMinutes, (c, v) -> c.watcher.maxMinutes = Math.max(v, c.watcher.minMinutes), 5, 600, 5));
        l.add(new Toggle("watcher-night", Page.WATCHER, c -> c.watcher.nightEnabled, (c, v) -> c.watcher.nightEnabled = v));
        l.add(new Toggle("scare-warning", Page.WATCHER, c -> c.watcher.warnOnJoin, (c, v) -> c.watcher.warnOnJoin = v));
        l.add(new Toggle("scare-decline-kicks", Page.WATCHER, c -> c.watcher.declineKicks, (c, v) -> c.watcher.declineKicks = v));
        l.add(new Toggle("watcher-ban", Page.WATCHER, c -> c.watcher.banAppearance, (c, v) -> c.watcher.banAppearance = v));
        l.add(new Toggle("watcher-rush", Page.WATCHER, c -> c.watcher.rareRush, (c, v) -> c.watcher.rareRush = v));
        for (WatcherEffect e : WatcherEffect.values()) {
            if (e == WatcherEffect.RUSH) {
                continue;
            }
            l.add(watcherEffect(e));
        }
        l.add(new TextS("watcher-text", Page.WATCHER, Items.PAPER, c -> c.watcher.watchingText, (c, v) -> c.watcher.watchingText = v));
        l.add(new TextS("watcher-glitch", Page.WATCHER, Items.PAPER, c -> c.watcher.glitchText, (c, v) -> c.watcher.glitchText = v));
        l.add(new TextS("watcher-whisper", Page.WATCHER, Items.PAPER, c -> c.watcher.whisperText, (c, v) -> c.watcher.whisperText = v));
        l.add(new TextS("watcher-voice", Page.WATCHER, Items.PAPER, c -> c.watcher.ownVoiceText, (c, v) -> c.watcher.ownVoiceText = v));
        l.add(new TextS("watcher-sleep", Page.WATCHER, Items.PAPER, c -> c.watcher.sleepText, (c, v) -> c.watcher.sleepText = v));
        l.add(new ListS("watcher-sign", Page.WATCHER, Items.DARK_OAK_SIGN, c -> c.watcher.signLines, (c, v) -> c.watcher.signLines = v));
        l.add(new ListS("watcher-motd", Page.WATCHER, Items.MAP, c -> c.watcher.serverListMessages, (c, v) -> c.watcher.serverListMessages = v));
        // Messages & style
        l.add(new Toggle("show-prefix", Page.STYLE, c -> c.general.showPrefix, (c, v) -> c.general.showPrefix = v));
        l.add(new Toggle("sounds", Page.STYLE, c -> c.general.sounds, (c, v) -> c.general.sounds = v));
        l.add(new Toggle("boss-bars", Page.STYLE, c -> c.general.bossBars, (c, v) -> c.general.bossBars = v));
        l.add(new Custom("my-sounds", Page.STYLE, Items.NOTE_BLOCK, () -> {
            var v = com.vylorq.anticheat.ui.Viewer.current();
            return onOff(v == null || !Ac.get().misc.quietUi.contains(v.getUuid()));
        }, () -> onOff(true), "ui.action.turn-off", (p, ck) -> {
            if (!Ac.get().misc.quietUi.remove(p.getUuid())) {
                Ac.get().misc.quietUi.add(p.getUuid());
            }
            Ac.markDirty("misc");
            Sounds.play(p, Sounds.Ui.SUCCESS);
        }, null));
        return l;
    }

    private static Custom watcherEffect(WatcherEffect e) {
        String effectId = e.id();
        Custom c = new Custom("watcher-effect", Page.WATCHER, Items.ENDER_EYE,
                () -> onOff(!cfg().watcher.disabledEffects.contains(effectId)), () -> onOff(true), "ui.action.turn-off",
                (p, ck) -> {
                    var w = cfg().watcher;
                    if (ck.isRight() && e.kind == WatcherEffect.Kind.POOLED) {
                        int v = Math.max(0, Math.min(100, w.weights.getOrDefault(effectId, e.defaultWeight) + (ck.isShift() ? 10 : 1)));
                        w.weights.put(effectId, v);
                        applied(p, null, Msg.trFor(p, "watcher.effect." + effectId) + ": " + Msg.trFor(p, "settings.weight", v, e.defaultWeight));
                        return;
                    }
                    if (!w.disabledEffects.remove(effectId)) {
                        w.disabledEffects.add(effectId);
                    }
                    applied(p, null, Msg.trFor(p, "watcher.effect." + effectId) + ": " + onOff(!w.disabledEffects.contains(effectId)));
                }, () -> {
            cfg().watcher.disabledEffects.remove(effectId);
            cfg().watcher.weights.remove(effectId);
        }) {
            @Override
            String name() {
                return Msg.tr("watcher.effect." + effectId);
            }

            @Override
            String desc() {
                return Msg.tr("watcher.effect." + effectId + ".desc");
            }

            @Override
            boolean changed() {
                return cfg().watcher.disabledEffects.contains(effectId) || cfg().watcher.weights.containsKey(effectId);
            }

            @Override
            void hints(Btn b) {
                if (e.kind == WatcherEffect.Kind.POOLED) {
                    b.line(Msg.tr("settings.weight", cfg().watcher.weights.getOrDefault(effectId, e.defaultWeight), e.defaultWeight));
                    b.left(Msg.tr(cfg().watcher.disabledEffects.contains(effectId) ? "ui.action.turn-on" : "ui.action.turn-off"))
                            .right(Msg.tr("settings.weight-up"));
                } else {
                    b.left(Msg.tr(cfg().watcher.disabledEffects.contains(effectId) ? "ui.action.turn-on" : "ui.action.turn-off"));
                }
            }
        };
        return c.icon(() -> cfg().watcher.disabledEffects.contains(effectId) ? Items.GRAY_DYE : Items.ENDER_EYE);
    }

    private static List<S> all;

    static List<S> all() {
        if (all == null) {
            all = build();
        }
        return all;
    }

    // ---- saving ----

    private static void applied(ServerPlayerEntity p, S s, String value) {
        Ac.get().configManager.save();
        String err = Ac.get().reload();
        if (err != null) {
            Ac.LOG.warn("Settings reload: {}", err);
        }
        String what = s == null ? value : s.id;
        Staff.log(p, "settings", null, what, value);
        Sounds.play(p, Sounds.Ui.SUCCESS);
        p.sendMessage(Theme.c(Theme.Sym.CHECK.sp() + (s == null ? value : Msg.trFor(p, "settings.changed", Msg.trFor(p, "set." + s.id), value)), Theme.GREEN), true);
    }

    private static void reopen(ServerPlayerEntity p, Menu menu) {
        menu.open(p);
    }

    private static boolean allowed(ServerPlayerEntity p) {
        if (!PermissionPolicy.canUseSettings(Perms.effectiveRole(p), Ac.config().permissions.adminsUseSettings)) {
            Perms.unauthorized(p, "settings");
            return false;
        }
        return true;
    }

    // ---- menus ----

    /** The settings hub: one button per page, plus Search. */
    public static void open(ServerPlayerEntity admin) {
        if (!allowed(admin)) {
            return;
        }
        Menu m = Menu.std(Category.SETTINGS, Msg.trFor(admin, "cat.settings")).perm(Perm.SETTINGS);
        m.renderer(menu -> {
            long changedCount = all().stream().filter(S::changed).count();
            menu.info(Btn.of(Items.COMPARATOR).name(Category.SETTINGS, Msg.tr("cat.settings")).desc(Msg.tr("settings.hub-desc"))
                    .line(Msg.tr("settings.changed-count", changedCount)).build());
            int[] slots = {19, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33};
            Page[] pages = Page.values();
            for (int i = 0; i < pages.length; i++) {
                Page pg = pages[i];
                long n = all().stream().filter(s -> s.page == pg).count();
                long ch = all().stream().filter(s -> s.page == pg && s.changed()).count();
                boolean locked = !Perms.has(admin, pg.perm);
                Btn b = Btn.of(pg.icon).name(Category.SETTINGS, Msg.tr("settings.page." + pg.id())).desc(Msg.tr("settings.page." + pg.id() + ".desc"))
                        .line(Msg.tr("settings.count", n));
                if (ch > 0) {
                    b.status(Theme.GOLD, Theme.Sym.CHANGED.sp() + Msg.tr("settings.changed-count", ch));
                }
                if (locked) {
                    b.status(Theme.RED, Msg.tr("settings.locked")).hint(Msg.tr("settings.locked-owner"));
                } else {
                    b.left(Msg.tr("ui.action.open"));
                }
                menu.set(slots[i], b.build(), null, (p, c) -> {
                    if (locked) {
                        Msg.error(p, "settings.locked-owner");
                        return;
                    }
                    page(p, pg);
                });
            }
            menu.set(38, Btn.of(Items.LEVER).color(Theme.GOLD_LIGHT).name(Msg.tr("features.title")).desc(Msg.tr("features.desc"))
                    .left(Msg.tr("ui.action.open")).build(), Perm.SETTINGS, (p, c) -> FeaturesMenu.open(p));
            menu.set(40, Btn.of(Items.BOOKSHELF).color(Theme.GOLD_LIGHT).name(Msg.tr("setbk.title")).desc(Msg.tr("setbk.desc"))
                    .count(SettingsBackups.list().size()).left(Msg.tr("ui.action.open")).build(), Perm.SETTINGS, (p, c) -> SettingsBackups.open(p));
            menu.set(Menu.SEARCH, Btn.of(Items.NAME_TAG).color(Theme.GOLD_LIGHT).name(Msg.tr("settings.search"))
                    .desc(Msg.tr("settings.search-desc")).left(Msg.tr("ui.action.search")).build(), null, (p, c) ->
                    Input.text(p, Msg.trFor(p, "settings.search"), "", txt -> search(p, txt == null ? "" : txt.trim(), menu)));
        });
        m.open(admin);
    }

    /** One page of settings (or every setting matching a search when {@code only} is given). */
    public static void page(ServerPlayerEntity admin, Page pg) {
        if (!allowed(admin) && pg != Page.WATCHER) {
            return;
        }
        if (!Perms.has(admin, pg.perm)) {
            Msg.error(admin, "settings.locked-owner");
            return;
        }
        Menu m = Menu.std(pg == Page.WATCHER ? Category.WATCHER : Category.SETTINGS, Msg.trFor(admin, "cat.settings"),
                Msg.trFor(admin, "settings.page." + pg.id())).perm(pg.perm);
        m.renderer(menu -> {
            List<S> list = all().stream().filter(s -> s.page == pg).toList();
            menu.info(Btn.of(pg.icon).name(Category.SETTINGS, Msg.tr("settings.page." + pg.id())).desc(Msg.tr("settings.page." + pg.id() + ".desc"))
                    .line(Msg.tr("settings.count", list.size())).build());
            menu.set(8, Btn.of(Items.LAVA_BUCKET).color(Theme.RED).name(Msg.tr("settings.reset-page")).desc(Msg.tr("settings.reset-page-desc"))
                    .left(Msg.tr("settings.reset")).build(), pg.perm, (p, c) -> Confirm.open(p, Category.SETTINGS,
                    Msg.tr("settings.confirm-reset-page", Msg.tr("settings.page." + pg.id())), Msg.tr("settings.confirm-reset-detail"), null, () -> {
                        for (S s : list) {
                            s.reset();
                        }
                        applied(p, null, Msg.trFor(p, "settings.reset-done", Msg.trFor(p, "settings.page." + pg.id())));
                        page(p, pg);
                    }));
            settingsList(admin, menu, list);
        });
        m.open(admin);
    }

    private static void search(ServerPlayerEntity admin, String query, Menu from) {
        if (query.isEmpty()) {
            from.open(admin);
            return;
        }
        Menu m = Menu.std(Category.SETTINGS, Msg.trFor(admin, "cat.settings"), Msg.trFor(admin, "ui.search.current", query)).perm(Perm.SETTINGS);
        m.parent(from);
        m.renderer(menu -> {
            String q = query.toLowerCase(Locale.ROOT);
            List<S> list = all().stream().filter(s -> (s.name() + " " + s.desc() + " " + s.id).toLowerCase(Locale.ROOT).contains(q)).toList();
            menu.info(Btn.of(Items.NAME_TAG).name(Category.SETTINGS, Msg.tr("settings.search")).line(Msg.tr("settings.count", list.size())).build());
            settingsList(admin, menu, list);
        });
        m.open(admin);
    }

    private static void settingsList(ServerPlayerEntity admin, Menu menu, List<S> list) {
        menu.list(list, s -> button(admin, s), s -> (p, c) -> {
                    if (!Perms.has(p, s.perm)) {
                        Msg.error(p, "settings.locked-owner");
                        return;
                    }
                    if (c == Menu.Click.SHIFT_RIGHT) {
                        if (s.def() == null && !(s instanceof ListS)) {
                            return;
                        }
                        Confirm.open(p, Category.SETTINGS, Msg.tr("settings.confirm-reset", s.name()),
                                Msg.tr("settings.confirm-reset-one", s.def() == null ? "" : s.def()), null, () -> {
                                    s.reset();
                                    applied(p, s, s.now());
                                    menu.open(p);
                                });
                        return;
                    }
                    s.click(p, c, menu);
                    if (menu.isOpenFor(p)) {
                        menu.refresh();
                    }
                },
                s -> s.name() + " " + s.desc(),
                List.of(Menu.Filter.of(Msg.tr("settings.filter.all"), s -> true), Menu.Filter.of(Msg.tr("settings.filter.changed"), S::changed)),
                Msg.tr("settings.none"), Msg.tr("settings.none-hint"));
    }

    private static net.minecraft.item.ItemStack button(ServerPlayerEntity viewer, S s) {
        boolean locked = !Perms.has(viewer, s.perm);
        Btn b = Btn.of(s.icon()).name(Category.SETTINGS, s.name()).desc(s.desc());
        b.status(Theme.GOLD_LIGHT, Msg.tr("settings.now", s.now()));
        if (s.def() != null) {
            b.status(Theme.SOFT, Msg.tr("settings.default", s.def()));
        }
        if (s.changed()) {
            b.status(Theme.GOLD, Theme.Sym.CHANGED.sp() + Msg.tr("settings.changed-mark"));
        }
        if (locked) {
            b.status(Theme.RED, Msg.tr("settings.locked")).hint(Msg.tr(s.perm == Perm.WATCHER || s.perm == Perm.MANAGE_ADMINS
                    ? "settings.locked-owner" : "settings.locked-admin"));
        } else {
            s.hints(b);
            if (s.def() != null || s instanceof ListS) {
                b.shiftRight(Msg.tr("settings.reset"));
            }
        }
        return b.glint(s.changed()).build();
    }

    /** Per-check sensitivity (Anti-cheat page). */
    private static final double[] LEVELS = {0, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0};

    public static void sensitivity(ServerPlayerEntity admin) {
        Menu m = Menu.std(Category.SETTINGS, Msg.trFor(admin, "cat.settings"), Msg.trFor(admin, "set.sensitivity")).perm(Perm.SETTINGS);
        m.renderer(menu -> {
            AcConfig cfg = Ac.config();
            menu.info(Btn.of(Items.COMPARATOR).name(Category.SETTINGS, Msg.tr("set.sensitivity")).desc(Msg.tr("set.sensitivity.desc")).build());
            menu.list(List.of(CheckType.values()), t -> {
                        double v = cfg.sensitivity(t.id());
                        double rate = Ac.get().stats.dismissalRate(t.id());
                        Btn b = Btn.of(v == 0 ? Items.GRAY_DYE : Items.LIME_DYE).name(Category.SETTINGS, t.displayName())
                                .status(Theme.GOLD_LIGHT, Msg.tr("settings.now", v == 0 ? onOff(false) : "x" + Dbl.fmt(v)))
                                .status(Theme.SOFT, Msg.tr("settings.default", "x1"));
                        if (rate > 0) {
                            b.line(Msg.tr("settings.dismissed", String.format(Locale.ROOT, "%.0f", rate * 100)));
                        }
                        return b.left(Msg.tr("settings.next-option")).right(Msg.tr("settings.prev-option")).glint(v != 1.0).build();
                    },
                    t -> (p, c) -> {
                        double v = cfg.sensitivity(t.id());
                        int i = 0;
                        while (i < LEVELS.length && LEVELS[i] != v) {
                            i++;
                        }
                        double next = LEVELS[Math.floorMod((i >= LEVELS.length ? 3 : i) + (c.isRight() ? -1 : 1), LEVELS.length)];
                        cfg.detection.disabledChecks.remove(t.id());
                        cfg.detection.sensitivity.put(t.id(), next);
                        applied(p, null, t.displayName() + ": x" + Dbl.fmt(next));
                        menu.refresh();
                    },
                    CheckType::displayName, List.of(), "", "");
        });
        m.open(admin);
    }
}
