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

    private static final int DEAL_DELAY = 6, DEAL_STAGGER = 2, DEAL_FLIGHT = 8;
    private static final int SHOWCASE_TICKS = 30;

    private int count, perRow, rowCount;
    private int cardW, cardH;
    /** Tick a card started flipping, or -1. */
    private int[] flipTick;
    private ArrayDeque<Integer> queue;
    private int lastQueued;
    /** Tick dealing starts, or -1 when the cards are already on the table (reopened ticket). */
    private int dealStart;
    private int dealtSounded;

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
        // a fresh ticket is dealt from the deck; a reopened one is already on the table
        dealStart = revealed == 0 ? ticks + DEAL_DELAY : -1;
        dealtSounded = 0;
    }

    private boolean dealing() {
        return dealStart >= 0 && ticks < dealStart + (count - 1) * DEAL_STAGGER + DEAL_FLIGHT;
    }

    /** Index of the best prize card (highest tier), or -1. */
    private int bestCard() {
        int best = -1;
        for (int i = 0; i < count; i++) {
            Draw d = draw(i);
            if (d.won() && (best < 0 || d.tier() > draw(best).tier())) best = i;
        }
        return best;
    }

    @Override
    protected int summaryDelay() {
        return SHOWCASE_TICKS + 6;
    }

    @Override
    protected void onFinished() {
        super.onFinished();
        int best = bestCard();
        if (best >= 0 && draw(best).tier() >= 2) {
            int[] r = cardRect(best);
            burstAt(r[0], r[1], r[2], r[3], 30, Tier.color(draw(best).tier()));
            play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.0F);
        }
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
        all.active = pending && !dealing();
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
        boolean wasDealing = dealing();
        super.tick();
        if (dealStart >= 0) {
            int launched = Mth.clamp((ticks - dealStart) / DEAL_STAGGER + 1, 0, count);
            while (dealtSounded < launched) {
                play(SoundEvents.BOOK_PAGE_TURN, 1.4F + rnd.nextFloat() * 0.3F, 0.5F);
                dealtSounded++;
            }
        }
        if (wasDealing && !dealing()) rebuildWidgets(); // enable "全部翻开"
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
        if (button != 0 || finished || dealing()) return false;
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
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        titleBar(g, Component.translatable("gui.lottery.cards.title", round.typeName, count));

        float now = ticks + partialTick;
        int hovered = dealing() ? -1 : cardAt(mouseX, mouseY);
        drawDeck(g, now);
        drawChargePillars(g, now);
        int best = finished ? bestCard() : -1;
        for (int i = 0; i < count; i++) if (i != best) drawCard(g, i, now, i == hovered && !finished);
        if (best >= 0) drawShowcase(g, best, now); // on top of its neighbours

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

    private int deckX() {
        return left + panelW / 2;
    }

    private int deckY() {
        return top + panelH - 36;
    }

    /** The face-down deck the cards are dealt from (only while dealing). */
    private void drawDeck(GuiGraphics g, float now) {
        if (dealStart < 0 || !dealing()) return;
        int remaining = count - Mth.clamp((int) ((now - dealStart) / DEAL_STAGGER) + 1, 0, count);
        if (remaining <= 0) return;
        int w = cardW * 2 / 3, h = cardH * 2 / 3;
        for (int k = Math.min(3, remaining) - 1; k >= 0; k--) {
            g.pose().pushPose();
            g.pose().translate(deckX() - w / 2F + k * 2, deckY() - h / 2F - k * 2, 0);
            g.pose().scale(w / (float) cardW, h / (float) cardH, 1);
            drawBack(g, 0, cardW, cardH, 0);
            g.pose().popPose();
        }
    }

    /** Light pillars that build up over rare cards while they shake, before they flip. */
    private void drawChargePillars(GuiGraphics g, float now) {
        for (int i = 0; i < count; i++) {
            if (!rare(i) || flipTick[i] < 0 || isRevealed(i)) continue;
            float t = Mth.clamp((now - flipTick[i]) / flipDuration(i), 0, 1);
            int[] r = cardRect(i);
            lightPillar(g, r[0] - 2, top + 32, r[1] + r[3], r[2] + 4, Tier.color(draw(i).tier()), 0.3F + t * 0.7F);
        }
    }

    /** The best card, enlarged for a moment when the ticket is finished, then left glowing. */
    private void drawShowcase(GuiGraphics g, int i, float now) {
        int[] r = cardRect(i);
        float t = (now - finishedAt) / SHOWCASE_TICKS;
        float scale;
        if (t < 0.3F) {
            scale = 1 + 0.25F * popScale(t / 0.3F);
        } else if (t < 1) {
            scale = 1.25F - 0.25F * ((t - 0.3F) / 0.7F);
        } else {
            scale = 1;
        }
        int tc = Tier.color(draw(i).tier());
        float pulse = 0.5F + 0.5F * Mth.sin(now * 0.25F);
        int a = (int) (90 + 120 * pulse);
        g.pose().pushPose();
        g.pose().translate(0, 0, 150);
        g.pose().translate(r[0] + r[2] / 2F, r[1] + r[3] / 2F, 0);
        g.pose().scale(scale, scale, 1);
        g.pose().translate(-r[2] / 2F, -r[3] / 2F, 0);
        g.fill(-3, -3, r[2] + 3, r[3] + 3, (a << 24) | tc);
        drawFront(g, i, r[2], r[3]);
        g.pose().popPose();
    }

    private void drawCard(GuiGraphics g, int i, float now, boolean hover) {
        int[] r = cardRect(i);
        int x = r[0], y = r[1], w = r[2], h = r[3];
        if (dealStart >= 0) {
            float d = (now - dealStart - i * DEAL_STAGGER) / DEAL_FLIGHT;
            if (d <= 0) return; // still in the deck
            if (d < 1) {
                // flying from the deck to its slot: ease out, spinning down and growing
                float e = 1 - (1 - d) * (1 - d) * (1 - d);
                float fx = deckX() + (x + w / 2F - deckX()) * e, fy = deckY() + (y + h / 2F - deckY()) * e;
                float s = 0.66F + 0.34F * e;
                g.pose().pushPose();
                g.pose().translate(fx, fy, 50);
                g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees((1 - e) * (i % 2 == 0 ? -30 : 30)));
                g.pose().scale(s, s, 1);
                g.pose().translate(-w / 2F, -h / 2F, 0);
                drawBack(g, i, w, h, 0);
                g.pose().popPose();
                return;
            }
        }
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
