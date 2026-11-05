package com.chatmiao.lottery.client;

import com.chatmiao.lottery.LotteryMod;
import com.chatmiao.lottery.ModRegistry;
import com.chatmiao.lottery.data.TicketNames;
import com.chatmiao.lottery.item.ScratchTicketItem;
import com.chatmiao.lottery.item.WinTicketData;
import com.chatmiao.lottery.item.WinningTicketItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

public final class ClientEvents {

    @EventBusSubscriber(modid = LotteryMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void itemColors(RegisterColorHandlersEvent.Item event) {
            event.register((stack, tint) -> tint == 0 ? 0xFF000000 | TicketColors.of(ScratchTicketItem.typeOf(stack)) : -1,
                    ModRegistry.SCRATCH_TICKET.get());
            event.register((stack, tint) -> {
                if (tint != 0) return -1;
                WinTicketData d = WinningTicketItem.dataOf(stack);
                return 0xFF000000 | TicketColors.of(d == null ? 0 : d.typeId());
            }, ModRegistry.WINNING_TICKET.get());
        }
    }

    @EventBusSubscriber(modid = LotteryMod.MODID, value = Dist.CLIENT)
    public static final class GameBus {
        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            TicketNames.clear();
        }
    }

    private ClientEvents() {}
}
