package com.chatmiao.lottery.event;

import com.chatmiao.lottery.LotteryConfig;
import com.chatmiao.lottery.LotteryMod;
import com.chatmiao.lottery.ModRegistry;
import com.chatmiao.lottery.block.ScratchTableBlockEntity;
import com.chatmiao.lottery.data.LotteryData;
import com.chatmiao.lottery.data.TicketNames;
import com.chatmiao.lottery.logic.RedeemService;
import com.chatmiao.lottery.logic.ScratchService;
import com.chatmiao.lottery.network.Network;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(modid = LotteryMod.MODID)
public final class CommonEvents {

    /**
     * All right-clicks on our blocks go through here, including sneak + right-click with an item in
     * hand (which vanilla would otherwise send to the item instead of the block).
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        BlockState state = event.getLevel().getBlockState(event.getPos());
        boolean table = state.is(ModRegistry.SCRATCH_TABLE.get());
        boolean station = state.is(ModRegistry.REDEEM_STATION.get());
        if (!table && !station) return;

        event.setCanceled(true);
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        if (table && event.getLevel().getBlockEntity(event.getPos()) instanceof ScratchTableBlockEntity be) {
            ScratchService.onUse(player, event.getPos(), be);
        } else if (station) {
            RedeemService.onUse(player, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        BlockState state = event.getState();
        if (!state.is(ModRegistry.SCRATCH_TABLE.get()) && !state.is(ModRegistry.REDEEM_STATION.get())) return;
        if (!event.getPlayer().hasPermissions(LotteryConfig.opLevel())) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.translatable("message.lottery.no_permission").withStyle(ChatFormatting.RED), true);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LotteryData data = LotteryData.get(player.server);
            Network.send(player, new Payloads.SyncNames(data.typeNames()));
            Network.send(player, new Payloads.SyncVouchers(data.voucherInfo()));
        }
    }

    /** {@code /gacha}: open the gacha screen anywhere (also bound to a key and to right-clicking a voucher). */
    @SubscribeEvent
    public static void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("gacha").executes(ctx -> {
            com.chatmiao.lottery.logic.HubService.open(ctx.getSource().getPlayerOrException());
            return 1;
        }));
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        LotteryData data = LotteryData.get(event.getServer());
        TicketNames.replaceAll(data.typeNames());
        com.chatmiao.lottery.data.VoucherNames.replaceAll(data.voucherInfo());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TicketNames.clear();
        com.chatmiao.lottery.data.VoucherNames.clear();
    }

    private CommonEvents() {}
}
