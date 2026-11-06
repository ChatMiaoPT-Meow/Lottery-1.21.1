package com.chatmiao.lottery.client;

import com.chatmiao.lottery.data.Draw;
import com.chatmiao.lottery.data.Tier;
import com.chatmiao.lottery.logic.Roller;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * 开箱转盘. One vertical reel per draw, side by side (up to 10); "开启" bursts the case open and the reels roll
 * downwards, slow down and stop on their prize at the pointer line. The reel strips (including the decoys) come from
 * the server, so the client only animates.
 * <p>
 * With the type's rare effect on, a reel heading for an epic-or-better prize builds up first: it rolls longer, its
 * frame pulses in the prize's colour and sparks rise from it. When it lands: a light pillar, a big burst, a shine
 * sweeping over the prize, a rarity banner over the reel and (legendary+) a flash.
 */
public class CaseScreen extends PlayScreen {
    private static final int RARE_TIER = 3;
    private static final float START_POS = 4.5F;
    private static final int TOP = 50, GAP = 6, SIDE = 12, LABEL_H = 26;
    private static final long STAGGER_MS = 260, RARE_EXTRA_MS = 1100;
    /** Last part of the spin (0..1) in which a rare reel shows its build-up. */
    private static final float BUILDUP_FROM = 0.55F;

    private static final int OPEN_TICKS = 16, FLASH_TICKS = 8, EFFECT_TICKS = 50, SHINE_TICKS = 18;

    private int reelCount;
    /** Per reel: when it started spinning (0 = idle), small random stop offset, last tile under the pointer. */
    private long[] startMs;
    private float[] jitter;
    private float[] overshoot;
    private int[] lastTile;
    /** Per reel: tick its rare reveal started, or -1. */
    private int[] effectStart;
    /** Per reel: the build-up chime was played. */
    private boolean[] chimed;
    private long lastTickSound;
    /** Tick the case-opening animation started (-1 = not opening). */
    private int openStart;
    private int flashStart;

    private int colW, tileSize, tileH, rowsShown, winH, reelsX;

    public CaseScreen(Source source, com.chatmiao.lottery.logic.ScratchRound round) {
        super(source, round);
    }

    @Override
    protected void onNewRound() {
        reelCount = round.reels.length;
        startMs = new long[reelCount];
        jitter = new float[reelCount];
        overshoot = new float[reelCount];
        lastTile = new int[reelCount];
        effectStart = new int[reelCount];
        chimed = new boolean[reelCount];
        for (int i = 0; i < reelCount; i++) {
            jitter[i] = (rnd.nextFloat() - 0.5F) * 0.7F;
            overshoot[i] = 0.22F + rnd.nextFloat() * 0.25F;
            effectStart[i] = -1;
        }
        openStart = -1;
        flashStart = -100;
    }

    private Draw draw(int i) {
        return i < round.draws.size() ? round.draws.get(i) : Draw.EMPTY;
    }

    /** Rare reel with the type's rare effect on. */
    private boolean rare(int i) {
        Draw d = draw(i);
        return round.rareEffect && d.won() && d.tier() >= RARE_TIER;
    }

    private boolean opening() {
        return openStart >= 0 && ticks - openStart < OPEN_TICKS;
    }

    @Override
    protected void layout() {
        int n = Math.max(1, reelCount);
        panelW = Mth.clamp(width - 16, 240, 440);
        colW = Mth.clamp((panelW - 2 * SIDE - (n - 1) * GAP) / n, 28, 56);
        panelW = Math.min(Math.max(panelW, 2 * SIDE + n * colW + (n - 1) * GAP), width - 4);
        tileSize = Math.max(20, colW - 8);
        tileH = tileSize + 4;
        int avail = height - 16 - TOP - LABEL_H - 50;
        rowsShown = Mth.clamp(avail / tileH, 3, 5);
        if (rowsShown % 2 == 0) rowsShown--; // odd, so the pointer row sits in the middle
        winH = rowsShown * tileH + 6;
        panelH = TOP + winH + LABEL_H + 50;
        int reelsW = n * colW + (n - 1) * GAP;
        reelsX = (panelW - reelsW) / 2;
    }

    private int reelX(int i) {
        return left + reelsX + i * (colW + GAP);
    }

    private int winY() {
        return top + TOP;
    }

    private int pointerY() {
        return winY() + winH / 2;
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
            startMs[i] = now + k * 90L; // reels start one after another
            k++;
        }
        flashStart = ticks;
        play(SoundEvents.CHEST_OPEN, 1.1F, 0.8F);
        play(SoundEvents.FIREWORK_ROCKET_BLAST, 1.2F, 0.5F);
        burstAt(left + panelW / 2.0 - 30, winY() + winH / 2.0 - 20, 60, 40, 30, 0xFFC94A);
        rebuildWidgets();
    }

    private void skip() {
        for (int i = 0; i < reelCount; i++) if (!isRevealed(i)) stop(i);
    }

    private long duration(int i) {
        return round.spinSeconds * 1000L + i * STAGGER_MS + (rare(i) ? RARE_EXTRA_MS : 0);
    }

    /** 0..1 progress of a spinning reel, or -1 when it is idle / stopped. */
    private float progress(int i, long now) {
        if (isRevealed(i) || startMs[i] == 0 || now < startMs[i]) return -1;
        return Mth.clamp((now - startMs[i]) / (float) duration(i), 0, 1);
    }

    /**
     * Reel position in tiles: the tile index currently on the pointer line (fractional). The reel decelerates a
     * little past its stop, then eases back onto it. Rare reels crawl over the last tiles.
     */
    private float position(int i, long now) {
        float target = Roller.REEL_STOP + jitter[i];
        if (isRevealed(i)) return target;
        float p = progress(i, now);
        if (p < 0) return START_POS;
        float over = overshoot[i];
        float settle = rare(i) ? 0.80F : 0.86F;
        if (p < settle) {
            float q = p / settle;
            // rare reels use a steeper ease-out: fast at first, then a long, tense crawl
            float eased = rare(i) ? 1 - (float) Math.pow(1 - q, 4) : 1 - (1 - q) * (1 - q) * (1 - q);
            return START_POS + (target + over - START_POS) * eased;
        }
        float q = (p - settle) / (1 - settle);
        return target + over - over * (q * q * (3 - 2 * q));
    }

    private void stop(int i) {
        if (isRevealed(i)) return;
        Draw d = draw(i);
        int x = reelX(i), py = pointerY();
        if (rare(i)) {
            effectStart[i] = ticks;
            burstAt(x - 6, py - tileH, colW + 12, tileH * 2, 40 + d.tier() * 12, Tier.color(d.tier()));
            burstAt(x, py - tileH / 2.0, colW, tileH, 16, 0xFFFFFF);
            if (d.tier() >= 4) {
                flashStart = ticks;
                play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.6F);
                play(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2F, 1.0F);
            } else {
                play(SoundEvents.PLAYER_LEVELUP, 1.4F, 0.6F);
                play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.0F);
            }
        } else {
            burstAt(x, py - tileH / 2.0, colW, tileH, d.won() ? 10 + d.tier() * 4 : 3, Tier.color(d.tier()));
            if (!d.won()) {
                play(SoundEvents.NOTE_BLOCK_BASS, 0.7F, 0.6F);
            } else if (d.tier() >= 4) {
                play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.5F);
            } else if (d.tier() >= 2) {
                play(SoundEvents.NOTE_BLOCK_CHIME, 1.2F, 0.8F);
            } else {
                play(SoundEvents.NOTE_BLOCK_PLING, 1.4F, 0.6F);
            }
        }
        markRevealed(1L << i);
        if (!finished) rebuildWidgets();
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
            if (startMs[i] != 0 && !isRevealed(i) && now - startMs[i] >= duration(i)) {
                stop(i);
                continue;
            }
            // rare build-up: one chime when it starts, then sparks rising from the reel
            float p = progress(i, now);
            if (p >= BUILDUP_FROM && rare(i)) {
                int color = Tier.color(draw(i).tier());
                if (!chimed[i]) {
                    chimed[i] = true;
                    play(SoundEvents.AMETHYST_BLOCK_CHIME, 0.7F, 1.0F);
                    play(SoundEvents.BEACON_ACTIVATE, 1.6F, 0.4F);
                }
                if (ticks % 3 == 0) burstAt(reelX(i), winY() + winH - 4, colW, 4, 2 + (int) (p * 4), color);
            }
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
            ItemStack t = drawReel(g, i, position(i, now), now, mouseX, mouseY, partialTick);
            if (!t.isEmpty()) tooltip = t;
        }
        drawPointer(g);
        tickSounds(now);
        drawRareReveals(g, partialTick);
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
        int y0 = winY() - 4, y1 = winY() + winH + 4;
        g.pose().pushPose();
        g.pose().translate(0, 0, 270);
        g.fill(left + 4, y0, left + panelW - 4, y1, 0xE8101216);
        int tc = TicketColors.of(round.typeId);
        lightPillar(g, left + panelW / 2 - 40, y0, y1, 80, tc, f * 0.6F);
        float shake = Mth.sin(t * 2.4F) * 3.5F * f;
        float scale = 3.0F + (f > 0.8F ? (f - 0.8F) * 4 : 0);
        g.pose().translate(left + panelW / 2F + shake, (y0 + y1) / 2F, 0);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(shake * 2));
        g.pose().scale(scale, scale, 1);
        g.renderItem(new ItemStack(net.minecraft.world.item.Items.CHEST), -8, -8);
        g.pose().popPose();
        Component hint = Component.translatable("gui.lottery.case.opening");
        g.pose().pushPose();
        g.pose().translate(0, 0, 420);
        g.drawString(font, hint, left + (panelW - font.width(hint)) / 2, y1 - 14, 0xFFFFE6A0, false);
        g.pose().popPose();
    }

    /** Pointer line across all reels, with arrow heads on both sides. */
    private void drawPointer(GuiGraphics g) {
        int py = pointerY(), x0 = reelX(0) - 6, x1 = reelX(reelCount - 1) + colW + 6;
        g.pose().pushPose();
        g.pose().translate(0, 0, 260);
        int pc = 0xFFFFC94A;
        g.fill(x0, py, x1, py + 1, 0x90FFC94A);
        for (int k = 0; k < 4; k++) {
            g.fill(x0 - 4 + k, py - 3 + k, x0 - 3 + k, py + 4 - k, pc);
            g.fill(x1 + 3 - k, py - 3 + k, x1 + 4 - k, py + 4 - k, pc);
        }
        g.pose().popPose();
    }

    /** Light pillars, prize shine and the rarity banner over every reel that landed on a rare prize. */
    private void drawRareReveals(GuiGraphics g, float pt) {
        int py = pointerY();
        for (int i = 0; i < reelCount; i++) {
            if (effectStart[i] < 0) continue;
            float age = ticks - effectStart[i] + pt;
            if (age > EFFECT_TICKS) continue;
            Draw d = draw(i);
            int color = Tier.color(d.tier());
            int x = reelX(i);
            float fade = 1 - age / EFFECT_TICKS;
            g.pose().pushPose();
            g.pose().translate(0, 0, 240);
            lightPillar(g, x, top + 32, py - tileH / 2, colW, color, fade * (d.tier() >= 4 ? 1.0F : 0.7F));
            g.pose().popPose();

            // a bright band sweeping down over the prize tile
            if (age < SHINE_TICKS) {
                float s = age / SHINE_TICKS;
                int ty = py - tileSize / 2;
                int band = Math.max(3, tileSize / 4);
                int by = ty - band + (int) (s * (tileSize + band));
                g.pose().pushPose();
                g.pose().translate(0, 0, 255);
                g.enableScissor(x + 4, ty, x + 4 + tileSize, ty + tileSize);
                g.fillGradient(x + 4, by, x + 4 + tileSize, by + band, 0x00FFFFFF, 0xC0FFFFFF);
                g.disableScissor();
                g.pose().popPose();
            }

            // rarity banner popping in above the reel
            float pop = popScale(Math.min(1, age / 8F));
            Component name = Tier.name(d.tier());
            int tw = font.width(name);
            float scale = (colW >= 44 ? 1.4F : 1.0F) * pop;
            g.pose().pushPose();
            g.pose().translate(x + colW / 2F, winY() - 9, 290);
            g.pose().scale(scale, scale, 1);
            int a = Mth.clamp((int) (Math.min(1, fade * 3) * 255), 0, 255);
            g.fill(-tw / 2 - 3, -5, tw / 2 + 3, 6, (Math.min(a, 220) << 24) | 0x101216);
            g.drawString(font, name, -tw / 2, -4, (a << 24) | color, false);
            g.pose().popPose();
        }
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
    private ItemStack drawReel(GuiGraphics g, int i, float pos, long now, int mouseX, int mouseY, float pt) {
        int x = reelX(i), wy = winY(), py = pointerY();
        Draw d = draw(i);
        boolean stopped = isRevealed(i);

        // rare build-up: the frame pulses in the prize's colour, faster as the reel slows down
        float p = progress(i, now);
        int border = 0xFF4A505C;
        if (rare(i) && p >= BUILDUP_FROM) {
            float k = (p - BUILDUP_FROM) / (1 - BUILDUP_FROM);
            float pulse = 0.5F + 0.5F * Mth.sin((ticks + pt) * (0.35F + k * 0.6F));
            int a = Mth.clamp((int) ((0.25F + 0.55F * k) * (0.4F + 0.6F * pulse) * 255), 0, 255);
            int c = Tier.color(d.tier());
            for (int r = 1; r <= 3; r++) {
                int ra = a / r;
                g.renderOutline(x - r, wy - r, colW + 2 * r, winH + 2 * r, (ra << 24) | c);
            }
            border = 0xFF000000 | c;
        }
        frame(g, x, wy, colW, winH, border, 0xFF14161A);

        int[] strip = round.reels[i];
        int half = rowsShown / 2 + 2;
        int first = Math.max(0, (int) Math.floor(pos) - half), last = Math.min(strip.length - 1, (int) Math.ceil(pos) + half);
        g.enableScissor(x + 1, wy + 1, x + colW - 1, wy + winH - 1);
        for (int k = first; k <= last; k++) {
            // tiles further along the strip sit above, so the reel rolls downwards
            int ty = Math.round(py + (pos - k) * tileH - tileSize / 2.0F);
            drawTile(g, i, strip[k], x + (colW - tileSize) / 2, ty);
        }
        g.disableScissor();
        // soft fade at the top and bottom of the window
        g.fillGradient(x + 1, wy + 1, x + colW - 1, wy + tileH / 2, 0xE014161A, 0x0014161A);
        g.fillGradient(x + 1, wy + winH - tileH / 2, x + colW - 1, wy + winH - 1, 0x0014161A, 0xE014161A);

        ItemStack hover = ItemStack.EMPTY;
        int tc = 0xFF000000 | Tier.color(d.tier());
        int ly = wy + winH + 4;
        if (stopped) {
            // dim everything but the prize tile and label the result under the reel
            int tt = py - tileH / 2, tb = py + tileH / 2;
            g.pose().pushPose();
            g.pose().translate(0, 0, 250);
            g.fill(x + 1, wy + 1, x + colW - 1, tt, 0xB0000000);
            g.fill(x + 1, tb, x + colW - 1, wy + winH - 1, 0xB0000000);
            g.renderOutline(x + 1, tt, colW - 2, tileH, tc);
            g.pose().popPose();

            g.fill(x, ly, x + colW, ly + LABEL_H - 4, 0xF0101216);
            g.fill(x, ly, x + colW, ly + 1, tc);
            if (d.won()) {
                String count = "×" + d.prize().count();
                String tier = font.plainSubstrByWidth(Tier.name(d.tier()).getString(), colW - 2);
                g.drawString(font, tier, x + (colW - font.width(tier)) / 2, ly + 2, tc, false);
                g.drawString(font, count, x + (colW - font.width(count)) / 2, ly + 12, 0xFFE8E8E8, false);
            } else {
                String t = font.plainSubstrByWidth(Component.translatable("gui.lottery.case.empty").getString(), colW - 2);
                g.drawString(font, t, x + (colW - font.width(t)) / 2, ly + 7, 0xFF6A6E78, false);
            }
            boolean overTile = mouseX >= x && mouseX < x + colW && mouseY >= tt && mouseY < tb;
            boolean overLabel = mouseX >= x && mouseX < x + colW && mouseY >= ly && mouseY < ly + LABEL_H;
            if (d.won() && (overTile || overLabel)) hover = d.prize().item().copyWithCount(Math.min(d.prize().count(), 99));
        } else {
            g.fill(x, ly, x + colW, ly + LABEL_H - 4, 0x60101216);
        }
        return hover;
    }

    private void drawTile(GuiGraphics g, int reel, int tile, int x, int y) {
        int s = tileSize;
        ItemStack item;
        int tier;
        if (tile == Roller.TILE_RESULT) {
            Draw d = draw(reel);
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
            String t = font.plainSubstrByWidth(Component.translatable("gui.lottery.case.empty").getString(), s - 2);
            g.drawString(font, t, x + (s - font.width(t)) / 2, y + (s - 3 - 8) / 2, 0xFF6A6E78, false);
            return;
        }
        float scale = s >= 36 ? 2.0F : 1.0F;
        int iconSize = (int) (16 * scale);
        g.pose().pushPose();
        g.pose().translate(x + (s - iconSize) / 2.0F, y + (s - 3 - iconSize) / 2.0F, 0);
        g.pose().scale(scale, scale, 1);
        g.renderItem(item, 0, 0);
        g.pose().popPose();
    }
}
