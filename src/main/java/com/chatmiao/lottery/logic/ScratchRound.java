package com.chatmiao.lottery.logic;

import com.chatmiao.lottery.data.Draw;
import com.chatmiao.lottery.data.PlayStyle;
import com.chatmiao.lottery.data.Prize;
import com.chatmiao.lottery.data.Tier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The ticket currently being played on a table. Created (and every draw locked) the moment the ticket is
 * taken off the table's stack; closing the GUI does not change it. The play-style settings are copied from
 * the type at that moment, so editing the type later never changes a ticket that is already out.
 * <p>
 * Reveal bits ({@link #revealedMask}):
 * MATCH: 0..winCount-1 = winning numbers, then one bit per grid cell. CASE: one per reel. CARDS: one per card.
 */
public final class ScratchRound {
    public int roundId;
    public int typeId;
    public String typeName = "";

    public int style = PlayStyle.MATCH.id;
    public int cols = 3, rows = 3, cardCols = 5, spinSeconds = 5;
    public boolean rareEffect = true;

    /** One entry per draw. */
    public List<Draw> draws = new ArrayList<>();

    // MATCH
    public int[] winning = new int[0];
    public int[] board = new int[0];
    /** Per draw: the board cell carrying its prize, or -1. */
    public int[] hitCells = new int[0];

    // CASE
    public List<ItemStack> palette = new ArrayList<>();
    public int[] paletteTier = new int[0];
    public int[][] reels = new int[0][];

    public String winId = "";
    public long revealedMask;

    public PlayStyle playStyle() {
        return PlayStyle.byId(style);
    }

    public boolean won() {
        for (Draw d : draws) if (d.won()) return true;
        return false;
    }

    public List<Prize> prizes() {
        List<Prize> out = new ArrayList<>();
        for (Draw d : draws) if (d.won()) out.add(d.prize());
        return out;
    }

    /** Number of reveal bits (always < 64). */
    public int cellCount() {
        return playStyle() == PlayStyle.MATCH ? winning.length + board.length : draws.size();
    }

    public long allMask() {
        int n = cellCount();
        return n >= 64 ? -1L : (1L << n) - 1;
    }

    /** MATCH: draw index whose prize sits in board cell {@code cell}, or -1. */
    public int drawAtCell(int cell) {
        for (int d = 0; d < hitCells.length; d++) if (hitCells[d] == cell) return d;
        return -1;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(roundId);
        buf.writeVarInt(typeId);
        buf.writeUtf(typeName, 256);
        buf.writeVarInt(style);
        buf.writeVarInt(cols);
        buf.writeVarInt(rows);
        buf.writeVarInt(cardCols);
        buf.writeVarInt(spinSeconds);
        buf.writeBoolean(rareEffect);
        buf.writeVarInt(draws.size());
        for (Draw d : draws) d.write(buf);
        buf.writeVarIntArray(winning);
        buf.writeVarIntArray(board);
        writeSigned(buf, hitCells);
        buf.writeVarInt(palette.size());
        for (ItemStack s : palette) ItemStack.STREAM_CODEC.encode(buf, s);
        writeSigned(buf, paletteTier);
        buf.writeVarInt(reels.length);
        for (int[] reel : reels) writeSigned(buf, reel);
        buf.writeUtf(winId, 64);
        buf.writeLong(revealedMask);
    }

    public static ScratchRound read(RegistryFriendlyByteBuf buf) {
        ScratchRound r = new ScratchRound();
        r.roundId = buf.readVarInt();
        r.typeId = buf.readVarInt();
        r.typeName = buf.readUtf(256);
        r.style = buf.readVarInt();
        r.cols = buf.readVarInt();
        r.rows = buf.readVarInt();
        r.cardCols = buf.readVarInt();
        r.spinSeconds = buf.readVarInt();
        r.rareEffect = buf.readBoolean();
        int n = Math.min(buf.readVarInt(), 64);
        for (int i = 0; i < n; i++) r.draws.add(Draw.read(buf));
        r.winning = buf.readVarIntArray(64);
        r.board = buf.readVarIntArray(64);
        r.hitCells = readSigned(buf, 64);
        int p = Math.min(buf.readVarInt(), 256);
        for (int i = 0; i < p; i++) r.palette.add(ItemStack.STREAM_CODEC.decode(buf));
        r.paletteTier = readSigned(buf, 256);
        int reelCount = Math.min(buf.readVarInt(), 16);
        r.reels = new int[reelCount][];
        for (int i = 0; i < reelCount; i++) r.reels[i] = readSigned(buf, 256);
        r.winId = buf.readUtf(64);
        r.revealedMask = buf.readLong();
        return r;
    }

    private static void writeSigned(RegistryFriendlyByteBuf buf, int[] arr) {
        buf.writeVarInt(arr.length);
        for (int v : arr) buf.writeVarInt(v + 2); // values are >= -2
    }

    private static int[] readSigned(RegistryFriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        if (n > max) throw new IllegalArgumentException("array too long: " + n);
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = buf.readVarInt() - 2;
        return arr;
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("roundId", roundId);
        tag.putInt("typeId", typeId);
        tag.putString("typeName", typeName);
        tag.putInt("style", style);
        tag.putInt("cols", cols);
        tag.putInt("rows", rows);
        tag.putInt("cardCols", cardCols);
        tag.putInt("spinSeconds", spinSeconds);
        tag.putBoolean("rareEffect", rareEffect);
        ListTag drawList = new ListTag();
        for (Draw d : draws) drawList.add(d.save(provider));
        tag.put("draws", drawList);
        tag.putIntArray("winning", winning);
        tag.putIntArray("board", board);
        tag.putIntArray("hitCells", hitCells);
        ListTag pal = new ListTag();
        for (ItemStack s : palette) pal.add(s.save(provider));
        tag.put("palette", pal);
        tag.putIntArray("paletteTier", paletteTier);
        ListTag reelList = new ListTag();
        for (int[] reel : reels) reelList.add(new IntArrayTag(reel));
        tag.put("reels", reelList);
        tag.putString("winId", winId);
        tag.putLong("revealedL", revealedMask);
        return tag;
    }

    public static ScratchRound load(CompoundTag tag, HolderLookup.Provider provider) {
        ScratchRound r = new ScratchRound();
        r.roundId = tag.getInt("roundId");
        r.typeId = tag.getInt("typeId");
        r.typeName = tag.getString("typeName");
        r.winId = tag.getString("winId");
        r.winning = tag.getIntArray("winning");
        r.board = tag.getIntArray("board");
        if (!tag.contains("draws")) {
            // a round saved before multi-draw tickets: classic 3×3 with one optional prize
            r.revealedMask = tag.getInt("revealed") & 0xFFFFFFFFL;
            int hit = tag.getInt("hitCell");
            if (tag.contains("prize")) {
                Prize p = Prize.load(tag.getCompound("prize"), provider);
                r.draws.add(new Draw(p, 0));
                r.hitCells = new int[]{hit};
            } else {
                r.draws.add(Draw.EMPTY);
                r.hitCells = new int[]{-1};
            }
            return r;
        }
        r.style = tag.getInt("style");
        r.cols = Math.max(1, tag.getInt("cols"));
        r.rows = Math.max(1, tag.getInt("rows"));
        r.cardCols = Math.max(1, tag.getInt("cardCols"));
        r.spinSeconds = Math.max(1, tag.getInt("spinSeconds"));
        r.rareEffect = tag.getBoolean("rareEffect");
        for (Tag t : tag.getList("draws", Tag.TAG_COMPOUND)) r.draws.add(Draw.load((CompoundTag) t, provider));
        r.hitCells = tag.getIntArray("hitCells");
        for (Tag t : tag.getList("palette", Tag.TAG_COMPOUND)) {
            ItemStack s = ItemStack.parseOptional(provider, (CompoundTag) t);
            r.palette.add(s.isEmpty() ? new ItemStack(net.minecraft.world.item.Items.BARRIER) : s);
        }
        r.paletteTier = tag.getIntArray("paletteTier");
        ListTag reelList = tag.getList("reels", Tag.TAG_INT_ARRAY);
        r.reels = new int[reelList.size()][];
        for (int i = 0; i < reelList.size(); i++) r.reels[i] = reelList.getIntArray(i);
        r.revealedMask = tag.getLong("revealedL");
        return r;
    }

    /** Display tier for a CASE tile value. */
    public int tileTier(int reel, int tile) {
        if (tile == Roller.TILE_RESULT) return reel < draws.size() ? draws.get(reel).tier() : Tier.NONE;
        return Roller.paletteTier(paletteTier, tile);
    }
}
