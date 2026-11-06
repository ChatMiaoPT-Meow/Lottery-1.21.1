package com.chatmiao.lottery.network;

import com.chatmiao.lottery.LotteryMod;
import com.chatmiao.lottery.client.ClientBridge;
import com.chatmiao.lottery.logic.RedeemService;
import com.chatmiao.lottery.logic.ScratchService;
import com.chatmiao.lottery.logic.TerminalService;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = LotteryMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class Network {

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // "2": play styles / multi-draw tickets changed TicketType, ScratchRound and Reveal on the wire
        PayloadRegistrar r = event.registrar("2");

        // Client-bound handlers only touch ClientBridge inside the lambda body, so the client
        // classes are never loaded on a dedicated server. Handlers run on the main thread.
        r.playToClient(Payloads.SyncNames.TYPE, Payloads.SyncNames.CODEC, (p, ctx) -> ClientBridge.syncNames(p));
        r.playToClient(Payloads.OpenTerminal.TYPE, Payloads.OpenTerminal.CODEC, (p, ctx) -> ClientBridge.openTerminal(p));
        r.playToClient(Payloads.ScratchState.TYPE, Payloads.ScratchState.CODEC, (p, ctx) -> ClientBridge.scratchState(p));
        r.playToClient(Payloads.OpenRedeem.TYPE, Payloads.OpenRedeem.CODEC, (p, ctx) -> ClientBridge.openRedeem(p));
        r.playToClient(Payloads.RedeemResult.TYPE, Payloads.RedeemResult.CODEC, (p, ctx) -> ClientBridge.redeemResult(p));

        r.playToServer(Payloads.SaveType.TYPE, Payloads.SaveType.CODEC,
                (p, ctx) -> TerminalService.save((ServerPlayer) ctx.player(), p.ticketType()));
        r.playToServer(Payloads.Generate.TYPE, Payloads.Generate.CODEC,
                (p, ctx) -> TerminalService.generate((ServerPlayer) ctx.player(), p.typeId(), p.count()));
        r.playToServer(Payloads.Reveal.TYPE, Payloads.Reveal.CODEC,
                (p, ctx) -> ScratchService.reveal((ServerPlayer) ctx.player(), p.pos(), p.roundId(), p.mask()));
        r.playToServer(Payloads.Finish.TYPE, Payloads.Finish.CODEC,
                (p, ctx) -> ScratchService.finish((ServerPlayer) ctx.player(), p.pos(), p.roundId(), p.next()));
        r.playToServer(Payloads.Redeem.TYPE, Payloads.Redeem.CODEC,
                (p, ctx) -> RedeemService.redeem((ServerPlayer) ctx.player(), p.pos(), p.winIds()));
    }

    /**
     * Sends a payload only if the player's connection negotiated our channel, so a connection
     * without the mod (or a fake player) can never break server-side logic halfway through.
     */
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    public static void sendToAll(MinecraftServer server, CustomPacketPayload payload) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player, payload);
    }

    private Network() {}
}
