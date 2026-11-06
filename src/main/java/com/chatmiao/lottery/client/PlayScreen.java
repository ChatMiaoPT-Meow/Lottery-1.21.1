package com.chatmiao.lottery.client;

import com.chatmiao.lottery.data.Draw;
import com.chatmiao.lottery.data.Tier;
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
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Shared part of the three ticket GUIs (对号刮奖 / 开箱转盘 / 卡牌抽卡). Every result is already locked on the
 * server when the screen opens; subclasses only animate the reveal. Reveal progress is reported back so a
 * reopened screen continues where it left off.
 * <p>
 * Common to all styles: the same title bar, an intro pop when a ticket appears, and — for winning tickets — a
 * prize summary that pops up over the ticket once everything is revealed (it can be hidden to look at the ticket).
 */
public abstract class PlayScreen extends Screen {
    private static final int INTRO_TICKS = 7;
    private static final int TILE = 26;

    protected final BlockPos pos;
    protected ScratchRound round;
    protected int stock;
    protected boolean waiting;
    protected boolean finished;
    /** Local reveal bits (see {@link ScratchRound#revealedMask}). */
    protected long revealed;

    protected int panelW = 232, panelH = 234;
    protected int left, top;
    protected int ticks;
    protected final List<Particle> particles = new ArrayList<>();
    protected final Random rnd = new Random();

    /** Tick the current ticket appeared (intro animation). */
    protected int introStart;
    /** Tick the ticket was finished; far in the past when it was already finished on open. */
    protected int finishedAt;
    private int summaryAt = -1;
    private int summaryShownAt = -1;
    private boolean summaryHidden;
    private boolean builtWithSummary;
    private int summaryTilesSounded;
    private int sx, sy, sw, sh, sCols;
    private final List<Button> overlayButtons = new ArrayList<>();

    protected PlayScreen(Payloads.ScratchState state) {
        super(Component.translatable("gui.lottery.scratch"));
        this.pos = state.pos();
        applyState(state);
    }

    /** Picks the GUI for the round's play style. */
    public static PlayScreen create(Payloads.ScratchState state) {
        return switch (state.round().playStyle()) {
            case MATCH -> new ScratchScreen(state);
            case CASE -> new CaseScreen(state);
            case CARDS -> new CardScreen(state);
        };
    }

    public BlockPos pos() {
        return pos;
    }

    /** True if this screen can show {@code state} in place (same table and same play style). */
    public boolean accepts(Payloads.ScratchState state) {
        return pos.equals(state.pos()) && state.round() != null && state.round().style == round.style;
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
        revealed = round.revealedMask & round.allMask();
        introStart = ticks;
        summaryAt = -1;
        summaryShownAt = -1;
        summaryHidden = false;
        summaryTilesSounded = 0;
        onNewRound();
        finished = allRevealed();
        finishedAt = -10_000;
        if (finished && round.won()) summaryAt = ticks; // reopened a finished ticket: summary right away
    }

    /** Reset per-ticket client state (called before layout is known; {@link #width} may still be 0). */
    protected abstract void onNewRound();

    /** Compute {@link #panelW}/{@link #panelH} and anything else that depends on the screen size. */
    protected abstract void layout();

    /** Buttons shown while the ticket is not finished, placed at {@code y} (bottom row). */
    protected abstract void addPlayButtons(int y);

    /** Ticket content (drawn after the background and the widgets, inside the intro transform). */
    protected abstract void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick);

    /** Ticks between finishing a winning ticket and the summary popping up (room for the style's own finale). */
    protected int summaryDelay() {
        return 20;
    }

    protected boolean isRevealed(int bit) {
        return (revealed & (1L << bit)) != 0;
    }

    protected boolean allRevealed() {
        long all = round.allMask();
        return (revealed & all) == all;
    }

    /** True while the intro pop is playing (input is ignored). */
    protected boolean introPlaying() {
        return ticks - introStart < INTRO_TICKS;
    }

    /** Marks bits revealed, tells the server, and finishes the ticket when everything is open. */
    protected void markRevealed(long mask) {
        long fresh = mask & ~revealed & round.allMask();
        if (fresh == 0) return;
        revealed |= fresh;
        PacketDistributor.sendToServer(new Payloads.Reveal(pos, round.roundId, fresh));
        checkFinished();
    }

    protected void checkFinished() {
        if (finished || !allRevealed()) return;
        finished = true;
        finishedAt = ticks;
        if (round.won()) summaryAt = ticks + summaryDelay();
        onFinished();
        rebuildWidgets();
    }

    protected void onFinished() {
        if (round.won()) {
            play(SoundEvents.PLAYER_LEVELUP, 1.0F, 1.0F);
        } else {
            play(SoundEvents.VILLAGER_NO, 1.0F, 0.6F);
        }
    }

    protected boolean summaryVisible() {
        return finished && round.won() && summaryAt >= 0 && ticks >= summaryAt && !summaryHidden;
    }

    // ------------------------------------------------------------------ widgets

    @Override
    protected void init() {
        layout();
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        overlayButtons.clear();
        builtWithSummary = summaryVisible();
        if (builtWithSummary) {
            if (summaryShownAt < 0) summaryShownAt = ticks;
            initSummary();
            return;
        }
        addRenderableWidget(Button.builder(Component.literal("×"), b -> onClose()).bounds(left + panelW - 16, top + 4, 12, 12).build());
        int by = top + panelH - 24;
        if (!finished) {
            addPlayButtons(by);
            return;
        }
        if (round.won() && summaryAt >= 0 && ticks >= summaryAt) {
            addRenderableWidget(Button.builder(Component.translatable("gui.lottery.summary.show"), b -> {
                summaryHidden = false;
                summaryShownAt = ticks;
                summaryTilesSounded = 0;
                rebuildWidgets();
            }).bounds(left + panelW - 70, top + 4, 52, 12).build());
        }
        for (Button b : resultButtons(left + panelW / 2, by)) addRenderableWidget(b);
    }

    /** "收下彩票 / 谢谢参与" (+ "再来一张") centred on {@code cx}. */
    private List<Button> resultButtons(int cx, int y) {
        Component doneText = round.won()
                ? Component.translatable("gui.lottery.scratch.collect")
                : Component.translatable("gui.lottery.scratch.thanks");
        List<Button> out = new ArrayList<>();
        Button done = Button.builder(doneText, b -> finish(false)).bounds(0, y, 100, 18).build();
        done.active = !waiting;
        out.add(done);
        if (stock > 0) {
            Button again = Button.builder(Component.translatable("gui.lottery.scratch.again", stock), b -> finish(true))
                    .bounds(cx + 4, y, 100, 18).build();
            again.active = !waiting;
            done.setX(cx - 104);
            out.add(again);
        } else {
            done.setX(cx - 50);
        }
        return out;
    }

    protected Button button(Component text, int x, int y, int w, Button.OnPress press) {
        return addRenderableWidget(Button.builder(text, press).bounds(x, y, w, 18).build());
    }

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

    // ------------------------------------------------------------------ summary overlay

    private List<Draw> sortedWins() {
        List<Draw> wins = new ArrayList<>();
        for (Draw d : round.draws) if (d.won()) wins.add(d);
        wins.sort(Comparator.comparingInt(Draw::tier).reversed());
        return wins;
    }

    private void initSummary() {
        int n = sortedWins().size();
        sCols = Math.min(5, Math.max(1, n));
        int rows = (n + sCols - 1) / sCols;
        sw = Math.max(230, sCols * (TILE + 6) + 30);
        sh = 58 + rows * (TILE + 6) + 34;
        sx = (width - sw) / 2;
        sy = (height - sh) / 2;
        List<Button> buttons = resultButtons(sx + sw / 2, sy + sh - 26);
        Button view = Button.builder(Component.translatable("gui.lottery.summary.view"), b -> {
            summaryHidden = true;
            rebuildWidgets();
        }).bounds(sx + sw - 58, sy + 5, 52, 12).build();
        buttons.add(view);
        for (Button b : buttons) {
            overlayButtons.add(b);
            addWidget(b); // input only; drawn above the overlay in render()
        }
    }

    private void renderSummary(GuiGraphics g, int mouseX, int mouseY, float pt) {
        float t = ticks - summaryShownAt + pt;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        int dim = (int) (Mth.clamp(t / 5F, 0, 1) * 0xA0);
        g.fill(0, 0, width, height, dim << 24);

        float k = popScale(t / 6F);
        int cx = sx + sw / 2, cy = sy + sh / 2;
        g.pose().translate(cx, cy, 0);
        g.pose().scale(k, k, 1);
        g.pose().translate(-cx, -cy, 0);

        List<Draw> wins = sortedWins();
        int best = wins.isEmpty() ? 0 : wins.get(0).tier();
        int bestColor = 0xFF000000 | Tier.color(best);
        g.fill(sx - 2, sy - 2, sx + sw + 2, sy + sh + 2, bestColor);
        g.fill(sx, sy, sx + sw, sy + sh, 0xFF1A1C22);
        g.fill(sx, sy, sx + sw, sy + 22, 0xFF000000 | TicketColors.of(round.typeId));
        g.drawString(font, Component.translatable("gui.lottery.summary.title", round.typeName), sx + 8, sy + 7, 0xFFFFFFFF, true);

        Component headline = Component.translatable("gui.lottery.summary.headline");
        g.pose().pushPose();
        float hs = 1.5F;
        g.pose().translate(cx - font.width(headline) * hs / 2, sy + 28, 0);
        g.pose().scale(hs, hs, 1);
        g.drawString(font, headline, 0, 0, 0xFFFFC94A, true);
        g.pose().popPose();
        Component sub = Component.translatable("gui.lottery.summary.sub", wins.size(), Tier.name(best));
        g.drawString(font, sub, cx - font.width(sub) / 2, sy + 44, 0xFFB0B4BC, false);

        int rows = (wins.size() + sCols - 1) / sCols;
        Draw hovered = null;
        for (int i = 0; i < wins.size(); i++) {
            float appear = (t - 6 - i * 3) / 4F;
            if (appear <= 0) continue;
            int row = i / sCols, col = i % sCols;
            int inRow = Math.min(sCols, wins.size() - row * sCols);
            int x = cx - (inRow * (TILE + 6) - 6) / 2 + col * (TILE + 6);
            int y = sy + 58 + row * (TILE + 6);
            Draw d = wins.get(i);
            int tc = 0xFF000000 | Tier.color(d.tier());
            float s = popScale(appear);
            g.pose().pushPose();
            g.pose().translate(x + TILE / 2F, y + TILE / 2F, 0);
            g.pose().scale(s, s, 1);
            g.pose().translate(-TILE / 2F, -TILE / 2F, 0);
            g.fill(-1, -1, TILE + 1, TILE + 1, tc);
            g.fill(0, 0, TILE, TILE, 0xFF2A2E36);
            g.fill(0, TILE - 3, TILE, TILE, tc);
            g.renderItem(d.prize().item(), (TILE - 16) / 2, (TILE - 16) / 2 - 1);
            g.renderItemDecorations(font, d.prize().item(), (TILE - 16) / 2, (TILE - 16) / 2 - 1,
                    d.prize().count() > 1 ? String.valueOf(d.prize().count()) : null);
            g.pose().popPose();
            if (mouseX >= x && mouseX < x + TILE && mouseY >= y && mouseY < y + TILE) hovered = d;
        }
        g.pose().popPose();

        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        for (Button b : overlayButtons) b.render(g, mouseX, mouseY, pt);
        if (waiting) {
            Component w = Component.translatable("gui.lottery.scratch.loading");
            g.drawString(font, w, cx - font.width(w) / 2, sy + 58 + rows * (TILE + 6), 0xFF9AA0AA, false);
        }
        if (hovered != null) g.renderTooltip(font, hovered.prize().item(), mouseX, mouseY);
        g.pose().popPose();
    }

    /** 0 → 1 with a small overshoot (ease-out-back), clamped. */
    protected static float popScale(float t) {
        if (t >= 1) return 1;
        if (t <= 0) return 0;
        float c1 = 1.70158F, c3 = c1 + 1;
        float u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    // ------------------------------------------------------------------ render / input

    @Override
    public final void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean overlay = summaryVisible() && builtWithSummary;
        int mx = overlay ? -10_000 : mouseX, my = overlay ? -10_000 : mouseY;
        float t = (ticks - introStart + partialTick) / INTRO_TICKS;
        float k = t >= 1 ? 1 : 0.82F + 0.18F * popScale(t);
        g.pose().pushPose();
        if (k != 1) {
            float cx = left + panelW / 2F, cy = top + panelH / 2F;
            g.pose().translate(cx, cy, 0);
            g.pose().scale(k, k, 1);
            g.pose().translate(-cx, -cy, 0);
        }
        super.render(g, mx, my, partialTick);
        renderContent(g, mx, my, partialTick);
        renderParticles(g);
        g.pose().popPose();
        if (overlay) renderSummary(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (introPlaying() && !builtWithSummary) return true;
        boolean handled = super.mouseClicked(mx, my, button);
        return handled || builtWithSummary; // the overlay swallows clicks on the ticket below
    }

    // ------------------------------------------------------------------ drawing helpers

    /** The shared title bar: type colour band, title and "编号 / 剩余". */
    protected void titleBar(GuiGraphics g, Component title) {
        g.drawString(font, title, left + 8, top + 6, 0xFFFFFFFF, true);
        g.drawString(font, Component.translatable("gui.lottery.scratch.sub", round.typeId, stock), left + 8, top + 18, 0xFFF0F0F0, false);
    }

    /** Centered text without the drop shadow. */
    protected void centered(GuiGraphics g, Component text, int y, int color) {
        g.drawString(font, text, left + (panelW - font.width(text)) / 2, y, color, false);
    }

    protected static void frame(GuiGraphics g, int x, int y, int w, int h, int border, int fill) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, border);
        g.fill(x, y, x + w, y + h, fill);
    }

    /** The summary line shown once the ticket is finished. */
    protected Component resultBanner() {
        if (!round.won()) return Component.translatable("gui.lottery.scratch.lost");
        var prizes = round.prizes();
        return prizes.size() == 1
                ? Component.translatable("gui.lottery.scratch.won", prizes.get(0).describe())
                : Component.translatable("gui.lottery.scratch.won_many", prizes.size());
    }

    /** A vertical beam of light in {@code color}, brightest at the bottom. */
    protected static void lightPillar(GuiGraphics g, int x, int y0, int y1, int w, int color, float strength) {
        int a = Mth.clamp((int) (strength * 200), 0, 255);
        int c = color & 0xFFFFFF;
        g.fillGradient(x, y0, x + w, y1, c, (a << 24) | c);
        int core = Math.max(2, w / 4);
        int ca = Mth.clamp((int) (strength * 255), 0, 255);
        g.fillGradient(x + (w - core) / 2, y0, x + (w + core) / 2, y1, 0xFFFFFF, (ca << 24) | 0xFFFFFF);
    }

    // ------------------------------------------------------------------ particles

    protected record Particle(double x, double y, double vx, double vy, int life, int maxLife, int color, int size) {
        Particle step() {
            return new Particle(x + vx, y + vy, vx * 0.95, vy + 0.35, life - 1, maxLife, color, size);
        }
    }

    protected void burstAt(double x, double y, double w, double h, int n, int color) {
        for (int k = 0; k < n; k++) {
            double px = x + rnd.nextDouble() * w, py = y + rnd.nextDouble() * h;
            int c = rnd.nextInt(3) == 0 ? 0xFFFFFF : color;
            particles.add(new Particle(px, py, rnd.nextGaussian() * 1.5, -1 - rnd.nextDouble() * 2.5, 18, 18, c, 1 + rnd.nextInt(2)));
        }
    }

    protected void renderParticles(GuiGraphics g) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        for (Particle p : particles) {
            int a = Mth.clamp((int) (255 * p.life / (float) p.maxLife), 0, 255);
            int x = (int) p.x, y = (int) p.y;
            g.fill(x, y, x + p.size, y + p.size, (a << 24) | (p.color & 0xFFFFFF));
        }
        g.pose().popPose();
    }

    @Override
    public void tick() {
        ticks++;
        List<Particle> next = new ArrayList<>(particles.size());
        for (Particle particle : particles) {
            Particle p = particle.step();
            if (p.life > 0) next.add(p);
        }
        particles.clear();
        particles.addAll(next);
        if (summaryVisible() != builtWithSummary) rebuildWidgets();
        if (builtWithSummary) {
            // a soft chime for every prize tile that pops in, pitched by rarity
            List<Draw> wins = sortedWins();
            int shown = Mth.clamp((ticks - summaryShownAt - 6) / 3 + 1, 0, wins.size());
            while (summaryTilesSounded < shown) {
                int tier = wins.get(summaryTilesSounded).tier();
                play(SoundEvents.NOTE_BLOCK_PLING, 0.9F + tier * 0.15F, 0.5F);
                summaryTilesSounded++;
            }
        }
    }

    protected void play(SoundEvent sound, float pitch, float volume) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    protected void play(net.minecraft.core.Holder<SoundEvent> sound, float pitch, float volume) {
        play(sound.value(), pitch, volume);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
