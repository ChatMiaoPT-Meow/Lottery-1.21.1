package com.chatmiao.lottery.gametest;

import com.chatmiao.lottery.LotteryMod;
import com.chatmiao.lottery.ModRegistry;
import com.chatmiao.lottery.block.ScratchTableBlock;
import com.chatmiao.lottery.client.PlayScreen;
import com.chatmiao.lottery.client.RedeemScreen;
import com.chatmiao.lottery.client.TerminalScreen;
import com.chatmiao.lottery.data.Draw;
import com.chatmiao.lottery.data.PlayStyle;
import com.chatmiao.lottery.data.Prize;
import com.chatmiao.lottery.data.TicketType;
import com.chatmiao.lottery.logic.Roller;
import net.minecraft.client.gui.components.Button;
import net.minecraft.util.RandomSource;
import com.chatmiao.lottery.item.ScratchTicketItem;
import com.chatmiao.lottery.item.WinTicketData;
import com.chatmiao.lottery.item.WinningTicketItem;
import com.chatmiao.lottery.logic.ScratchRound;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;

/**
 * Dev-only visual check: with -Dlottery.demo=true the client walks through every GUI with sample data
 * and saves screenshots to run/screenshots, then quits. Lives in the gametest source set, never shipped.
 */
@EventBusSubscriber(modid = LotteryMod.MODID, value = Dist.CLIENT)
public final class ScreenshotDemo {
    private static int ticks = -1;

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("lottery.demo")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        ticks++;
        switch (ticks) {
            case 20 -> setupWorld(mc);
            case 80 -> shot(mc, "1_world");
            case 82 -> closeUp(mc);
            case 86 -> shot(mc, "1b_kiosk");
            case 87 -> {
                mc.getSingleplayerServer().overworld().setDayTime(18000);
                mc.level.setDayTime(18000);
            }
            case 96 -> shot(mc, "1c_kiosk_night");
            case 97 -> {
                mc.getSingleplayerServer().overworld().setDayTime(6000);
                mc.level.setDayTime(6000);
                tableCloseUp(mc);
            }
            case 103 -> shot(mc, "1d_table");
            case 104 -> {
                mc.level.setDayTime(6000);
                mc.options.hideGui = false;
                mc.getSingleplayerServer().overworld().setDayTime(6000);
                mc.setScreen(new TerminalScreen(new Payloads.OpenTerminal(sampleTypes(), 2, 1000)));
            }
            case 110 -> shot(mc, "2_terminal");
            // pools tab: search for "锭" and select the 2nd item of the pool
            case 112 -> {
                if (mc.screen instanceof TerminalScreen ts) ts.demo(1, "锭", 1);
            }
            case 118 -> shot(mc, "2b_terminal_edit");
            // style tab for each play style
            case 120 -> mc.setScreen(new TerminalScreen(new Payloads.OpenTerminal(sampleTypes(), 4, 1000)));
            case 122 -> press(mc, "gui.lottery.terminal.tab_style");
            case 128 -> shot(mc, "2c_style_match");
            case 130 -> mc.setScreen(new TerminalScreen(new Payloads.OpenTerminal(sampleTypes(), 5, 1000)));
            case 132 -> press(mc, "gui.lottery.terminal.tab_style");
            case 138 -> shot(mc, "2d_style_case");
            case 140 -> mc.setScreen(new TerminalScreen(new Payloads.OpenTerminal(sampleTypes(), 6, 1000)));
            case 142 -> press(mc, "gui.lottery.terminal.tab_style");
            case 148 -> shot(mc, "2e_style_cards");
            // classic 3×3
            // classic 3×3: intro pop, partly scratched, then "全部揭开" → stamp → prize summary
            case 150 -> open(mc, sampleRound(0b000_000_101_011), 23);
            case 152 -> shot(mc, "3a_intro");
            case 170 -> shot(mc, "3_scratch_partial");
            case 172 -> press(mc, "gui.lottery.scratch.reveal_all");
            case 181 -> shot(mc, "4a_stamp_falling");
            case 192 -> shot(mc, "4_scratch_won");
            case 215 -> shot(mc, "4c_summary");
            case 220 -> {
                ScratchRound lost = sampleRound(0xFFF);
                lost.draws = List.of(Draw.EMPTY);
                lost.hitCells = new int[]{-1};
                lost.board[4] = 17;
                open(mc, lost, 0);
            }
            case 235 -> shot(mc, "5_scratch_lost");
            // 10×4, already finished: summary straight away, then "看票面"
            case 240 -> open(mc, bigMatchRound(), 5);
            case 256 -> shot(mc, "8a_match_summary");
            case 258 -> press(mc, "gui.lottery.summary.view");
            case 264 -> shot(mc, "8_match_10x4");
            // case opening: shake → burst → spin → overshoot → legendary pillar → summary
            case 270 -> open(mc, caseRound(), 2);
            case 280 -> press(mc, "gui.lottery.case.open");
            case 290 -> shot(mc, "9a_case_opening");
            case 315 -> shot(mc, "9_case_spinning");
            case 366 -> shot(mc, "9c_case_pillar");
            case 384 -> shot(mc, "9b_case_done");
            case 415 -> shot(mc, "9d_case_summary");
            // card draw: dealing → flipping (rare charge) → showcase → summary
            case 420 -> open(mc, cardRound(), 9);
            case 431 -> shot(mc, "10a_cards_dealing");
            case 455 -> press(mc, "gui.lottery.cards.flip_all");
            case 491 -> shot(mc, "10_cards_flipping");
            case 512 -> shot(mc, "10b_cards_done");
            case 560 -> shot(mc, "10c_cards_summary");
            case 570 -> mc.setScreen(new RedeemScreen(new Payloads.OpenRedeem(BlockPos.ZERO, List.of(
                    new Payloads.RedeemEntry("W2-ABCDEFGH", "夏日限定"), new Payloads.RedeemEntry("W2-JKLMNPQR", "夏日限定")))));
            case 590 -> shot(mc, "6_redeem_box");
            case 600 -> {
                if (mc.screen instanceof RedeemScreen rs) {
                    rs.onResult(new Payloads.RedeemResult(List.of(
                            new Prize(new ItemStack(Items.DIAMOND), 5), new Prize(new ItemStack(Items.GOLD_INGOT), 12)), List.of()));
                }
            }
            case 622 -> shot(mc, "7_redeem_opened");
            case 640 -> mc.stop();
            default -> {}
        }
    }

    private static void open(Minecraft mc, ScratchRound round, int stock) {
        mc.setScreen(PlayScreen.create(new Payloads.ScratchState(BlockPos.ZERO, round, stock)));
    }

    /** Presses the visible button whose label is the given translation. */
    private static void press(Minecraft mc, String key) {
        String label = Component.translatable(key).getString();
        for (var w : mc.screen.children()) {
            if (w instanceof Button b && b.visible && b.active && b.getMessage().getString().equals(label)) {
                b.onPress();
                return;
            }
        }
        LotteryMod.LOGGER.warn("demo: no button {}", label);
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> LotteryMod.LOGGER.info("demo screenshot {}", name));
    }

    private static void setupWorld(Minecraft mc) {
        ServerLevel level = mc.getSingleplayerServer().overworld();
        ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
        BlockPos base = sp.blockPosition();
        level.setDayTime(6000);
        sp.setYRot(180);
        sp.setXRot(25);
        sp.connection.teleport(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 180, 25);
        BlockPos front = base.north(3);
        var table = ModRegistry.SCRATCH_TABLE.get().defaultBlockState().setValue(ScratchTableBlock.FACING, net.minecraft.core.Direction.SOUTH);
        level.setBlockAndUpdate(front.west(2), table);
        level.setBlockAndUpdate(front.west(), table.setValue(ScratchTableBlock.HAS_TICKET, true));
        level.setBlockAndUpdate(front.west(4), table.setValue(ScratchTableBlock.FACING, net.minecraft.core.Direction.EAST));
        tables = front.west();
        placeKiosk(level, front.east(), net.minecraft.core.Direction.SOUTH);
        placeKiosk(level, front.east(3), net.minecraft.core.Direction.WEST);
        kiosk = front.east();
        sp.getInventory().setItem(0, ScratchTicketItem.create(1, 64));
        sp.getInventory().setItem(1, ScratchTicketItem.create(2, 12));
        sp.getInventory().setItem(2, ScratchTicketItem.create(3, 3));
        sp.getInventory().setItem(3, WinningTicketItem.create(new WinTicketData("W2-ABCDEFGH", 2, "夏日限定", "Dev",
                new Prize(new ItemStack(Items.DIAMOND), 5).describe())));
        sp.getInventory().setItem(4, new ItemStack(ModRegistry.LOTTERY_TERMINAL.get()));
        sp.getInventory().selected = 1;
        com.chatmiao.lottery.data.TicketNames.put(1, "幸运7");
        com.chatmiao.lottery.data.TicketNames.put(2, "夏日限定");
        com.chatmiao.lottery.data.TicketNames.put(3, "钻石狂欢");
        mc.gui.setOverlayMessage(Component.literal("刮奖台 / 刮奖台(有票) / 彩票站"), false);
    }

    private static BlockPos kiosk;
    private static BlockPos tables;

    private static void tableCloseUp(Minecraft mc) {
        ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
        double x = tables.getX() + 0.5 + 1.2, z = tables.getZ() + 0.5 + 2.2;
        float yaw = (float) Math.toDegrees(Math.atan2(-(tables.getX() + 0.0 - x), tables.getZ() + 0.5 - z));
        sp.connection.teleport(x, tables.getY(), z, yaw, 30);
    }

    private static void placeKiosk(ServerLevel level, BlockPos pos, net.minecraft.core.Direction facing) {
        var lower = ModRegistry.REDEEM_STATION.get().defaultBlockState()
                .setValue(com.chatmiao.lottery.block.RedeemStationBlock.FACING, facing);
        level.setBlock(pos, lower, 2);
        level.setBlock(pos.above(), lower.setValue(com.chatmiao.lottery.block.RedeemStationBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
    }

    private static void closeUp(Minecraft mc) {
        ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
        double x = kiosk.getX() + 0.5 - 1.6, z = kiosk.getZ() + 0.5 + 2.4;
        float yaw = (float) Math.toDegrees(Math.atan2(-(kiosk.getX() + 0.5 - x), kiosk.getZ() + 0.5 - z));
        sp.connection.teleport(x, kiosk.getY(), z, yaw, -8);
        mc.options.hideGui = true;
    }

    private static List<TicketType> sampleTypes() {
        TicketType a = new TicketType();
        a.id = 1;
        a.name = "幸运7";
        a.emptyWeight = 70;
        a.issued = 640;
        a.scratched = 512;
        a.wins = 160;
        TicketType b = new TicketType();
        b.id = 2;
        b.name = "夏日限定";
        b.emptyWeight = 65;
        b.issued = 1000;
        b.scratched = 873;
        b.wins = 301;
        b.pools.add(pool("三等奖池", 25, 1, new Object[][]{{Items.IRON_INGOT, 60, 8, 16}, {Items.GOLD_INGOT, 25, 2, 6}, {Items.BREAD, 15, 16, 32}}));
        b.pools.add(pool("二等奖池", 8, 2, new Object[][]{{Items.EMERALD, 1, 4, 8}}));
        b.pools.add(pool("一等奖池", 2, 4, new Object[][]{{Items.DIAMOND, 1, 5, 5}}));
        b.pools.add(pool("空池", 3, 0, new Object[0][]));
        TicketType c = new TicketType();
        c.id = 3;
        c.name = "钻石狂欢";
        c.enabled = false;

        TicketType big = new TicketType();
        big.id = 4;
        big.name = "超级大乐透";
        big.emptyWeight = 80;
        big.cols = 10;
        big.rows = 4;
        big.winCount = 5;
        big.draws = 3;
        big.pityCount = 50;
        big.pityTier = 4;
        tieredPools(big);

        TicketType box = new TicketType();
        box.id = 5;
        box.name = "龙之宝箱";
        box.style = PlayStyle.CASE.id;
        box.emptyWeight = 30;
        box.draws = 3;
        box.spinSeconds = 3;
        tieredPools(box);

        TicketType ten = new TicketType();
        ten.id = 6;
        ten.name = "星辉十连";
        ten.style = PlayStyle.CARDS.id;
        ten.emptyWeight = 40;
        ten.draws = 10;
        ten.cardCols = 5;
        ten.guaranteeTier = 3;
        ten.pityCount = 80;
        ten.pityTier = 5;
        tieredPools(ten);
        return List.of(a, b, c, big, box, ten);
    }

    private static void tieredPools(TicketType t) {
        t.pools.add(pool("普通", 50, 0, new Object[][]{{Items.IRON_INGOT, 60, 8, 16}, {Items.BREAD, 40, 16, 32}}));
        t.pools.add(pool("优良", 25, 1, new Object[][]{{Items.GOLD_INGOT, 1, 4, 8}}));
        t.pools.add(pool("稀有", 12, 2, new Object[][]{{Items.EMERALD, 1, 4, 8}}));
        t.pools.add(pool("史诗", 5, 3, new Object[][]{{Items.DIAMOND, 1, 2, 3}}));
        t.pools.add(pool("传说", 2, 4, new Object[][]{{Items.NETHERITE_INGOT, 1, 1, 1}}));
        t.pools.add(pool("神话", 1, 5, new Object[][]{{Items.NETHER_STAR, 1, 1, 1}}));
    }

    private static TicketType.Pool pool(String name, int weight, int tier, Object[][] items) {
        TicketType.Pool p = new TicketType.Pool();
        p.name = name;
        p.weight = weight;
        p.tier = tier;
        for (Object[] row : items) {
            TicketType.PoolItem it = new TicketType.PoolItem();
            it.item = new ItemStack((net.minecraft.world.item.Item) row[0]);
            it.weight = (int) row[1];
            it.min = (int) row[2];
            it.max = (int) row[3];
            p.items.add(it);
        }
        return p;
    }

    private static ScratchRound sampleRound(long mask) {
        ScratchRound r = new ScratchRound();
        r.roundId = 1;
        r.typeId = 2;
        r.typeName = "夏日限定";
        r.winning = new int[]{7, 21, 13};
        r.board = new int[]{3, 18, 25, 9, 21, 30, 1, 12, 27};
        r.draws = List.of(new Draw(new Prize(new ItemStack(Items.DIAMOND), 5), 4));
        r.hitCells = new int[]{4};
        r.winId = "W2-ABCDEFGH";
        r.revealedMask = mask;
        return r;
    }

    private static Draw prize(net.minecraft.world.item.Item item, int count, int tier) {
        return new Draw(new Prize(new ItemStack(item), count), tier);
    }

    private static TicketType type(int id) {
        for (TicketType t : sampleTypes()) if (t.id == id) return t;
        throw new IllegalStateException();
    }

    private static ScratchRound bigMatchRound() {
        ScratchRound r = new ScratchRound();
        r.roundId = 2;
        r.typeId = 4;
        r.typeName = "超级大乐透";
        r.cols = 10;
        r.rows = 4;
        r.draws = List.of(prize(Items.NETHERITE_INGOT, 1, 4), Draw.EMPTY, prize(Items.IRON_INGOT, 12, 0));
        Roller.Board b = Roller.buildBoard(RandomSource.create(5), 60, 5, 40, r.draws);
        r.winning = b.winning();
        r.board = b.board();
        r.hitCells = b.hitCells();
        r.winId = "W4-ABCDEFGH";
        r.revealedMask = r.allMask();
        return r;
    }

    private static ScratchRound caseRound() {
        ScratchRound r = new ScratchRound();
        r.roundId = 3;
        r.typeId = 5;
        r.typeName = "龙之宝箱";
        r.style = PlayStyle.CASE.id;
        r.spinSeconds = 3;
        r.draws = List.of(prize(Items.GOLD_INGOT, 6, 1), prize(Items.NETHERITE_INGOT, 1, 4), Draw.EMPTY);
        Roller.Reels reels = Roller.buildReels(type(5), RandomSource.create(9), 3);
        r.palette = reels.palette();
        r.paletteTier = reels.paletteTier();
        r.reels = reels.reels();
        r.winId = "W5-ABCDEFGH";
        return r;
    }

    private static ScratchRound cardRound() {
        ScratchRound r = new ScratchRound();
        r.roundId = 4;
        r.typeId = 6;
        r.typeName = "星辉十连";
        r.style = PlayStyle.CARDS.id;
        r.cardCols = 5;
        r.draws = List.of(prize(Items.IRON_INGOT, 12, 0), Draw.EMPTY, prize(Items.GOLD_INGOT, 5, 1), prize(Items.BREAD, 24, 0),
                prize(Items.DIAMOND, 2, 3), Draw.EMPTY, prize(Items.EMERALD, 6, 2), prize(Items.IRON_INGOT, 9, 0),
                prize(Items.NETHER_STAR, 1, 5), Draw.EMPTY);
        r.winId = "W6-ABCDEFGH";
        return r;
    }

    private ScreenshotDemo() {}
}
