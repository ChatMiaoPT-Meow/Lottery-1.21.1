package com.chatmiao.lottery.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One kind of lottery ticket ("编号 #id"). Every ticket carrying this id is identical and stacks.
 * <p>
 * Probability model (two-level normalisation):
 * <ul>
 *   <li>Ticket level: {@link #emptyWeight} competes with each pool's {@link Pool#weight}.</li>
 *   <li>Pool level: each {@link PoolItem#weight} competes with the other items in the same pool,
 *       then the amount is uniform in [{@link PoolItem#min}, {@link PoolItem#max}].</li>
 * </ul>
 * Pools with weight 0 or without any usable item are ignored (they never steal probability).
 */
public final class TicketType {
    public static final int MAX_NAME = 32;
    public static final int MAX_POOLS = 16;
    public static final int MAX_ITEMS = 45;
    public static final int MAX_WEIGHT = 1_000_000;
    public static final int MAX_AMOUNT = 64 * 36;

    public int id;
    public String name = "";
    public int emptyWeight = 65;
    public boolean enabled = true;
    public final List<Pool> pools = new ArrayList<>();
    // statistics, maintained by the server only
    public int issued;
    public int scratched;
    public int wins;

    public static final class Pool {
        public String name = "";
        public int weight;
        public final List<PoolItem> items = new ArrayList<>();

        public boolean usable() {
            return weight > 0 && totalItemWeight() > 0;
        }

        public long totalItemWeight() {
            long sum = 0;
            for (PoolItem it : items) {
                if (it.usable()) sum += it.weight;
            }
            return sum;
        }

        public Pool copy() {
            Pool p = new Pool();
            p.name = name;
            p.weight = weight;
            for (PoolItem it : items) p.items.add(it.copy());
            return p;
        }
    }

    public static final class PoolItem {
        public ItemStack item = ItemStack.EMPTY;
        public int weight = 10;
        public int min = 1;
        public int max = 1;

        public boolean usable() {
            return weight > 0 && !item.isEmpty();
        }

        public PoolItem copy() {
            PoolItem p = new PoolItem();
            p.item = item.copy();
            p.weight = weight;
            p.min = min;
            p.max = max;
            return p;
        }
    }

    /** Sum of the ticket-level weights that actually take part in a roll. */
    public long totalWeight() {
        long sum = Math.max(0, emptyWeight);
        for (Pool p : pools) {
            if (p.usable()) sum += p.weight;
        }
        return sum;
    }

    /** Chance (0..1) that one ticket wins anything. */
    public double winChance() {
        long total = totalWeight();
        return total <= 0 ? 0 : (double) (total - Math.max(0, emptyWeight)) / total;
    }

    public TicketType copy() {
        TicketType t = new TicketType();
        t.id = id;
        t.name = name;
        t.emptyWeight = emptyWeight;
        t.enabled = enabled;
        for (Pool p : pools) t.pools.add(p.copy());
        t.issued = issued;
        t.scratched = scratched;
        t.wins = wins;
        return t;
    }

    /** Clamp everything a (trusted, but possibly buggy) client sent into sane bounds. */
    public void sanitize() {
        name = name == null ? "" : name.strip();
        if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
        if (name.isEmpty()) name = "彩票";
        emptyWeight = clamp(emptyWeight, 0, MAX_WEIGHT);
        while (pools.size() > MAX_POOLS) pools.remove(pools.size() - 1);
        for (Pool p : pools) {
            p.name = p.name == null ? "" : p.name.strip();
            if (p.name.length() > MAX_NAME) p.name = p.name.substring(0, MAX_NAME);
            p.weight = clamp(p.weight, 0, MAX_WEIGHT);
            p.items.removeIf(it -> it.item == null || it.item.isEmpty());
            while (p.items.size() > MAX_ITEMS) p.items.remove(p.items.size() - 1);
            for (PoolItem it : p.items) {
                it.item = it.item.copyWithCount(1);
                it.weight = clamp(it.weight, 0, MAX_WEIGHT);
                it.min = clamp(it.min, 1, MAX_AMOUNT);
                it.max = clamp(it.max, it.min, MAX_AMOUNT);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------------- network ----------------

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeUtf(name, 256);
        buf.writeVarInt(emptyWeight);
        buf.writeBoolean(enabled);
        buf.writeVarInt(issued);
        buf.writeVarInt(scratched);
        buf.writeVarInt(wins);
        buf.writeVarInt(pools.size());
        for (Pool p : pools) {
            buf.writeUtf(p.name, 256);
            buf.writeVarInt(p.weight);
            buf.writeVarInt(p.items.size());
            for (PoolItem it : p.items) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, it.item);
                buf.writeVarInt(it.weight);
                buf.writeVarInt(it.min);
                buf.writeVarInt(it.max);
            }
        }
    }

    public static TicketType read(RegistryFriendlyByteBuf buf) {
        TicketType t = new TicketType();
        t.id = buf.readVarInt();
        t.name = buf.readUtf(256);
        t.emptyWeight = buf.readVarInt();
        t.enabled = buf.readBoolean();
        t.issued = buf.readVarInt();
        t.scratched = buf.readVarInt();
        t.wins = buf.readVarInt();
        int poolCount = Math.min(buf.readVarInt(), MAX_POOLS * 4);
        for (int i = 0; i < poolCount; i++) {
            Pool p = new Pool();
            p.name = buf.readUtf(256);
            p.weight = buf.readVarInt();
            int itemCount = Math.min(buf.readVarInt(), MAX_ITEMS * 4);
            for (int j = 0; j < itemCount; j++) {
                PoolItem it = new PoolItem();
                it.item = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                it.weight = buf.readVarInt();
                it.min = buf.readVarInt();
                it.max = buf.readVarInt();
                p.items.add(it);
            }
            t.pools.add(p);
        }
        return t;
    }

    // ---------------- NBT ----------------

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("name", name);
        tag.putInt("emptyWeight", emptyWeight);
        tag.putBoolean("enabled", enabled);
        tag.putInt("issued", issued);
        tag.putInt("scratched", scratched);
        tag.putInt("wins", wins);
        ListTag poolList = new ListTag();
        for (Pool p : pools) {
            CompoundTag pt = new CompoundTag();
            pt.putString("name", p.name);
            pt.putInt("weight", p.weight);
            ListTag items = new ListTag();
            for (PoolItem it : p.items) {
                if (it.item.isEmpty()) continue;
                CompoundTag itTag = new CompoundTag();
                itTag.put("item", it.item.save(provider));
                itTag.putInt("weight", it.weight);
                itTag.putInt("min", it.min);
                itTag.putInt("max", it.max);
                items.add(itTag);
            }
            pt.put("items", items);
            poolList.add(pt);
        }
        tag.put("pools", poolList);
        return tag;
    }

    public static TicketType load(CompoundTag tag, HolderLookup.Provider provider) {
        TicketType t = new TicketType();
        t.id = tag.getInt("id");
        t.name = tag.getString("name");
        t.emptyWeight = tag.getInt("emptyWeight");
        t.enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
        t.issued = tag.getInt("issued");
        t.scratched = tag.getInt("scratched");
        t.wins = tag.getInt("wins");
        for (Tag pTag : tag.getList("pools", Tag.TAG_COMPOUND)) {
            CompoundTag pt = (CompoundTag) pTag;
            Pool p = new Pool();
            p.name = pt.getString("name");
            p.weight = pt.getInt("weight");
            for (Tag iTag : pt.getList("items", Tag.TAG_COMPOUND)) {
                CompoundTag it = (CompoundTag) iTag;
                PoolItem pi = new PoolItem();
                pi.item = ItemStack.parseOptional(provider, it.getCompound("item"));
                pi.weight = it.getInt("weight");
                pi.min = it.getInt("min");
                pi.max = it.getInt("max");
                // items from a removed mod parse as EMPTY; keep the row out so it can't be rolled
                if (!pi.item.isEmpty()) p.items.add(pi);
            }
            t.pools.add(p);
        }
        return t;
    }
}
