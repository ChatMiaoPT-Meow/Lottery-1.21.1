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
            if (mc.screen instanceof PlayScreen screen && screen.pos().equals(p.pos())) screen.onClose();
            return;
        }
        if (mc.screen instanceof PlayScreen screen && screen.accepts(p)) {
            screen.setState(p);
        } else {
            mc.setScreen(PlayScreen.create(p));
        }
    }

    public static void syncVouchers(Payloads.SyncVouchers p) {
        com.chatmiao.lottery.data.VoucherNames.replaceAll(p.info());
    }

    public static void openVouchers(Payloads.OpenVouchers p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof VoucherScreen screen) {
            screen.update(p);
        } else {
            mc.setScreen(new VoucherScreen(p));
        }
    }

    public static void openHub(Payloads.OpenHub p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof GachaScreen screen) {
            screen.update(p);
        } else {
            mc.setScreen(new GachaScreen(p));
        }
    }

    public static void hubState(Payloads.HubState p) {
        Minecraft mc = Minecraft.getInstance();
        if (p.round().revealedMask == 0) {
            // a fresh pull (or one closed before anything was revealed): play the summon first
            mc.setScreen(new SummonScreen(p));
        } else if (mc.screen instanceof PlayScreen screen && screen.accepts(p)) {
            screen.setState(p);
        } else {
            mc.setScreen(PlayScreen.create(p));
        }
    }

    public static void hubHistory(Payloads.HubHistory p) {
        if (Minecraft.getInstance().screen instanceof GachaScreen screen) screen.setHistory(p.records());
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
