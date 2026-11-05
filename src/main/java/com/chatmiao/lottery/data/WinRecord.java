package com.chatmiao.lottery.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Server ledger entry for one winning ticket. */
public final class WinRecord {
    public String winId;
    public int typeId;
    public String typeName;
    public Prize prize;
    public UUID winnerId;
    public String winnerName;
    public long rolledAt;
    public boolean redeemed;
    public long redeemedAt;
    public UUID redeemerId;
    public String redeemerName = "";

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putString("winId", winId);
        tag.putInt("typeId", typeId);
        tag.putString("typeName", typeName);
        tag.put("prize", prize.save(provider));
        tag.putUUID("winner", winnerId);
        tag.putString("winnerName", winnerName);
        tag.putLong("rolledAt", rolledAt);
        tag.putBoolean("redeemed", redeemed);
        if (redeemed) {
            tag.putLong("redeemedAt", redeemedAt);
            tag.putUUID("redeemer", redeemerId);
            tag.putString("redeemerName", redeemerName);
        }
        return tag;
    }

    public static WinRecord load(CompoundTag tag, HolderLookup.Provider provider) {
        WinRecord r = new WinRecord();
        r.winId = tag.getString("winId");
        r.typeId = tag.getInt("typeId");
        r.typeName = tag.getString("typeName");
        r.prize = Prize.load(tag.getCompound("prize"), provider);
        r.winnerId = tag.getUUID("winner");
        r.winnerName = tag.getString("winnerName");
        r.rolledAt = tag.getLong("rolledAt");
        r.redeemed = tag.getBoolean("redeemed");
        if (r.redeemed) {
            r.redeemedAt = tag.getLong("redeemedAt");
            r.redeemerId = tag.getUUID("redeemer");
            r.redeemerName = tag.getString("redeemerName");
        }
        return r;
    }
}
