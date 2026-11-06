package com.chatmiao.lottery.client;

import com.chatmiao.lottery.data.Draw;
import com.chatmiao.lottery.data.Tier;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

/**
 * 对号刮奖. Winning numbers on top, a cols×rows grid of my numbers below, all under a scratch coating.
 * Click a cell to flip it, or hold the left button and drag to scratch. The grid adapts to the screen size.
 */
public class ScratchScreen extends PlayScreen {
    private static final int PIX = 2;            // coating resolution in GUI pixels
    private static final int BRUSH = 5;          // scratch radius
    private static final float AUTO_REVEAL = 0.55F;
    private static final int WIN_W = 40, WIN_H = 24, WIN_GAP = 8, WIN_Y = 42, GRID_Y = 84, SIDE = 14;

    private int winCount, cols, rows, cells;
    private int cellW = 64, cellH = 30, cellGap = 6, gridX = SIDE;
    private boolean[][] cleared;
    private int[] clearedCount;
    private int coatW = -1, coatH = -1;

    private boolean dragging;
    private double pressX, pressY, lastX, lastY, dragDist;
    private long lastScratchSound;

    public ScratchScreen(Payloads.ScratchState state) {
        super(state);
    }

    @Override
    protected void onNewRound() {
        winCount = round.winning.length;
        cols = Math.max(1, round.cols);
        cells = round.board.length;
        rows = Math.max(1, (cells + cols - 1) / cols);
        coatW = coatH = -1; // coating arrays are rebuilt by layout()
        if (width > 0) layout();
    }

    @Override
    protected void layout() {
        cellGap = cols > 5 ? 4 : 6;
        int maxW = Math.min(width - 16, 460);
        cellW = Mth.clamp((maxW - 2 * SIDE - (cols - 1) * cellGap) / cols, 28, 64);
        int gridW = cols * cellW + (cols - 1) * cellGap;
        int winW = winCount * WIN_W + (winCount - 1) * WIN_GAP;
        panelW = Math.max(232, Math.max(gridW, winW) + 2 * SIDE);
        int availH = height - 16 - GRID_Y - 40;
        cellH = Mth.clamp((availH - (rows - 1) * cellGap) / rows, 16, 30);
        panelH = GRID_Y + rows * cellH + (rows - 1) * cellGap + 40;
        gridX = (panelW - gridW) / 2;
        if (coatW != cellW || coatH != cellH) {
            // sizes changed (first layout or window resized): restart the coating, keep revealed cells open
            coatW = cellW;
            coatH = cellH;
            int n = winCount + cells;
            cleared = new boolean[n][];
            clearedCount = new int[n];
            for (int i = 0; i < n; i++) {
                int[] r = cellRect(i);
                cleared[i] = new boolean[(r[2] / PIX) * (r[3] / PIX)];
            }
        }
    }

    /** {x, y, w, h} relative to the panel. Cells 0..winCount-1 = winning numbers, then my numbers. */
    private int[] cellRect(int i) {
        if (i < winCount) {
            int total = winCount * WIN_W + (winCount - 1) * WIN_GAP;
            return new int[]{(panelW - total) / 2 + i * (WIN_W + WIN_GAP), WIN_Y, WIN_W, WIN_H};
        }
        int b = i - winCount;
        return new int[]{gridX + (b % cols) * (cellW + cellGap), GRID_Y + (b / cols) * (cellH + cellGap), cellW, cellH};
    }

    private int cellCount() {
        return winCount + cells;
    }

    /** The draw shown in grid cell {@code i} (panel cell index), or null. */
    private Draw drawAt(int i) {
        if (i < winCount) return null;
        int d = round.drawAtCell(i - winCount);
        return d >= 0 ? round.draws.get(d) : null;
    }

    @Override
    protected void addPlayButtons(int y) {
        button(Component.translatable("gui.lottery.scratch.reveal_all"), left + panelW / 2 - 45, y, 90, b -> revealAll());
    }

    // ------------------------------------------------------------------ actions

    private void revealAll() {
        long mask = 0;
        for (int i = 0; i < cellCount(); i++) {
            if (!isRevealed(i)) {
                mask |= 1L << i;
                burst(i, 6);
            }
        }
        if (mask != 0) play(SoundEvents.BOOK_PAGE_TURN, 1.0F, 1.0F);
        markRevealed(mask);
    }

    private void reveal(int i) {
        if (isRevealed(i)) return;
        burst(i, 10);
        play(SoundEvents.BOOK_PAGE_TURN, 1.2F, 0.8F);
        markRevealed(1L << i);
    }

    @Override
    protected void onFinished() {
        super.onFinished();
        for (int i = winCount; i < cellCount(); i++) if (drawAt(i) != null) burst(i, 30);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button == 0 && !finished) {
            dragging = true;
            pressX = lastX = mx;
            pressY = lastY = my;
            dragDist = 0;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && button == 0) {
            dragDist += Math.abs(dx) + Math.abs(dy);
            // interpolate so fast strokes leave no gaps
            double dist = Math.hypot(mx - lastX, my - lastY);
            int steps = Math.max(1, (int) (dist / 2));
            for (int s = 1; s <= steps; s++) {
                scratchAt(lastX + (mx - lastX) * s / steps, lastY + (my - lastY) * s / steps);
            }
            lastX = mx;
            lastY = my;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging && button == 0) {
            dragging = false;
            if (dragDist < 3) {
                int cell = cellAt(pressX, pressY);
                if (cell >= 0) reveal(cell);   // a click flips the whole cell
            }
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    private int cellAt(double mx, double my) {
        for (int i = 0; i < cellCount(); i++) {
            int[] r = cellRect(i);
            if (mx >= left + r[0] && mx < left + r[0] + r[2] && my >= top + r[1] && my < top + r[1] + r[3]) return i;
        }
        return -1;
    }

    private void scratchAt(double mx, double my) {
        boolean any = false;
        for (int i = 0; i < cellCount(); i++) {
            if (isRevealed(i)) continue;
            int[] r = cellRect(i);
            double lx = mx - (left + r[0]), ly = my - (top + r[1]);
            if (lx < -BRUSH || ly < -BRUSH || lx > r[2] + BRUSH || ly > r[3] + BRUSH) continue;
            int cw = r[2] / PIX, ch = r[3] / PIX;
            int cx0 = Math.max(0, (int) ((lx - BRUSH) / PIX)), cx1 = Math.min(cw - 1, (int) ((lx + BRUSH) / PIX));
            int cy0 = Math.max(0, (int) ((ly - BRUSH) / PIX)), cy1 = Math.min(ch - 1, (int) ((ly + BRUSH) / PIX));
            for (int cy = cy0; cy <= cy1; cy++) {
                for (int cx = cx0; cx <= cx1; cx++) {
                    double ddx = cx * PIX + PIX / 2.0 - lx, ddy = cy * PIX + PIX / 2.0 - ly;
                    if (ddx * ddx + ddy * ddy > BRUSH * BRUSH) continue;
                    int idx = cy * cw + cx;
                    if (!cleared[i][idx]) {
                        cleared[i][idx] = true;
                        clearedCount[i]++;
                        any = true;
                        if (rnd.nextInt(6) == 0) {
                            particles.add(new Particle(left + r[0] + cx * PIX, top + r[1] + cy * PIX,
                                    rnd.nextGaussian() * 0.8, -rnd.nextDouble() * 1.5, 12, 12, 0xC1C1CA, 1 + rnd.nextInt(2)));
                        }
                    }
                }
            }
            if ((float) clearedCount[i] / Math.max(1, cleared[i].length) >= AUTO_REVEAL) reveal(i);
        }
        if (any) {
            long now = System.currentTimeMillis();
            if (now - lastScratchSound > 90) {
                lastScratchSound = now;
                play(SoundEvents.BRUSH_GENERIC, 1.3F + rnd.nextFloat() * 0.3F, 0.35F);
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int color = 0xFF000000 | TicketColors.of(round.typeId);
        int dark = 0xFF000000 | TicketColors.dark(round.typeId);
        g.fill(left - 2, top - 2, left + panelW + 2, top + panelH + 2, dark);
        g.fill(left, top, left + panelW, top + panelH, 0xFFFBF5E4);
        g.fill(left, top, left + panelW, top + 30, color);
        for (int x = left + 4; x < left + panelW - 2; x += 6) g.fill(x, top + 31, x + 2, top + 32, dark);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, Component.translatable("gui.lottery.scratch.title", round.typeName), left + 8, top + 6, 0xFFFFFFFF, true);
        g.drawString(font, Component.translatable("gui.lottery.scratch.sub", round.typeId, stock), left + 8, top + 18, 0xFFF0F0F0, false);

        centered(g, Component.translatable("gui.lottery.scratch.winning_numbers"), top + WIN_Y - 10, 0xFF6A6050);
        if (finished) {
            centered(g, resultBanner(), top + GRID_Y - 12, round.won() ? 0xFFD08A00 : 0xFF707070);
        } else {
            centered(g, Component.translatable("gui.lottery.scratch.my_numbers"), top + GRID_Y - 12, 0xFF6A6050);
        }

        for (int i = 0; i < cellCount(); i++) drawCell(g, i, mouseX, mouseY);
        renderParticles(g);

        int noteY = top + panelH - 36;
        if (waiting) {
            centered(g, Component.translatable("gui.lottery.scratch.loading"), noteY, 0xFF8A8070);
        } else if (!finished) {
            centered(g, Component.translatable("gui.lottery.scratch.hint"), noteY, 0xFF8A8070);
        }

        // tooltip for prize icons
        for (int i = winCount; i < cellCount(); i++) {
            Draw d = drawAt(i);
            if (d == null || !isRevealed(i)) continue;
            int[] ic = iconPos(i);
            if (mouseX >= ic[0] && mouseX < ic[0] + 16 && mouseY >= ic[1] && mouseY < ic[1] + 16) {
                g.renderTooltip(font, d.prize().item(), mouseX, mouseY);
            }
        }
    }

    private int[] iconPos(int i) {
        int[] r = cellRect(i);
        return new int[]{left + r[0] + r[2] - 18, top + r[1] + (r[3] - 16) / 2};
    }

    private void drawCell(GuiGraphics g, int i, int mouseX, int mouseY) {
        int[] r = cellRect(i);
        int x = left + r[0], y = top + r[1], w = r[2], h = r[3];
        boolean winningCell = i < winCount;
        Draw d = drawAt(i);
        boolean hit = d != null && isRevealed(i);

        int border = winningCell ? 0xFFC9A44A : 0xFFB0A890;
        int bg = winningCell ? 0xFFFFF1CC : 0xFFFFFFFF;
        if (hit) {
            float pulse = (Mth.sin(System.currentTimeMillis() / 150.0F) + 1) / 2;
            int tc = Tier.color(d.tier());
            border = 0xFF000000 | (pulse > 0.5F ? tc : 0xFFB300);
            bg = 0xFFFFF0A8;
        }
        frame(g, x, y, w, h, border, bg);

        // content under the coating
        int number = winningCell ? round.winning[i] : round.board[i - winCount];
        String text = String.valueOf(number);
        int textColor = winningCell || hit ? 0xFFB03020 : 0xFF303030;
        float scale = w >= 50 && h >= 22 ? 1.5F : 1.0F;
        int tw = (int) (font.width(text) * scale);
        int contentW = hit ? w - 18 : w;
        g.pose().pushPose();
        g.pose().translate(x + (contentW - tw) / 2.0F, y + (h - 8 * scale) / 2.0F + 1, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, 0, 0, textColor, false);
        g.pose().popPose();
        if (hit) {
            int[] ic = iconPos(i);
            g.renderItem(d.prize().item(), ic[0], ic[1]);
            g.renderItemDecorations(font, d.prize().item(), ic[0], ic[1], String.valueOf(d.prize().count()));
        }

        if (isRevealed(i)) return;

        // coating
        int cw = w / PIX, ch = h / PIX;
        boolean[] mask = cleared[i];
        g.pose().pushPose();
        g.pose().translate(0, 0, 200); // above item icons
        for (int cy = 0; cy < ch; cy++) {
            int runStart = -1, runColor = 0;
            for (int cx = 0; cx <= cw; cx++) {
                boolean covered = cx < cw && !mask[cy * cw + cx];
                int c = covered ? coatColor(i, cx, cy) : 0;
                if (runStart >= 0 && (!covered || c != runColor)) {
                    g.fill(x + runStart * PIX, y + cy * PIX, x + cx * PIX, y + (cy + 1) * PIX, runColor);
                    runStart = -1;
                }
                if (covered && runStart < 0) {
                    runStart = cx;
                    runColor = c;
                }
            }
        }
        if (clearedCount[i] == 0) {
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
            g.drawString(font, "?", x + (w - font.width("?")) / 2, y + (h - 8) / 2, hover ? 0xFFFFFFFF : 0xFF7C7C86, false);
        }
        g.pose().popPose();
    }

    private static int coatColor(int cell, int cx, int cy) {
        int n = (cx * 73856093) ^ (cy * 19349663) ^ (cell * 83492791);
        n = (n >>> 13) ^ n;
        int v = (n * (n * n * 15731 + 789221) + 1376312589) >>> 28;
        if (v < 2) return 0xFFA9A9B3;
        if (v > 13) return 0xFFD4D4DC;
        return 0xFFC1C1CA;
    }

    private void burst(int cell, int n) {
        int[] r = cellRect(cell);
        Draw d = drawAt(cell);
        int color = d != null ? Tier.color(d.tier()) : 0xB8B8C2;
        burstAt(left + r[0], top + r[1], r[2], r[3], n, color);
    }
}
