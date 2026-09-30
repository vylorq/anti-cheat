package com.vylorq.anticheat.util;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.item.ItemInfo;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Converts item stacks to the Minecraft-independent {@link ItemInfo} and to/from strings for storage. */
public final class ItemConv {
    private ItemConv() {
    }

    public static RegistryWrapper.WrapperLookup registries() {
        return Ac.server().getRegistryManager();
    }

    /** Full, exact encoding of a stack (SNBT). Empty stacks encode to "". */
    public static String encode(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return toNbt(stack).toString();
    }

    /** Stack to NBT with the item codec (empty stacks give an empty compound). */
    public static NbtCompound toNbt(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new NbtCompound();
        }
        NbtElement e = ItemStack.CODEC.encodeStart(registries().getOps(NbtOps.INSTANCE), stack).getOrThrow();
        return e instanceof NbtCompound c ? c : new NbtCompound();
    }

    public static ItemStack fromNbt(NbtCompound nbt) {
        if (nbt == null || nbt.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return ItemStack.CODEC.parse(registries().getOps(NbtOps.INSTANCE), nbt).result().orElse(ItemStack.EMPTY);
    }

    /** Parses SNBT text into a compound. */
    public static NbtCompound parseSnbt(String s) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return StringNbtReader.readCompound(s);
    }

    public static ItemStack decode(String s) {
        if (s == null || s.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            return fromNbt(parseSnbt(s));
        } catch (Exception e) {
            Ac.LOG.warn("Could not decode stored item: {}", e.getMessage());
            return ItemStack.EMPTY;
        }
    }

    /** Encodes with the slot number as a prefix: "12|{...}". */
    public static String encodeSlot(int slot, ItemStack stack) {
        return slot + "|" + encode(stack);
    }

    public static int slotOf(String encoded) {
        int i = encoded.indexOf('|');
        return i < 0 ? -1 : Integer.parseInt(encoded.substring(0, i));
    }

    public static ItemStack decodeSlot(String encoded) {
        int i = encoded.indexOf('|');
        return decode(i < 0 ? encoded : encoded.substring(i + 1));
    }

    public static ItemInfo info(ItemStack s) {
        ItemInfo i = new ItemInfo(Mc.itemId(s.getItem()), s.getCount());
        i.maxCount = s.getMaxCount();
        if (s.isDamageable()) {
            i.damage = s.getDamage();
            i.maxDamage = s.getMaxDamage();
        }
        enchants(s.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT), i.enchantments);
        enchants(s.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT), i.storedEnchantments);
        Text name = s.get(DataComponentTypes.CUSTOM_NAME);
        if (name != null) {
            i.customName = name.getString();
        }
        ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
        if (c != null) {
            i.contents = new ArrayList<>();
            for (ItemStack inner : c.iterateNonEmpty()) {
                i.contents.add(info(inner));
            }
        }
        return i;
    }

    private static void enchants(ItemEnchantmentsComponent comp, Map<String, Integer> out) {
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> e : comp.getEnchantmentEntries()) {
            e.getKey().getKey().ifPresent(k -> out.put(k.getValue().toString(), e.getIntValue()));
        }
    }

    public static List<ItemInfo> infos(Iterable<ItemStack> stacks) {
        List<ItemInfo> out = new ArrayList<>();
        for (ItemStack s : stacks) {
            if (!s.isEmpty()) {
                out.add(info(s));
            }
        }
        return out;
    }

    public static Optional<RegistryEntry.Reference<Enchantment>> enchantment(String id) {
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) {
            return Optional.empty();
        }
        return Ac.server().getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT).getEntry(ident);
    }

    // ---- Custom data tags (tools, trader items, death drops) ----

    public static String tag(ItemStack s, String key) {
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        if (c == null) {
            return null;
        }
        NbtCompound n = c.copyNbt();
        return n.getString(key).orElse(null);
    }

    public static void setTag(ItemStack s, String key, String value) {
        editCustom(s, n -> n.putString(key, value));
    }

    public static void removeTag(ItemStack s, String key) {
        editCustom(s, n -> n.remove(key));
    }

    private static void editCustom(ItemStack s, Consumer<NbtCompound> edit) {
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound n = c == null ? new NbtCompound() : c.copyNbt();
        edit.accept(n);
        if (n.isEmpty()) {
            s.remove(DataComponentTypes.CUSTOM_DATA);
        } else {
            s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(n));
        }
    }
}
