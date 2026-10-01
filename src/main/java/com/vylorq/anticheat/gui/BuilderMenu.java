package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.feature.BuildFiles;
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
        Menu m = Menu.std(Theme.Category.LOBBY, 5, Msg.trFor(p, "build.menu.title"));
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
                    pickFromInventory(pl, menu, Msg.trFor(pl, "build.menu.set"), s -> BuilderTools.set(pl, s)));
            menu.set(20, Btn.of(Items.WATER_BUCKET).name(Msg.tr("build.menu.replace")).desc(Msg.tr("build.menu.replace-desc"))
                    .left(Msg.tr("build.menu.pick")).build(), null, (pl, c) ->
                    pickInSelection(pl, menu, from -> pickFromInventory(pl, menu, Msg.trFor(pl, "build.menu.replace-to"),
                            to -> BuilderTools.replace(pl, from, to))));
            menu.set(21, Btn.of(Items.STONE_BRICK_WALL).name(Msg.tr("build.menu.walls")).desc(Msg.tr("build.menu.walls-desc"))
                    .left(Msg.tr("build.menu.pick")).build(), null, (pl, c) ->
                    pickFromInventory(pl, menu, Msg.trFor(pl, "build.menu.walls"), s -> BuilderTools.walls(pl, s)));
            menu.set(22, Btn.of(Items.SLIME_BALL).name(Msg.tr("build.menu.copy")).desc(Msg.tr("build.menu.copy-desc")).build(), null, (pl, c) -> {
                BuilderTools.copy(pl);
                menu.refresh();
            });
            menu.set(23, Btn.of(Items.HONEY_BOTTLE).name(Msg.tr("build.menu.paste")).desc(Msg.tr("build.menu.paste-desc"))
                    .left(Msg.tr("build.menu.paste-air")).right(Msg.tr("build.menu.paste-noair")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                BuilderTools.paste(pl, !c.isRight());
            });
            menu.set(24, Btn.of(Items.COMPASS).name(Msg.tr("build.menu.rotate")).desc(Msg.tr("build.menu.rotate-desc")).build(), null, (pl, c) -> {
                BuilderTools.rotate(pl);
                menu.refresh();
            });
            menu.set(25, Btn.of(Items.CLOCK).name(Msg.tr("build.menu.undo")).desc(Msg.tr("build.menu.undo-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                BuilderTools.undo(pl);
            });

            menu.set(29, Btn.of(Items.CHEST_MINECART).name(Msg.tr("build.menu.builds")).desc(Msg.tr("build.menu.builds-desc"))
                    .count(BuildFiles.list().size()).build(), null, (pl, c) -> builds(pl, menu));
            menu.set(31, Btn.of(Items.WRITABLE_BOOK).name(Msg.tr("build.menu.save")).desc(Msg.tr("build.menu.save-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Msg.sendRaw(pl, Msg.suggest("§b" + Msg.trFor(pl, "build.menu.save-click"), "/build save ", "/build save <name>"));
            });
            menu.set(33, Btn.of(Items.ENDER_EYE).name(Msg.tr("build.menu.import")).desc(Msg.tr("build.menu.import-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Msg.sendRaw(pl, Msg.suggest("§b" + Msg.trFor(pl, "build.menu.import-click"), "/build import ", "/build import <name> <link>"));
            });
        });
        m.open(p);
    }

    /** Pick one of the building blocks in the player's inventory. */
    static void pickFromInventory(ServerPlayerEntity p, Menu parent, String title, Consumer<BlockState> then) {
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
                    then.accept(b.getDefaultState());
                });
            }
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
