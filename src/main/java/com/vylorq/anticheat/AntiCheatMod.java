package com.vylorq.anticheat;

import com.vylorq.anticheat.command.Commands;
import com.vylorq.anticheat.core.trade.SecureTrade;
import com.vylorq.anticheat.feature.DetectionListener;
import com.vylorq.anticheat.feature.KitPresets;
import com.vylorq.anticheat.feature.Protection;
import com.vylorq.anticheat.feature.Ticker;
import com.vylorq.anticheat.feature.Trades;
import com.vylorq.anticheat.feature.Xray;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;

/** Mod entrypoint. Server-side only: works with vanilla Java clients and Bedrock clients through Geyser. */
public final class AntiCheatMod implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            com.vylorq.anticheat.feature.Watcher.reset();
            com.vylorq.anticheat.ui.BossBars.reset();
            Ac.start(server);
            Ac.get().engine.setListener(new DetectionListener());
            Xray.reloadLists();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            KitPresets.ensure();
            com.vylorq.anticheat.feature.TempAdmins.onServerStarted();
            com.vylorq.anticheat.feature.Traders.economyWarnings();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (!Ac.running()) {
                return;
            }
            // Give everything in open trade and trader windows back before players are saved.
            for (ServerPlayerEntity p : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
                Trades.cancelFor(p, SecureTrade.CancelReason.DISCONNECTED);
                if (p.currentScreenHandler != p.playerScreenHandler) {
                    p.closeHandledScreen();
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            com.vylorq.anticheat.feature.BuilderLog.closeAll();
            Ac.stop();
        });
        ServerTickEvents.END_SERVER_TICK.register(Ticker::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> Commands.register(dispatcher));
        Protection.register();
        com.vylorq.anticheat.feature.BuilderLog.register();
        com.vylorq.anticheat.feature.Watcher.register();
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> com.vylorq.anticheat.ui.BossBars.forget(handler.player.getUuid()));
        Ac.LOG.info("Vigil loaded.");
    }
}
