package com.chatmiao.lottery.logic;

import com.chatmiao.lottery.data.Prize;
import com.chatmiao.lottery.data.TicketType;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/** The draw. Server-only; clients never roll. */
public final class Roller {
    public static final int WINNING_COUNT = 3;
    public static final int BOARD_SIZE = 9;

    /** @return the prize, or {@code null} for "谢谢参与". */
    public static Prize roll(TicketType type, RandomSource random) {
        long total = type.totalWeight();
        if (total <= 0) return null;
        long r = nextLong(random, total);
        r -= Math.max(0, type.emptyWeight);
        if (r < 0) return null;
        for (TicketType.Pool pool : type.pools) {
            if (!pool.usable()) continue;
            if (r < pool.weight) return rollInPool(pool, random);
            r -= pool.weight;
        }
        return null; // unreachable when weights are consistent
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

    /**
     * Number-match board. Winning numbers are 3 distinct values; the 9 player numbers are distinct too.
     * A losing board never contains a winning number. A winning board has exactly one cell equal to one
     * of the winning numbers.
     */
    public static Board buildBoard(RandomSource random, int range, boolean won) {
        List<Integer> pool = new ArrayList<>();
        for (int i = 1; i <= range; i++) pool.add(i);
        int[] winning = new int[WINNING_COUNT];
        for (int i = 0; i < WINNING_COUNT; i++) winning[i] = pool.remove(random.nextInt(pool.size()));
        int[] board = new int[BOARD_SIZE];
        for (int i = 0; i < BOARD_SIZE; i++) board[i] = pool.remove(random.nextInt(pool.size()));
        int hit = -1;
        if (won) {
            hit = random.nextInt(BOARD_SIZE);
            board[hit] = winning[random.nextInt(WINNING_COUNT)];
        }
        return new Board(winning, board, hit);
    }

    public record Board(int[] winning, int[] board, int hitCell) {}

    private Roller() {}
}
