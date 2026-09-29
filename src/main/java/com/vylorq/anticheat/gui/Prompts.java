package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.platform.Floodgate;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Text input: Java players type in chat, Bedrock players get a Floodgate form with a text box (section 4).
 */
public final class Prompts {
    private Prompts() {
    }

    public static void ask(ServerPlayerEntity p, String question, Consumer<String> answer) {
        PlayerSession s = Ac.session(p);
        UUID id = p.getUuid();
        if (s.bedrock && Floodgate.askText(id, "Input", List.of(question), res -> Ac.server().execute(() -> {
            ServerPlayerEntity online = Ac.server().getPlayerManager().getPlayer(id);
            if (online != null && res != null && res.length > 0) {
                answer.accept(res[0]);
            }
        }))) {
            p.closeHandledScreen();
            return;
        }
        p.closeHandledScreen();
        s.chatPrompt = answer;
        Msg.send(p, "prompt.type", question);
    }

    /** Handles chat typed while a prompt is open. @return true if the message was consumed. */
    public static boolean onChat(ServerPlayerEntity p, String message) {
        PlayerSession s = Ac.session(p);
        Consumer<String> c = s.chatPrompt;
        if (c == null) {
            return false;
        }
        s.chatPrompt = null;
        if (message.equalsIgnoreCase("cancel")) {
            Msg.send(p, "prompt.cancelled");
            return true;
        }
        Ac.server().execute(() -> c.accept(message));
        return true;
    }
}
