package com.chatmiao.lottery.logic;

import com.chatmiao.lottery.data.Draw;
import com.chatmiao.lottery.data.Prize;
import com.chatmiao.lottery.data.TicketType;
import com.chatmiao.lottery.data.Tier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** The draw. Server-only; clients never roll. */
public final class Roller {
    /** CASE reels: tiles per reel, and the tile the reel stops on (always the real result). */
    public static final int REEL_LEN = 48, REEL_STOP = 40;
    /** Reel tile markers: the real result / a "谢谢参与" decoy. Other values index the palette. */
    public static final int TILE_RESULT = -2, TILE_EMPTY = -1;
    private static final int MAX_PALETTE = 64;

    /** One plain draw. @return the prize, or {@code null} for "谢谢参与". */
    public static Prize roll(TicketType type, RandomSource random) {
        return rollDraw(type, random, -1).prize();
    }

    /**
     * One draw. With {@code minTier >= 0} the draw is forced into a usable pool of at least that tier
     * (pool weights still decide which one); if no such pool exists it falls back to a normal draw.
     */
    public static Draw rollDraw(TicketType type, RandomSource random, int minTier) {
        if (minTier >= 0) {
            long total = 0;
            for (TicketType.Pool p : type.pools) if (p.usable() && p.tier >= minTier) total += p.weight;
            if (total > 0) {
                long r = nextLong(random, total);
                for (TicketType.Pool p : type.pools) {
                    if (!p.usable() || p.tier < minTier) continue;
                    if (r < p.weight) return new Draw(rollInPool(p, random), p.tier);
                    r -= p.weight;
                }
            }
        }
        long total = type.totalWeight();
        if (total <= 0) return Draw.EMPTY;
        long r = nextLong(random, total);
        r -= Math.max(0, type.emptyWeight);
        if (r < 0) return Draw.EMPTY;
        for (TicketType.Pool pool : type.pools) {
            if (!pool.usable()) continue;
            if (r < pool.weight) return new Draw(rollInPool(pool, random), pool.tier);
            r -= pool.weight;
        }
        return Draw.EMPTY; // unreachable when weights are consistent
    }

    /**
     * All draws of one ticket, applying the pity rules.
     *
     * @param pity in/out: draws in a row (for this player and type) without a prize of tier >= pityTier
     */
    public static List<Draw> rollTicket(TicketType type, RandomSource random, int[] pity) {
        int n = Math.max(1, Math.min(type.draws, type.maxDraws()));
        boolean pityOn = type.pityCount > 0 && type.hasTierAtLeast(type.pityTier);
        List<Draw> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            boolean force = pityOn && pity[0] >= type.pityCount - 1;
            Draw d = rollDraw(type, random, force ? type.pityTier : -1);
            if (pityOn) pity[0] = d.won() && d.tier() >= type.pityTier ? 0 : pity[0] + 1;
            out.add(d);
        }
        int g = type.guaranteeTier;
        if (g >= 0 && n > 1 && type.hasTierAtLeast(g)) {
            boolean has = false;
            for (Draw d : out) if (d.won() && d.tier() >= g) has = true;
            if (!has) {
                // replace a random draw so the guaranteed prize doesn't always sit in the last slot
                Draw forced = rollDraw(type, random, g);
                out.set(random.nextInt(n), forced);
                if (pityOn && forced.tier() >= type.pityTier) pity[0] = 0;
            }
        }
        return out;
    }

    private static Prize rollInPool(TicketType.Pool pool, RandomSource random) {
        long r = nextLong(random, pool.totalItemWeight());
        for (TicketType.PoolItem it : pool.items) {
            if (!it.usable()) continue;
            if (r < it.weight) {
                int count = it.min + (it.max > it.min ? random.nextInt(it.max - it.min + 1) : 0);
                return new Prize(it.item, count);
            }
            r -= it.weight;
        }
        return null;
    }

    private static long nextLong(RandomSource random, long bound) {
        if (bound <= Integer.MAX_VALUE) return random.nextInt((int) bound);
        return Math.floorMod(random.nextLong(), bound);
    }

    // ------------------------------------------------------------------ MATCH board

    /**
     * Number-match board. {@code winCount} distinct winning numbers; {@code cells} player numbers, distinct from
     * each other and from the winning numbers. Then every winning draw takes its own cell and replaces it with
     * one of the winning numbers. So a cell matches a winning number exactly when it carries a prize.
     *
     * @param range numbers come from 1..range, must be >= winCount + cells
     */
    public static Board buildBoard(RandomSource random, int range, int winCount, int cells, List<Draw> draws) {
        List<Integer> pool = new ArrayList<>();
        for (int i = 1; i <= Math.max(range, winCount + cells); i++) pool.add(i);
        int[] winning = new int[winCount];
        for (int i = 0; i < winCount; i++) winning[i] = pool.remove(random.nextInt(pool.size()));
        int[] board = new int[cells];
        for (int i = 0; i < cells; i++) board[i] = pool.remove(random.nextInt(pool.size()));
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < cells; i++) free.add(i);
        int[] hits = new int[draws.size()];
        for (int d = 0; d < draws.size(); d++) {
            if (!draws.get(d).won() || free.isEmpty()) {
                hits[d] = -1;
                continue;
            }
            int cell = free.remove(random.nextInt(free.size()));
            board[cell] = winning[random.nextInt(winCount)];
            hits[d] = cell;
        }
        return new Board(winning, board, hits);
    }

    /** @param hitCells per draw: the board cell carrying its prize, or -1 when the draw lost */
    public record Board(int[] winning, int[] board, int[] hitCells) {}

    // ------------------------------------------------------------------ CASE reels

    /**
     * Reel strips for the case-opening style. Decoy tiles are drawn from the real pools (weighted, so rare
     * items show up rarely) with some "谢谢参与" tiles mixed in; tile {@link #REEL_STOP} is the real result.
     */
    public static Reels buildReels(TicketType type, RandomSource random, int reelCount) {
        List<ItemStack> palette = new ArrayList<>();
        List<Integer> tiers = new ArrayList<>();
        int[][] reels = new int[reelCount][REEL_LEN];
        boolean anyPool = type.hasTierAtLeast(0);
        for (int[] reel : reels) {
            for (int k = 0; k < REEL_LEN; k++) {
                if (k == REEL_STOP) {
                    reel[k] = TILE_RESULT;
                    continue;
                }
                if (!anyPool || (type.emptyWeight > 0 && random.nextInt(4) == 0)) {
                    reel[k] = TILE_EMPTY;
                    continue;
                }
                Draw d = rollDraw(type, random, 0);
                reel[k] = d.won() ? paletteIndex(palette, tiers, d, random) : TILE_EMPTY;
            }
        }
        int[] tierArr = new int[tiers.size()];
        for (int i = 0; i < tierArr.length; i++) tierArr[i] = tiers.get(i);
        return new Reels(palette, tierArr, reels);
    }

    private static int paletteIndex(List<ItemStack> palette, List<Integer> tiers, Draw d, RandomSource random) {
        ItemStack item = d.prize().item();
        for (int i = 0; i < palette.size(); i++) {
            if (ItemStack.isSameItemSameComponents(palette.get(i), item)) return i;
        }
        if (palette.size() >= MAX_PALETTE) return random.nextInt(palette.size());
        palette.add(item.copyWithCount(1));
        tiers.add(d.tier());
        return palette.size() - 1;
    }

    public record Reels(List<ItemStack> palette, int[] paletteTier, int[][] reels) {}

    /** Display tier of a palette entry (defensive against bad indices). */
    public static int paletteTier(int[] tiers, int index) {
        return index >= 0 && index < tiers.length ? tiers[index] : Tier.NONE;
    }

    private Roller() {}
}
