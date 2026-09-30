package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.platform.Floodgate;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Viewer;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Text input (34.8): an anvil on Java (type, then click the paper on the right), a native form on Bedrock. Typing
 * the answer in chat always works too.
 */
public final class Input {
    private Input() {
    }

    public static void text(ServerPlayerEntity p, String title, String current, Consumer<String> answer) {
        PlayerSession s = Ac.session(p);
        UUID id = p.getUuid();
        String cur = current == null ? "" : current;
        if (s.bedrock && Floodgate.askText(id, title, List.of(title), List.of(cur), res -> Ac.server().execute(() -> {
            ServerPlayerEntity online = Ac.server().getPlayerManager().getPlayer(id);
            if (online != null && res != null && res.length > 0) {
                Viewer.with(online, () -> answer.accept(res[0]));
            }
        }))) {
            p.closeHandledScreen();
            return;
        }
        s.chatPrompt = txt -> {
            if (p.currentScreenHandler instanceof InputAnvil) {
                p.closeHandledScreen();
            }
            Viewer.with(p, () -> answer.accept(txt));
        };
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inv, player) -> new InputAnvil(syncId, inv, cur, txt -> {
                    s.chatPrompt = null;
                    Viewer.with(p, () -> answer.accept(txt));
                }), Theme.c(title, Theme.GOLD)));
        Msg.info(p, "prompt.type", title);
    }

    /** An anvil that never uses real items: the paper is a display item and nothing can be taken out. */
    static final class InputAnvil extends AnvilScreenHandler {
        private final Consumer<String> done;
        private final ItemStack paper;
        private String typed;
        private boolean finished;

        InputAnvil(int syncId, PlayerInventory inv, String current, Consumer<String> done) {
            super(syncId, inv, ScreenHandlerContext.EMPTY);
            this.done = done;
            this.typed = current;
            this.paper = Btn.of(Items.PAPER).color(Theme.WHITE).name(current.isEmpty() ? " " : current).build();
            this.input.setStack(0, paper.copy());
            updateResult();
        }

        @Override
        public boolean setNewItemName(String name) {
            this.typed = name == null ? "" : name;
            updateResult();
            return true;
        }

        @Override
        public void updateResult() {
            if (paper == null) {
                return;
            }
            ItemStack out = paper.copy();
            out.set(DataComponentTypes.CUSTOM_NAME, Text.literal(typed == null || typed.isEmpty() ? " " : typed));
            this.output.setStack(0, out);
            sendContentUpdates();
        }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity player) {
            if (slot == 2 && !finished && player instanceof ServerPlayerEntity sp) {
                finished = true;
                String answer = typed == null ? "" : typed.trim();
                sp.closeHandledScreen();
                done.accept(answer);
                return;
            }
            // Everything else is display only.
            syncState();
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return true;
        }

        @Override
        public void onClosed(PlayerEntity player) {
            // Nothing is given back: the paper was never a real item.
            this.input.clear();
            this.output.clear();
            super.onClosed(player);
        }
    }
}
