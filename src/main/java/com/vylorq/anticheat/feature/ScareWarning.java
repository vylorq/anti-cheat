package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.gui.Menu;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.UUID;

/**
 * The first time someone joins they're warned that the server has jumpscares, sudden loud screams, flashing and
 * dark screens. Until they answer they can't move or use commands and nothing can hurt them. Accepting lets them play;
 * declining disconnects them (or, if {@code watcher.declineKicks} is off, lets them play with no scares at all).
 */
public final class ScareWarning {
    private ScareWarning() {
    }

    /** Raise this when the warning text changes in a way everyone should see again. */
    public static final int VERSION = 1;

    private static boolean enabled() {
        return Ac.running() && Ac.config().watcher.warnOnJoin;
    }

    /** Whether this player said yes to scares. */
    public static boolean accepted(UUID id) {
        return !enabled() || Ac.get().misc.scareAccepted.getOrDefault(id, 0) >= VERSION;
    }

    /** Whether this player chose to play without scares. */
    public static boolean declined(UUID id) {
        return enabled() && Ac.get().misc.scareDeclined.contains(id);
    }

    /** Still has to answer (can't move, use commands or get hurt meanwhile). */
    public static boolean pending(ServerPlayerEntity p) {
        return enabled() && !accepted(p.getUuid()) && !declined(p.getUuid());
    }

    /** Every second: anyone who still has to answer gets the warning (again, if they closed it). */
    public static void tick() {
        if (!enabled()) {
            return;
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (pending(p) && p.currentScreenHandler == p.playerScreenHandler && p.isAlive()) {
                open(p);
            }
        }
    }

    public static void open(ServerPlayerEntity p) {
        Menu m = Menu.std(Theme.Category.PLAYER, 3, Msg.trFor(p, "scarewarn.title"));
        boolean kicks = Ac.config().watcher.declineKicks;
        m.renderer(menu -> {
            menu.set(11, Btn.of(Items.LIME_CONCRETE).color(Theme.GREEN).name(Msg.tr("scarewarn.accept"))
                    .desc(Msg.tr("scarewarn.accept-desc")).left(Msg.tr("scarewarn.choose")).build(), null, (pl, c) -> accept(pl));
            menu.icon(13, Btn.of(Items.WITHER_SKELETON_SKULL).color(Theme.RED).name(Msg.tr("scarewarn.title"))
                    .desc(Msg.tr("scarewarn.info")).build());
            menu.set(15, Btn.of(Items.RED_CONCRETE).color(Theme.RED).name(Msg.tr("scarewarn.decline"))
                    .desc(Msg.tr(kicks ? "scarewarn.decline-desc-kick" : "scarewarn.decline-desc-noscares"))
                    .left(Msg.tr("scarewarn.choose")).build(), null, (pl, c) -> decline(pl));
        });
        m.open(p);
    }

    public static void accept(ServerPlayerEntity p) {
        Ac.get().misc.scareAccepted.put(p.getUuid(), VERSION);
        Ac.get().misc.scareDeclined.remove(p.getUuid());
        Ac.saveNow("misc");
        p.closeHandledScreen();
        Msg.send(p, "scarewarn.thanks");
        Ac.LOG.info("{} accepted the scare warning.", p.getGameProfile().name());
    }

    public static void decline(ServerPlayerEntity p) {
        Ac.LOG.info("{} declined the scare warning.", p.getGameProfile().name());
        if (Ac.config().watcher.declineKicks) {
            p.networkHandler.disconnect(Text.literal(Msg.trFor(p, "scarewarn.kick")));
            return;
        }
        Ac.get().misc.scareDeclined.add(p.getUuid());
        Ac.saveNow("misc");
        p.closeHandledScreen();
        Msg.send(p, "scarewarn.no-scares");
    }
}
