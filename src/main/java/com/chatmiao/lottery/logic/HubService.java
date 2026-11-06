package com.chatmiao.lottery.logic;

import com.chatmiao.lottery.data.Draw;
import com.chatmiao.lottery.data.HubEntry;
import com.chatmiao.lottery.data.LotteryData;
import com.chatmiao.lottery.data.Prize;
import com.chatmiao.lottery.data.PullRecord;
import com.chatmiao.lottery.data.TicketType;
import com.chatmiao.lottery.data.VoucherType;
import com.chatmiao.lottery.item.VoucherItem;
import com.chatmiao.lottery.network.Network;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡池 (gacha screen) server logic. A pull is paid with vouchers from the player's inventory, rolled and
 * locked immediately, and kept as the player's {@link HubSession} until collected — so closing the screen,
 * relogging or a server restart never re-rolls or loses it. Every request is idempotent through the round id.
 */
public final class HubService {

    /** Opens the gacha screen, or resumes an uncollected pull. */
    public static void open(ServerPlayer player) {
        LotteryData data = LotteryData.get(player.server);
        HubSession s = data.session(player.getUUID());
        if (s != null) {
            sendState(player, data, s);
            return;
        }
        Network.send(player, new Payloads.OpenHub(entries(player, data), balances(player)));
    }

    static boolean voucherOk(LotteryData data, int voucherId) {
        VoucherType v = data.voucher(voucherId);
        return v != null && v.enabled;
    }

    public static TicketType.HubStatus status(LotteryData data, TicketType t) {
        return t.hubStatus(System.currentTimeMillis(), ZoneId.systemDefault(), voucherOk(data, t.voucherId));
    }

    private static List<HubEntry> entries(ServerPlayer player, LotteryData data) {
        List<HubEntry> out = new ArrayList<>();
        for (TicketType t : data.types()) {
            if (status(data, t) != TicketType.HubStatus.OPEN) continue;
            HubEntry e = HubEntry.of(t);
            e.pityNow = data.pity(player.getUUID(), t.id);
            out.add(e);
        }
        return out;
    }

    /** Voucher id -> how many the player carries. */
    static Map<Integer, Integer> balances(ServerPlayer player) {
        Map<Integer, Integer> map = new HashMap<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            int id = VoucherItem.typeOf(s);
            if (id > 0) map.merge(id, s.getCount(), Integer::sum);
        }
        return map;
    }

    /** Removes {@code count} vouchers of {@code voucherId}; only call after checking the balance. */
    private static void takeVouchers(ServerPlayer player, int voucherId, int count) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && count > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (VoucherItem.typeOf(s) != voucherId) continue;
            int n = Math.min(count, s.getCount());
            s.shrink(n);
            count -= n;
        }
        inv.setChanged();
    }

    /** "单抽" / "N 连": pay, roll and lock. */
    public static void pull(ServerPlayer player, int typeId, boolean multi) {
        LotteryData data = LotteryData.get(player.server);
        if (data.session(player.getUUID()) != null) {
            open(player); // finish the open pull first (also swallows double-clicks)
            return;
        }
        TicketType type = data.type(typeId);
        if (type == null || status(data, type) != TicketType.HubStatus.OPEN) {
            fail(player, "message.lottery.hub.closed");
            if (type == null) LotteryLog.warn(player, "tried to pull unknown pool #" + typeId);
            return;
        }
        int draws = multi ? type.effectiveMulti() : 1;
        int cost = multi ? type.costMulti : type.costSingle;
        if (draws <= 0) {
            fail(player, "message.lottery.hub.closed");
            return;
        }
        int have = balances(player).getOrDefault(type.voucherId, 0);
        VoucherType voucher = data.voucher(type.voucherId);
        if (have < cost) {
            player.displayClientMessage(Component.translatable("message.lottery.hub.not_enough", voucher.name, cost, have)
                    .withStyle(ChatFormatting.RED), true);
            open(player);
            return;
        }
        if (cost > 0) takeVouchers(player, type.voucherId, cost);
        voucher.spent += cost;

        RandomSource random = player.getRandom();
        TicketType sized = type.copy();
        sized.draws = draws;
        int[] pity = {data.pity(player.getUUID(), type.id)};
        List<Draw> rolled = Roller.rollTicket(sized, random, pity);
        if (type.pityCount > 0) data.setPity(player.getUUID(), type.id, pity[0]);

        ScratchRound round = ScratchService.buildRound(sized, rolled, random, data.nextHubRoundId());
        type.scratched++;
        List<Prize> prizes = round.prizes();
        if (!prizes.isEmpty()) {
            type.wins++;
            if (type.delivery == TicketType.DELIVER_TICKET) round.winId = ScratchService.recordWin(player, data, type, prizes, random);
        }
        HubSession s = new HubSession();
        s.round = round;
        s.multi = multi;
        s.delivery = type.delivery;
        data.setSession(player.getUUID(), s);

        PullRecord rec = new PullRecord();
        rec.time = System.currentTimeMillis();
        rec.typeId = type.id;
        rec.typeName = type.name;
        rec.draws = rolled.size();
        for (Draw d : rolled) if (d.won()) rec.wins.add(d);
        data.addHistory(player.getUUID(), rec);
        data.setDirty();

        LotteryLog.log(player.server, "PULL", LotteryLog.who(player) + " pool #" + type.id + " " + type.name + " x" + rolled.size()
                + " paid " + cost + " " + voucher.name + " -> " + (prizes.isEmpty() ? "no prize" : LotteryLog.prizes(prizes))
                + (round.winId.isEmpty() ? "" : " (" + round.winId + ")") + " pity " + pity[0]);
        player.level().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6F, 1.4F);
        sendState(player, data, s);
    }

    public static void reveal(ServerPlayer player, int roundId, long mask) {
        HubSession s = LotteryData.get(player.server).session(player.getUUID());
        if (s == null || s.round.roundId != roundId) return;
        s.round.revealedMask |= mask & s.round.allMask();
        LotteryData.get(player.server).setDirty();
    }

    /** Collect the pull (prizes or a winning ticket), then either pull again or go back to the pool list. */
    public static void finish(ServerPlayer player, int roundId, boolean again) {
        LotteryData data = LotteryData.get(player.server);
        HubSession s = data.session(player.getUUID());
        if (s == null || s.round.roundId != roundId) return; // replayed / stale request
        data.setSession(player.getUUID(), null);
        List<Prize> prizes = s.round.prizes();
        if (!prizes.isEmpty()) {
            if (s.delivery == TicketType.DELIVER_TICKET) {
                ItemHandlerHelper.giveItemToPlayer(player, ScratchService.winningTicket(s.round, player.getGameProfile().getName()));
                player.displayClientMessage(Component.translatable("message.lottery.table.got_winning_ticket", Prize.summary(prizes))
                        .withStyle(ChatFormatting.GOLD), false);
            } else {
                for (Prize p : prizes) {
                    for (ItemStack st : p.toStacks()) ItemHandlerHelper.giveItemToPlayer(player, st);
                }
                player.displayClientMessage(Component.translatable("message.lottery.hub.got", Prize.summary(prizes))
                        .withStyle(ChatFormatting.GOLD), false);
                LotteryLog.log(player.server, "DELIVER", LotteryLog.who(player) + " pool #" + s.round.typeId + " -> " + LotteryLog.prizes(prizes));
            }
        }
        if (again) {
            pull(player, s.round.typeId, s.multi);
        } else {
            open(player);
        }
    }

    public static void history(ServerPlayer player) {
        Network.send(player, new Payloads.HubHistory(LotteryData.get(player.server).history(player.getUUID())));
    }

    private static void sendState(ServerPlayer player, LotteryData data, HubSession s) {
        TicketType type = data.type(s.round.typeId);
        int cost = 0, voucherId = 0;
        boolean canAgain = false;
        if (type != null && status(data, type) == TicketType.HubStatus.OPEN) {
            int draws = s.multi ? type.effectiveMulti() : 1;
            cost = s.multi ? type.costMulti : type.costSingle;
            voucherId = type.voucherId;
            canAgain = draws > 0 && balances(player).getOrDefault(voucherId, 0) >= cost;
        }
        Network.send(player, new Payloads.HubState(s.round, s.delivery == TicketType.DELIVER_DIRECT, voucherId, cost, canAgain));
    }

    private static void fail(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true);
        open(player);
    }

    private HubService() {}
}
