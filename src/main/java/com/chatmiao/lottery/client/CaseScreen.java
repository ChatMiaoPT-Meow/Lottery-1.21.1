package com.chatmiao.lottery.client;

import com.chatmiao.lottery.data.Draw;
import com.chatmiao.lottery.data.Tier;
import com.chatmiao.lottery.logic.Roller;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * 开箱转盘. One horizontal reel per draw; "开启" spins them, each slows down and stops on its prize under the
 * pointer. The reel strips (including the decoys) come from the server, so the client only animates.
 */
public class CaseScreen extends PlayScreen {
    private static final float START_POS = 4.5F;
    private static final int TOP = 44, GAP = 6, SIDE = 12;
    private static final long STAGGER_MS = 350;

    private static final int OPEN_TICKS = 16, FLASH_TICKS = 8, EFFECT_TICKS = 40;

    private int reelCount;
    /** Per reel: when it started spinning (0 = idle), small random stop offset, last tile under the pointer. */
    private long[] startMs;
    private float[] jitter;
    private float[] overshoot;
    private int[] lastTile;
    /** Per reel: tick its rare-prize light pillar started, or -1. */
    private int[] effectStart;
    private long lastTickSound;
    /** Tick the case-opening animation started (-1 = not opening). */
    private int openStart;
    private int flashStart;

    private int reelH, tileSize, tileW, winX, winW;

    public CaseScreen(Payloads.ScratchState state) {
        super(state);
    }

    @Override
    protected void onNewRound() {
        reelCount = round.reels.length;
        startMs = new long[reelCount];
        jitter = new float[reelCount];
        overshoot = new float[reelCount];
        lastTile = new int[reelCount];
        effectStart = new int[reelCount];
        for (int i = 0; i < reelCount; i++) {
            jitter[i] = (rnd.nextFloat() - 0.5F) * 0.7F;
            overshoot[i] = 0.22F + rnd.nextFloat() * 0.25F;
            effectStart[i] = -1;
        }
        openStart = -1;
        flashStart = -100;
    }

    private boolean opening() {
        return openStart >= 0 && ticks - openStart < OPEN_TICKS;
    }

    @Override
    protected void layout() {
        panelW = Mth.clamp(width - 16, 220, 320);
        reelH = Mth.clamp((height - 16 - TOP - 50 - (reelCount - 1) * GAP) / Math.max(1, reelCount), 30, 48);
        tileSize = reelH - 10;
        tileW = tileSize + 4;
        panelH = TOP + reelCount * reelH + (reelCount - 1) * GAP + 50;
        winX = SIDE;
        winW = panelW - 2 * SIDE;
    }

    private boolean spinning() {
        for (int i = 0; i < reelCount; i++) if (startMs[i] != 0 && !isRevealed(i)) return true;
        return false;
    }

    @Override
    protected void addPlayButtons(int y) {
        if (opening()) {
            Button b = button(Component.translatable("gui.lottery.case.opening"), left + panelW / 2 - 45, y, 90, x -> {});
            b.active = false;
        } else if (spinning()) {
            button(Component.translatable("gui.lottery.case.skip"), left + panelW / 2 - 45, y, 90, b -> skip());
        } else {
            Button open = button(Component.translatable("gui.lottery.case.open"), left + panelW / 2 - 45, y, 90, b -> spin());
            open.active = !waiting;
        }
    }

    /** "开启": the case shakes and bursts open, then the reels start. */
    private void spin() {
        if (openStart >= 0 || introPlaying()) return;
        openStart = ticks;
        play(SoundEvents.CHEST_LOCKED, 1.0F, 0.6F);
        rebuildWidgets();
    }

    private void startReels() {
        long now = System.currentTimeMillis();
        int k = 0;
        for (int i = 0; i < reelCount; i++) {
            if (isRevealed(i) || startMs[i] != 0) continue;
            startMs[i] = now + k * 120L; // reels start one after another
            k++;
        }
        flashStart = ticks;
        play(SoundEvents.CHEST_OPEN, 1.1F, 0.8F);
        play(SoundEvents.FIREWORK_ROCKET_BLAST, 1.2F, 0.5F);
        burstAt(left + panelW / 2.0 - 30, top + TOP + reelsHeight() / 2.0 - 20, 60, 40, 30, 0xFFC94A);
        rebuildWidgets();
    }

    private int reelsHeight() {
        return reelCount * reelH + (reelCount - 1) * GAP;
    }

    private void skip() {
        for (int i = 0; i < reelCount; i++) if (!isRevealed(i)) stop(i);
    }

    private long duration(int i) {
        return round.spinSeconds * 1000L + i * STAGGER_MS;
    }

    /**
     * Reel position in tiles: the tile index currently under the pointer (fractional). The reel decelerates a
     * little past its stop, then eases back onto it.
     */
    private float position(int i, long now) {
        float target = Roller.REEL_STOP + jitter[i];
        if (isRevealed(i)) return target;
        if (startMs[i] == 0 || now < startMs[i]) return START_POS;
        float p = Mth.clamp((now - startMs[i]) / (float) duration(i), 0, 1);
        float over = overshoot[i];
        if (p < 0.86F) {
            float q = p / 0.86F;
            float eased = 1 - (1 - q) * (1 - q) * (1 - q);
            return START_POS + (target + over - START_POS) * eased;
        }
        float q = (p - 0.86F) / 0.14F;
        return target + over - over * (q * q * (3 - 2 * q));
    }

    private void stop(int i) {
        if (isRevealed(i)) return;
        Draw d = i < round.draws.size() ? round.draws.get(i) : Draw.EMPTY;
        int cx = left + winX + winW / 2, y = top + reelY(i);
        burstAt(cx - tileW / 2.0, y, tileW, reelH, d.won() ? 14 + d.tier() * 6 : 4, Tier.color(d.tier()));
        if (d.won() && d.tier() >= 3) effectStart[i] = ticks;
        if (d.won() && d.tier() >= 4) flashStart = ticks;
        if (!d.won()) {
            play(SoundEvents.NOTE_BLOCK_BASS, 0.7F, 0.6F);
        } else if (d.tier() >= 4) {
            play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.5F);
        } else if (d.tier() >= 2) {
            play(SoundEvents.NOTE_BLOCK_CHIME, 1.2F, 0.8F);
        } else {
            play(SoundEvents.NOTE_BLOCK_PLING, 1.4F, 0.6F);
        }
        markRevealed(1L << i);
        if (!finished) rebuildWidgets();
    }

    private int reelY(int i) {
        return TOP + i * (reelH + GAP);
    }

    @Override
    public void tick() {
        super.tick();
        if (openStart >= 0 && !finished) {
            int t = ticks - openStart;
            if (t < OPEN_TICKS && t % 3 == 0) play(SoundEvents.NOTE_BLOCK_SNARE, 0.8F + t * 0.04F, 0.4F);
            if (t == OPEN_TICKS) startReels();
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < reelCount; i++) {
            if (startMs[i] != 0 && !isRevealed(i) && now - startMs[i] >= duration(i)) stop(i);
        }
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
        titleBar(g, Component.translatable("gui.lottery.case.title", round.typeName));

        long now = System.currentTimeMillis();
        ItemStack tooltip = ItemStack.EMPTY;
        for (int i = 0; i < reelCount; i++) {
            ItemStack t = drawReel(g, i, position(i, now), mouseX, mouseY);
            if (!t.isEmpty()) tooltip = t;
        }
        tickSounds(now);
        drawPillars(g, partialTick);
        drawOpening(g, partialTick);
        drawFlash(g, partialTick);

        int noteY = top + panelH - 40;
        if (waiting) {
            centered(g, Component.translatable("gui.lottery.scratch.loading"), noteY, 0xFF9AA0AA);
        } else if (finished) {
            centered(g, resultBanner(), noteY, round.won() ? 0xFFFFC94A : 0xFF9AA0AA);
        } else if (!spinning() && openStart < 0) {
            centered(g, Component.translatable("gui.lottery.case.hint"), noteY, 0xFF9AA0AA);
        }
        if (!tooltip.isEmpty()) g.renderTooltip(font, tooltip, mouseX, mouseY);
    }

    /** The case itself: shakes harder and harder over the reels, then bursts (see {@link #startReels()}). */
    private void drawOpening(GuiGraphics g, float pt) {
        if (!opening()) return;
        float t = ticks - openStart + pt;
        float f = Mth.clamp(t / OPEN_TICKS, 0, 1);
        int y0 = top + TOP - 4, y1 = top + TOP + reelsHeight() + 4;
        g.pose().pushPose();
        g.pose().translate(0, 0, 270);
        g.fill(left + 4, y0, left + panelW - 4, y1, 0xE8101216);
        int glow = (int) (f * 160);
        int tc = TicketColors.of(round.typeId);
        lightPillar(g, left + panelW / 2 - 40, y0, y1, 80, tc, f * 0.6F);
        float shake = Mth.sin(t * 2.4F) * 3.5F * f;
        float scale = 3.0F + (f > 0.8F ? (f - 0.8F) * 4 : 0);
        g.pose().translate(left + panelW / 2F + shake, (y0 + y1) / 2F, 0);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(shake * 2));
        g.pose().scale(scale, scale, 1);
        g.renderItem(new ItemStack(net.minecraft.world.item.Items.CHEST), -8, -8);
        g.pose().popPose();
        if (glow > 0) {
            Component hint = Component.translatable("gui.lottery.case.opening");
            g.pose().pushPose();
            g.pose().translate(0, 0, 420);
            g.drawString(font, hint, left + (panelW - font.width(hint)) / 2, y1 - 14, 0xFFFFE6A0, false);
            g.pose().popPose();
        }
    }

    /** A beam of light over every reel that stopped on an epic-or-better prize. */
    private void drawPillars(GuiGraphics g, float pt) {
        int cx = left + winX + winW / 2;
        g.pose().pushPose();
        g.pose().translate(0, 0, 240);
        for (int i = 0; i < reelCount; i++) {
            if (effectStart[i] < 0) continue;
            float age = ticks - effectStart[i] + pt;
            if (age > EFFECT_TICKS) continue;
            Draw d = round.draws.get(i);
            float strength = (1 - age / EFFECT_TICKS) * (d.tier() >= 4 ? 1.0F : 0.6F);
            int wy = top + reelY(i);
            lightPillar(g, cx - tileW / 2, top + 32, wy + reelH, tileW, Tier.color(d.tier()), strength);
        }
        g.pose().popPose();
    }

    private void drawFlash(GuiGraphics g, float pt) {
        float age = ticks - flashStart + pt;
        if (age < 0 || age > FLASH_TICKS) return;
        int a = (int) ((1 - age / FLASH_TICKS) * 150);
        g.pose().pushPose();
        g.pose().translate(0, 0, 280);
        g.fill(left, top, left + panelW, top + panelH, (a << 24) | 0xFFFFFF);
        g.pose().popPose();
    }

    private void tickSounds(long now) {
        boolean crossed = false;
        for (int i = 0; i < reelCount; i++) {
            if (startMs[i] == 0 || isRevealed(i)) continue;
            int tile = Math.round(position(i, now));
            if (tile != lastTile[i]) {
                lastTile[i] = tile;
                crossed = true;
            }
        }
        if (crossed && now - lastTickSound > 45) {
            lastTickSound = now;
            play(SoundEvents.NOTE_BLOCK_HAT, 1.6F, 0.35F);
        }
    }

    /** @return the item under the mouse (only for stopped reels), or EMPTY */
    private ItemStack drawReel(GuiGraphics g, int i, float pos, int mouseX, int mouseY) {
        int wx = left + winX, wy = top + reelY(i);
        frame(g, wx, wy, winW, reelH, 0xFF4A505C, 0xFF14161A);
        int cx = wx + winW / 2;
        int[] strip = round.reels[i];
        int half = winW / tileW / 2 + 2;
        int first = Math.max(0, (int) Math.floor(pos) - half), last = Math.min(strip.length - 1, (int) Math.ceil(pos) + half);

        g.enableScissor(wx, wy, wx + winW, wy + reelH);
        for (int k = first; k <= last; k++) {
            int tx = Math.round(cx + (k - pos) * tileW - tileW / 2.0F) + 2;
            drawTile(g, i, strip[k], tx, wy + 5);
        }
        g.disableScissor();

        boolean stopped = isRevealed(i);
        Draw d = i < round.draws.size() ? round.draws.get(i) : Draw.EMPTY;
        ItemStack hover = ItemStack.EMPTY;
        if (stopped) {
            // dim everything but the winning tile and label the result beside it
            int tl = cx - tileW / 2, tr = cx + tileW / 2;
            g.pose().pushPose();
            g.pose().translate(0, 0, 250);
            g.fill(wx, wy, tl, wy + reelH, 0xB0000000);
            g.fill(tr, wy, wx + winW, wy + reelH, 0xB0000000);
            int tc = 0xFF000000 | Tier.color(d.tier());
            g.renderOutline(tl, wy + 1, tileW, reelH - 2, tc);
            Component name = d.won() ? d.prize().describe() : Component.translatable("gui.lottery.scratch.thanks");
            int room = wx + winW - tr - 10;
            String nameText = font.plainSubstrByWidth(name.getString(), room);
            String tierText = d.won() ? Tier.name(d.tier()).getString() : "";
            int labelW = Math.max(font.width(nameText), font.width(tierText));
            int ly = d.won() ? wy + reelH / 2 - 9 : wy + reelH / 2 - 4;
            // solid plate so the text never sits on top of the dimmed tiles
            g.fill(tr + 2, ly - 2, tr + 8 + labelW, ly + (d.won() ? 19 : 10), 0xF0101216);
            g.drawString(font, nameText, tr + 5, ly, tc, false);
            if (d.won()) g.drawString(font, tierText, tr + 5, ly + 10, tc, false);
            g.pose().popPose();
            if (d.won() && mouseX >= tl && mouseX < tr && mouseY >= wy && mouseY < wy + reelH) hover = d.prize().item();
        }

        // pointer
        g.pose().pushPose();
        g.pose().translate(0, 0, 260);
        int pc = 0xFFFFC94A;
        g.fill(cx - 1, wy - 3, cx + 1, wy + reelH + 3, pc);
        g.fill(cx - 3, wy - 3, cx + 3, wy - 1, pc);
        g.fill(cx - 3, wy + reelH + 1, cx + 3, wy + reelH + 3, pc);
        g.pose().popPose();
        return hover;
    }

    private void drawTile(GuiGraphics g, int reel, int tile, int x, int y) {
        int s = tileSize;
        ItemStack item;
        int tier;
        if (tile == Roller.TILE_RESULT) {
            Draw d = reel < round.draws.size() ? round.draws.get(reel) : Draw.EMPTY;
            item = d.won() ? d.prize().item() : ItemStack.EMPTY;
            tier = d.tier();
        } else if (tile >= 0 && tile < round.palette.size()) {
            item = round.palette.get(tile);
            tier = round.tileTier(reel, tile);
        } else {
            item = ItemStack.EMPTY;
            tier = Tier.NONE;
        }
        int tc = Tier.color(tier);
        g.fill(x, y, x + s, y + s, item.isEmpty() ? 0xFF24262B : 0xFF2C3038);
        g.fill(x, y + s - 3, x + s, y + s, 0xFF000000 | tc);
        if (item.isEmpty()) {
            Component t = Component.translatable("gui.lottery.case.empty");
            g.drawString(font, t, x + (s - font.width(t)) / 2, y + (s - 3 - 8) / 2, 0xFF6A6E78, false);
            return;
        }
        float scale = s >= 34 ? 2.0F : 1.0F;
        int iconSize = (int) (16 * scale);
        g.pose().pushPose();
        g.pose().translate(x + (s - iconSize) / 2.0F, y + (s - 3 - iconSize) / 2.0F, 0);
        g.pose().scale(scale, scale, 1);
        g.renderItem(item, 0, 0);
        g.pose().popPose();
    }
}
