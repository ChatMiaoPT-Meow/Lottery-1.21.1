package com.chatmiao.lottery.gametest;

import com.chatmiao.lottery.LotteryMod;
import com.chatmiao.lottery.ModRegistry;
import com.chatmiao.lottery.block.ScratchTableBlockEntity;
import com.chatmiao.lottery.data.Draw;
import com.chatmiao.lottery.data.LotteryData;
import com.chatmiao.lottery.data.PlayStyle;
import com.chatmiao.lottery.data.Prize;
import com.chatmiao.lottery.data.TicketType;
import com.chatmiao.lottery.data.WinRecord;
import com.chatmiao.lottery.item.ScratchTicketItem;
import com.chatmiao.lottery.item.WinTicketData;
import com.chatmiao.lottery.item.WinningTicketItem;
import com.chatmiao.lottery.logic.RedeemService;
import com.chatmiao.lottery.logic.Roller;
import com.chatmiao.lottery.logic.ScratchRound;
import com.chatmiao.lottery.logic.ScratchService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@GameTestHolder(LotteryMod.MODID)
@PrefixGameTestTemplate(false)
public class LotteryGameTests {

    private static TicketType.Pool pool(String name, int weight, ItemStack item, int itemWeight, int min, int max) {
        TicketType.Pool p = new TicketType.Pool();
        p.name = name;
        p.weight = weight;
        TicketType.PoolItem it = new TicketType.PoolItem();
        it.item = item;
        it.weight = itemWeight;
        it.min = min;
        it.max = max;
        p.items.add(it);
        return p;
    }

    private static TicketType saveType(GameTestHelper h, String name, int emptyWeight, TicketType.Pool... pools) {
        TicketType t = new TicketType();
        t.name = name;
        t.emptyWeight = emptyWeight;
        for (TicketType.Pool p : pools) t.pools.add(p);
        return LotteryData.get(h.getLevel().getServer()).saveType(t);
    }

    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 0));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    /** 65/25/8/2 → 35% win rate; pool and item weights respected; amounts within range. */
    @GameTest(template = "empty")
    public static void rollOdds(GameTestHelper h) {
        TicketType t = new TicketType();
        t.emptyWeight = 65;
        t.pools.add(pool("C", 25, new ItemStack(Items.IRON_INGOT), 1, 8, 16));
        t.pools.add(pool("B", 8, new ItemStack(Items.GOLD_INGOT), 1, 2, 6));
        t.pools.add(pool("A", 2, new ItemStack(Items.DIAMOND), 1, 1, 1));
        t.pools.add(pool("unused-weight0", 0, new ItemStack(Items.NETHERITE_INGOT), 1, 1, 1));
        t.pools.add(new TicketType.Pool()); // empty pool must not steal probability
        h.assertTrue(Math.abs(t.winChance() - 0.35) < 1e-9, "winChance should be 0.35 but was " + t.winChance());

        RandomSource rnd = RandomSource.create(42);
        int n = 200_000, wins = 0, iron = 0, gold = 0, diamond = 0;
        for (int i = 0; i < n; i++) {
            Prize p = Roller.roll(t, rnd);
            if (p == null) continue;
            wins++;
            if (p.item().is(Items.IRON_INGOT)) {
                iron++;
                h.assertTrue(p.count() >= 8 && p.count() <= 16, "iron count out of range: " + p.count());
            } else if (p.item().is(Items.GOLD_INGOT)) {
                gold++;
                h.assertTrue(p.count() >= 2 && p.count() <= 6, "gold count out of range: " + p.count());
            } else if (p.item().is(Items.DIAMOND)) {
                diamond++;
            } else {
                h.fail("unexpected prize " + p.item());
            }
        }
        assertNear(h, "win", wins / (double) n, 0.35);
        assertNear(h, "iron", iron / (double) n, 0.25);
        assertNear(h, "gold", gold / (double) n, 0.08);
        assertNear(h, "diamond", diamond / (double) n, 0.02);

        TicketType none = new TicketType();
        none.emptyWeight = 0;
        h.assertTrue(Roller.roll(none, rnd) == null, "no weights at all must be a loss");
        h.succeed();
    }

    private static void assertNear(GameTestHelper h, String what, double actual, double expected) {
        h.assertTrue(Math.abs(actual - expected) < 0.006, what + " rate " + actual + " too far from " + expected);
    }

    /**
     * Board rules for any layout (3×3 up to 10×5, 1..5 winning numbers, several draws): a cell matches a winning
     * number exactly when a winning draw put its prize there, every winning draw gets its own cell, and the
     * non-matching numbers are all distinct.
     */
    @GameTest(template = "empty")
    public static void boardRules(GameTestHelper h) {
        RandomSource rnd = RandomSource.create(7);
        int[][] layouts = {{3, 3, 3}, {10, 4, 5}, {10, 5, 5}, {1, 1, 1}, {5, 2, 2}};
        for (int i = 0; i < 6000; i++) {
            int[] l = layouts[i % layouts.length];
            int cells = l[0] * l[1], winCount = l[2], range = winCount + cells + rnd.nextInt(10);
            int n = 1 + rnd.nextInt(Math.min(10, cells));
            List<Draw> draws = new java.util.ArrayList<>();
            int expectedHits = 0;
            for (int d = 0; d < n; d++) {
                boolean won = rnd.nextBoolean();
                draws.add(won ? new Draw(new Prize(new ItemStack(Items.STONE), 1), 0) : Draw.EMPTY);
                if (won) expectedHits++;
            }
            Roller.Board b = Roller.buildBoard(rnd, range, winCount, cells, draws);
            Set<Integer> winning = new HashSet<>();
            for (int w : b.winning()) winning.add(w);
            h.assertTrue(winning.size() == winCount, "winning numbers must be distinct");
            h.assertTrue(b.board().length == cells && b.hitCells().length == n, "wrong board/hit sizes");
            Set<Integer> hitCells = new HashSet<>();
            for (int d = 0; d < n; d++) {
                int c = b.hitCells()[d];
                h.assertTrue((c >= 0) == draws.get(d).won(), "hit cell must exist exactly for winning draws");
                if (c >= 0) h.assertTrue(hitCells.add(c), "two draws share a cell");
            }
            int matches = 0;
            Set<Integer> seen = new HashSet<>();
            for (int k = 0; k < cells; k++) {
                int v = b.board()[k];
                h.assertTrue(v >= 1 && v <= range, "number out of range");
                if (winning.contains(v)) {
                    matches++;
                    h.assertTrue(hitCells.contains(k), "a match must be on a hit cell");
                } else {
                    h.assertTrue(seen.add(v), "non-matching numbers must be distinct");
                }
            }
            h.assertTrue(matches == expectedHits, "wrong number of matches: " + matches + " expected " + expectedHits);
        }
        h.succeed();
    }

    /** Multi-draw odds: each of the N draws follows the pool weights independently. */
    @GameTest(template = "empty")
    public static void multiDrawOdds(GameTestHelper h) {
        TicketType t = new TicketType();
        t.emptyWeight = 60;
        t.style = PlayStyle.CARDS.id;
        t.draws = 10;
        t.pools.add(tiered(pool("C", 30, new ItemStack(Items.IRON_INGOT), 1, 1, 1), 0));
        t.pools.add(tiered(pool("A", 10, new ItemStack(Items.DIAMOND), 1, 1, 1), 4));
        RandomSource rnd = RandomSource.create(3);
        int tickets = 20_000, draws = 0, iron = 0, diamond = 0, anyWin = 0;
        int[] pity = {0};
        for (int i = 0; i < tickets; i++) {
            List<Draw> ds = Roller.rollTicket(t, rnd, pity);
            h.assertTrue(ds.size() == 10, "a ten-pull must have 10 draws");
            boolean won = false;
            for (Draw d : ds) {
                draws++;
                if (!d.won()) {
                    h.assertTrue(d.tier() == -1, "an empty draw has no tier");
                    continue;
                }
                won = true;
                if (d.prize().item().is(Items.IRON_INGOT)) {
                    iron++;
                    h.assertTrue(d.tier() == 0, "iron comes from the tier-0 pool");
                } else {
                    diamond++;
                    h.assertTrue(d.tier() == 4, "diamond comes from the tier-4 pool");
                }
            }
            if (won) anyWin++;
        }
        assertNear(h, "iron", iron / (double) draws, 0.30);
        assertNear(h, "diamond", diamond / (double) draws, 0.10);
        assertNear(h, "ticket", anyWin / (double) tickets, 1 - Math.pow(0.6, 10));
        h.assertTrue(Math.abs(t.ticketWinChance() - (1 - Math.pow(0.6, 10))) < 1e-9, "ticketWinChance formula");

        // draws are clamped to what the style allows
        t.draws = 99;
        h.assertTrue(Roller.rollTicket(t, rnd, pity).size() == PlayStyle.CARDS.maxDraws, "draws must be clamped");
        t.style = PlayStyle.MATCH.id;
        t.cols = 2;
        t.rows = 2;
        h.assertTrue(Roller.rollTicket(t, rnd, pity).size() == 4, "MATCH can't draw more than it has cells");
        h.succeed();
    }

    /** Pity: never more than pityCount-1 misses in a row; per-ticket guarantee: every multi-draw ticket has one. */
    @GameTest(template = "empty")
    public static void pityAndGuarantee(GameTestHelper h) {
        TicketType t = new TicketType();
        t.emptyWeight = 1000;
        t.draws = 1;
        t.pools.add(tiered(pool("C", 100, new ItemStack(Items.IRON_INGOT), 1, 1, 1), 0));
        t.pools.add(tiered(pool("S", 1, new ItemStack(Items.NETHER_STAR), 1, 1, 1), 5));
        t.pityCount = 20;
        t.pityTier = 5;
        RandomSource rnd = RandomSource.create(11);
        int[] pity = {0};
        int sinceStar = 0, stars = 0;
        for (int i = 0; i < 20_000; i++) {
            Draw d = Roller.rollTicket(t, rnd, pity).get(0);
            if (d.won() && d.tier() == 5) {
                stars++;
                sinceStar = 0;
                h.assertTrue(pity[0] == 0, "pity counter must reset on a hit");
            } else {
                sinceStar++;
                h.assertTrue(sinceStar < 20, "pity broken: " + sinceStar + " misses in a row");
                h.assertTrue(pity[0] == sinceStar, "pity counter out of sync");
            }
        }
        h.assertTrue(stars >= 20_000 / 20, "pity should give at least one star per 20 draws, got " + stars);

        // pity pointing at a tier no pool reaches must be ignored (normal draw, counter untouched)
        t.pools.remove(1);
        t.pityTier = 4;
        pity[0] = 500;
        Draw d = Roller.rollTicket(t, rnd, pity).get(0);
        h.assertTrue(d.tier() <= 0 && pity[0] == 500, "no tier-4+ pool exists, pity must stay inactive");

        // per-ticket guarantee
        TicketType g = new TicketType();
        g.style = PlayStyle.CARDS.id;
        g.emptyWeight = 1000;
        g.draws = 10;
        g.guaranteeTier = 3;
        g.pools.add(tiered(pool("C", 100, new ItemStack(Items.IRON_INGOT), 1, 1, 1), 0));
        g.pools.add(tiered(pool("E", 1, new ItemStack(Items.DIAMOND), 1, 1, 1), 3));
        for (int i = 0; i < 2000; i++) {
            List<Draw> ds = Roller.rollTicket(g, rnd, new int[]{0});
            h.assertTrue(ds.stream().anyMatch(x -> x.won() && x.tier() >= 3), "ten-pull without its guaranteed prize");
        }
        h.assertTrue(g.ticketWinChance() == 1, "guaranteed ticket always wins");
        h.succeed();
    }

    /** Case reels: fixed length, the stop tile is the real result, decoys reference the palette. */
    @GameTest(template = "empty")
    public static void caseReels(GameTestHelper h) {
        TicketType t = new TicketType();
        t.style = PlayStyle.CASE.id;
        t.emptyWeight = 10;
        t.pools.add(tiered(pool("C", 10, new ItemStack(Items.IRON_INGOT), 1, 1, 1), 0));
        t.pools.add(tiered(pool("A", 1, new ItemStack(Items.DIAMOND), 1, 1, 1), 4));
        Roller.Reels r = Roller.buildReels(t, RandomSource.create(1), 3);
        h.assertTrue(r.reels().length == 3, "one reel per draw");
        for (int[] reel : r.reels()) {
            h.assertTrue(reel.length == Roller.REEL_LEN, "reel length");
            for (int k = 0; k < reel.length; k++) {
                if (k == Roller.REEL_STOP) h.assertTrue(reel[k] == Roller.TILE_RESULT, "stop tile must be the result");
                else h.assertTrue(reel[k] == Roller.TILE_EMPTY || (reel[k] >= 0 && reel[k] < r.palette().size()), "bad tile " + reel[k]);
            }
        }
        h.assertTrue(r.palette().size() <= 2 && r.paletteTier().length == r.palette().size(), "palette dedups items");
        // a type with no pools still gets valid (all empty) reels
        TicketType none = new TicketType();
        none.style = PlayStyle.CASE.id;
        Roller.Reels e = Roller.buildReels(none, RandomSource.create(1), 1);
        h.assertTrue(e.palette().isEmpty(), "no pools, no palette");
        h.succeed();
    }

    /** A ten-pull on the table: one winning ticket holds every prize; redeeming it pays all of them once. */
    @GameTest(template = "empty")
    public static void tenPullRedeem(GameTestHelper h) {
        TicketType t = new TicketType();
        t.name = "十连测试";
        t.style = PlayStyle.CARDS.id;
        t.emptyWeight = 0;
        t.draws = 10;
        t.pools.add(tiered(pool("C", 1, new ItemStack(Items.IRON_INGOT), 1, 3, 3), 0));
        TicketType type = LotteryData.get(h.getLevel().getServer()).saveType(t);
        ServerPlayer player = player(h);
        BlockPos table = h.absolutePos(new BlockPos(0, 1, 0));
        BlockPos station = h.absolutePos(new BlockPos(2, 1, 0));
        h.setBlock(new BlockPos(0, 1, 0), ModRegistry.SCRATCH_TABLE.get());
        h.setBlock(new BlockPos(2, 1, 0), ModRegistry.REDEEM_STATION.get());
        ScratchTableBlockEntity be = (ScratchTableBlockEntity) h.getLevel().getBlockEntity(table);
        player.setItemInHand(InteractionHand.MAIN_HAND, ScratchTicketItem.create(type.id, 1));
        player.setShiftKeyDown(true);
        ScratchService.onUse(player, table, be);
        player.setShiftKeyDown(false);
        ScratchService.onUse(player, table, be);
        ScratchRound round = be.round;
        h.assertTrue(round != null && round.playStyle() == PlayStyle.CARDS && round.draws.size() == 10, "ten cards expected");
        h.assertTrue(round.prizes().size() == 10 && round.cellCount() == 10, "all ten draws win");

        // reveal bits beyond the round's cells are dropped
        ScratchService.reveal(player, table, round.roundId, -1L);
        h.assertTrue(round.revealedMask == round.allMask(), "reveal mask must be clipped to the cards");

        // the round survives a save/load round trip unchanged
        var provider = h.getLevel().registryAccess();
        ScratchRound copy = ScratchRound.load(round.save(provider), provider);
        h.assertTrue(copy.draws.size() == 10 && copy.style == round.style && copy.revealedMask == round.revealedMask
                && copy.winId.equals(round.winId), "round NBT round trip");

        ScratchService.finish(player, table, round.roundId, false);
        List<ItemStack> wins = winningTickets(player);
        h.assertTrue(wins.size() == 1, "exactly one winning ticket for a ten-pull, got " + wins.size());
        String winId = WinningTicketItem.dataOf(wins.get(0)).winId();
        RedeemService.redeem(player, station, List.of(winId));
        h.assertTrue(countItem(player, Items.IRON_INGOT) == 30, "10 prizes × 3 iron expected, got " + countItem(player, Items.IRON_INGOT));
        player.getInventory().add(WinningTicketItem.create(WinningTicketItem.dataOf(wins.get(0))));
        RedeemService.redeem(player, station, List.of(winId));
        h.assertTrue(countItem(player, Items.IRON_INGOT) == 30, "must not pay twice");
        h.succeed();
    }

    /** Ledger entries and rounds saved by the first version (single prize, 3×3, int reveal mask) still load. */
    @GameTest(template = "empty")
    public static void legacyDataLoads(GameTestHelper h) {
        var provider = h.getLevel().registryAccess();
        Prize diamond = new Prize(new ItemStack(Items.DIAMOND), 5);

        CompoundTag oldType = new CompoundTag();
        oldType.putInt("id", 7);
        oldType.putString("name", "老彩票");
        oldType.putInt("emptyWeight", 65);
        net.minecraft.nbt.ListTag pools = new net.minecraft.nbt.ListTag();
        CompoundTag pt = new CompoundTag();
        pt.putString("name", "p");
        pt.putInt("weight", 35);
        net.minecraft.nbt.ListTag items = new net.minecraft.nbt.ListTag();
        CompoundTag it = new CompoundTag();
        it.put("item", new ItemStack(Items.DIAMOND).save(provider));
        it.putInt("weight", 1);
        it.putInt("min", 1);
        it.putInt("max", 2);
        items.add(it);
        pt.put("items", items);
        pools.add(pt);
        oldType.put("pools", pools);
        TicketType t = TicketType.load(oldType, provider);
        h.assertTrue(t.playStyle() == PlayStyle.MATCH && t.draws == 1 && t.cols == 3 && t.rows == 3 && t.winCount == 3
                && t.pityCount == 0 && t.guaranteeTier == -1 && t.pools.get(0).tier == 0, "old type must load as classic 3×3");
        TicketType again = TicketType.load(t.save(provider), provider);
        h.assertTrue(again.pools.size() == 1 && again.draws == 1 && again.playStyle() == PlayStyle.MATCH, "type NBT round trip");

        CompoundTag oldWin = new CompoundTag();
        oldWin.putString("winId", "W7-OLDOLDOL");
        oldWin.putInt("typeId", 7);
        oldWin.putString("typeName", "老彩票");
        oldWin.put("prize", diamond.save(provider));
        oldWin.putUUID("winner", java.util.UUID.randomUUID());
        oldWin.putString("winnerName", "old");
        WinRecord w = WinRecord.load(oldWin, provider);
        h.assertTrue(w.prizes.size() == 1 && w.prizes.get(0).count() == 5, "old single-prize record must load");
        WinRecord w2 = WinRecord.load(w.save(provider), provider);
        h.assertTrue(w2.prizes.size() == 1, "record NBT round trip");

        CompoundTag oldRound = new CompoundTag();
        oldRound.putInt("roundId", 3);
        oldRound.putInt("typeId", 7);
        oldRound.putString("typeName", "老彩票");
        oldRound.putIntArray("winning", new int[]{7, 21, 13});
        oldRound.putIntArray("board", new int[]{3, 18, 25, 9, 21, 30, 1, 12, 27});
        oldRound.putInt("hitCell", 4);
        oldRound.put("prize", diamond.save(provider));
        oldRound.putString("winId", "W7-OLDOLDOL");
        oldRound.putInt("revealed", 0b111);
        ScratchRound r = ScratchRound.load(oldRound, provider);
        h.assertTrue(r.playStyle() == PlayStyle.MATCH && r.draws.size() == 1 && r.won() && r.hitCells[0] == 4
                && r.drawAtCell(4) == 0 && r.revealedMask == 0b111 && r.cellCount() == 12, "old round must load");
        h.succeed();
    }

    // ------------------------------------------------------------------ gacha pools (卡池) + vouchers

    private static com.chatmiao.lottery.data.VoucherType voucher(GameTestHelper h, String name) {
        com.chatmiao.lottery.data.VoucherType v = new com.chatmiao.lottery.data.VoucherType();
        v.name = name;
        return LotteryData.get(h.getLevel().getServer()).saveVoucher(v);
    }

    private static TicketType hubPool(GameTestHelper h, int voucherId, int delivery) {
        TicketType t = new TicketType();
        t.name = "卡池测试";
        t.style = PlayStyle.CARDS.id;
        t.emptyWeight = 0;
        t.pools.add(tiered(pool("C", 1, new ItemStack(Items.IRON_INGOT), 1, 2, 2), 0));
        t.inHub = true;
        t.voucherId = voucherId;
        t.costSingle = 1;
        t.multiCount = 10;
        t.costMulti = 9;
        t.delivery = delivery;
        return LotteryData.get(h.getLevel().getServer()).saveType(t);
    }

    private static int countVouchers(ServerPlayer p, int voucherId) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (com.chatmiao.lottery.item.VoucherItem.typeOf(s) == voucherId) n += s.getCount();
        return n;
    }

    /** Pay with vouchers, lock the result, collect prizes directly; double pulls and replays are harmless. */
    @GameTest(template = "empty")
    public static void hubPullAndCollect(GameTestHelper h) {
        LotteryData data = LotteryData.get(h.getLevel().getServer());
        var v = voucher(h, "测试券");
        TicketType type = hubPool(h, v.id, TicketType.DELIVER_DIRECT);
        ServerPlayer player = player(h);
        player.getInventory().add(com.chatmiao.lottery.item.VoucherItem.create(v.id, 12));

        com.chatmiao.lottery.logic.HubService.pull(player, type.id, true);
        var s = data.session(player.getUUID());
        h.assertTrue(s != null && s.round.draws.size() == 10 && s.multi, "a 10-pull session must be open");
        // all ten draws are iron: only the first one is "NEW" for this player
        h.assertTrue(s.newMask == 1L, "only the first iron is new, mask=" + Long.toBinaryString(s.newMask));
        h.assertTrue(countVouchers(player, v.id) == 3, "10-pull costs 9, left " + countVouchers(player, v.id));
        h.assertTrue(countItem(player, Items.IRON_INGOT) == 0, "prizes are only handed out when collected");

        // a second click while the pull is open neither charges nor re-rolls
        com.chatmiao.lottery.logic.HubService.pull(player, type.id, true);
        h.assertTrue(data.session(player.getUUID()) == s && countVouchers(player, v.id) == 3, "double pull must be ignored");

        com.chatmiao.lottery.logic.HubService.reveal(player, s.round.roundId, -1L);
        h.assertTrue(s.round.revealedMask == s.round.allMask(), "reveal clipped to the cards");
        com.chatmiao.lottery.logic.HubService.finish(player, s.round.roundId, false);
        h.assertTrue(data.session(player.getUUID()) == null, "collected session must be closed");
        h.assertTrue(countItem(player, Items.IRON_INGOT) == 20, "10 draws × 2 iron, got " + countItem(player, Items.IRON_INGOT));
        com.chatmiao.lottery.logic.HubService.finish(player, s.round.roundId, false);
        h.assertTrue(countItem(player, Items.IRON_INGOT) == 20, "a replayed finish must not pay twice");
        h.assertTrue(winningTickets(player).isEmpty(), "direct delivery gives no winning ticket");

        var hist = data.history(player.getUUID());
        h.assertTrue(hist.size() == 1 && hist.get(0).draws == 10 && hist.get(0).wins.size() == 10, "history must record the pull");
        h.assertTrue(data.voucher(v.id).spent == 9 && type.scratched == 1 && type.wins == 1, "stats");

        // single pull, then "再抽一次" pays and rolls the next one right away
        com.chatmiao.lottery.logic.HubService.pull(player, type.id, false);
        var s2 = data.session(player.getUUID());
        h.assertTrue(s2 != null && s2.round.draws.size() == 1 && countVouchers(player, v.id) == 2, "single pull costs 1");
        h.assertTrue(s2.newMask == 0, "iron is not new any more");
        com.chatmiao.lottery.logic.HubService.finish(player, s2.round.roundId, true);
        var s3 = data.session(player.getUUID());
        h.assertTrue(s3 != null && s3 != s2 && countVouchers(player, v.id) == 1, "again must start a new paid pull");
        h.assertTrue(countItem(player, Items.IRON_INGOT) == 22, "first single pull collected");

        // not enough vouchers: nothing happens
        com.chatmiao.lottery.logic.HubService.finish(player, s3.round.roundId, false);
        com.chatmiao.lottery.logic.HubService.pull(player, type.id, true);
        h.assertTrue(data.session(player.getUUID()) == null && countVouchers(player, v.id) == 1, "a 10-pull needs 9 vouchers");

        // the pending pull survives a save/load round trip unchanged
        com.chatmiao.lottery.logic.HubService.pull(player, type.id, false);
        var pending = data.session(player.getUUID());
        var provider = h.getLevel().registryAccess();
        var copy = com.chatmiao.lottery.logic.HubSession.load(pending.save(provider), provider);
        h.assertTrue(copy.round.roundId == pending.round.roundId && copy.round.draws.size() == 1 && !copy.multi, "session NBT round trip");
        h.succeed();
    }

    /** Closed pools (not listed, no/disabled voucher, before start, after end, wrong weekday) can't be pulled. */
    @GameTest(template = "empty")
    public static void hubSchedule(GameTestHelper h) {
        LotteryData data = LotteryData.get(h.getLevel().getServer());
        var v = voucher(h, "排期券");
        TicketType type = hubPool(h, v.id, TicketType.DELIVER_DIRECT);
        long now = System.currentTimeMillis();
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        h.assertTrue(type.hubStatus(now, zone, true) == TicketType.HubStatus.OPEN, "open by default");
        h.assertTrue(type.hubStatus(now, zone, false) == TicketType.HubStatus.NO_VOUCHER, "needs an enabled voucher");
        type.startTime = now + 3_600_000;
        h.assertTrue(type.hubStatus(now, zone, true) == TicketType.HubStatus.NOT_STARTED, "before start");
        type.startTime = 0;
        type.endTime = now - 1;
        h.assertTrue(type.hubStatus(now, zone, true) == TicketType.HubStatus.ENDED, "after end");
        type.endTime = 0;
        int today = java.time.Instant.ofEpochMilli(now).atZone(zone).getDayOfWeek().getValue() - 1;
        type.weekMask = 0x7F & ~(1 << today);
        h.assertTrue(type.hubStatus(now, zone, true) == TicketType.HubStatus.CLOSED_TODAY, "not today");
        type.weekMask = 1 << today;
        h.assertTrue(type.hubStatus(now, zone, true) == TicketType.HubStatus.OPEN, "today only");

        // pulling a closed pool takes nothing
        ServerPlayer player = player(h);
        player.getInventory().add(com.chatmiao.lottery.item.VoucherItem.create(v.id, 5));
        type.endTime = now - 1;
        com.chatmiao.lottery.logic.HubService.pull(player, type.id, false);
        h.assertTrue(data.session(player.getUUID()) == null && countVouchers(player, v.id) == 5, "closed pool must not charge");
        type.endTime = 0;
        v.enabled = false;
        com.chatmiao.lottery.logic.HubService.pull(player, type.id, false);
        h.assertTrue(data.session(player.getUUID()) == null && countVouchers(player, v.id) == 5, "disabled voucher must not charge");
        v.enabled = true;
        // vouchers of another type don't count
        var other = voucher(h, "别的券");
        player.getInventory().clearContent();
        player.getInventory().add(com.chatmiao.lottery.item.VoucherItem.create(other.id, 5));
        com.chatmiao.lottery.logic.HubService.pull(player, type.id, false);
        h.assertTrue(data.session(player.getUUID()) == null && countVouchers(player, other.id) == 5, "wrong voucher type must not pay");

        // an old type (no hub settings) is never listed
        TicketType legacy = TicketType.load(new TicketType().save(h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(!legacy.inHub && legacy.hubStatus(now, zone, true) == TicketType.HubStatus.NOT_LISTED, "legacy types stay off the gacha");
        h.succeed();
    }

    /** Pools set to "winning ticket" hand out one ticket for the pull, redeemable once at the station. */
    @GameTest(template = "empty")
    public static void hubTicketDelivery(GameTestHelper h) {
        LotteryData data = LotteryData.get(h.getLevel().getServer());
        var v = voucher(h, "票券");
        TicketType type = hubPool(h, v.id, TicketType.DELIVER_TICKET);
        ServerPlayer player = player(h);
        BlockPos station = h.absolutePos(new BlockPos(2, 1, 0));
        h.setBlock(new BlockPos(2, 1, 0), ModRegistry.REDEEM_STATION.get());
        player.getInventory().add(com.chatmiao.lottery.item.VoucherItem.create(v.id, 9));
        com.chatmiao.lottery.logic.HubService.pull(player, type.id, true);
        var s = data.session(player.getUUID());
        h.assertTrue(s != null && !s.round.winId.isEmpty(), "ticket delivery records a win id at pull time");
        com.chatmiao.lottery.logic.HubService.finish(player, s.round.roundId, false);
        h.assertTrue(countItem(player, Items.IRON_INGOT) == 0 && winningTickets(player).size() == 1, "one winning ticket, no items yet");
        RedeemService.redeem(player, station, List.of(s.round.winId));
        h.assertTrue(countItem(player, Items.IRON_INGOT) == 20, "redeeming pays all 10 prizes");
        h.succeed();
    }

    private static TicketType.Pool tiered(TicketType.Pool p, int tier) {
        p.tier = tier;
        return p;
    }

    /** Full flow: load stack → open (rolls) → reopen (same result) → collect → redeem → no double redeem. */
    @GameTest(template = "empty")
    public static void scratchAndRedeem(GameTestHelper h) {
        TicketType type = saveType(h, "必中测试", 0, pool("头奖", 1, new ItemStack(Items.DIAMOND), 1, 5, 5));
        ServerPlayer player = player(h);
        BlockPos table = h.absolutePos(new BlockPos(0, 1, 0));
        BlockPos station = h.absolutePos(new BlockPos(2, 1, 0));
        h.setBlock(new BlockPos(0, 1, 0), ModRegistry.SCRATCH_TABLE.get());
        h.setBlock(new BlockPos(2, 1, 0), ModRegistry.REDEEM_STATION.get());
        ScratchTableBlockEntity be = (ScratchTableBlockEntity) h.getLevel().getBlockEntity(table);

        // empty table + empty hand: nothing happens
        ScratchService.onUse(player, table, be);
        h.assertTrue(be.isEmpty(), "table should still be empty");

        // sneak + right-click with 3 tickets loads all three
        player.setItemInHand(InteractionHand.MAIN_HAND, ScratchTicketItem.create(type.id, 3));
        player.setShiftKeyDown(true);
        ScratchService.onUse(player, table, be);
        h.assertTrue(be.stock == 3 && player.getMainHandItem().isEmpty(), "all 3 tickets should be on the table, stock=" + be.stock);

        // open: one ticket taken off and rolled
        player.setShiftKeyDown(false);
        ScratchService.onUse(player, table, be);
        h.assertTrue(be.round != null && be.stock == 2, "a round should be active with 2 left");
        ScratchRound first = be.round;
        h.assertTrue(first.won() && first.hitCells.length == 1 && first.hitCells[0] >= 0, "a 100% ticket must win");

        // close + reopen: same ticket, same result (no reroll)
        ScratchService.onUse(player, table, be);
        h.assertTrue(be.round == first && be.stock == 2, "reopening must not reroll or consume a ticket");

        // another player can't use it
        ServerPlayer other = player(h);
        ScratchService.onUse(other, table, be);
        ScratchService.finish(other, table, first.roundId, false);
        h.assertTrue(be.round == first, "another player must not be able to finish this round");

        // finish with "再来一张": winning ticket into inventory, next ticket rolled
        ScratchService.finish(player, table, first.roundId, true);
        h.assertTrue(be.round != null && be.round.roundId != first.roundId && be.stock == 1, "next round should be active");
        // the same finish packet again must be ignored (idempotent)
        ScratchService.finish(player, table, first.roundId, true);
        h.assertTrue(be.stock == 1, "replayed finish must not consume another ticket");

        List<ItemStack> wins = winningTickets(player);
        h.assertTrue(wins.size() == 1, "expected one winning ticket, got " + wins.size());
        WinTicketData data = WinningTicketItem.dataOf(wins.get(0));
        h.assertTrue(data.winId().equals(first.winId), "ticket win id must match the round");
        LotteryData ledger = LotteryData.get(h.getLevel().getServer());
        WinRecord rec = ledger.win(data.winId());
        h.assertTrue(rec != null && !rec.redeemed && rec.winnerId.equals(player.getUUID()), "ledger must hold an open record");

        // redeem at the station
        RedeemService.redeem(player, station, List.of(data.winId()));
        h.assertTrue(rec.redeemed, "record must be redeemed");
        h.assertTrue(countItem(player, Items.DIAMOND) == 5, "player should have 5 diamonds, has " + countItem(player, Items.DIAMOND));
        h.assertTrue(winningTickets(player).isEmpty(), "redeemed ticket must be taken");

        // a duplicated ticket with the same win id is rejected
        player.getInventory().add(WinningTicketItem.create(data));
        RedeemService.redeem(player, station, List.of(data.winId()));
        h.assertTrue(countItem(player, Items.DIAMOND) == 5, "duplicate must not pay out again");

        // a forged win id is rejected
        player.getInventory().add(WinningTicketItem.create(new WinTicketData("W1-FAKEFAKE", type.id, "x", "x", data.prize())));
        RedeemService.redeem(player, station, List.of("W1-FAKEFAKE"));
        h.assertTrue(countItem(player, Items.DIAMOND) == 5, "forged ticket must not pay out");

        // whoever holds a valid ticket can redeem it
        ScratchRound second = be.round;
        ScratchService.finish(player, table, second.roundId, false);
        h.assertTrue(be.round == null && be.stock == 1, "finish without next keeps the rest");
        ItemStack ticket2 = null;
        for (ItemStack s : winningTickets(player)) {
            if (WinningTicketItem.dataOf(s).winId().equals(second.winId)) ticket2 = s;
        }
        h.assertTrue(ticket2 != null, "second winning ticket missing");
        ItemStack moved = ticket2.copy();
        ticket2.setCount(0);
        other.getInventory().add(moved);
        RedeemService.redeem(other, station, List.of(second.winId));
        h.assertTrue(countItem(other, Items.DIAMOND) == 5, "holder should be able to redeem");

        // sneak + empty hand takes the unscratched rest back
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        ScratchService.onUse(player, table, be);
        h.assertTrue(be.isEmpty() && be.owner == null, "table should be empty and free again");
        h.assertTrue(countTickets(player, type.id) == 1, "the last ticket should be back");
        h.assertTrue(type.issued == 0 && type.scratched == 2 && type.wins == 2, "stats wrong: " + type.scratched + "/" + type.wins);
        h.succeed();
    }

    /** A losing ticket disappears; a ticket of another type can't be mixed in; unknown types are rejected. */
    @GameTest(template = "empty")
    public static void losingTicketAndValidation(GameTestHelper h) {
        TicketType lose = saveType(h, "必不中测试", 1);
        TicketType otherType = saveType(h, "别的彩票", 1);
        ServerPlayer player = player(h);
        BlockPos table = h.absolutePos(new BlockPos(0, 1, 0));
        h.setBlock(new BlockPos(0, 1, 0), ModRegistry.SCRATCH_TABLE.get());
        ScratchTableBlockEntity be = (ScratchTableBlockEntity) h.getLevel().getBlockEntity(table);

        // unknown type
        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, ScratchTicketItem.create(99999, 5));
        ScratchService.onUse(player, table, be);
        h.assertTrue(be.isEmpty() && player.getMainHandItem().getCount() == 5, "unknown type must be rejected");

        player.setItemInHand(InteractionHand.MAIN_HAND, ScratchTicketItem.create(lose.id, 1));
        ScratchService.onUse(player, table, be);
        player.setItemInHand(InteractionHand.MAIN_HAND, ScratchTicketItem.create(otherType.id, 1));
        ScratchService.onUse(player, table, be);
        h.assertTrue(be.stock == 1 && player.getMainHandItem().getCount() == 1, "other type must not be mixed in");

        player.setShiftKeyDown(false);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        ScratchService.onUse(player, table, be);
        ScratchRound round = be.round;
        h.assertTrue(round != null && !round.won() && round.winId.isEmpty(), "must be a losing round");
        ScratchService.finish(player, table, round.roundId, true); // "再来一张" with none left just ends
        h.assertTrue(be.isEmpty(), "table should be empty");
        h.assertTrue(winningTickets(player).isEmpty() && countTickets(player, lose.id) == 0, "losing ticket must disappear");
        h.assertTrue(!h.getLevel().getBlockState(table).getValue(com.chatmiao.lottery.block.ScratchTableBlock.HAS_TICKET),
                "top texture should be back to empty");
        h.succeed();
    }

    /** Breaking the table mid-ticket: unscratched tickets drop, the locked winning ticket drops as won. */
    @GameTest(template = "empty")
    public static void breakTableKeepsResult(GameTestHelper h) {
        TicketType type = saveType(h, "破坏测试", 0, pool("p", 1, new ItemStack(Items.EMERALD), 1, 1, 1));
        ServerPlayer player = player(h);
        BlockPos table = h.absolutePos(new BlockPos(0, 1, 0));
        h.setBlock(new BlockPos(0, 1, 0), ModRegistry.SCRATCH_TABLE.get());
        ScratchTableBlockEntity be = (ScratchTableBlockEntity) h.getLevel().getBlockEntity(table);
        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, ScratchTicketItem.create(type.id, 4));
        ScratchService.onUse(player, table, be);
        player.setShiftKeyDown(false);
        ScratchService.onUse(player, table, be);
        String winId = be.round.winId;
        h.destroyBlock(new BlockPos(0, 1, 0));
        h.runAfterDelay(1, () -> {
            int tickets = 0;
            boolean winning = false;
            for (var e : h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(table).inflate(3))) {
                ItemStack s = e.getItem();
                if (ScratchTicketItem.typeOf(s) == type.id) tickets += s.getCount();
                WinTicketData d = WinningTicketItem.dataOf(s);
                if (d != null && d.winId().equals(winId)) winning = true;
            }
            h.assertTrue(tickets == 3, "3 unscratched tickets should drop, got " + tickets);
            h.assertTrue(winning, "the locked winning ticket should drop");
            h.succeed();
        });
    }

    /** The 2-tall kiosk: breaking either half removes both and drops exactly one item. */
    @GameTest(template = "empty")
    public static void kioskBreaksAsOne(GameTestHelper h) {
        var lower = ModRegistry.REDEEM_STATION.get().defaultBlockState();
        var upper = lower.setValue(com.chatmiao.lottery.block.RedeemStationBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
        BlockPos a = new BlockPos(0, 1, 0), b = new BlockPos(2, 1, 0);
        h.setBlock(a, lower);
        h.setBlock(a.above(), upper);
        h.setBlock(b, lower);
        h.setBlock(b.above(), upper);
        h.destroyBlock(a);          // break the bottom half
        h.destroyBlock(b.above());  // break the top half
        h.runAfterDelay(2, () -> {
            h.assertBlockNotPresent(ModRegistry.REDEEM_STATION.get(), a.above());
            h.assertBlockNotPresent(ModRegistry.REDEEM_STATION.get(), b);
            int drops = 0;
            for (var e : h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(h.absolutePos(a)).inflate(5))) {
                if (e.getItem().is(ModRegistry.REDEEM_STATION_ITEM.get())) drops += e.getItem().getCount();
            }
            h.assertTrue(drops == 2, "expected one drop per kiosk (2 total), got " + drops);
            h.succeed();
        });
    }

    private static List<ItemStack> winningTickets(ServerPlayer p) {
        return p.getInventory().items.stream().filter(s -> WinningTicketItem.dataOf(s) != null).toList();
    }

    private static int countItem(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(item)) n += s.getCount();
        return n;
    }

    private static int countTickets(ServerPlayer p, int typeId) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (ScratchTicketItem.typeOf(s) == typeId) n += s.getCount();
        return n;
    }
}
