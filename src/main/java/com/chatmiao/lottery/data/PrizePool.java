package com.chatmiao.lottery.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * A reusable prize pool (奖池): just a weighted item list. Configured once in the terminal's "奖池设置" page and
 * referenced by any number of ticket types / gacha pools; each of them gives it its own weight and rarity.
 */
public final class PrizePool {
    public static final int MAX_POOLS = 256;

    public int id;
    public String name = "";
    /** Rarity stored by 0.4.0-beta.1/2, when pools still had one; only used to migrate those saves (-1 = none). */
    public transient int legacyTier = -1;
    public final List<TicketType.PoolItem> items = new ArrayList<>();

    public long totalItemWeight() {
        long sum = 0;
        for (TicketType.PoolItem it : items) if (it.usable()) sum += it.weight;
        return sum;
    }

    public PrizePool copy() {
        PrizePool p = new PrizePool();
        p.id = id;
        p.name = name;
        p.legacyTier = legacyTier;
        for (TicketType.PoolItem it : items) p.items.add(it.copy());
        return p;
    }

    public void sanitize() {
        name = name == null ? "" : name.strip();
        if (name.length() > TicketType.MAX_NAME) name = name.substring(0, TicketType.MAX_NAME);
        if (name.isEmpty()) name = "奖池";
        items.removeIf(it -> it.item == null || it.item.isEmpty());
        while (items.size() > TicketType.MAX_ITEMS) items.remove(items.size() - 1);
        for (TicketType.PoolItem it : items) it.sanitize();
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeUtf(name, 256);
        TicketType.writeItems(buf, items);
    }

    public static PrizePool read(RegistryFriendlyByteBuf buf) {
        PrizePool p = new PrizePool();
        p.id = buf.readVarInt();
        p.name = buf.readUtf(256);
        TicketType.readItems(buf, p.items);
        return p;
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("name", name);
        tag.put("items", TicketType.saveItems(items, provider));
        return tag;
    }

    public static PrizePool load(CompoundTag tag, HolderLookup.Provider provider) {
        PrizePool p = new PrizePool();
        p.id = tag.getInt("id");
        p.name = tag.getString("name");
        p.legacyTier = tag.contains("tier") ? Tier.clamp(tag.getInt("tier")) : -1;
        TicketType.loadItems(tag.getList("items", Tag.TAG_COMPOUND), provider, p.items);
        return p;
    }

}
