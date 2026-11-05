package com.chatmiao.lottery.logic;

import com.chatmiao.lottery.LotteryConfig;
import com.chatmiao.lottery.block.ScratchTableBlockEntity;
import com.chatmiao.lottery.data.LotteryData;
import com.chatmiao.lottery.data.Prize;
import com.chatmiao.lottery.data.TicketType;
import com.chatmiao.lottery.data.WinRecord;
import com.chatmiao.lottery.item.ScratchTicketItem;
import com.chatmiao.lottery.item.WinTicketData;
import com.chatmiao.lottery.item.WinningTicketItem;
import com.chatmiao.lottery.network.Network;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * 刮奖台 server logic.
 * <p>
 * Anti-reroll rule: a ticket's result is rolled and locked the moment it is taken off the table's
 * stack (when the GUI opens or "再来一张" is pressed). Closing the GUI, relogging or breaking the
 * table never re-rolls; reopening shows the same ticket.
 */
public final class ScratchService {

    /** Right-click on a scratch table with the main hand (server side). */
    public static void onUse(ServerPlayer player, BlockPos pos, ScratchTableBlockEntity table) {
        ItemStack held = player.getMainHandItem();
        boolean sneaking = player.isSecondaryUseActive();
        boolean isTicket = held.getItem() instanceof ScratchTicketItem;

        if (isTicket && sneaking) {
            insert(player, table, held);
        } else if (held.isEmpty() && sneaking && !table.isEmpty()) {
            retrieve(player, table);
        } else if (table.isEmpty()) {
            msg(player, isTicket ? "message.lottery.table.sneak_to_insert" : "message.lottery.table.insert_ticket", ChatFormatting.YELLOW);
        } else {
            open(player, pos, table);
        }
    }

    private static void insert(ServerPlayer player, ScratchTableBlockEntity table, ItemStack held) {
        int typeId = ScratchTicketItem.typeOf(held);
        LotteryData data = LotteryData.get(player.server);
        TicketType type = data.type(typeId);
        if (type == null) {
            msg(player, "message.lottery.ticket.invalid", ChatFormatting.RED);
            LotteryLog.warn(player, "tried to insert a ticket with unknown type #" + typeId);
            return;
        }
        if (!type.enabled) {
            msg(player, "message.lottery.ticket.disabled", ChatFormatting.RED);
            return;
        }
        if (!table.isEmpty()) {
            if (!player.getUUID().equals(table.owner)) {
                player.displayClientMessage(Component.translatable("message.lottery.table.in_use", table.ownerName).withStyle(ChatFormatting.RED), true);
                return;
            }
            if (table.typeId != typeId) {
                msg(player, "message.lottery.table.other_type", ChatFormatting.RED);
                return;
            }
        }
        int count = held.getCount();
        table.owner = player.getUUID();
        table.ownerName = player.getGameProfile().getName();
        table.typeId = typeId;
        table.stock += count;
        held.shrink(count);
        table.changed();
        player.level().playSound(null, table.getBlockPos(), SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0F, 1.0F);
        player.displayClientMessage(Component.translatable("message.lottery.table.inserted", count,
                table.stock + (table.round != null ? 1 : 0)).withStyle(ChatFormatting.GREEN), true);
    }

    private static void retrieve(ServerPlayer player, ScratchTableBlockEntity table) {
        if (!player.getUUID().equals(table.owner)) {
            player.displayClientMessage(Component.translatable("message.lottery.table.in_use", table.ownerName).withStyle(ChatFormatting.RED), true);
            return;
        }
        if (table.stock <= 0) {
            msg(player, "message.lottery.table.finish_current", ChatFormatting.YELLOW);
            return;
        }
        int n = table.stock;
        int left = n;
        while (left > 0) {
            int c = Math.min(left, 64);
            ItemHandlerHelper.giveItemToPlayer(player, ScratchTicketItem.create(table.typeId, c));
            left -= c;
        }
        table.stock = 0;
        table.changed();
        player.displayClientMessage(Component.translatable(table.round != null
                ? "message.lottery.table.retrieved_keep" : "message.lottery.table.retrieved", n).withStyle(ChatFormatting.GREEN), true);
    }

    private static void open(ServerPlayer player, BlockPos pos, ScratchTableBlockEntity table) {
        if (table.owner != null && !player.getUUID().equals(table.owner)) {
            player.displayClientMessage(Component.translatable("message.lottery.table.in_use", table.ownerName).withStyle(ChatFormatting.RED), true);
            return;
        }
        if (table.round == null && !startRound(player, table)) return;
        sendState(player, pos, table);
    }

    /** Takes one ticket off the stack and locks its result. */
    private static boolean startRound(ServerPlayer player, ScratchTableBlockEntity table) {
        if (table.stock <= 0) return false;
        LotteryData data = LotteryData.get(player.server);
        TicketType type = data.type(table.typeId);
        if (type == null || !type.enabled) {
            msg(player, type == null ? "message.lottery.ticket.invalid" : "message.lottery.ticket.disabled", ChatFormatting.RED);
            return false;
        }
        table.stock--;
        Prize prize = Roller.roll(type, player.getRandom());
        Roller.Board board = Roller.buildBoard(player.getRandom(), LotteryConfig.numberRange(), prize != null);

        ScratchRound round = new ScratchRound();
        round.roundId = table.nextRoundId++;
        round.typeId = type.id;
        round.typeName = type.name;
        round.winning = board.winning();
        round.board = board.board();
        round.hitCell = board.hitCell();
        round.prize = prize;
        type.scratched++;
        if (prize != null) {
            type.wins++;
            WinRecord rec = new WinRecord();
            rec.winId = data.newWinId(type.id, player.getRandom());
            rec.typeId = type.id;
            rec.typeName = type.name;
            rec.prize = prize;
            rec.winnerId = player.getUUID();
            rec.winnerName = player.getGameProfile().getName();
            rec.rolledAt = System.currentTimeMillis();
            data.addWin(rec);
            round.winId = rec.winId;
            LotteryLog.log(player.server, "WIN", LotteryLog.who(player) + " type #" + type.id + " " + type.name
                    + " -> " + rec.winId + " " + prize.item().getItem() + " x" + prize.count());
        } else {
            LotteryLog.log(player.server, "ROLL", LotteryLog.who(player) + " type #" + type.id + " " + type.name + " -> no prize");
        }
        data.setDirty();
        table.round = round;
        table.changed();
        return true;
    }

    /** Client revealed some cells (only used to restore progress when the GUI is reopened). */
    public static void reveal(ServerPlayer player, BlockPos pos, int roundId, int mask) {
        ScratchTableBlockEntity table = tableFor(player, pos);
        if (table == null || table.round == null || table.round.roundId != roundId) return;
        table.round.revealedMask |= mask & ScratchRound.ALL_CELLS;
        table.setChanged();
    }

    /** "谢谢参与" / "恭喜中奖" (optionally followed by "再来一张"). Idempotent through roundId. */
    public static void finish(ServerPlayer player, BlockPos pos, int roundId, boolean next) {
        ScratchTableBlockEntity table = tableFor(player, pos);
        if (table == null || table.round == null || table.round.roundId != roundId) return;
        ScratchRound round = table.round;
        table.round = null;
        if (round.won()) {
            ItemHandlerHelper.giveItemToPlayer(player, winningTicket(round, table.ownerName));
            player.displayClientMessage(Component.translatable("message.lottery.table.got_winning_ticket", round.prize.describe())
                    .withStyle(ChatFormatting.GOLD), false);
        }
        table.changed();
        if (next && table.stock > 0 && startRound(player, table)) {
            sendState(player, pos, table);
        } else {
            Network.send(player, new Payloads.ScratchState(pos, null, table.stock));
        }
    }

    public static ItemStack winningTicket(ScratchRound round, String winner) {
        return WinningTicketItem.create(new WinTicketData(round.winId, round.typeId, round.typeName, winner, round.prize.describe()));
    }

    private static void sendState(ServerPlayer player, BlockPos pos, ScratchTableBlockEntity table) {
        Network.send(player, new Payloads.ScratchState(pos, table.round, table.stock));
    }

    private static ScratchTableBlockEntity tableFor(ServerPlayer player, BlockPos pos) {
        if (!player.level().isLoaded(pos)) return null;
        if (player.position().distanceToSqr(Vec3.atCenterOf(pos)) > LotteryConfig.useDistanceSqr()) return null;
        if (!(player.level().getBlockEntity(pos) instanceof ScratchTableBlockEntity table)) return null;
        if (!player.getUUID().equals(table.owner)) return null;
        return table;
    }

    static void msg(ServerPlayer player, String key, ChatFormatting color) {
        player.displayClientMessage(Component.translatable(key).withStyle(color), true);
    }

    private ScratchService() {}
}
