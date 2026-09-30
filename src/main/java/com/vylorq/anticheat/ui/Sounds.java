package com.vylorq.anticheat.ui;

import com.vylorq.anticheat.Ac;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/** The standard UI sounds (34.11). Off server-wide in settings, or per admin with /vigil sounds. */
public final class Sounds {
    private Sounds() {
    }

    public enum Ui {
        OPEN(SoundEvents.ITEM_BOOK_PAGE_TURN, 0.4f, 1.0f),
        CLICK(SoundEvents.UI_BUTTON_CLICK, 0.25f, 1.0f),
        PAGE(SoundEvents.ITEM_BOOK_PAGE_TURN, 0.6f, 1.1f),
        SUCCESS(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.4f),
        ERROR(SoundEvents.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f),
        WARNING(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 0.6f, 0.8f),
        ALERT(SoundEvents.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.0f);

        final Object sound;
        final float volume;
        final float pitch;

        Ui(Object sound, float volume, float pitch) {
            this.sound = sound;
            this.volume = volume;
            this.pitch = pitch;
        }
    }

    @SuppressWarnings("unchecked")
    private static RegistryEntry<SoundEvent> entry(Object s) {
        if (s instanceof RegistryEntry<?> r) {
            return (RegistryEntry<SoundEvent>) r;
        }
        return Registries.SOUND_EVENT.getEntry((SoundEvent) s);
    }

    public static boolean enabledFor(ServerPlayerEntity p) {
        return Ac.running() && Ac.config().general.sounds && !Ac.get().misc.quietUi.contains(p.getUuid());
    }

    public static void play(ServerPlayerEntity p, Ui s) {
        if (p == null || !enabledFor(p)) {
            return;
        }
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(entry(s.sound), SoundCategory.MASTER, p.getX(), p.getY(), p.getZ(),
                s.volume, s.pitch, p.getRandom().nextLong()));
    }
}
