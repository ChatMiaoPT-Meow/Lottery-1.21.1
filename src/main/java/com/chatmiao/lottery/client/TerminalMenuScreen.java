package com.chatmiao.lottery.client;

import com.chatmiao.lottery.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 彩票终端 start menu (OP only). Three separate jobs, three separate pages:
 * <pre>
 *   卡券设置  — voucher kinds (what a pull costs)
 *   奖池设置  — reusable prize pools (rarity + items)
 *   卡池设置  — tickets / gacha pools: which prize pools, play style, listing
 * </pre>
 * The usual order is top to bottom, so the entries are numbered.
 */
public class TerminalMenuScreen extends Screen {
    private static final int W = 260, ENTRY_H = 40, GAP = 6;
    private static final int C_PANEL = 0xF0202329, C_BORDER = 0xFF4A505C, C_ENTRY = 0xFF2B2F36, C_HOVER = 0xFF353A44,
            C_TEXT = 0xFFE8E8E8, C_DIM = 0xFF9AA0AA, C_ACCENT = 0xFFFFC94A;

    private final Payloads.OpenMenu data;
    private int left, top, h;

    public TerminalMenuScreen(Payloads.OpenMenu data) {
        super(Component.translatable("gui.lottery.menu"));
        this.data = data;
    }

    @Override
    protected void init() {
        h = 30 + 3 * ENTRY_H + 2 * GAP + 12;
        left = (width - W) / 2;
        top = (height - h) / 2;
        int y = top + 28;
        addRenderableWidget(new Entry(left + 10, y, 1, "vouchers", data.vouchers(), Payloads.Request.VOUCHERS));
        y += ENTRY_H + GAP;
        addRenderableWidget(new Entry(left + 10, y, 2, "pools", data.prizePools(), Payloads.Request.PRIZE_POOLS));
        y += ENTRY_H + GAP;
        addRenderableWidget(new Entry(left + 10, y, 3, "types", data.types(), Payloads.Request.TERMINAL));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left - 1, top - 1, left + W + 1, top + h + 1, C_BORDER);
        g.fill(left, top, left + W, top + h, C_PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, title, left + 10, top + 10, C_ACCENT, false);
        Component hint = Component.translatable("gui.lottery.menu.hint");
        g.drawString(font, hint, left + W - 10 - font.width(hint), top + 10, C_DIM, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A big two-line button: "1  卡券设置   3 种" / what it is for. */
    private class Entry extends Button {
        private final int number, count;
        private final String key;

        Entry(int x, int y, int number, String key, int count, int request) {
            super(x, y, W - 20, ENTRY_H, Component.translatable("gui.lottery.menu." + key), b ->
                    PacketDistributor.sendToServer(new Payloads.Request(request)), DEFAULT_NARRATION);
            this.number = number;
            this.key = key;
            this.count = count;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean hover = isHoveredOrFocused();
            int x = getX(), y = getY();
            g.fill(x, y, x + width, y + height, hover ? C_HOVER : C_ENTRY);
            g.fill(x, y, x + 3, y + height, hover ? C_ACCENT : C_BORDER);
            g.drawString(font, String.valueOf(number), x + 10, y + 9, C_ACCENT, false);
            g.drawString(font, getMessage(), x + 22, y + 9, hover ? C_ACCENT : C_TEXT, false);
            Component c = Component.translatable("gui.lottery.menu.count_" + key, count);
            g.drawString(font, c, x + width - 8 - font.width(c), y + 9, C_DIM, false);
            Component desc = Component.translatable("gui.lottery.menu." + key + ".desc");
            g.drawString(font, font.plainSubstrByWidth(desc.getString(), width - 30), x + 22, y + 23, C_DIM, false);
            if (hover) g.drawString(font, "›", x + width - 10, y + 23, C_ACCENT, false);
        }
    }
}
