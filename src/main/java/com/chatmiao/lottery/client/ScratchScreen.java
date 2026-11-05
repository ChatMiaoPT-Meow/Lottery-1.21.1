package com.chatmiao.lottery.client;

import com.chatmiao.lottery.logic.Roller;
import com.chatmiao.lottery.logic.ScratchRound;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 刮奖界面. The result is already locked on the server when this opens; the client only draws it.
 * Click a cell to flip it, or hold the left button and drag to scratch the coating off.
 */
public class ScratchScreen extends Screen {
    private static final int W = 232, H = 234;
    private static final int PIX = 2;            // coating resolution in GUI pixels
    private static final int BRUSH = 5;          // scratch radius
    private static final float AUTO_REVEAL = 0.55F;

    // cell layout, relative to the panel
    private static final int WIN_W = 40, WIN_H = 24, WIN_GAP = 8, WIN_Y = 42;
    private static final int CELL_W = 64, CELL_H = 30, CELL_GAP = 6, GRID_X = 14, GRID_Y = 84;

    private final BlockPos pos;
    private ScratchRound round;
    private int stock;
    private boolean waiting;
    private boolean finished;
    private final boolean[] revealed = new boolean[ScratchRound.CELL_COUNT];
    private final boolean[][] cleared = new boolean[ScratchRound.CELL_COUNT][];
    private final int[] clearedCount = new int[ScratchRound.CELL_COUNT];

    private int left, top;
    private boolean dragging;
    private double pressX, pressY, lastX, lastY, dragDist;
    private long lastScratchSound;
    private final List<Particle> particles = new ArrayList<>();
    private final Random rnd = new Random();
    private int ticks;

    public ScratchScreen(Payloads.ScratchState state) {
        super(Component.translatable("gui.lottery.scratch"));
        this.pos = state.pos();
        applyState(state);
    }

    public BlockPos pos() {
        return pos;
    }

    public void setState(Payloads.ScratchState state) {
        applyState(state);
        rebuildWidgets();
    }

    private void applyState(Payloads.ScratchState state) {
        round = state.round();
        stock = state.stock();
        waiting = false;
        particles.clear();
        for (int i = 0; i < ScratchRound.CELL_COUNT; i++) {
            revealed[i] = (round.revealedMask & (1 << i)) != 0;
            int[] r = cellRect(i);
            cleared[i] = new boolean[(r[2] / PIX) * (r[3] / PIX)];
            clearedCount[i] = 0;
        }
        finished = allRevealed();
    }

    // ------------------------------------------------------------------ layout

    /** {x, y, w, h} relative to the panel. Cells 0..2 = winning numbers, 3..11 = my numbers. */
    private static int[] cellRect(int i) {
        if (i < Roller.WINNING_COUNT) {
            int total = Roller.WINNING_COUNT * WIN_W + (Roller.WINNING_COUNT - 1) * WIN_GAP;
            return new int[]{(W - total) / 2 + i * (WIN_W + WIN_GAP), WIN_Y, WIN_W, WIN_H};
        }
        int b = i - Roller.WINNING_COUNT;
        return new int[]{GRID_X + (b % 3) * (CELL_W + CELL_GAP), GRID_Y + (b / 3) * (CELL_H + CELL_GAP), CELL_W, CELL_H};
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        addRenderableWidget(Button.builder(Component.literal("×"), b -> onClose()).bounds(left + W - 16, top + 4, 12, 12).build());

        int by = top + H - 24;
        if (!finished) {
            addRenderableWidget(Button.builder(Component.translatable("gui.lottery.scratch.reveal_all"), b -> revealAll())
                    .bounds(left + W / 2 - 45, by, 90, 18).build());
            return;
        }
        Component doneText = round.won()
                ? Component.translatable("gui.lottery.scratch.collect")
                : Component.translatable("gui.lottery.scratch.thanks");
        Button done = Button.builder(doneText, b -> finish(false)).bounds(0, by, 100, 18).build();
        if (stock > 0) {
            Button again = Button.builder(Component.translatable("gui.lottery.scratch.again", stock), b -> finish(true))
                    .bounds(0, by, 100, 18).build();
            done.setX(left + W / 2 - 104);
            again.setX(left + W / 2 + 4);
            done.active = again.active = !waiting;
            addRenderableWidget(done);
            addRenderableWidget(again);
        } else {
            done.setX(left + W / 2 - 50);
            done.active = !waiting;
            addRenderableWidget(done);
        }
    }

    // ------------------------------------------------------------------ actions

    private void finish(boolean next) {
        if (waiting) return;
        waiting = true;
        PacketDistributor.sendToServer(new Payloads.Finish(pos, round.roundId, next));
        if (!next) {
            onClose();
        } else {
            rebuildWidgets();
        }
    }

    private void revealAll() {
        int mask = 0;
        for (int i = 0; i < ScratchRound.CELL_COUNT; i++) {
            if (!revealed[i]) {
                revealed[i] = true;
                mask |= 1 << i;
                burst(i, 6);
            }
        }
        if (mask != 0) {
            PacketDistributor.sendToServer(new Payloads.Reveal(pos, round.roundId, mask));
            play(SoundEvents.BOOK_PAGE_TURN, 1.0F, 1.0F);
        }
        checkFinished();
    }

    private void reveal(int i) {
        if (revealed[i]) return;
        revealed[i] = true;
        burst(i, 10);
        play(SoundEvents.BOOK_PAGE_TURN, 1.2F, 0.8F);
        PacketDistributor.sendToServer(new Payloads.Reveal(pos, round.roundId, 1 << i));
        checkFinished();
    }

    private void checkFinished() {
        if (finished || !allRevealed()) return;
        finished = true;
        if (round.won()) {
            play(SoundEvents.PLAYER_LEVELUP, 1.0F, 1.0F);
            int hit = Roller.WINNING_COUNT + round.hitCell;
            burst(hit, 40);
        } else {
            play(SoundEvents.VILLAGER_NO, 1.0F, 0.6F);
        }
        rebuildWidgets();
    }

    private boolean allRevealed() {
        for (boolean b : revealed) if (!b) return false;
        return true;
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
        for (int i = 0; i < ScratchRound.CELL_COUNT; i++) {
            int[] r = cellRect(i);
            if (mx >= left + r[0] && mx < left + r[0] + r[2] && my >= top + r[1] && my < top + r[1] + r[3]) return i;
        }
        return -1;
    }

    private void scratchAt(double mx, double my) {
        boolean any = false;
        for (int i = 0; i < ScratchRound.CELL_COUNT; i++) {
            if (revealed[i]) continue;
            int[] r = cellRect(i);
            double lx = mx - (left + r[0]), ly = my - (top + r[1]);
            if (lx < -BRUSH || ly < -BRUSH || lx > r[2] + BRUSH || ly > r[3] + BRUSH) continue;
            int cols = r[2] / PIX, rows = r[3] / PIX;
            int cx0 = Math.max(0, (int) ((lx - BRUSH) / PIX)), cx1 = Math.min(cols - 1, (int) ((lx + BRUSH) / PIX));
            int cy0 = Math.max(0, (int) ((ly - BRUSH) / PIX)), cy1 = Math.min(rows - 1, (int) ((ly + BRUSH) / PIX));
            for (int cy = cy0; cy <= cy1; cy++) {
                for (int cx = cx0; cx <= cx1; cx++) {
                    double ddx = cx * PIX + PIX / 2.0 - lx, ddy = cy * PIX + PIX / 2.0 - ly;
                    if (ddx * ddx + ddy * ddy > BRUSH * BRUSH) continue;
                    int idx = cy * cols + cx;
                    if (!cleared[i][idx]) {
                        cleared[i][idx] = true;
                        clearedCount[i]++;
                        any = true;
                        if (rnd.nextInt(6) == 0) spawnDust(left + r[0] + cx * PIX, top + r[1] + cy * PIX);
                    }
                }
            }
            if ((float) clearedCount[i] / cleared[i].length >= AUTO_REVEAL) reveal(i);
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
        // ticket body
        g.fill(left - 2, top - 2, left + W + 2, top + H + 2, dark);
        g.fill(left, top, left + W, top + H, 0xFFFBF5E4);
        // header band
        g.fill(left, top, left + W, top + 30, color);
        // perforation dots
        for (int x = left + 4; x < left + W - 2; x += 6) {
            g.fill(x, top + 31, x + 2, top + 32, dark);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        // header text
        g.drawString(font, Component.translatable("gui.lottery.scratch.title", round.typeName), left + 8, top + 6, 0xFFFFFFFF, true);
        g.drawString(font, Component.translatable("gui.lottery.scratch.sub", round.typeId, stock), left + 8, top + 18, 0xFFF0F0F0, false);

        drawLabel(g, Component.translatable("gui.lottery.scratch.winning_numbers"), top + WIN_Y - 10);
        if (finished) {
            Component banner = round.won()
                    ? Component.translatable("gui.lottery.scratch.won", round.prize.describe())
                    : Component.translatable("gui.lottery.scratch.lost");
            int c = round.won() ? 0xFFD08A00 : 0xFF707070;
            centered(g, banner, top + GRID_Y - 12, c);
        } else {
            drawLabel(g, Component.translatable("gui.lottery.scratch.my_numbers"), top + GRID_Y - 12);
        }

        for (int i = 0; i < ScratchRound.CELL_COUNT; i++) drawCell(g, i, mouseX, mouseY);

        for (Particle p : particles) {
            int a = Mth.clamp((int) (255 * p.life / (float) p.maxLife), 0, 255);
            int x = (int) p.x, y = (int) p.y;
            g.fill(x, y, x + p.size, y + p.size, (a << 24) | (p.color & 0xFFFFFF));
        }

        int noteY = top + GRID_Y + 3 * CELL_H + 2 * CELL_GAP + 5;
        if (waiting) {
            centered(g, Component.translatable("gui.lottery.scratch.loading"), noteY, 0xFF8A8070);
        } else if (!finished) {
            centered(g, Component.translatable("gui.lottery.scratch.hint"), noteY, 0xFF8A8070);
        }

        // tooltip for the prize icon
        if (round.won() && revealed[Roller.WINNING_COUNT + round.hitCell]) {
            int[] r = cellRect(Roller.WINNING_COUNT + round.hitCell);
            int ix = left + r[0] + r[2] - 22, iy = top + r[1] + (r[3] - 16) / 2;
            if (mouseX >= ix && mouseX < ix + 16 && mouseY >= iy && mouseY < iy + 16) {
                g.renderTooltip(font, round.prize.item(), mouseX, mouseY);
            }
        }
    }

    private void drawLabel(GuiGraphics g, Component text, int y) {
        centered(g, text, y, 0xFF6A6050);
    }

    /** Centered text without the drop shadow (shadows look muddy on the light ticket paper). */
    private void centered(GuiGraphics g, Component text, int y, int color) {
        g.drawString(font, text, left + (W - font.width(text)) / 2, y, color, false);
    }

    private void drawCell(GuiGraphics g, int i, int mouseX, int mouseY) {
        int[] r = cellRect(i);
        int x = left + r[0], y = top + r[1], w = r[2], h = r[3];
        boolean winningCell = i < Roller.WINNING_COUNT;
        boolean hit = !winningCell && round.won() && i - Roller.WINNING_COUNT == round.hitCell && revealed[i];

        int border = winningCell ? 0xFFC9A44A : 0xFFB0A890;
        int bg = winningCell ? 0xFFFFF1CC : 0xFFFFFFFF;
        if (hit) {
            float pulse = (Mth.sin(System.currentTimeMillis() / 150.0F) + 1) / 2;
            border = pulse > 0.5F ? 0xFFFFB300 : 0xFFFF7A00;
            bg = 0xFFFFF0A8;
        }
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, border);
        g.fill(x, y, x + w, y + h, bg);

        // content under the coating
        int number = winningCell ? round.winning[i] : round.board[i - Roller.WINNING_COUNT];
        String text = String.valueOf(number);
        int textColor = winningCell ? 0xFFB03020 : (hit ? 0xFFB03020 : 0xFF303030);
        float scale = 1.5F;
        int tw = (int) (font.width(text) * scale);
        int contentW = hit ? w - 24 : w;
        g.pose().pushPose();
        g.pose().translate(x + (contentW - tw) / 2.0F, y + (h - 8 * scale) / 2.0F + 1, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, 0, 0, textColor, false);
        g.pose().popPose();
        if (hit) {
            int ix = x + w - 22, iy = y + (h - 16) / 2;
            g.renderItem(round.prize.item(), ix, iy);
            g.renderItemDecorations(font, round.prize.item(), ix, iy, String.valueOf(round.prize.count()));
        }

        if (revealed[i]) return;

        // coating
        int cols = w / PIX, rows = h / PIX;
        boolean[] mask = cleared[i];
        g.pose().pushPose();
        g.pose().translate(0, 0, 200); // above item icons
        for (int cy = 0; cy < rows; cy++) {
            int runStart = -1, runColor = 0;
            for (int cx = 0; cx <= cols; cx++) {
                boolean covered = cx < cols && !mask[cy * cols + cx];
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

    // ------------------------------------------------------------------ particles

    private record Particle(double x, double y, double vx, double vy, int life, int maxLife, int color, int size) {
        Particle step() {
            return new Particle(x + vx, y + vy, vx * 0.95, vy + 0.35, life - 1, maxLife, color, size);
        }
    }

    private void spawnDust(double x, double y) {
        particles.add(new Particle(x, y, rnd.nextGaussian() * 0.8, -rnd.nextDouble() * 1.5, 12, 12, 0xC1C1CA, 1 + rnd.nextInt(2)));
    }

    private void burst(int cell, int n) {
        int[] r = cellRect(cell);
        boolean gold = round.won() && cell == Roller.WINNING_COUNT + round.hitCell;
        for (int k = 0; k < n; k++) {
            double x = left + r[0] + rnd.nextDouble() * r[2], y = top + r[1] + rnd.nextDouble() * r[3];
            int color = gold ? (rnd.nextBoolean() ? 0xFFC83A : 0xFF8A00) : 0xB8B8C2;
            particles.add(new Particle(x, y, rnd.nextGaussian() * 1.5, -1 - rnd.nextDouble() * 2.5, 18, 18, color, 1 + rnd.nextInt(2)));
        }
    }

    @Override
    public void tick() {
        ticks++;
        List<Particle> next = new ArrayList<>(particles.size());
        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle p = it.next().step();
            if (p.life > 0) next.add(p);
        }
        particles.clear();
        particles.addAll(next);
    }

    private void play(SoundEvent sound, float pitch, float volume) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
