package com.chatmiao.lottery.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** The result of one draw on a ticket: a prize from a pool of {@link #tier}, or nothing ({@code prize == null}). */
public record Draw(Prize prize, int tier) {
    public static final Draw EMPTY = new Draw(null, Tier.NONE);

    public boolean won() {
        return prize != null;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(prize != null);
        if (prize != null) Prize.STREAM_CODEC.encode(buf, prize);
        buf.writeVarInt(tier + 1);
    }

    public static Draw read(RegistryFriendlyByteBuf buf) {
        Prize p = buf.readBoolean() ? Prize.STREAM_CODEC.decode(buf) : null;
        return new Draw(p, buf.readVarInt() - 1);
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        if (prize != null) tag.put("prize", prize.save(provider));
        tag.putInt("tier", tier);
        return tag;
    }

    public static Draw load(CompoundTag tag, HolderLookup.Provider provider) {
        if (!tag.contains("prize")) return EMPTY;
        Prize p = Prize.load(tag.getCompound("prize"), provider);
        // a prize whose item no longer exists (mod removed) is treated as no prize
        if (p.item().isEmpty()) return EMPTY;
        return new Draw(p, tag.getInt("tier"));
    }
}
