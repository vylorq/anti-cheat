package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.feature.BuildFiles;
import com.vylorq.anticheat.feature.BuilderDrafts;
import com.vylorq.anticheat.feature.BuilderMode;
import com.vylorq.anticheat.feature.BuilderTools;
import com.vylorq.anticheat.feature.Tools;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The Builder Menu: every builder tool in one place, so it works the same on phones, consoles and PC. */
public final class BuilderMenu {
    private BuilderMenu() {
    }

    private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    public static void open(ServerPlayerEntity p) {
        Menu m = Menu.std(Theme.Category.LOBBY, 6, Msg.trFor(p, "build.menu.title"));
        m.renderer(menu -> {
            menu.set(10, Btn.of(Tools.builderWand()).name(Msg.tr("build.menu.wand")).desc(Msg.tr("build.menu.wand-desc"))
                    .left(Msg.tr("build.menu.get")).build(), null, (pl, c) -> {
                pl.getInventory().insertStack(Tools.builderWand());
                pl.currentScreenHandler.sendContentUpdates();
            });
            menu.set(12, Btn.of(Items.MAP).name(Msg.tr("build.menu.selection")).desc(BuilderTools.selectionInfo(p))
                    .left(Msg.tr("build.menu.clear")).build(), null, (pl, c) -> {
                BuilderTools.clear(pl);
                menu.refresh();
            });
            menu.set(14, Btn.of(Items.BOOK).name(Msg.tr("build.menu.help")).desc(Msg.tr("build.menu.help-desc")).build(), null, null);
            var clip = BuilderTools.clipboard(p);
            menu.set(16, Btn.of(Items.PAPER).name(Msg.tr("build.menu.clipboard"))
                    .desc(clip == null ? Msg.tr("build.menu.clipboard-empty") : clip.sx + "x" + clip.sy + "x" + clip.sz).build(), null, null);

            menu.set(19, Btn.of(Items.GRASS_BLOCK).name(Msg.tr("build.menu.set")).desc(Msg.tr("build.menu.set-desc"))
                    .left(Msg.tr("build.menu.pick")).build(), null, (pl, c) ->
                    pickFromInventory(pl, menu, Msg.trFor(pl, "build.menu.set"), pat -> BuilderTools.set(pl, pat)));
            menu.set(20, Btn.of(Items.WATER_BUCKET).name(Msg.tr("build.menu.replace")).desc(Msg.tr("build.menu.replace-desc"))
                    .left(Msg.tr("build.menu.pick")).build(), null, (pl, c) ->
                    pickInSelection(pl, menu, from -> pickFromInventory(pl, menu, Msg.trFor(pl, "build.menu.replace-to"),
                            pat -> BuilderTools.replace(pl, from, pat))));
            menu.set(21, Btn.of(Items.STONE_BRICK_WALL).name(Msg.tr("build.menu.walls")).desc(Msg.tr("build.menu.walls-desc"))
                    .left(Msg.tr("build.menu.pick")).build(), null, (pl, c) ->
                    pickFromInventory(pl, menu, Msg.trFor(pl, "build.menu.walls"), pat -> BuilderTools.walls(pl, pat)));
            menu.set(22, Btn.of(Items.GLASS).name(Msg.tr("build.menu.hollow")).desc(Msg.tr("build.menu.hollow-desc"))
                    .left(Msg.tr("build.menu.pick")).build(), null, (pl, c) ->
                    pickFromInventory(pl, menu, Msg.trFor(pl, "build.menu.hollow"), pat -> BuilderTools.hollow(pl, pat)));
            menu.set(23, Btn.of(Items.END_ROD).name(Msg.tr("build.menu.line")).desc(Msg.tr("build.menu.line-desc"))
                    .left(Msg.tr("build.menu.pick")).build(), null, (pl, c) ->
                    pickFromInventory(pl, menu, Msg.trFor(pl, "build.menu.line"), pat -> BuilderTools.line(pl, pat)));
            menu.set(24, Btn.of(Items.SNOWBALL).name(Msg.tr("build.menu.shapes")).desc(Msg.tr("build.menu.shapes-desc"))
                    .left(Msg.tr("ui.action.open")).build(), null, (pl, c) -> shapes(pl, menu));
            var brush = BuilderTools.brush(p);
            menu.set(25, Btn.of(Tools.builderBrush()).name(Msg.tr("build.menu.brush")).desc(Msg.tr("build.menu.brush-desc"))
                    .line(brush == null ? Msg.tr("build.menu.no-brush") : brush.mode().name().toLowerCase() + " r" + brush.radius()
                            + (brush.pattern() == null ? "" : " · " + brush.pattern().describe()))
                    .left(Msg.tr("ui.action.open")).build(), null, (pl, c) -> brushes(pl, menu));

            menu.set(28, Btn.of(Items.SLIME_BALL).name(Msg.tr("build.menu.copy")).desc(Msg.tr("build.menu.copy-desc")).build(), null, (pl, c) -> {
                BuilderTools.copy(pl);
                menu.refresh();
            });
            menu.set(29, Btn.of(Items.HONEY_BOTTLE).name(Msg.tr("build.menu.paste")).desc(Msg.tr("build.menu.paste-desc"))
                    .left(Msg.tr("build.menu.paste-air")).right(Msg.tr("build.menu.paste-noair")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                BuilderTools.paste(pl, !c.isRight());
            });
            menu.set(30, Btn.of(Items.COMPASS).name(Msg.tr("build.menu.rotate")).desc(Msg.tr("build.menu.rotate-desc")).build(), null, (pl, c) -> {
                BuilderTools.rotate(pl);
                menu.refresh();
            });
            menu.set(31, Btn.of(Items.LIGHT_WEIGHTED_PRESSURE_PLATE).name(Msg.tr("build.menu.flip")).desc(Msg.tr("build.menu.flip-desc"))
                    .left(Msg.tr("build.menu.flip-x")).right(Msg.tr("build.menu.flip-z")).build(), null, (pl, c) -> {
                BuilderTools.flip(pl, !c.isRight());
                menu.refresh();
            });
            menu.set(32, Btn.of(Items.CLOCK).name(Msg.tr("build.menu.undo")).desc(Msg.tr("build.menu.undo-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                BuilderTools.undo(pl);
            });
            var draft = BuilderDrafts.get(p.getUuid());
            if (draft != null) {
                menu.set(34, Btn.of(Items.LIME_BANNER).name(Msg.tr("build.menu.submit")).desc(Msg.tr("build.menu.submit-desc"))
                        .status(draft.submitted ? Theme.GOLD : Theme.AQUA, Msg.tr(draft.submitted ? "draft.waiting" : "draft.in-progress"))
                        .build(), null, (pl, c) -> {
                    pl.closeHandledScreen();
                    BuilderDrafts.submit(pl);
                });
            }

            menu.set(37, Btn.of(Items.CHEST_MINECART).name(Msg.tr("build.menu.builds")).desc(Msg.tr("build.menu.builds-desc"))
                    .count(BuildFiles.list().size()).build(), null, (pl, c) -> builds(pl, menu));
            menu.set(39, Btn.of(Items.WRITABLE_BOOK).name(Msg.tr("build.menu.save")).desc(Msg.tr("build.menu.save-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Msg.sendRaw(pl, Msg.suggest("§b" + Msg.trFor(pl, "build.menu.save-click"), "/build save ", "/build save <name>"));
            });
            menu.set(41, Btn.of(Items.ENDER_EYE).name(Msg.tr("build.menu.import")).desc(Msg.tr("build.menu.import-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Msg.sendRaw(pl, Msg.suggest("§b" + Msg.trFor(pl, "build.menu.import-click"), "/build import ", "/build import <name> <link>"));
            });
        });
        m.open(p);
    }

    private static final int[] SIZES = {3, 5, 8, 12, 16, 24, 32};
    private static final int[] BRUSH_SIZES = {2, 3, 5, 8, 12, 16};

    /** Spheres, cylinders and pyramids at your position. */
    static void shapes(ServerPlayerEntity p, Menu parent) {
        Menu m = Menu.std(Theme.Category.LOBBY, 4, Msg.trFor(p, "build.menu.title"), Msg.trFor(p, "build.menu.shapes"));
        m.parent(parent);
        m.renderer(menu -> {
            String[] ids = {"sphere", "hsphere", "cyl", "hcyl", "pyramid", "hpyramid"};
            net.minecraft.item.Item[] icons = {Items.SLIME_BLOCK, Items.GLASS, Items.HAY_BLOCK, Items.SCAFFOLDING, Items.SANDSTONE_STAIRS, Items.GLASS_PANE};
            for (int i = 0; i < ids.length; i++) {
                String id = ids[i];
                menu.set(10 + i, Btn.of(icons[i]).name(Msg.tr("build.shape." + id)).desc(Msg.tr("build.shape-desc")).left(Msg.tr("ui.action.open")).build(),
                        null, (pl, c) -> size(pl, menu, Msg.trFor(pl, "build.shape." + id), SIZES, n ->
                                pickFromInventory(pl, menu, Msg.trFor(pl, "build.shape." + id), pat -> {
                                    switch (id) {
                                        case "sphere" -> BuilderTools.sphere(pl, pat, n, false);
                                        case "hsphere" -> BuilderTools.sphere(pl, pat, n, true);
                                        case "cyl" -> BuilderTools.cylinder(pl, pat, n, Math.min(BuilderTools.MAX_RADIUS, n * 2), false);
                                        case "hcyl" -> BuilderTools.cylinder(pl, pat, n, Math.min(BuilderTools.MAX_RADIUS, n * 2), true);
                                        case "pyramid" -> BuilderTools.pyramid(pl, pat, n, false);
                                        default -> BuilderTools.pyramid(pl, pat, n, true);
                                    }
                                })));
            }
        });
        m.open(p);
    }

    /** Brush mode, then size, then blocks; gives the brush. */
    static void brushes(ServerPlayerEntity p, Menu parent) {
        Menu m = Menu.std(Theme.Category.LOBBY, 4, Msg.trFor(p, "build.menu.title"), Msg.trFor(p, "build.menu.brush"));
        m.parent(parent);
        m.renderer(menu -> {
            BuilderTools.BrushMode[] modes = BuilderTools.BrushMode.values();
            net.minecraft.item.Item[] icons = {Items.SLIME_BALL, Items.BRUSH, Items.SHOVEL_POTTERY_SHERD, Items.POPPY};
            for (int i = 0; i < modes.length; i++) {
                var mode = modes[i];
                String key = "build.brush." + mode.name().toLowerCase();
                menu.set(11 + i, Btn.of(icons[i]).name(Msg.tr(key)).desc(Msg.tr(key + "-desc")).left(Msg.tr("ui.action.open")).build(), null,
                        (pl, c) -> size(pl, menu, Msg.trFor(pl, key), BRUSH_SIZES, n -> {
                            if (mode == BuilderTools.BrushMode.SMOOTH) {
                                pl.closeHandledScreen();
                                BuilderTools.setBrush(pl, mode, null, n);
                            } else {
                                pickFromInventory(pl, menu, Msg.trFor(pl, key), pat -> BuilderTools.setBrush(pl, mode, pat, n));
                            }
                        }));
            }
        });
        m.open(p);
    }

    static void size(ServerPlayerEntity p, Menu parent, String title, int[] sizes, java.util.function.IntConsumer then) {
        Menu m = Menu.std(Theme.Category.LOBBY, 3, Msg.trFor(p, "build.menu.title"), title);
        m.parent(parent);
        m.renderer(menu -> {
            for (int i = 0; i < sizes.length; i++) {
                int n = sizes[i];
                menu.set(10 + i, Btn.of(Items.SLIME_BALL).name(Msg.tr("build.menu.size", n)).amount(n).build(), null, (pl, c) -> then.accept(n));
            }
        });
        m.open(p);
    }

    /** Pick one of the building blocks in the player's inventory. */
    static void pickFromInventory(ServerPlayerEntity p, Menu parent, String title, Consumer<BuilderTools.Pattern> then) {
        Map<Block, ItemStack> blocks = new LinkedHashMap<>();
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() instanceof BlockItem bi && BuilderMode.allowedBlock(bi.getBlock())) {
                blocks.putIfAbsent(bi.getBlock(), s.copyWithCount(1));
            }
        }
        if (blocks.isEmpty()) {
            Msg.send(p, "build.menu.no-blocks");
            return;
        }
        Menu m = Menu.std(Theme.Category.LOBBY, 6, Msg.trFor(p, "build.menu.title"), title);
        m.parent(parent);
        m.renderer(menu -> {
            int i = 0;
            for (var e : blocks.entrySet()) {
                if (i >= GRID.length) {
                    break;
                }
                Block b = e.getKey();
                menu.set(GRID[i++], Btn.of(e.getValue()).name(b.getName().getString()).left(Msg.tr("build.menu.use")).build(), null, (pl, c) -> {
                    pl.closeHandledScreen();
                    then.accept(BuilderTools.single(b.getDefaultState()));
                });
            }
            menu.set(Menu.INFO, Btn.of(Items.BUNDLE).name(Msg.tr("build.menu.mix")).desc(Msg.tr("build.menu.mix-desc"))
                    .left(Msg.tr("build.menu.use")).build(), null, (pl, c) -> {
                var mix = BuilderTools.hotbarMix(pl);
                if (mix == null) {
                    Msg.send(pl, "build.menu.no-blocks");
                    return;
                }
                pl.closeHandledScreen();
                then.accept(mix);
            });
        });
        m.open(p);
    }

    /** Pick one of the blocks in the selection (most common first). */
    static void pickInSelection(ServerPlayerEntity p, Menu parent, Consumer<Block> then) {
        Map<Block, Integer> counts = BuilderTools.blocksIn(p);
        if (counts == null) {
            return;
        }
        List<Map.Entry<Block, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        Menu m = Menu.std(Theme.Category.LOBBY, 6, Msg.trFor(p, "build.menu.title"), Msg.trFor(p, "build.menu.replace-from"));
        m.parent(parent);
        m.renderer(menu -> {
            int i = 0;
            for (var e : list) {
                if (i >= GRID.length) {
                    break;
                }
                Block b = e.getKey();
                ItemStack icon = b.asItem() == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(b.asItem());
                menu.set(GRID[i++], Btn.of(icon).name(b.getName().getString()).desc(Msg.tr("build.menu.count", e.getValue()))
                        .left(Msg.tr("build.menu.use")).build(), null, (pl, c) -> then.accept(b));
            }
        });
        m.open(p);
    }

    /** The saved builds: click one to load it into the clipboard. */
    static void builds(ServerPlayerEntity p, Menu parent) {
        Map<String, java.nio.file.Path> files = BuildFiles.list();
        Menu m = Menu.std(Theme.Category.LOBBY, 6, Msg.trFor(p, "build.menu.title"), Msg.trFor(p, "build.menu.builds"));
        m.parent(parent);
        m.renderer(menu -> {
            int i = 0;
            for (String name : files.keySet()) {
                if (i >= GRID.length) {
                    break;
                }
                menu.set(GRID[i++], Btn.of(Items.PAPER).name(name).desc(files.get(name).getFileName().toString())
                        .left(Msg.tr("build.menu.load")).build(), null, (pl, c) -> {
                    BuilderTools.load(pl, name);
                    parent.open(pl);
                });
            }
            if (files.isEmpty()) {
                menu.icon(22, Btn.of(Items.BARRIER).name(Msg.tr("build.menu.no-builds")).desc(Msg.tr("build.menu.import-desc")).build());
            }
        });
        m.open(p);
    }
}
