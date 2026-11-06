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

    public ScratchScreen(Source source, com.chatmiao.lottery.logic.ScratchRound round) {
        super(source, round);
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

    @Override
    protected int summaryDelay() {
        return STAMP_DELAY + STAMP_FALL + 18; // let the stamp land and be seen first
    }

    @Override
    public void tick() {
        super.tick();
        if (finished && ticks - finishedAt == STAMP_DELAY + STAMP_FALL) {
            play(SoundEvents.ANVIL_LAND, round.won() ? 1.7F : 1.4F, 0.25F);
            int cx = left + panelW / 2, cy = stampCenterY();
            burstAt(cx - 30, cy - 30, 60, 60, round.won() ? 24 : 8, round.won() ? 0xD03030 : 0x808080);
        }
    }

    // ------------------------------------------------------------------ finale: links and stamp

    private static final int STAMP_DELAY = 6, STAMP_FALL = 5;

    /** Winning-number cell index (0..winCount-1) showing the same number as board cell {@code i}, or -1. */
    private int winningCellFor(int i) {
        int v = round.board[i - winCount];
        for (int w = 0; w < winCount; w++) if (round.winning[w] == v) return w;
        return -1;
    }

    /** Dashed, slowly flowing lines from each revealed prize cell up to its winning number. */
    private void drawLinks(GuiGraphics g, float pt) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 220);
        float flow = (ticks + pt) * 0.6F;
        for (int i = winCount; i < cellCount(); i++) {
            Draw d = drawAt(i);
            if (d == null || !isRevealed(i)) continue;
            int w = winningCellFor(i);
            if (w < 0 || !isRevealed(w)) continue;
            int[] a = cellRect(w), b = cellRect(i);
            float x0 = left + a[0] + a[2] / 2F, y0 = top + a[1] + a[3];
            float x1 = left + b[0] + b[2] / 2F, y1 = top + b[1];
            int color = 0xFF000000 | Tier.color(d.tier());
            float len = (float) Math.hypot(x1 - x0, y1 - y0);
            for (float s = 0; s < len; s += 2) {
                if (((s + flow) % 8) >= 5) continue; // dash pattern
                float f = s / len;
                int x = Math.round(x0 + (x1 - x0) * f), y = Math.round(y0 + (y1 - y0) * f);
                g.fill(x - 1, y - 1, x + 1, y + 1, color);
            }
            // matching winning number gets the prize colour too
            g.renderOutline(left + a[0] - 2, top + a[1] - 2, a[2] + 4, a[3] + 4, color);
        }
        g.pose().popPose();
    }

    private int stampCenterY() {
        int gridH = rows * cellH + (rows - 1) * cellGap;
        return top + GRID_Y + gridH / 2;
    }

    /** A rubber stamp ("中奖" red / "未中奖" grey) that slams onto the ticket when it is finished. */
    private void drawStamp(GuiGraphics g, float pt) {
        if (!finished) return;
        float t = ticks - finishedAt + pt - STAMP_DELAY;
        if (t <= 0) return;
        float fall = Mth.clamp(t / STAMP_FALL, 0, 1);
        float scale = 2.6F - 1.6F * fall * fall;
        boolean won = round.won();
        int color = won ? 0xD03030 : 0x8A8A8A;
        int alpha = (int) (Mth.clamp(fall * 1.5F, 0, 1) * (won ? 215 : 170));
        int argb = (alpha << 24) | color;
        int radius = Mth.clamp(Math.min(rows * cellH, panelW / 3) / 2 + 6, 22, 36);

        g.pose().pushPose();
        g.pose().translate(left + panelW / 2F, stampCenterY(), 260);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-14));
        g.pose().scale(scale, scale, 1);
        ring(g, radius, 3, argb);
        ring(g, radius - 6, 1, argb);
        Component text = Component.translatable(won ? "gui.lottery.stamp.won" : "gui.lottery.stamp.lost");
        float ts = Math.max(1, (radius - 8) * 2F / Math.max(1, font.width(text)));
        ts = Math.min(ts, 2.4F);
        g.pose().scale(ts, ts, 1);
        g.drawString(font, text, -font.width(text) / 2, -4, argb, false);
        g.pose().popPose();
    }

    /** A circle outline centred on the origin, built from one-pixel rows. */
    private static void ring(GuiGraphics g, int r, int thickness, int argb) {
        int inner = r - thickness;
        for (int dy = -r; dy < r; dy++) {
            float yc = dy + 0.5F;
            int xo = (int) Math.round(Math.sqrt(Math.max(0, r * r - yc * yc)));
            if (Math.abs(yc) < inner) {
                int xi = (int) Math.round(Math.sqrt(inner * inner - yc * yc));
                g.fill(-xo, dy, -xi, dy + 1, argb);
                g.fill(xi, dy, xo, dy + 1, argb);
            } else {
                g.fill(-xo, dy, xo, dy + 1, argb);
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button == 0 && !finished && !introPlaying()) {
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
                        if (rnd.nextInt(3) == 0) {
                            // flakes of the coating fly off the brush and fall
                            int c = coatColor(i, cx, cy) & 0xFFFFFF;
                            int life = 14 + rnd.nextInt(10);
                            particles.add(new Particle(left + r[0] + cx * PIX, top + r[1] + cy * PIX,
                                    rnd.nextGaussian() * 1.4, -0.5 - rnd.nextDouble() * 2.2, life, life, c, 1 + rnd.nextInt(3)));
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
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        titleBar(g, Component.translatable("gui.lottery.scratch.title", round.typeName));

        centered(g, Component.translatable("gui.lottery.scratch.winning_numbers"), top + WIN_Y - 10, 0xFF6A6050);
        if (finished) {
            centered(g, resultBanner(), top + GRID_Y - 12, round.won() ? 0xFFD08A00 : 0xFF707070);
        } else {
            centered(g, Component.translatable("gui.lottery.scratch.my_numbers"), top + GRID_Y - 12, 0xFF6A6050);
        }

        for (int i = 0; i < cellCount(); i++) drawCell(g, i, mouseX, mouseY);
        drawLinks(g, partialTick);
        drawStamp(g, partialTick);

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
