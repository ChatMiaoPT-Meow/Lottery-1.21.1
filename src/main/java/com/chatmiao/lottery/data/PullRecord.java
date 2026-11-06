package com.chatmiao.lottery.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** One line of a player's gacha history (抽卡记录): when, which pool, how many draws and what came out. */
public final class PullRecord {
    public long time;
    public int typeId;
    public String typeName = "";
    public int draws;
    /** Only the winning draws (prize + tier). */
    public List<Draw> wins = new ArrayList<>();

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarLong(time);
        buf.writeVarInt(typeId);
        buf.writeUtf(typeName, 256);
        buf.writeVarInt(draws);
        buf.writeVarInt(wins.size());
        for (Draw d : wins) d.write(buf);
    }

    public static PullRecord read(RegistryFriendlyByteBuf buf) {
        PullRecord r = new PullRecord();
        r.time = buf.readVarLong();
        r.typeId = buf.readVarInt();
        r.typeName = buf.readUtf(256);
        r.draws = buf.readVarInt();
        int n = Math.min(buf.readVarInt(), 64);
        for (int i = 0; i < n; i++) r.wins.add(Draw.read(buf));
        return r;
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("time", time);
        tag.putInt("type", typeId);
        tag.putString("typeName", typeName);
        tag.putInt("draws", draws);
        ListTag list = new ListTag();
        for (Draw d : wins) list.add(d.save(provider));
        tag.put("wins", list);
        return tag;
    }

    public static PullRecord load(CompoundTag tag, HolderLookup.Provider provider) {
        PullRecord r = new PullRecord();
        r.time = tag.getLong("time");
        r.typeId = tag.getInt("type");
        r.typeName = tag.getString("typeName");
        r.draws = tag.getInt("draws");
        for (Tag t : tag.getList("wins", Tag.TAG_COMPOUND)) {
            Draw d = Draw.load((CompoundTag) t, provider);
            if (d.won()) r.wins.add(d);
        }
        return r;
    }
}
