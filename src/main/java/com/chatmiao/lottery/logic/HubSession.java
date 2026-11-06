package com.chatmiao.lottery.logic;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * A gacha pull that has been paid for and rolled but not collected yet (the player is still watching the
 * animation, closed the screen, or disconnected). Saved in the ledger so the result can never be re-rolled;
 * reopening the gacha screen resumes it.
 */
public final class HubSession {
    public ScratchRound round;
    /** Whether this was a multi pull (used for "再抽一次"). */
    public boolean multi;
    /** {@link com.chatmiao.lottery.data.TicketType#DELIVER_DIRECT} or DELIVER_TICKET, fixed at pull time. */
    public int delivery;

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.put("round", round.save(provider));
        tag.putBoolean("multi", multi);
        tag.putInt("delivery", delivery);
        return tag;
    }

    public static HubSession load(CompoundTag tag, HolderLookup.Provider provider) {
        HubSession s = new HubSession();
        s.round = ScratchRound.load(tag.getCompound("round"), provider);
        s.multi = tag.getBoolean("multi");
        s.delivery = tag.getInt("delivery");
        return s;
    }
}
