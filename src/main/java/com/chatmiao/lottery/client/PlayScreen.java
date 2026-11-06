package com.chatmiao.lottery.client;

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
import java.util.List;
import java.util.Random;

/**
 * Shared part of the three ticket GUIs (对号刮奖 / 开箱转盘 / 卡牌抽卡). Every result is already locked on the
 * server when the screen opens; subclasses only animate the reveal. Reveal progress is reported back so a
 * reopened screen continues where it left off.
 */
public abstract class PlayScreen extends Screen {
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
        onNewRound();
        finished = allRevealed();
    }

    /** Reset per-ticket client state (called before layout is known; {@link #width} may still be 0). */
    protected abstract void onNewRound();

    /** Compute {@link #panelW}/{@link #panelH} and anything else that depends on the screen size. */
    protected abstract void layout();

    /** Buttons shown while the ticket is not finished, placed at {@code y} (bottom row). */
    protected abstract void addPlayButtons(int y);

    protected boolean isRevealed(int bit) {
        return (revealed & (1L << bit)) != 0;
    }

    protected boolean allRevealed() {
        long all = round.allMask();
        return (revealed & all) == all;
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

    @Override
    protected void init() {
        layout();
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        addRenderableWidget(Button.builder(Component.literal("×"), b -> onClose()).bounds(left + panelW - 16, top + 4, 12, 12).build());
        int by = top + panelH - 24;
        if (!finished) {
            addPlayButtons(by);
            return;
        }
        Component doneText = round.won()
                ? Component.translatable("gui.lottery.scratch.collect")
                : Component.translatable("gui.lottery.scratch.thanks");
        Button done = Button.builder(doneText, b -> finish(false)).bounds(0, by, 100, 18).build();
        if (stock > 0) {
            Button again = Button.builder(Component.translatable("gui.lottery.scratch.again", stock), b -> finish(true))
                    .bounds(0, by, 100, 18).build();
            done.setX(left + panelW / 2 - 104);
            again.setX(left + panelW / 2 + 4);
            done.active = again.active = !waiting;
            addRenderableWidget(done);
            addRenderableWidget(again);
        } else {
            done.setX(left + panelW / 2 - 50);
            done.active = !waiting;
            addRenderableWidget(done);
        }
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

    // ------------------------------------------------------------------ drawing helpers

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
