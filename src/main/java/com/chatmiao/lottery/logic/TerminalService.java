package com.chatmiao.lottery.logic;

import com.chatmiao.lottery.LotteryConfig;
import com.chatmiao.lottery.data.LotteryData;
import com.chatmiao.lottery.data.TicketType;
import com.chatmiao.lottery.data.VoucherType;
import com.chatmiao.lottery.item.VoucherItem;
import com.chatmiao.lottery.item.ScratchTicketItem;
import com.chatmiao.lottery.network.Network;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.List;

/** 彩票终端 server logic. Every request re-checks the operator permission. */
public final class TerminalService {

    public static void open(ServerPlayer player, int selectId) {
        LotteryData data = LotteryData.get(player.server);
        List<TicketType> types = new ArrayList<>(data.types());
        Network.send(player, new Payloads.OpenTerminal(types, new ArrayList<>(data.vouchers()), selectId, LotteryConfig.maxGenerate()));
    }

    /** Re-open the terminal from another admin screen (permission re-checked). */
    public static void reopen(ServerPlayer player) {
        if (allowed(player)) open(player, 0);
    }

    public static void openVouchers(ServerPlayer player, int selectId) {
        if (!allowed(player)) return;
        Network.send(player, new Payloads.OpenVouchers(new ArrayList<>(LotteryData.get(player.server).vouchers()), selectId,
                LotteryConfig.maxGenerate()));
    }

    public static void saveVoucher(ServerPlayer player, VoucherType input) {
        if (!allowed(player)) return;
        LotteryData data = LotteryData.get(player.server);
        boolean created = input.id <= 0 || data.voucher(input.id) == null;
        VoucherType stored = data.saveVoucher(input);
        LotteryLog.log(player.server, "CONFIG", LotteryLog.who(player) + (created ? " created" : " updated") + " voucher #"
                + stored.id + " " + stored.name + " enabled=" + stored.enabled);
        Network.sendToAll(player.server, new Payloads.SyncVouchers(data.voucherInfo()));
        player.displayClientMessage(Component.translatable("message.lottery.voucher.saved", stored.name).withStyle(ChatFormatting.GREEN), true);
        openVouchers(player, stored.id);
    }

    public static void generateVouchers(ServerPlayer player, int voucherId, int count) {
        if (!allowed(player)) return;
        LotteryData data = LotteryData.get(player.server);
        VoucherType v = data.voucher(voucherId);
        if (v == null) return;
        count = Math.max(1, Math.min(count, LotteryConfig.maxGenerate()));
        int left = count;
        while (left > 0) {
            int n = Math.min(left, 64);
            ItemHandlerHelper.giveItemToPlayer(player, VoucherItem.create(voucherId, n));
            left -= n;
        }
        v.issued += count;
        data.setDirty();
        LotteryLog.log(player.server, "ISSUE", LotteryLog.who(player) + " generated " + count + " vouchers #" + v.id + " " + v.name);
        player.displayClientMessage(Component.translatable("message.lottery.voucher.generated", count, v.name)
                .withStyle(ChatFormatting.GREEN), true);
        openVouchers(player, voucherId);
    }

    public static void save(ServerPlayer player, TicketType input) {
        if (!allowed(player)) return;
        LotteryData data = LotteryData.get(player.server);
        boolean created = input.id <= 0 || data.type(input.id) == null;
        TicketType stored = data.saveType(input);
        LotteryLog.log(player.server, "CONFIG", LotteryLog.who(player) + (created ? " created" : " updated") + " type #"
                + stored.id + " " + stored.name + " enabled=" + stored.enabled + " empty=" + stored.emptyWeight
                + " pools=" + stored.pools.size() + String.format(" winChance=%.2f%%", stored.winChance() * 100));
        Network.sendToAll(player.server, new Payloads.SyncNames(data.typeNames()));
        player.displayClientMessage(Component.translatable("message.lottery.terminal.saved", stored.id, stored.name)
                .withStyle(ChatFormatting.GREEN), true);
        open(player, stored.id);
    }

    public static void generate(ServerPlayer player, int typeId, int count) {
        if (!allowed(player)) return;
        LotteryData data = LotteryData.get(player.server);
        TicketType type = data.type(typeId);
        if (type == null) return;
        count = Math.max(1, Math.min(count, LotteryConfig.maxGenerate()));
        int left = count;
        while (left > 0) {
            int n = Math.min(left, 64);
            ItemHandlerHelper.giveItemToPlayer(player, ScratchTicketItem.create(typeId, n));
            left -= n;
        }
        type.issued += count;
        data.setDirty();
        LotteryLog.log(player.server, "ISSUE", LotteryLog.who(player) + " generated " + count + " tickets of type #"
                + type.id + " " + type.name);
        player.displayClientMessage(Component.translatable("message.lottery.terminal.generated", count, type.id, type.name)
                .withStyle(ChatFormatting.GREEN), true);
        open(player, typeId);
    }

    private static boolean allowed(ServerPlayer player) {
        if (player.hasPermissions(LotteryConfig.opLevel())) return true;
        LotteryLog.warn(player, "sent a terminal request without permission");
        return false;
    }

    private TerminalService() {}
}
