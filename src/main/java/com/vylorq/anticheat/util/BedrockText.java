package com.vylorq.anticheat.util;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Chat for Bedrock players: their chat can't be clicked, so every clickable part also shows the command it would
 * run ("[Accept] /booth accept 12"), which they can type.
 */
public final class BedrockText {
    private BedrockText() {
    }

    static String command(Text t) {
        ClickEvent ce = t.getStyle().getClickEvent();
        if (ce instanceof ClickEvent.RunCommand r) {
            return r.command();
        }
        if (ce instanceof ClickEvent.SuggestCommand s) {
            return s.command();
        }
        return null;
    }

    public static boolean hasButtons(Text t) {
        if (command(t) != null) {
            return true;
        }
        for (Text sib : t.getSiblings()) {
            if (hasButtons(sib)) {
                return true;
            }
        }
        return false;
    }

    public static Text withCommands(Text t) {
        MutableText out = t.copyContentOnly().setStyle(t.getStyle());
        for (Text sib : t.getSiblings()) {
            out.append(withCommands(sib));
        }
        String cmd = command(t);
        if (cmd != null) {
            String shown = cmd.trim();
            if (!shown.startsWith("/")) {
                shown = "/" + shown;
            }
            // Skip it when the text already shows the command (e.g. "/inspect <player>").
            String own = t.copyContentOnly().getString();
            String first = shown.split(" ")[0];
            if (!own.contains(first)) {
                out.append(Text.literal(" " + shown).formatted(Formatting.GRAY, Formatting.ITALIC));
            }
        }
        return out;
    }
}
