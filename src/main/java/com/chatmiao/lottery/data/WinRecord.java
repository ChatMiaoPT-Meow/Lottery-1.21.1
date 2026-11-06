package com.chatmiao.lottery.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server ledger entry for one winning ticket. A multi-draw ticket holds all of its prizes in one record. */
public final class WinRecord {
    public String winId;
    public int typeId;
    public String typeName;
    public List<Prize> prizes = new ArrayList<>();
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
        ListTag list = new ListTag();
        for (Prize p : prizes) list.add(p.save(provider));
        tag.put("prizes", list);
        // the first prize is also kept under the old key so older versions of the mod can still read the record
        if (!prizes.isEmpty()) tag.put("prize", prizes.get(0).save(provider));
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
        if (tag.contains("prizes", Tag.TAG_LIST)) {
            for (Tag t : tag.getList("prizes", Tag.TAG_COMPOUND)) r.prizes.add(Prize.load((CompoundTag) t, provider));
        } else if (tag.contains("prize")) {
            r.prizes.add(Prize.load(tag.getCompound("prize"), provider));
        }
        r.prizes.removeIf(p -> p.item().isEmpty() || p.count() <= 0);
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
