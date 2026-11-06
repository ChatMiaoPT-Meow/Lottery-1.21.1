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
    private static final int TILE = 26, WALL_W = 30, WALL_H = 66;

    /**
     * Where the round comes from: a scratch table (pos + tickets left on it) or a gacha pull
     * (prizes direct or as a winning ticket, and what "再抽一次" costs).
     */
    public record Source(BlockPos pos, int stock, boolean hub, boolean direct, int voucherId, int againCost, boolean canAgain,
                         long newMask) {
        public static Source of(Payloads.ScratchState s) {
            return new Source(s.pos(), s.stock(), false, false, 0, 0, false, 0);
        }

        public static Source of(Payloads.HubState s) {
            return new Source(BlockPos.ZERO, 0, true, s.direct(), s.voucherId(), s.againCost(), s.canAgain(), s.newMask());
        }

        /** The draw at {@code index} is a first-time item for this player. */
        public boolean isNew(int index) {
            return index >= 0 && index < 64 && (newMask & (1L << index)) != 0;
        }
    }

    protected final BlockPos pos;
    protected Source source;
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
    /** The full-screen mythic showcase already ran for this ticket. */
    private boolean mythicPlayed;
    private int sx, sy, sw, sh, sCols;
    private final List<Button> overlayButtons = new ArrayList<>();

    protected PlayScreen(Source source, ScratchRound round) {
        super(Component.translatable("gui.lottery.scratch"));
        this.pos = source.pos();
        applyState(source, round);
    }

    /** Picks the GUI for the round's play style. */
    public static PlayScreen create(Payloads.ScratchState state) {
        return create(Source.of(state), state.round());
    }

    public static PlayScreen create(Payloads.HubState state) {
        return create(Source.of(state), state.round());
    }

    private static PlayScreen create(Source source, ScratchRound round) {
        return switch (round.playStyle()) {
            case MATCH -> new ScratchScreen(source, round);
            case CASE -> new CaseScreen(source, round);
            case CARDS -> new CardScreen(source, round);
        };
    }

    public BlockPos pos() {
        return pos;
    }

    public boolean isHub() {
        return source.hub();
    }

    /** True if this screen can show {@code state} in place (same table and same play style). */
    public boolean accepts(Payloads.ScratchState state) {
        return !source.hub() && pos.equals(state.pos()) && state.round() != null && state.round().style == round.style;
    }

    public boolean accepts(Payloads.HubState state) {
        return source.hub() && state.round().style == round.style;
    }

    public void setState(Payloads.ScratchState state) {
        applyState(Source.of(state), state.round());
        rebuildWidgets();
    }

    public void setState(Payloads.HubState state) {
        applyState(Source.of(state), state.round());
        rebuildWidgets();
    }

    private void applyState(Source source, ScratchRound round) {
        this.source = source;
        this.round = round;
        stock = source.stock();
        waiting = false;
        particles.clear();
        revealed = round.revealedMask & round.allMask();
        introStart = ticks;
        summaryAt = -1;
        summaryShownAt = -1;
        summaryHidden = false;
        summaryTilesSounded = 0;
        mythicPlayed = false;
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

    /** Ticks after finishing before the mythic showcase (lets the style's own reveal land first). */
    protected int mythicDelay() {
        return 14;
    }

    private List<Draw> mythics() {
        List<Draw> out = new ArrayList<>();
        for (Draw d : round.draws) if (d.won() && d.tier() >= MythicScreen.MYTHIC) out.add(d);
        return out;
    }

    /** Back from the mythic showcase: show the result summary right away. */
    void afterMythic() {
        if (finished && round.won()) {
            summaryAt = ticks;
            summaryShownAt = -1;
            summaryHidden = false;
            summaryTilesSounded = 0;
        }
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
        PacketDistributor.sendToServer(source.hub()
                ? new Payloads.HubReveal(round.roundId, fresh)
                : new Payloads.Reveal(pos, round.roundId, fresh));
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
        Component doneText = !round.won() ? Component.translatable("gui.lottery.scratch.thanks")
                : source.hub() && source.direct() ? Component.translatable("gui.lottery.hub.collect")
                : Component.translatable("gui.lottery.scratch.collect");
        List<Button> out = new ArrayList<>();
        Button done = Button.builder(doneText, b -> finish(false)).bounds(0, y, 100, 18).build();
        done.active = !waiting;
        out.add(done);
        boolean offerAgain = source.hub() ? source.voucherId() > 0 : stock > 0;
        if (offerAgain) {
            Component againText = source.hub()
                    ? Component.translatable("gui.lottery.hub.again", source.againCost())
                    : Component.translatable("gui.lottery.scratch.again", stock);
            Button again = Button.builder(againText, b -> finish(true)).bounds(cx + 4, y, 100, 18).build();
            again.active = !waiting && (!source.hub() || source.canAgain());
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
        if (source.hub()) {
            // the server answers with the next pull or the pool list, which replaces this screen
            PacketDistributor.sendToServer(new Payloads.HubFinish(round.roundId, next));
            rebuildWidgets();
            return;
        }
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

    /** Gacha multi-pulls show every draw as a tall card (展示墙) instead of prize tiles. */
    private boolean wallMode() {
        return source.hub() && round.draws.size() > 1;
    }

    /** Draw indices to show: prizes by rarity (rarest first); in wall mode the empty draws follow. */
    private List<Integer> order() {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < round.draws.size(); i++) if (round.draws.get(i).won() || wallMode()) idx.add(i);
        idx.sort((a, b) -> Integer.compare(round.draws.get(b).tier(), round.draws.get(a).tier()));
        return idx;
    }

    private int cellW() {
        return wallMode() ? WALL_W : TILE;
    }

    private int cellH() {
        return wallMode() ? WALL_H : TILE;
    }

    private void initSummary() {
        int n = order().size();
        int gap = wallMode() ? 4 : 6;
        sCols = wallMode() ? n : Math.min(5, Math.max(1, n));
        if (wallMode() && n * (WALL_W + gap) + 30 > width - 20) sCols = (n + 1) / 2; // two rows on narrow screens
        int rows = (n + sCols - 1) / sCols;
        sw = Math.max(230, sCols * (cellW() + gap) + 30);
        sh = 58 + rows * (cellH() + 6) + 34;
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

        List<Integer> order = order();
        boolean wall = wallMode();
        int gap = wall ? 4 : 6, w = cellW(), h = cellH();
        int rows = (order.size() + sCols - 1) / sCols;
        Draw hovered = null;
        for (int i = 0; i < order.size(); i++) {
            float appear = (t - 6 - i * 3) / (wall ? 5F : 4F);
            if (appear <= 0) continue;
            int row = i / sCols, col = i % sCols;
            int inRow = Math.min(sCols, order.size() - row * sCols);
            int x = cx - (inRow * (w + gap) - gap) / 2 + col * (w + gap);
            int y = sy + 58 + row * (h + 6);
            int index = order.get(i);
            Draw d = round.draws.get(index);
            float s = popScale(appear);
            g.pose().pushPose();
            if (wall) {
                // cards slide in from the right while growing
                float slide = (1 - Mth.clamp(appear, 0, 1)) * 24;
                g.pose().translate(x + slide + w / 2F, y + h / 2F, 0);
            } else {
                g.pose().translate(x + w / 2F, y + h / 2F, 0);
            }
            g.pose().scale(s, s, 1);
            g.pose().translate(-w / 2F, -h / 2F, 0);
            if (wall) {
                drawWallCard(g, d, w, h, t - 6 - i * 3, source.isNew(index));
            } else {
                int tc = 0xFF000000 | Tier.color(d.tier());
                g.fill(-1, -1, w + 1, h + 1, tc);
                g.fill(0, 0, w, h, 0xFF2A2E36);
                g.fill(0, h - 3, w, h, tc);
                g.renderItem(d.prize().item(), (w - 16) / 2, (h - 16) / 2 - 1);
                g.renderItemDecorations(font, d.prize().item(), (w - 16) / 2, (h - 16) / 2 - 1,
                        d.prize().count() > 1 ? String.valueOf(d.prize().count()) : null);
                if (source.isNew(index)) newBadge(g, w);
            }
            g.pose().popPose();
            if (d.won() && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) hovered = d;
        }
        g.pose().popPose();

        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        for (Button b : overlayButtons) b.render(g, mouseX, mouseY, pt);
        if (waiting) {
            Component wt = Component.translatable("gui.lottery.scratch.loading");
            g.drawString(font, wt, cx - font.width(wt) / 2, sy + 58 + rows * (h + 6), 0xFF9AA0AA, false);
        }
        if (hovered != null) g.renderTooltip(font, hovered.prize().item(), mouseX, mouseY);
        g.pose().popPose();
    }

    /**
     * One card of the result wall, drawn at the origin: rarity-coloured glow rising from the bottom, the item,
     * the amount and rarity pips; epic+ cards get a light pillar, legendary+ a pulsing halo. Empty draws are dim.
     */
    private void drawWallCard(GuiGraphics g, Draw d, int w, int h, float age, boolean isNew) {
        if (!d.won()) {
            g.fill(0, 0, w, h, 0xFF3A3E46);
            g.fill(1, 1, w - 1, h - 1, 0xFF202228);
            g.drawString(font, "—", (w - font.width("—")) / 2, h / 2 - 4, 0xFF5A5E66, false);
            return;
        }
        int tier = d.tier();
        int c = Tier.color(tier);
        float pulse = 0.5F + 0.5F * Mth.sin((ticks + age) * 0.25F);
        if (tier >= 4) {
            int a = (int) (70 + 110 * pulse);
            g.fill(-3, -3, w + 3, h + 3, (a << 24) | c);
        }
        g.fill(0, 0, w, h, 0xFF000000 | c);
        g.fillGradient(1, 1, w - 1, h - 1, 0xFF15171C, (0xD0 << 24) | (c & 0xFFFFFF));
        if (tier >= 3) lightPillar(g, 2, 1, h - 12, w - 4, c, 0.35F + 0.25F * pulse);
        g.fill(1, 1, w - 1, 3, 0xFF000000 | c);
        // the item, larger when the card is wide enough
        float scale = w >= 28 ? 1.5F : 1.0F;
        int icon = (int) (16 * scale);
        g.pose().pushPose();
        g.pose().translate((w - icon) / 2F, h / 2F - icon / 2F - 6, 0);
        g.pose().scale(scale, scale, 1);
        g.renderItem(d.prize().item(), 0, 0);
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        String amount = "×" + d.prize().count();
        g.drawString(font, amount, (w - font.width(amount)) / 2, h - 20, 0xFFFFFFFF, true);
        // rarity pips (one per tier step)
        int pips = tier + 1, pw = pips * 3 - 1;
        for (int k = 0; k < pips; k++) g.fill((w - pw) / 2 + k * 3, h - 8, (w - pw) / 2 + k * 3 + 2, h - 6, 0xFFFFFFFF);
        if (isNew) newBadge(g, w);
        g.pose().popPose();
    }

    /** "NEW" tag in the top-right corner of a {@code w}-wide cell drawn at the origin. */
    private void newBadge(GuiGraphics g, int w) {
        String s = Component.translatable("gui.lottery.summary.new").getString();
        int bw = font.width(s) + 4;
        g.pose().pushPose();
        g.pose().translate(0, 0, 250);
        g.fill(w - bw + 3, -4, w + 3, 6, 0xFFFF5A3C);
        g.drawString(font, s, w - bw + 5, -3, 0xFFFFFFFF, false);
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
        Component sub = source.hub()
                ? Component.translatable("gui.lottery.hub.sub", round.typeId, round.draws.size())
                : Component.translatable("gui.lottery.scratch.sub", round.typeId, stock);
        g.drawString(font, sub, left + 8, top + 18, 0xFFF0F0F0, false);
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
        // a freshly revealed mythic prize gets its full-screen moment before the summary
        if (finished && !mythicPlayed && finishedAt > -10_000 && ticks - finishedAt >= mythicDelay()) {
            mythicPlayed = true;
            List<Draw> mythics = mythics();
            if (!mythics.isEmpty()) {
                minecraft.setScreen(new MythicScreen(this, mythics));
                return;
            }
        }
        if (summaryVisible() != builtWithSummary) rebuildWidgets();
        if (builtWithSummary) {
            // a chime for every card/tile that pops in, pitched by rarity (a soft click for empty draws)
            List<Integer> order = order();
            int shown = Mth.clamp((ticks - summaryShownAt - 6) / 3 + 1, 0, order.size());
            while (summaryTilesSounded < shown) {
                int index = order.get(summaryTilesSounded);
                Draw d = round.draws.get(index);
                if (!d.won()) {
                    play(SoundEvents.UI_BUTTON_CLICK, 1.6F, 0.2F);
                } else {
                    play(SoundEvents.NOTE_BLOCK_PLING, 0.9F + d.tier() * 0.15F, 0.5F);
                    if (d.tier() >= 4) play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.0F);
                    if (source.isNew(index)) play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4F, 0.4F);
                }
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
