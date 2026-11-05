package com.chatmiao.lottery.gametest;

import com.chatmiao.lottery.LotteryMod;
import com.chatmiao.lottery.ModRegistry;
import com.chatmiao.lottery.block.ScratchTableBlockEntity;
import com.chatmiao.lottery.data.LotteryData;
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

    /** Losing boards never contain a winning number; winning boards contain exactly one. */
    @GameTest(template = "empty")
    public static void boardRules(GameTestHelper h) {
        RandomSource rnd = RandomSource.create(7);
        for (int i = 0; i < 5000; i++) {
            boolean won = rnd.nextBoolean();
            Roller.Board b = Roller.buildBoard(rnd, 30, won);
            Set<Integer> winning = new HashSet<>();
            for (int w : b.winning()) winning.add(w);
            h.assertTrue(winning.size() == 3, "winning numbers must be distinct");
            int matches = 0;
            Set<Integer> seen = new HashSet<>();
            for (int k = 0; k < b.board().length; k++) {
                int v = b.board()[k];
                h.assertTrue(v >= 1 && v <= 30, "number out of range");
                h.assertTrue(seen.add(v), "board numbers must be distinct");
                if (winning.contains(v)) {
                    matches++;
                    h.assertTrue(k == b.hitCell(), "match must be on the hit cell");
                }
            }
            h.assertTrue(matches == (won ? 1 : 0), "wrong number of matches: " + matches + " won=" + won);
        }
        h.succeed();
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
        h.assertTrue(first.won() && first.hitCell >= 0, "a 100% ticket must win");

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
