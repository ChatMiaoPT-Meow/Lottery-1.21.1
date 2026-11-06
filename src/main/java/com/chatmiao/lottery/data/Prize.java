package com.chatmiao.lottery.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** A prize: one item (count 1 template, components kept) and how many of it. Count may exceed a stack. */
public record Prize(ItemStack item, int count) {
    public static final StreamCodec<RegistryFriendlyByteBuf, Prize> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ItemStack.STREAM_CODEC.encode(buf, p.item);
                buf.writeVarInt(p.count);
            },
            buf -> new Prize(ItemStack.STREAM_CODEC.decode(buf), buf.readVarInt()));

    public Prize {
        item = item.copyWithCount(1);
    }

    /** Splits into stacks that respect max stack size. */
    public List<ItemStack> toStacks() {
        List<ItemStack> out = new ArrayList<>();
        int left = count;
        int max = Math.max(1, item.getMaxStackSize());
        while (left > 0) {
            int n = Math.min(left, max);
            out.add(item.copyWithCount(n));
            left -= n;
        }
        return out;
    }

    public Component describe() {
        return Component.empty().append(item.getHoverName()).append(" ×" + count);
    }

    /** "钻石 ×5" for one prize, "钻石 ×5 等 4 件奖品" for several. */
    public static Component summary(List<Prize> prizes) {
        if (prizes.isEmpty()) return Component.empty();
        if (prizes.size() == 1) return prizes.get(0).describe();
        return Component.translatable("gui.lottery.prizes_more", prizes.get(0).describe(), prizes.size());
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.put("item", item.save(provider));
        tag.putInt("count", count);
        return tag;
    }

    public static Prize load(CompoundTag tag, HolderLookup.Provider provider) {
        return new Prize(ItemStack.parseOptional(provider, tag.getCompound("item")), tag.getInt("count"));
    }
}
