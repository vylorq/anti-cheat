package com.vylorq.anticheat.feature;

import com.google.gson.JsonObject;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.item.ItemStack;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The custom blocks of the secret structures (made by scripts/blocks/build.py). Each one is a note block in a state
 * that never happens on its own: instrument custom_head, one of the notes listed in blocks.json, unpowered. The pack
 * retextures those states on Java, and Geyser turns them into Bedrock custom blocks. This class keeps them frozen
 * (no tuning, no instrument or redstone changes) and turns placed custom block items into the right state.
 */
public final class CustomBlocks {
    private CustomBlocks() {
    }

    private static Map<Integer, String> byNote;
    private static Map<String, Integer> notes;

    private static synchronized void load() {
        if (byNote != null) {
            return;
        }
        byNote = new HashMap<>();
        notes = new HashMap<>();
        try (var in = CustomBlocks.class.getResourceAsStream("/vigil/blocks.json")) {
            if (in != null) {
                JsonObject o = ConfigManager.GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                for (var e : o.entrySet()) {
                    int note = e.getValue().getAsJsonObject().get("note").getAsInt();
                    byNote.put(note, e.getKey());
                    notes.put(e.getKey(), note);
                }
            }
        } catch (Exception ex) {
            Ac.LOG.warn("Could not read custom blocks", ex);
        }
    }

    /** The custom block this state shows, or null for any ordinary block (or ordinary note block). */
    public static String nameOf(BlockState s) {
        if (!s.isOf(Blocks.NOTE_BLOCK) || s.get(NoteBlock.INSTRUMENT) != NoteBlockInstrument.CUSTOM_HEAD || s.get(NoteBlock.POWERED)) {
            return null;
        }
        load();
        return byNote.get(s.get(NoteBlock.NOTE));
    }

    public static boolean isCustom(BlockState s) {
        return nameOf(s) != null;
    }

    /** The block state of a custom block, or null if there's no such block. */
    public static BlockState state(String name) {
        load();
        Integer note = notes.get(name);
        return note == null ? null : Blocks.NOTE_BLOCK.getDefaultState().with(NoteBlock.INSTRUMENT, NoteBlockInstrument.CUSTOM_HEAD)
                .with(NoteBlock.NOTE, note).with(NoteBlock.POWERED, false);
    }

    /** Which custom block an item places (its custom data says so), or null. */
    public static String placedBy(ItemStack stack) {
        if (!stack.isOf(net.minecraft.item.Items.NOTE_BLOCK)) {
            return null;
        }
        String name = com.vylorq.anticheat.util.ItemConv.tag(stack, "vigil_block");
        return name != null && state(name) != null ? name : null;
    }

    public static java.util.Set<String> all() {
        load();
        return java.util.Collections.unmodifiableSet(notes.keySet());
    }
}
