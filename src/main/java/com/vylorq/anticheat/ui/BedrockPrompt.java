package com.vylorq.anticheat.ui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.platform.Floodgate;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Requests that Java players answer with chat buttons ([Accept] [Decline]) pop up as a native form on Bedrock,
 * where chat can't be clicked. Each choice runs its command as the player.
 */
public final class BedrockPrompt {
    private BedrockPrompt() {
    }

    /** @return true if a form was shown (Bedrock player with Floodgate) */
    public static boolean ask(ServerPlayerEntity p, String title, String content, List<String> labels, List<String> commands) {
        if (p == null || !Viewer.isBedrock(p)) {
            return false;
        }
        List<String> buttons = new ArrayList<>(labels);
        buttons.add(Msg.trFor(p, "ui.later"));
        UUID id = p.getUuid();
        return Floodgate.askChoice(id, title.replaceAll("§.", ""), content.replaceAll("§.", ""), buttons, i -> Ac.server().execute(() -> {
            ServerPlayerEntity on = Ac.server().getPlayerManager().getPlayer(id);
            if (on != null && i != null && i >= 0 && i < commands.size()) {
                Mc.run(on, commands.get(i));
            }
        }));
    }
}
