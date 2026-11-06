package com.chatmiao.lottery.client;

import com.chatmiao.lottery.data.Draw;
import com.chatmiao.lottery.data.Tier;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;

/**
 * 卡牌抽卡 (e.g. a ten-pull). One face-down card per draw; click a card to flip it or flip them all one by one.
 * With the type's rare effect on, cards of tier >= 3 glow in their colour before flipping and shake first.
 */
public class CardScreen extends PlayScreen {
    private static final int TOP = 44, GAP = 6, SIDE = 14, BOTTOM = 54;
    private static final int FLIP_TICKS = 6, RARE_TICKS = 16, QUEUE_INTERVAL = 4;
    private static final int RARE_TIER = 3;

    private int count, perRow, rowCount;
    private int cardW, cardH;
    /** Tick a card started flipping, or -1. */
    private int[] flipTick;
    private ArrayDeque<Integer> queue;
    private int lastQueued;

    public CardScreen(Payloads.ScratchState state) {
        super(state);
    }

    @Override
    protected void onNewRound() {
        count = round.draws.size();
        perRow = Mth.clamp(round.cardCols, 1, Math.max(1, count));
        rowCount = (count + perRow - 1) / perRow;
        flipTick = new int[count];
        java.util.Arrays.fill(flipTick, -1);
        queue = new ArrayDeque<>();
    }

    @Override
    protected void layout() {
        int availH = height - 16 - TOP - BOTTOM;
        cardH = Mth.clamp((availH - (rowCount - 1) * GAP) / Math.max(1, rowCount), 36, 72);
        cardW = cardH * 3 / 4;
        int maxW = width - 16 - 2 * SIDE;
        if (perRow * cardW + (perRow - 1) * GAP > maxW) {
            cardW = Math.max(24, (maxW - (perRow - 1) * GAP) / perRow);
            cardH = cardW * 4 / 3;
        }
        panelW = Math.max(220, perRow * cardW + (perRow - 1) * GAP + 2 * SIDE);
        panelH = TOP + rowCount * cardH + (rowCount - 1) * GAP + BOTTOM;
    }

    private int[] cardRect(int i) {
        int row = i / perRow, col = i % perRow;
        int inRow = Math.min(perRow, count - row * perRow);
        int rowW = inRow * cardW + (inRow - 1) * GAP;
        int x = left + (panelW - rowW) / 2 + col * (cardW + GAP);
        int y = top + TOP + row * (cardH + GAP);
        return new int[]{x, y, cardW, cardH};
    }

    private Draw draw(int i) {
        return i < round.draws.size() ? round.draws.get(i) : Draw.EMPTY;
    }

    private boolean rare(int i) {
        Draw d = draw(i);
        return round.rareEffect && d.won() && d.tier() >= RARE_TIER;
    }

    private int flipDuration(int i) {
        return rare(i) ? RARE_TICKS : FLIP_TICKS;
    }

    @Override
    protected void addPlayButtons(int y) {
        Button all = button(Component.translatable("gui.lottery.cards.flip_all"), left + panelW / 2 - 45, y, 90, b -> flipAll());
        boolean pending = false;
        for (int i = 0; i < count; i++) if (!isRevealed(i) && flipTick[i] < 0 && !queue.contains(i)) pending = true;
        all.active = pending;
    }

    private void flipAll() {
        for (int i = 0; i < count; i++) {
            if (!isRevealed(i) && flipTick[i] < 0 && !queue.contains(i)) queue.add(i);
        }
        lastQueued = ticks - QUEUE_INTERVAL;
        rebuildWidgets();
    }

    private void flip(int i) {
        if (isRevealed(i) || flipTick[i] >= 0) return;
        queue.remove(i);
        flipTick[i] = ticks;
        play(SoundEvents.BOOK_PAGE_TURN, rare(i) ? 0.8F : 1.3F, 0.8F);
        if (rare(i)) play(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 0.9F);
    }

    private void landed(int i) {
        Draw d = draw(i);
        int[] r = cardRect(i);
        burstAt(r[0], r[1], r[2], r[3], d.won() ? 8 + d.tier() * 8 : 3, Tier.color(d.tier()));
        if (d.won() && d.tier() >= 4) {
            play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.5F);
        } else if (d.won() && d.tier() >= 2) {
            play(SoundEvents.NOTE_BLOCK_CHIME, 1.2F, 0.8F);
        } else if (d.won()) {
            play(SoundEvents.NOTE_BLOCK_PLING, 1.4F, 0.5F);
        }
        markRevealed(1L << i);
    }

    @Override
    public void tick() {
        super.tick();
        if (!queue.isEmpty() && ticks - lastQueued >= QUEUE_INTERVAL) {
            lastQueued = ticks;
            flip(queue.poll());
        }
        for (int i = 0; i < count; i++) {
            if (flipTick[i] >= 0 && !isRevealed(i) && ticks - flipTick[i] >= flipDuration(i)) landed(i);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0 || finished) return false;
        int i = cardAt(mx, my);
        if (i >= 0) {
            flip(i);
            rebuildWidgets();
            return true;
        }
        return false;
    }

    private int cardAt(double mx, double my) {
        for (int i = 0; i < count; i++) {
            int[] r = cardRect(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int color = 0xFF000000 | TicketColors.of(round.typeId);
        g.fill(left - 2, top - 2, left + panelW + 2, top + panelH + 2, 0xFF0C0D10);
        g.fill(left, top, left + panelW, top + panelH, 0xFF1E2128);
        g.fill(left, top, left + panelW, top + 30, color);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, Component.translatable("gui.lottery.cards.title", round.typeName, count), left + 8, top + 6, 0xFFFFFFFF, true);
        g.drawString(font, Component.translatable("gui.lottery.scratch.sub", round.typeId, stock), left + 8, top + 18, 0xFFF0F0F0, false);

        float now = ticks + partialTick;
        int hovered = cardAt(mouseX, mouseY);
        for (int i = 0; i < count; i++) drawCard(g, i, now, i == hovered && !finished);
        renderParticles(g);

        int noteY = top + panelH - 40;
        if (waiting) {
            centered(g, Component.translatable("gui.lottery.scratch.loading"), noteY, 0xFF9AA0AA);
        } else if (finished) {
            centered(g, resultBanner(), noteY, round.won() ? 0xFFFFC94A : 0xFF9AA0AA);
        } else {
            centered(g, Component.translatable("gui.lottery.cards.hint"), noteY, 0xFF9AA0AA);
        }

        if (hovered >= 0 && isRevealed(hovered) && draw(hovered).won()) {
            g.renderTooltip(font, draw(hovered).prize().item(), mouseX, mouseY);
        }
    }

    private void drawCard(GuiGraphics g, int i, float now, boolean hover) {
        int[] r = cardRect(i);
        int x = r[0], y = r[1], w = r[2], h = r[3];
        boolean front;
        float xScale = 1;
        float shake = 0;
        float glow = rare(i) ? 0.5F + 0.5F * Mth.sin(now * 0.3F) : 0;
        if (isRevealed(i)) {
            front = true;
        } else if (flipTick[i] >= 0) {
            float t = Mth.clamp((now - flipTick[i]) / flipDuration(i), 0, 1);
            float f = t;
            if (rare(i)) {
                // shake and brighten for the first 60%, then flip
                if (t < 0.6F) {
                    shake = Mth.sin(now * 2.6F) * 2.0F * (t / 0.6F);
                    glow = 0.5F + t;
                    f = 0;
                } else {
                    f = (t - 0.6F) / 0.4F;
                }
            }
            front = f >= 0.5F;
            xScale = Math.max(0.05F, Math.abs(1 - 2 * f));
        } else {
            front = false;
            if (hover) y -= 2;
        }

        g.pose().pushPose();
        g.pose().translate(x + w / 2.0F + shake, y + h / 2.0F, 0);
        g.pose().scale(xScale, 1, 1);
        g.pose().translate(-w / 2.0F, -h / 2.0F, 0);
        if (front) {
            drawFront(g, i, w, h);
        } else {
            drawBack(g, i, w, h, glow);
        }
        g.pose().popPose();
    }

    private void drawBack(GuiGraphics g, int i, int w, int h, float glow) {
        int color = 0xFF000000 | TicketColors.of(round.typeId);
        int dark = 0xFF000000 | TicketColors.dark(round.typeId);
        if (glow > 0) {
            int a = Mth.clamp((int) (glow * 200), 0, 255);
            int tc = Tier.color(draw(i).tier());
            g.fill(-2, -2, w + 2, h + 2, (a << 24) | tc);
        }
        g.fill(0, 0, w, h, dark);
        g.fill(2, 2, w - 2, h - 2, color);
        g.fill(5, 5, w - 5, h - 5, dark);
        g.fill(6, 6, w - 6, h - 6, color);
        String q = "?";
        float s = w >= 36 ? 2.0F : 1.0F;
        g.pose().pushPose();
        g.pose().translate((w - font.width(q) * s) / 2.0F, (h - 8 * s) / 2.0F, 0);
        g.pose().scale(s, s, 1);
        g.drawString(font, q, 0, 0, 0xFFFFFFFF, true);
        g.pose().popPose();
    }

    private void drawFront(GuiGraphics g, int i, int w, int h) {
        Draw d = draw(i);
        int tc = 0xFF000000 | Tier.color(d.tier());
        g.fill(0, 0, w, h, d.won() ? tc : 0xFF4A4E58);
        g.fill(2, 2, w - 2, h - 2, d.won() ? 0xFF2A2E36 : 0xFF24262B);
        if (!d.won()) {
            Component t = Component.translatable("gui.lottery.cards.empty");
            String s = font.plainSubstrByWidth(t.getString(), w - 4);
            g.drawString(font, s, (w - font.width(s)) / 2, (h - 8) / 2, 0xFF6A6E78, false);
            return;
        }
        g.fill(2, 2, w - 2, 5, tc);
        ItemStack item = d.prize().item();
        // tall cards: big icon, amount line, rarity line. Short cards: small icon with the amount on it, rarity line.
        boolean tall = h >= 56 && w >= 40;
        float scale = tall ? 2.0F : 1.0F;
        int icon = (int) (16 * scale);
        int textArea = tall ? 21 : 11;
        int iy = Math.max(6, (h - textArea - icon) / 2 + 3);
        int ix = (w - icon) / 2;
        g.pose().pushPose();
        g.pose().translate(ix, iy, 0);
        g.pose().scale(scale, scale, 1);
        g.renderItem(item, 0, 0);
        if (!tall) g.renderItemDecorations(font, item, 0, 0, String.valueOf(d.prize().count()));
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        if (tall) {
            String amount = "×" + d.prize().count();
            g.drawString(font, amount, (w - font.width(amount)) / 2, h - 20, 0xFFFFFFFF, false);
        }
        String name = font.plainSubstrByWidth(Tier.name(d.tier()).getString(), w - 4);
        g.drawString(font, name, (w - font.width(name)) / 2, h - 10, tc, false);
        g.pose().popPose();
    }
}
