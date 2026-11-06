package com.chatmiao.lottery.client;

import com.chatmiao.lottery.LotteryMod;
import com.chatmiao.lottery.ModRegistry;
import com.chatmiao.lottery.data.TicketNames;
import com.chatmiao.lottery.data.VoucherNames;
import com.chatmiao.lottery.item.ScratchTicketItem;
import com.chatmiao.lottery.item.VoucherItem;
import com.chatmiao.lottery.item.WinTicketData;
import com.chatmiao.lottery.item.WinningTicketItem;
import com.chatmiao.lottery.network.Payloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

public final class ClientEvents {
    /** Opens the gacha screen from anywhere (default K). */
    public static final KeyMapping OPEN_HUB = new KeyMapping("key.lottery.open_hub", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K, "key.categories.lottery");

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
            event.register((stack, tint) -> tint == 0 ? 0xFF000000 | VoucherNames.color(VoucherItem.typeOf(stack)) : -1,
                    ModRegistry.VOUCHER.get());
        }

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_HUB);
        }
    }

    @EventBusSubscriber(modid = LotteryMod.MODID, value = Dist.CLIENT)
    public static final class GameBus {
        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            TicketNames.clear();
            VoucherNames.clear();
        }

        @SubscribeEvent
        public static void onTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            while (OPEN_HUB.consumeClick()) {
                if (mc.player != null && mc.screen == null && mc.getConnection() != null
                        && mc.getConnection().hasChannel(Payloads.Request.TYPE)) {
                    PacketDistributor.sendToServer(new Payloads.Request(Payloads.Request.HUB));
                }
            }
        }
    }

    private ClientEvents() {}
}
