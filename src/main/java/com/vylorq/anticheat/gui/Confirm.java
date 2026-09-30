package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.platform.Floodgate;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Sounds;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Viewer;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/**
 * "Are you sure?" for anything destructive (34.7): a 3-row screen with Confirm (lime) far from Cancel (red), and
 * the thing affected in the middle. Bedrock players get a native yes/no form.
 */
public final class Confirm {
    private Confirm() {
    }

    /**
     * @param question what will happen, e.g. "Delete claim 'Spawn'?"
     * @param detail   consequences, e.g. "Everyone loses access. This can't be undone."
     * @param subject  the thing affected (a head, a claim icon), or null
     */
    public static void open(ServerPlayerEntity p, Theme.Category cat, String question, String detail, ItemStack subject, Runnable yes) {
        Menu returnTo = p.currentScreenHandler instanceof MenuHandler h ? h.menu() : null;
        Sounds.play(p, Sounds.Ui.WARNING);
        PlayerSession s = Ac.session(p);
        UUID id = p.getUuid();
        if (s.bedrock && Floodgate.askConfirm(id, question, detail == null ? "" : detail,
                Msg.trFor(p, "ui.confirm"), Msg.trFor(p, "ui.cancel"), ok -> Ac.server().execute(() -> {
                    ServerPlayerEntity online = Ac.server().getPlayerManager().getPlayer(id);
                    if (online == null) {
                        return;
                    }
                    if (ok) {
                        Viewer.with(online, yes);
                    } else if (returnTo != null) {
                        returnTo.back(online);
                    }
                }))) {
            p.closeHandledScreen();
            return;
        }
        Menu m = Menu.std(cat, 3, Msg.trFor(p, "ui.confirm.title"));
        m.parent(returnTo);
        m.renderer(menu -> {
            menu.info(Btn.of(Items.OAK_SIGN).color(Theme.GOLD_LIGHT).name(question).desc(detail).build());
            menu.set(10, Btn.of(Items.LIME_CONCRETE).color(Theme.GREEN).name(Theme.Sym.CHECK.sp() + Msg.tr("ui.confirm"))
                    .desc(question).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                yes.run();
            });
            if (subject != null) {
                menu.icon(13, subject);
            }
            menu.set(16, Btn.of(Items.RED_CONCRETE).color(Theme.RED).name(Theme.Sym.CROSS.sp() + Msg.tr("ui.cancel"))
                    .desc(Msg.tr("ui.cancel.desc")).build(), null, (pl, c) -> menu.back(pl));
            // Keep the bottom row plain so Cancel/Back isn't next to Confirm.
            menu.icon(18, Btn.pane(cat.glass));
            menu.icon(26, Btn.pane(cat.glass));
        });
        m.open(p);
    }
}
