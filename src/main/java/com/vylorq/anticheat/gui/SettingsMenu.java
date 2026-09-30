package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.perm.PermissionPolicy;
import com.vylorq.anticheat.core.watcher.WatcherEffect;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Icons;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.Difficulty;
import net.minecraft.world.rule.GameRule;
import net.minecraft.world.rule.GameRules;

import java.util.function.Consumer;

/** /settings: live server settings (section 26). Every change is logged. */
public final class SettingsMenu {
    private SettingsMenu() {
    }

    private static boolean allowed(ServerPlayerEntity p) {
        if (!PermissionPolicy.canUseSettings(Perms.effectiveRole(p), Ac.config().permissions.adminsUseSettings)) {
            Perms.unauthorized(p, "settings");
            return false;
        }
        return true;
    }

    private static void changed(ServerPlayerEntity p, String what, Object value) {
        Ac.get().configManager.save();
        Staff.log(p, "settings", null, what, String.valueOf(value));
    }

    private static boolean rule(MinecraftServer s, GameRule<Boolean> key) {
        return s.getOverworld().getGameRules().getValue(key);
    }

    private static void setRule(ServerPlayerEntity p, GameRule<Boolean> key, boolean v) {
        MinecraftServer s = Ac.server();
        for (var w : s.getWorlds()) {
            w.getGameRules().setValue(key, v, s);
        }
        Staff.log(p, "settings", null, key.toShortString(), String.valueOf(v));
    }

    /** Fire spread is a radius since 1.21.11: 0 turns it off. */
    private static boolean fireSpreads(MinecraftServer s) {
        return s.getOverworld().getGameRules().getValue(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER) > 0;
    }

    private static void setFireSpread(ServerPlayerEntity p, boolean on) {
        MinecraftServer s = Ac.server();
        int radius = on ? GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER.getDefaultValue() : 0;
        for (var w : s.getWorlds()) {
            w.getGameRules().setValue(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, radius, s);
        }
        Staff.log(p, "settings", null, "fire_spread", String.valueOf(on));
    }

    public static void open(ServerPlayerEntity admin) {
        if (!allowed(admin)) {
            return;
        }
        Menu m = new Menu("§8Server settings", 6).perm(Perm.SETTINGS);
        m.renderer(menu -> {
            MinecraftServer s = Ac.server();
            AcConfig cfg = Ac.config();
            menu.set(10, Icons.toggle(rule(s, GameRules.PVP), "PvP"), (p, c) -> {
                if (!allowed(p)) return;
                setRule(p, GameRules.PVP, !rule(s, GameRules.PVP));
                menu.refresh();
            });
            menu.set(11, Icons.toggle(rule(s, GameRules.KEEP_INVENTORY), "Keep inventory"), (p, c) -> {
                if (!allowed(p)) return;
                setRule(p, GameRules.KEEP_INVENTORY, !rule(s, GameRules.KEEP_INVENTORY));
                menu.refresh();
            });
            menu.set(12, Icons.toggle(rule(s, GameRules.NATURAL_HEALTH_REGENERATION), "Natural regeneration"), (p, c) -> {
                if (!allowed(p)) return;
                setRule(p, GameRules.NATURAL_HEALTH_REGENERATION, !rule(s, GameRules.NATURAL_HEALTH_REGENERATION));
                menu.refresh();
            });
            menu.set(13, Icons.of(Items.ZOMBIE_HEAD, "§eDifficulty: §f" + s.getSaveProperties().getDifficulty().getName(), "Click to cycle"), (p, c) -> {
                if (!allowed(p)) return;
                Difficulty next = Difficulty.byId((s.getSaveProperties().getDifficulty().getId() + 1) % 4);
                s.setDifficulty(next, true);
                Staff.log(p, "settings", null, "difficulty", next.getName());
                menu.refresh();
            });
            menu.set(14, Icons.toggle(rule(s, GameRules.DO_MOB_GRIEFING), "Mob griefing"), (p, c) -> {
                if (!allowed(p)) return;
                setRule(p, GameRules.DO_MOB_GRIEFING, !rule(s, GameRules.DO_MOB_GRIEFING));
                menu.refresh();
            });
            menu.set(15, Icons.toggle(fireSpreads(s), "Fire spread"), (p, c) -> {
                if (!allowed(p)) return;
                setFireSpread(p, !fireSpreads(s));
                menu.refresh();
            });
            menu.set(16, Icons.toggle(Ac.get().misc.explosionsEnabled, "Explosions break blocks"), (p, c) -> {
                if (!allowed(p)) return;
                Ac.get().misc.explosionsEnabled = !Ac.get().misc.explosionsEnabled;
                Ac.markDirty("misc");
                Staff.log(p, "settings", null, "explosions", String.valueOf(Ac.get().misc.explosionsEnabled));
                menu.refresh();
            });
            menu.set(19, Icons.toggle(!rule(s, GameRules.ADVANCE_TIME), "Time lock"), (p, c) -> {
                if (!allowed(p)) return;
                setRule(p, GameRules.ADVANCE_TIME, !rule(s, GameRules.ADVANCE_TIME));
                menu.refresh();
            });
            menu.set(20, Icons.toggle(!rule(s, GameRules.ADVANCE_WEATHER), "Weather lock"), (p, c) -> {
                if (!allowed(p)) return;
                setRule(p, GameRules.ADVANCE_WEATHER, !rule(s, GameRules.ADVANCE_WEATHER));
                menu.refresh();
            });
            toggle(menu, 21, "Chat", cfg.chat.chatEnabled, v -> cfg.chat.chatEnabled = v, "chat");
            toggle(menu, 22, "Waiting room", cfg.waitingRoom.enabled, v -> cfg.waitingRoom.enabled = v, "waitingRoom");
            toggle(menu, 23, "Warnings to flagged players", cfg.warnings.enabled, v -> cfg.warnings.enabled = v, "warnings");
            toggle(menu, 24, "Ban effects", cfg.fun.banEffects, v -> cfg.fun.banEffects = v, "banEffects");
            toggle(menu, 25, "Caught counter", cfg.fun.caughtCounter, v -> cfg.fun.caughtCounter = v, "caughtCounter");
            boolean spawnProt = Ac.get().claims.get("spawn") != null && Ac.get().claims.get("spawn").isActive();
            menu.icon(28, Icons.of(Items.BEACON, "§eSpawn protection", spawnProt ? "§aActive (claim 'spawn')" : "§7Create with /claim spawn"));
            number(menu, 29, "Review threshold", cfg.detection.reviewThreshold, 5, 5, 100, v -> cfg.detection.reviewThreshold = v, "reviewThreshold");
            number(menu, 30, "Warning threshold", cfg.warnings.warnScore, 5, 5, 100, v -> cfg.warnings.warnScore = v, "warnScore");
            number(menu, 31, "Auto-watch threshold", cfg.detection.autoWatchScore, 5, 5, 100, v -> cfg.detection.autoWatchScore = v, "autoWatchScore");
            toggle(menu, 32, "Auto-watch", cfg.detection.autoWatchEnabled, v -> cfg.detection.autoWatchEnabled = v, "autoWatch");
            toggle(menu, 33, "Movement setbacks", cfg.movement.setbacks, v -> {
                cfg.movement.setbacks = v;
                Ac.get().predictor.settings().setbacks = v;
            }, "setbacks");
            menu.set(34, Icons.of(Items.COMPARATOR, "§eCheck sensitivity", "Per-check multipliers"), (p, c) -> sensitivity(p));
            toggle(menu, 37, "Jail: only online time counts", cfg.jail.onlineTimeOnly, v -> cfg.jail.onlineTimeOnly = v, "jail.onlineTimeOnly");
            toggle(menu, 38, "Jail: announce", cfg.jail.announce, v -> cfg.jail.announce = v, "jail.announce");
            toggle(menu, 39, "Lobby: no hunger", cfg.lobby.noHunger, v -> cfg.lobby.noHunger = v, "lobby.noHunger");
            number(menu, 40, "Trader rotation (min)", cfg.traders.rotationMinutes, 30, 30, 1440, v -> cfg.traders.rotationMinutes = v, "traders.rotationMinutes");
            number(menu, 41, "Arena countdown (s)", cfg.arenas.countdownSeconds, 1, 1, 30, v -> cfg.arenas.countdownSeconds = v, "arenas.countdownSeconds");
            toggle(menu, 42, "Chat protection", cfg.chat.enabled, v -> cfg.chat.enabled = v, "chat.enabled");
            toggle(menu, 43, "Maintenance mode", Ac.get().staff.maintenance(), v -> {
                Ac.get().staff.setMaintenance(v);
                Ac.markDirty("staff");
            }, "maintenance");
            if (Perms.has(admin, Perm.WATCHER)) {
                menu.set(44, Icons.of(Items.BLACK_CANDLE, "§8The Watcher", "Effects, frequency and messages", "§7Owner only"),
                        (p, c) -> {
                            if (Perms.require(p, Perm.WATCHER)) {
                                watcher(p);
                            }
                        });
            }
        });
        m.open(admin);
    }

    private static void toggle(Menu menu, int slot, String name, boolean value, Consumer<Boolean> set, String key) {
        menu.set(slot, Icons.toggle(value, name), (p, c) -> {
            if (!allowed(p)) return;
            set.accept(!value);
            changed(p, key, !value);
            menu.refresh();
        });
    }

    private static void number(Menu menu, int slot, String name, int value, int step, int min, int max, Consumer<Integer> set, String key) {
        menu.set(slot, Icons.of(Items.REPEATER, "§e" + name + ": §f" + value, "Left: +" + step, "Right: -" + step), (p, c) -> {
            if (!allowed(p)) return;
            int v = Math.max(min, Math.min(max, value + (c.isRight() ? -step : step)));
            set.accept(v);
            changed(p, key, v);
            menu.refresh();
        });
    }

    // ---- The Watcher (section 33.9): owner only ----

    private static void watcherChanged(ServerPlayerEntity p, String what, Object value) {
        Ac.get().configManager.save();
        Staff.log(p, "watcher-settings", null, what, String.valueOf(value));
    }

    public static void watcher(ServerPlayerEntity owner) {
        Menu m = new Menu("§8The Watcher", 6).perm(Perm.WATCHER);
        m.renderer(menu -> {
            AcConfig.Watcher w = Ac.config().watcher;
            menu.set(4, Icons.toggle(w.enabled, "The Watcher"), (p, c) -> {
                if (!Perms.require(p, Perm.WATCHER)) return;
                w.enabled = !w.enabled;
                if (!w.enabled) {
                    com.vylorq.anticheat.feature.Watcher.stopAll();
                }
                watcherChanged(p, "enabled", w.enabled);
                menu.refresh();
            });
            int slot = 9;
            for (WatcherEffect e : WatcherEffect.values()) {
                boolean on = !w.disabledEffects.contains(e.id()) && (e != WatcherEffect.RUSH || w.rareRush);
                int weight = w.weights.getOrDefault(e.id(), e.defaultWeight);
                String kind = e.kind == WatcherEffect.Kind.POOLED ? "§7Weight: §f" + weight + " §8(right +1, shift-right -1)"
                        : e.kind == WatcherEffect.Kind.RARE ? "§7Rare" : "§7When a player sleeps";
                menu.set(slot++, Icons.of(on ? Items.ENDER_EYE : Items.GRAY_DYE, (on ? "§a" : "§c") + e.id(), "Left: on/off", kind), (p, c) -> {
                    if (!Perms.require(p, Perm.WATCHER)) return;
                    if (c.isRight() && e.kind == WatcherEffect.Kind.POOLED) {
                        int v = Math.max(0, Math.min(100, weight + (c.isShift() ? -1 : 1)));
                        w.weights.put(e.id(), v);
                        watcherChanged(p, "weight." + e.id(), v);
                    } else if (e == WatcherEffect.RUSH) {
                        w.rareRush = !w.rareRush;
                        w.disabledEffects.remove(e.id());
                        watcherChanged(p, "rareRush", w.rareRush);
                    } else {
                        if (!w.disabledEffects.remove(e.id())) {
                            w.disabledEffects.add(e.id());
                        }
                        watcherChanged(p, "effect." + e.id(), !w.disabledEffects.contains(e.id()));
                    }
                    menu.refresh();
                });
            }
            watcherNumber(menu, 36, "Min minutes between events", w.minMinutes, 5, 5, 600, v -> w.minMinutes = Math.min(v, w.maxMinutes), "minMinutes");
            watcherNumber(menu, 37, "Max minutes between events", w.maxMinutes, 5, 5, 600, v -> w.maxMinutes = Math.max(v, w.minMinutes), "maxMinutes");
            menu.set(38, Icons.toggle(w.nightEnabled, "Watcher Night"), (p, c) -> {
                if (!Perms.require(p, Perm.WATCHER)) return;
                w.nightEnabled = !w.nightEnabled;
                watcherChanged(p, "nightEnabled", w.nightEnabled);
                menu.refresh();
            });
            menu.set(39, Icons.toggle(w.banAppearance, "Ban appearance"), (p, c) -> {
                if (!Perms.require(p, Perm.WATCHER)) return;
                w.banAppearance = !w.banAppearance;
                watcherChanged(p, "banAppearance", w.banAppearance);
                menu.refresh();
            });
            watcherText(menu, 41, "Watching message", w.watchingText, v -> w.watchingText = v, "watchingText");
            watcherText(menu, 42, "Glitched message", w.glitchText, v -> w.glitchText = v, "glitchText");
            watcherText(menu, 43, "Sign lines (use | between lines)", String.join("|", w.signLines),
                    v -> w.signLines = new java.util.ArrayList<>(java.util.Arrays.asList(v.split("\\|", -1))), "signLines");
            watcherText(menu, 44, "Whisper text", w.whisperText, v -> w.whisperText = v, "whisperText");
            watcherText(menu, 45, "Your-own-voice text", w.ownVoiceText, v -> w.ownVoiceText = v, "ownVoiceText");
            watcherText(menu, 46, "Sleep text", w.sleepText, v -> w.sleepText = v, "sleepText");
            watcherText(menu, 47, "Server list messages (use | between)", String.join("|", w.serverListMessages),
                    v -> w.serverListMessages = new java.util.ArrayList<>(java.util.Arrays.stream(v.split("\\|")).map(String::trim)
                            .filter(x -> !x.isEmpty()).toList()), "serverListMessages");
            menu.set(49, Menu.back(), (p, c) -> open(p));
        });
        m.open(owner);
    }

    private static void watcherNumber(Menu menu, int slot, String name, int value, int step, int min, int max, Consumer<Integer> set, String key) {
        menu.set(slot, Icons.of(Items.CLOCK, "§e" + name + ": §f" + value, "Left: +" + step, "Right: -" + step), (p, c) -> {
            if (!Perms.require(p, Perm.WATCHER)) return;
            int v = Math.max(min, Math.min(max, value + (c.isRight() ? -step : step)));
            set.accept(v);
            watcherChanged(p, key, v);
            menu.refresh();
        });
    }

    private static void watcherText(Menu menu, int slot, String name, String value, Consumer<String> set, String key) {
        menu.set(slot, Icons.of(Items.PAPER, "§e" + name, "§f" + value, "§7Click to change"), (p, c) -> {
            if (!Perms.require(p, Perm.WATCHER)) return;
            p.closeHandledScreen();
            Prompts.ask(p, name + ":", txt -> {
                set.accept(txt);
                watcherChanged(p, key, txt);
                watcher(p);
            });
        });
    }

    private static final double[] LEVELS = {0, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0};

    public static void sensitivity(ServerPlayerEntity admin) {
        Menu m = new Menu("§8Check sensitivity", 6).perm(Perm.SETTINGS);
        m.renderer(menu -> {
            AcConfig cfg = Ac.config();
            int slot = 0;
            for (CheckType t : CheckType.values()) {
                double v = cfg.sensitivity(t.id());
                double rate = Ac.get().stats.dismissalRate(t.id());
                menu.set(slot++, Icons.of(v == 0 ? Items.GRAY_DYE : Items.LIME_DYE, "§e" + t.displayName() + ": §f" + (v == 0 ? "OFF" : "x" + v),
                        "Click to cycle 0 / 0.5 / 0.75 / 1 / 1.25 / 1.5 / 2",
                        rate > 0 ? String.format("§7Dismissed as false flag: §f%.0f%%", rate * 100) : ""), (p, c) -> {
                    if (!allowed(p)) return;
                    int i = 0;
                    while (i < LEVELS.length && LEVELS[i] != v) {
                        i++;
                    }
                    double next = LEVELS[(i + 1) % LEVELS.length];
                    cfg.detection.disabledChecks.remove(t.id());
                    cfg.detection.sensitivity.put(t.id(), next);
                    changed(p, "sensitivity." + t.id(), next);
                    menu.refresh();
                });
            }
            menu.set(49, Menu.back(), (p, c) -> open(p));
        });
        m.open(admin);
    }

}
