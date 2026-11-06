package com.chatmiao.lottery.data;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What the gacha screen shows about one open pool: costs, schedule, the player's pity progress and the full
 * odds table (概率公示). Built on the server; the client never computes odds or results itself.
 */
public final class HubEntry {
    public int typeId;
    public String name = "";
    public int style;
    public int voucherId;
    public int costSingle;
    /** Draws of a multi pull (0 = none) and its cost. */
    public int multi;
    public int costMulti;
    public long endTime;
    public int weekMask;
    public int delivery;
    /** Chance of a prize per draw. */
    public double winChance;
    /** The player's current pity count, the threshold (0 = off) and target tier. */
    public int pityNow, pityCount, pityTier;
    public int guaranteeTier = -1;
    public final List<OddsPool> pools = new ArrayList<>();
    public final List<OddsItem> items = new ArrayList<>();

    public record OddsPool(String name, int tier, double chance) {}

    public record OddsItem(ItemStack item, int tier, double chance, int min, int max) {}

    /** Pity is configured and can be honoured. */
    public boolean pityActive() {
        return pityCount > 0;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(typeId);
        buf.writeUtf(name, 256);
        buf.writeVarInt(style);
        buf.writeVarInt(voucherId);
        buf.writeVarInt(costSingle);
        buf.writeVarInt(multi);
        buf.writeVarInt(costMulti);
        buf.writeVarLong(endTime);
        buf.writeVarInt(weekMask);
        buf.writeVarInt(delivery);
        buf.writeDouble(winChance);
        buf.writeVarInt(pityNow);
        buf.writeVarInt(pityCount);
        buf.writeVarInt(pityTier);
        buf.writeVarInt(guaranteeTier + 1);
        buf.writeVarInt(pools.size());
        for (OddsPool p : pools) {
            buf.writeUtf(p.name(), 256);
            buf.writeVarInt(p.tier());
            buf.writeDouble(p.chance());
        }
        buf.writeVarInt(items.size());
        for (OddsItem it : items) {
            ItemStack.STREAM_CODEC.encode(buf, it.item());
            buf.writeVarInt(it.tier());
            buf.writeDouble(it.chance());
            buf.writeVarInt(it.min());
            buf.writeVarInt(it.max());
        }
    }

    public static HubEntry read(RegistryFriendlyByteBuf buf) {
        HubEntry e = new HubEntry();
        e.typeId = buf.readVarInt();
        e.name = buf.readUtf(256);
        e.style = buf.readVarInt();
        e.voucherId = buf.readVarInt();
        e.costSingle = buf.readVarInt();
        e.multi = buf.readVarInt();
        e.costMulti = buf.readVarInt();
        e.endTime = buf.readVarLong();
        e.weekMask = buf.readVarInt();
        e.delivery = buf.readVarInt();
        e.winChance = buf.readDouble();
        e.pityNow = buf.readVarInt();
        e.pityCount = buf.readVarInt();
        e.pityTier = buf.readVarInt();
        e.guaranteeTier = buf.readVarInt() - 1;
        int np = Math.min(buf.readVarInt(), TicketType.MAX_POOLS * 4);
        for (int i = 0; i < np; i++) e.pools.add(new OddsPool(buf.readUtf(256), buf.readVarInt(), buf.readDouble()));
        int ni = Math.min(buf.readVarInt(), TicketType.MAX_POOLS * TicketType.MAX_ITEMS);
        for (int i = 0; i < ni; i++) {
            e.items.add(new OddsItem(ItemStack.STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readDouble(), buf.readVarInt(), buf.readVarInt()));
        }
        return e;
    }

    /** Odds table for {@code type} (pools that can't be drawn are left out). */
    public static HubEntry of(TicketType type) {
        HubEntry e = new HubEntry();
        e.typeId = type.id;
        e.name = type.name;
        e.style = type.style;
        e.voucherId = type.voucherId;
        e.costSingle = type.costSingle;
        e.multi = type.effectiveMulti();
        e.costMulti = e.multi > 0 ? type.costMulti : 0;
        e.endTime = type.endTime;
        e.weekMask = type.weekMask;
        e.delivery = type.delivery;
        e.winChance = type.winChance();
        e.pityCount = type.pityCount > 0 && type.hasTierAtLeast(type.pityTier) ? type.pityCount : 0;
        e.pityTier = type.pityTier;
        e.guaranteeTier = type.guaranteeTier >= 0 && type.hasTierAtLeast(type.guaranteeTier) ? type.guaranteeTier : -1;
        long total = type.totalWeight();
        if (total <= 0) return e;
        List<TicketType.Pool> sorted = new ArrayList<>(type.pools);
        sorted.sort((a, b) -> Integer.compare(b.tier, a.tier));
        for (TicketType.Pool p : sorted) {
            if (!p.usable()) continue;
            double pc = (double) p.weight / total;
            e.pools.add(new OddsPool(p.name, p.tier, pc));
            long iw = p.totalItemWeight();
            for (TicketType.PoolItem it : p.items) {
                if (!it.usable()) continue;
                e.items.add(new OddsItem(it.item.copyWithCount(1), p.tier, pc * it.weight / iw, it.min, it.max));
            }
        }
        return e;
    }
}
