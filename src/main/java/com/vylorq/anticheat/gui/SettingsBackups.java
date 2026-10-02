package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

/** Saves all settings (config.json) to a backup and restores one with a click. */
public final class SettingsBackups {
    private SettingsBackups() {
    }

    static final int KEEP = 20;

    static Path dir() {
        return Ac.get().dir.resolve("backups").resolve("settings");
    }

    public static List<Path> list() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir())) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir())) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(out::add);
        } catch (Exception e) {
            Ac.LOG.warn("Could not list settings backups", e);
        }
        out.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return out;
    }

    /** @return the backup file, or null if it failed */
    public static Path save(String by) {
        try {
            Ac.get().configManager.save();
            Files.createDirectories(dir());
            String name = "settings-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".json";
            Path to = dir().resolve(name);
            Files.copy(Ac.get().configManager.file(), to, StandardCopyOption.REPLACE_EXISTING);
            List<Path> all = list();
            for (int i = KEEP; i < all.size(); i++) {
                Files.deleteIfExists(all.get(i));
            }
            return to;
        } catch (Exception e) {
            Ac.LOG.warn("Could not back up settings", e);
            return null;
        }
    }

    /** Puts a backup back as the live settings (the current ones are backed up first). @return error or null */
    public static String restore(Path backup) {
        try {
            save("before restore");
            Files.copy(backup, Ac.get().configManager.file(), StandardCopyOption.REPLACE_EXISTING);
            return Ac.get().reload();
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    static String label(Path p) {
        return p.getFileName().toString().replace("settings-", "").replace(".json", "").replace('_', ' ');
    }

    public static void open(ServerPlayerEntity admin) {
        Menu m = Menu.std(Category.SETTINGS, Msg.trFor(admin, "cat.settings"), Msg.trFor(admin, "setbk.title")).perm(Perm.SETTINGS);
        m.renderer(menu -> {
            List<Path> all = list();
            menu.info(Btn.of(Items.BOOKSHELF).name(Category.SETTINGS, Msg.tr("setbk.title")).desc(Msg.tr("setbk.desc"))
                    .line(Msg.tr("setbk.count", all.size(), KEEP)).build());
            menu.list(all, p -> Btn.of(Items.PAPER).color(Theme.WHITE).name(label(p))
                    .left(Msg.tr("setbk.restore")).shift(Msg.tr("panel.action.delete")).build(), p -> (pl, c) -> {
                if (c.isShift()) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // gone already
                    }
                    menu.refresh();
                    return;
                }
                Confirm.open(pl, Category.SETTINGS, Msg.trFor(pl, "setbk.confirm", label(p)), Msg.trFor(pl, "setbk.confirm-detail"),
                        new ItemStack(Items.BOOKSHELF), () -> {
                            String err = restore(p);
                            if (err == null) {
                                Staff.log(pl, "settings-restore", null, label(p), "");
                                Msg.send(pl, "setbk.restored", label(p));
                            } else {
                                Msg.send(pl, "setbk.failed", err);
                            }
                            menu.reopen(pl);
                        });
            }, SettingsBackups::label, List.of(), Msg.tr("setbk.none"), Msg.tr("setbk.none-hint"));
            menu.set(48, Btn.of(Items.WRITABLE_BOOK).color(Theme.GREEN).name(Msg.tr("setbk.save")).desc(Msg.tr("setbk.save-desc"))
                    .left(Msg.tr("setbk.save-action")).build(), null, (pl, c) -> {
                Path p = save(pl.getGameProfile().name());
                if (p != null) {
                    Staff.log(pl, "settings-backup", null, label(p), "");
                    Msg.send(pl, "setbk.saved", label(p));
                } else {
                    Msg.send(pl, "setbk.failed", "?");
                }
                menu.refresh();
            });
        });
        m.open(admin);
    }
}
