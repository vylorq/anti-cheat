package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Shared steps for big actions (34.7): bans and jails ask for the time and the reason first, then show a confirm
 * screen with the target's head in the middle.
 */
public final class Flows {
    private Flows() {
    }

    /**
     * @param askTime     ask for a length first (tempban, mute, jail)
     * @param defaultTime used when the answer is empty (ignored when {@code askTime} is false)
     * @param action      gets (reason, duration in millis or {@link Durations#PERMANENT})
     */
    public static void punish(ServerPlayerEntity admin, Theme.Category cat, UUID target, String name, String verbKey, boolean askTime,
                              long defaultTime, String defaultReason, BiConsumer<String, Long> action) {
        if (askTime) {
            Input.text(admin, Msg.tr("flow.how-long", name), Durations.format(defaultTime), len -> {
                OptionalLong d = len == null || len.isBlank() ? OptionalLong.of(defaultTime) : Durations.parse(len.trim());
                if (d.isEmpty()) {
                    Msg.error(admin, "general.bad-duration");
                    return;
                }
                reason(admin, cat, target, name, verbKey, d.getAsLong(), defaultReason, action);
            });
        } else {
            reason(admin, cat, target, name, verbKey, Durations.PERMANENT, defaultReason, action);
        }
    }

    private static void reason(ServerPlayerEntity admin, Theme.Category cat, UUID target, String name, String verbKey, long duration,
                               String defaultReason, BiConsumer<String, Long> action) {
        Input.text(admin, Msg.tr("flow.why", name), defaultReason, why -> {
            String r = why == null || why.isBlank() ? defaultReason : why.trim();
            String time = duration == Durations.PERMANENT ? Msg.tr("ui.permanent") : Durations.format(duration);
            Confirm.open(admin, cat, Msg.tr(verbKey, name), Msg.tr("flow.detail", time, r),
                    Btn.head(target, name).name(cat, name).line(Msg.tr("flow.time", time)).line(Msg.tr("flow.reason", r)).build(),
                    () -> action.accept(r, duration));
        });
    }
}
