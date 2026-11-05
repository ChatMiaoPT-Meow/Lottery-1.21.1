package com.chatmiao.lottery.client;

import com.chatmiao.lottery.data.TicketNames;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Entry points for client-bound packets. Only ever invoked on the physical client. */
public final class ClientBridge {

    public static void syncNames(Payloads.SyncNames p) {
        TicketNames.replaceAll(p.names());
    }

    public static void openTerminal(Payloads.OpenTerminal p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TerminalScreen screen) {
            screen.update(p);
        } else {
            mc.setScreen(new TerminalScreen(p));
        }
    }

    public static void scratchState(Payloads.ScratchState p) {
        Minecraft mc = Minecraft.getInstance();
        if (p.round() == null) {
            if (mc.screen instanceof ScratchScreen screen && screen.pos().equals(p.pos())) screen.onClose();
            return;
        }
        if (mc.screen instanceof ScratchScreen screen && screen.pos().equals(p.pos())) {
            screen.setState(p);
        } else {
            mc.setScreen(new ScratchScreen(p));
        }
    }

    public static void openRedeem(Payloads.OpenRedeem p) {
        Minecraft.getInstance().setScreen(new RedeemScreen(p));
    }

    public static void redeemResult(Payloads.RedeemResult p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RedeemScreen screen) {
            screen.onResult(p);
        } else if (mc.player != null) {
            for (Component c : p.errors()) mc.player.displayClientMessage(c, false);
        }
    }

    private ClientBridge() {}
}
