package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.chat.ChatFilter;
import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.evidence.EvidenceEvent;
import com.vylorq.anticheat.core.staff.Punishment;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.gui.Prompts;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Chat: prompts, mutes, waiting room, staff chat toggle, chat protection (sections 9, 15, 25). */
public final class ChatFeature {
    private ChatFeature() {
    }

    /** @return true to let the message through. */
    public static boolean allow(ServerPlayerEntity p, String message) {
        Ac ac = Ac.get();
        if (ac == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (Prompts.onChat(p, message)) {
            return false;
        }
        ac.logs.chat(now, p.getUuid(), p.getGameProfile().getName(), "chat", message);
        ac.evidence.record(p.getUuid(), EvidenceEvent.Type.CHAT, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), "chat: " + message);
        if (WaitingRoomFeature.waiting(p)) {
            WaitingRoomFeature.chat(p, message);
            return false;
        }
        if (ac.staff.staffChatOn(p.getUuid()) && Perms.isActiveStaff(p)) {
            StaffTools.staffChat(p, message);
            return false;
        }
        Punishment mute = ac.punishments.active(p.getUuid(), Punishment.Type.MUTE);
        if (mute != null) {
            Msg.send(p, "mute.muted", Durations.formatRemaining(mute.expiresAt, now), mute.reason);
            return false;
        }
        var jail = ac.jail.get(p.getUuid());
        if (jail != null && (jail.muted || !Ac.config().jail.allowChat)) {
            Msg.send(p, "jail.no-chat");
            return false;
        }
        AcConfig.Chat c = Ac.config().chat;
        if (!c.chatEnabled && !Perms.isActiveStaff(p)) {
            Msg.send(p, "chat.disabled");
            return false;
        }
        if (!c.enabled || Perms.isActiveStaff(p)) {
            return true;
        }
        ChatFilter.Verdict v = ac.chatFilter.check(p.getUuid(), message, now, new ChatFilter.Settings(c.maxMessagesPer10s,
                c.minMillisBetween, c.maxRepeats, c.floodMaxLength, c.maxCapsRatio, c.blockLinks, c.allowedDomains));
        if (v != ChatFilter.Verdict.OK) {
            Msg.send(p, "chat.blocked." + v.name().toLowerCase());
            ac.logs.activity(now, p.getUuid(), "chat-blocked", v.name() + ": " + message);
            if (v == ChatFilter.Verdict.ADVERTISING) {
                Staff.broadcast(Msg.prefixed(Msg.tr("chat.ad-alert", p.getGameProfile().getName(), message)));
            }
            return false;
        }
        return true;
    }

    public static void broadcastRaw(String legacy) {
        Ac.server().getPlayerManager().broadcast(Text.literal(legacy), false);
    }
}
