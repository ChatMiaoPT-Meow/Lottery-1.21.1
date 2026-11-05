package com.chatmiao.lottery.logic;

import com.chatmiao.lottery.data.Prize;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * The ticket currently being scratched on a table. Created (and its result locked) the moment the
 * ticket is taken off the table's stack; closing the GUI does not change it.
 * Cells: bits 0..2 = winning numbers, bits 3..11 = player numbers.
 */
public final class ScratchRound {
    public static final int CELL_COUNT = Roller.WINNING_COUNT + Roller.BOARD_SIZE;
    public static final int ALL_CELLS = (1 << CELL_COUNT) - 1;

    public int roundId;
    public int typeId;
    public String typeName = "";
    public int[] winning = new int[Roller.WINNING_COUNT];
    public int[] board = new int[Roller.BOARD_SIZE];
    /** index into {@link #board}, or -1 when the ticket lost. */
    public int hitCell = -1;
    public Prize prize;
    public String winId = "";
    public int revealedMask;

    public boolean won() {
        return prize != null;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(roundId);
        buf.writeVarInt(typeId);
        buf.writeUtf(typeName, 256);
        for (int n : winning) buf.writeVarInt(n);
        for (int n : board) buf.writeVarInt(n);
        buf.writeVarInt(hitCell + 1);
        buf.writeBoolean(prize != null);
        if (prize != null) Prize.STREAM_CODEC.encode(buf, prize);
        buf.writeUtf(winId, 64);
        buf.writeVarInt(revealedMask);
    }

    public static ScratchRound read(RegistryFriendlyByteBuf buf) {
        ScratchRound r = new ScratchRound();
        r.roundId = buf.readVarInt();
        r.typeId = buf.readVarInt();
        r.typeName = buf.readUtf(256);
        for (int i = 0; i < r.winning.length; i++) r.winning[i] = buf.readVarInt();
        for (int i = 0; i < r.board.length; i++) r.board[i] = buf.readVarInt();
        r.hitCell = buf.readVarInt() - 1;
        if (buf.readBoolean()) r.prize = Prize.STREAM_CODEC.decode(buf);
        r.winId = buf.readUtf(64);
        r.revealedMask = buf.readVarInt();
        return r;
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("roundId", roundId);
        tag.putInt("typeId", typeId);
        tag.putString("typeName", typeName);
        tag.putIntArray("winning", winning);
        tag.putIntArray("board", board);
        tag.putInt("hitCell", hitCell);
        if (prize != null) tag.put("prize", prize.save(provider));
        tag.putString("winId", winId);
        tag.putInt("revealed", revealedMask);
        return tag;
    }

    public static ScratchRound load(CompoundTag tag, HolderLookup.Provider provider) {
        ScratchRound r = new ScratchRound();
        r.roundId = tag.getInt("roundId");
        r.typeId = tag.getInt("typeId");
        r.typeName = tag.getString("typeName");
        int[] w = tag.getIntArray("winning");
        int[] b = tag.getIntArray("board");
        if (w.length == r.winning.length) r.winning = w;
        if (b.length == r.board.length) r.board = b;
        r.hitCell = tag.getInt("hitCell");
        if (tag.contains("prize")) r.prize = Prize.load(tag.getCompound("prize"), provider);
        r.winId = tag.getString("winId");
        r.revealedMask = tag.getInt("revealed");
        return r;
    }
}
