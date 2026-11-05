package com.chatmiao.lottery.gametest;

import com.chatmiao.lottery.LotteryMod;
import com.chatmiao.lottery.ModRegistry;
import com.chatmiao.lottery.block.ScratchTableBlock;
import com.chatmiao.lottery.client.RedeemScreen;
import com.chatmiao.lottery.client.ScratchScreen;
import com.chatmiao.lottery.client.TerminalScreen;
import com.chatmiao.lottery.data.Prize;
import com.chatmiao.lottery.data.TicketType;
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
            case 98 -> {
                mc.level.setDayTime(6000);
                mc.options.hideGui = false;
                mc.getSingleplayerServer().overworld().setDayTime(6000);
                mc.setScreen(new TerminalScreen(new Payloads.OpenTerminal(sampleTypes(), 2, 1000)));
            }
            case 110 -> shot(mc, "2_terminal");
            case 112 -> {
                // type into the search box and select the 2nd item of the pool
                var boxes = mc.screen.children().stream()
                        .filter(w -> w instanceof net.minecraft.client.gui.components.EditBox)
                        .map(w -> (net.minecraft.client.gui.components.EditBox) w).toList();
                boxes.get(7).setValue("锭");
                int left = (mc.screen.width - 420) / 2, top = (mc.screen.height - 238) / 2;
                mc.screen.mouseClicked(left + 268 + 40, top + 20 + 18 + 5, 0);
            }
            case 118 -> shot(mc, "2b_terminal_edit");
            case 120 -> mc.setScreen(new ScratchScreen(new Payloads.ScratchState(BlockPos.ZERO, sampleRound(0b000_000_101_011), 23)));
            case 140 -> shot(mc, "3_scratch_partial");
            case 150 -> mc.setScreen(new ScratchScreen(new Payloads.ScratchState(BlockPos.ZERO, sampleRound(ScratchRound.ALL_CELLS), 23)));
            case 170 -> shot(mc, "4_scratch_won");
            case 180 -> {
                ScratchRound lost = sampleRound(ScratchRound.ALL_CELLS);
                lost.prize = null;
                lost.hitCell = -1;
                lost.board[4] = 17;
                mc.setScreen(new ScratchScreen(new Payloads.ScratchState(BlockPos.ZERO, lost, 0)));
            }
            case 200 -> shot(mc, "5_scratch_lost");
            case 210 -> mc.setScreen(new RedeemScreen(new Payloads.OpenRedeem(BlockPos.ZERO, List.of(
                    new Payloads.RedeemEntry("W2-ABCDEFGH", "夏日限定"), new Payloads.RedeemEntry("W2-JKLMNPQR", "夏日限定")))));
            case 230 -> shot(mc, "6_redeem_box");
            case 240 -> {
                if (mc.screen instanceof RedeemScreen rs) {
                    rs.onResult(new Payloads.RedeemResult(List.of(
                            new Prize(new ItemStack(Items.DIAMOND), 5), new Prize(new ItemStack(Items.GOLD_INGOT), 12)), List.of()));
                }
            }
            case 262 -> shot(mc, "7_redeem_opened");
            case 280 -> mc.stop();
            default -> {}
        }
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
        level.setBlockAndUpdate(front.west(2), ModRegistry.SCRATCH_TABLE.get().defaultBlockState());
        level.setBlockAndUpdate(front.west(), ModRegistry.SCRATCH_TABLE.get().defaultBlockState().setValue(ScratchTableBlock.HAS_TICKET, true));
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
        b.pools.add(pool("三等奖池", 25, new Object[][]{{Items.IRON_INGOT, 60, 8, 16}, {Items.GOLD_INGOT, 25, 2, 6}, {Items.BREAD, 15, 16, 32}}));
        b.pools.add(pool("二等奖池", 8, new Object[][]{{Items.EMERALD, 1, 4, 8}}));
        b.pools.add(pool("一等奖池", 2, new Object[][]{{Items.DIAMOND, 1, 5, 5}}));
        b.pools.add(pool("空池", 3, new Object[0][]));
        TicketType c = new TicketType();
        c.id = 3;
        c.name = "钻石狂欢";
        c.enabled = false;
        return List.of(a, b, c);
    }

    private static TicketType.Pool pool(String name, int weight, Object[][] items) {
        TicketType.Pool p = new TicketType.Pool();
        p.name = name;
        p.weight = weight;
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

    private static ScratchRound sampleRound(int mask) {
        ScratchRound r = new ScratchRound();
        r.roundId = 1;
        r.typeId = 2;
        r.typeName = "夏日限定";
        r.winning = new int[]{7, 21, 13};
        r.board = new int[]{3, 18, 25, 9, 21, 30, 1, 12, 27};
        r.hitCell = 4;
        r.prize = new Prize(new ItemStack(Items.DIAMOND), 5);
        r.winId = "W2-ABCDEFGH";
        r.revealedMask = mask;
        return r;
    }

    private ScreenshotDemo() {}
}
