package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Rarity;

import java.util.List;

/**
 * Dungeon Keys: bosses drop them now and then, and each opens one locked vault in any of the structures (the vault
 * rooms, data/vigil/loot_table/chests/*_keyvault). A locked vault can't be opened or broken without one; the key is
 * used up as it opens. Once open, the vault is an ordinary chest.
 */
public final class DungeonKeys {
    private DungeonKeys() {
    }

    static final String TAG = "vigil_dungeon_key";

    public static ItemStack key() {
        ItemStack s = new ItemStack(Items.TRIAL_KEY);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§6§lDungeon Key").styled(x -> x.withItalic(false)));
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7Opens one locked vault").styled(x -> x.withItalic(false)),
                Text.literal("§7in any boss structure").styled(x -> x.withItalic(false)))));
        s.set(DataComponentTypes.RARITY, Rarity.EPIC);
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        ItemConv.setTag(s, TAG, "1");
        return s;
    }

    public static boolean isKey(ItemStack s) {
        return s.isOf(Items.TRIAL_KEY) && "1".equals(ItemConv.tag(s, TAG));
    }

    /** A vault chest that hasn't been opened yet (its loot isn't rolled until it is). */
    public static boolean lockedVault(BlockEntity be) {
        if (!(be instanceof LootableContainerBlockEntity c)) {
            return false;
        }
        var table = c.getLootTable();
        return table != null && table.getValue().getNamespace().equals("vigil") && table.getValue().getPath().endsWith("_keyvault");
    }

    public static void register() {
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!Ac.running() || hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity p) || !(world instanceof ServerWorld w)) {
                return ActionResult.PASS;
            }
            BlockEntity be = w.getBlockEntity(hit.getBlockPos());
            if (!lockedVault(be) || Perms.isOwner(p.getUuid())) {
                return ActionResult.PASS;
            }
            ItemStack held = p.getMainHandStack();
            if (!isKey(held)) {
                Msg.actionBar(p, "§6" + Msg.trFor(p, "dkey.locked"));
                w.playSound(null, hit.getBlockPos(), SoundEvents.BLOCK_VAULT_INSERT_ITEM_FAIL, SoundCategory.BLOCKS, 1f, 1f);
                return ActionResult.FAIL;
            }
            held.decrement(1);
            w.playSound(null, hit.getBlockPos(), SoundEvents.BLOCK_VAULT_OPEN_SHUTTER, SoundCategory.BLOCKS, 1f, 1f);
            Msg.actionBar(p, "§a" + Msg.trFor(p, "dkey.opened"));
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, st, be) -> {
            if (Ac.running() && lockedVault(be) && player instanceof ServerPlayerEntity p && !Perms.isOwner(p.getUuid())) {
                Msg.actionBar(p, "§6" + Msg.trFor(p, "dkey.locked"));
                return false;
            }
            return true;
        });
    }
}
