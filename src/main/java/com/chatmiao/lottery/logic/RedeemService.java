package com.chatmiao.lottery.logic;

import com.chatmiao.lottery.LotteryConfig;
import com.chatmiao.lottery.ModRegistry;
import com.chatmiao.lottery.data.LotteryData;
import com.chatmiao.lottery.data.Prize;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 彩票站 (兑奖台) server logic. Whoever holds a winning ticket may redeem it, once. */
public final class RedeemService {

    public static void onUse(ServerPlayer player, BlockPos pos) {
        ItemStack held = player.getMainHandItem();
        if (WinningTicketItem.dataOf(held) == null) {
            ScratchService.msg(player, held.getItem() instanceof ScratchTicketItem
                    ? "message.lottery.station.scratch_first" : "message.lottery.station.insert_ticket", ChatFormatting.YELLOW);
            return;
        }
        List<ItemStack> candidates = new ArrayList<>();
        if (player.isSecondaryUseActive()) {
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (WinningTicketItem.dataOf(inv.getItem(i)) != null) candidates.add(inv.getItem(i));
            }
        } else {
            candidates.add(held);
        }
        LotteryData data = LotteryData.get(player.server);
        List<Payloads.RedeemEntry> entries = new ArrayList<>();
        for (ItemStack stack : candidates) {
            WinTicketData d = WinningTicketItem.dataOf(stack);
            Component problem = check(player, data, d.winId());
            if (problem != null) {
                player.sendSystemMessage(problem);
            } else {
                entries.add(new Payloads.RedeemEntry(d.winId(), d.typeName()));
            }
        }
        if (!entries.isEmpty()) {
            Network.send(player, new Payloads.OpenRedeem(pos, entries));
        }
    }

    /** The gift box was opened: validate every ticket again, pay out and take the tickets. */
    public static void redeem(ServerPlayer player, BlockPos pos, List<String> winIds) {
        if (!player.level().isLoaded(pos) || !player.level().getBlockState(pos).is(ModRegistry.REDEEM_STATION.get())) return;
        if (player.position().distanceToSqr(Vec3.atCenterOf(pos)) > LotteryConfig.useDistanceSqr()) return;
        LotteryData data = LotteryData.get(player.server);
        List<Prize> prizes = new ArrayList<>();
        List<Component> errors = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (String winId : new LinkedHashSet<>(winIds)) {
            // the result packet holds at most 256 entries; one ticket adds up to 10 prizes
            if (prizes.size() + errors.size() >= 256 - 10) break;
            int slot = findTicket(inv, winId);
            if (slot < 0) {
                errors.add(Component.translatable("message.lottery.station.not_found", winId));
                continue;
            }
            Component problem = check(player, data, winId);
            if (problem != null) {
                errors.add(problem);
                continue;
            }
            WinRecord rec = data.win(winId);
            rec.redeemed = true;
            rec.redeemedAt = System.currentTimeMillis();
            rec.redeemerId = player.getUUID();
            rec.redeemerName = player.getGameProfile().getName();
            data.setDirty();
            inv.removeItemNoUpdate(slot);
            for (Prize prize : rec.prizes) {
                for (ItemStack s : prize.toStacks()) ItemHandlerHelper.giveItemToPlayer(player, s);
                prizes.add(prize);
            }
            LotteryLog.log(player.server, "REDEEM", LotteryLog.who(player) + " redeemed " + winId + " (type #" + rec.typeId
                    + ", won by " + rec.winnerName + ") -> " + LotteryLog.prizes(rec.prizes));
        }
        inv.setChanged();
        if (!prizes.isEmpty()) {
            player.level().playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.2F);
        }
        Network.send(player, new Payloads.RedeemResult(prizes, errors));
    }

    /** @return null if the ticket can be redeemed, otherwise the reason (and a warning is logged). */
    private static Component check(ServerPlayer player, LotteryData data, String winId) {
        WinRecord rec = data.win(winId);
        if (rec == null) {
            LotteryLog.warn(player, "tried to redeem unknown win id " + winId);
            return Component.translatable("message.lottery.station.unknown", winId).withStyle(ChatFormatting.RED);
        }
        if (rec.redeemed) {
            LotteryLog.warn(player, "tried to redeem already redeemed " + winId + " (redeemed by " + rec.redeemerName + ")");
            return Component.translatable("message.lottery.station.already", winId).withStyle(ChatFormatting.RED);
        }
        return null;
    }

    private static int findTicket(Inventory inv, String winId) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            WinTicketData d = WinningTicketItem.dataOf(inv.getItem(i));
            if (d != null && d.winId().equals(winId)) return i;
        }
        return -1;
    }

    private RedeemService() {}
}
