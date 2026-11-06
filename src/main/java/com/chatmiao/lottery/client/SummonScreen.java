package com.chatmiao.lottery.client;

import com.chatmiao.lottery.data.Draw;
import com.chatmiao.lottery.data.Tier;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 召唤预兆: shown right after a gacha pull, before the ticket animation. Meteors streak across a night sky and
 * crash in the middle; the lead meteor (and the impact flash) takes the colour of the best prize in the pull —
 * grey for nothing, then blue / purple / gold / red. Purely cosmetic: the result is already locked on the server.
 */
public class SummonScreen extends Screen {
    private static final int FADE = 6, FLY = 20, IMPACT = FADE + FLY, BURST = 14, END = IMPACT + BURST + 6;

    private final Payloads.HubState state;
    private final int best;
    private final List<Integer> meteorTiers = new ArrayList<>();
    private final float[] startX, startY, endX;
    private final long seed;
    private int ticks;
    private boolean done;

    public SummonScreen(Payloads.HubState state) {
        super(Component.translatable("gui.lottery.summon"));
        this.state = state;
        List<Integer> tiers = new ArrayList<>();
        for (Draw d : state.round().draws) tiers.add(d.won() ? d.tier() : Tier.NONE);
        tiers.sort((a, b) -> Integer.compare(b, a));
        best = tiers.isEmpty() ? Tier.NONE : tiers.get(0);
        int n = Math.max(1, Math.min(5, tiers.size()));
        for (int i = 0; i < n; i++) meteorTiers.add(tiers.isEmpty() ? Tier.NONE : tiers.get(Math.min(i, tiers.size() - 1)));
        seed = state.round().roundId * 31L + 7;
        Random r = new Random(seed);
        startX = new float[n];
        startY = new float[n];
        endX = new float[n];
        for (int i = 0; i < n; i++) {
            // from the upper-right corner region, just inside the screen so the streak is visible from the start
            startX[i] = 0.78F + r.nextFloat() * 0.2F;
            startY[i] = 0.02F + r.nextFloat() * 0.12F;
            endX[i] = 0.5F + (i == 0 ? 0 : (r.nextFloat() - 0.5F) * 0.12F);
        }
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.summon.skip"), b -> finish())
                .bounds(width - 70, height - 26, 60, 18).build());
    }

    private void finish() {
        if (done) return;
        done = true;
        minecraft.setScreen(PlayScreen.create(state));
    }

    @Override
    public void tick() {
        ticks++;
        if (ticks == FADE) play(SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.8F, 0.8F);
        if (ticks == IMPACT - 2) {
            play(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, best >= 4 ? 0.7F : 1.0F, 0.9F);
            if (best >= 4) play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.6F);
            else if (best >= 3) play(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 1.0F);
        }
        if (ticks == IMPACT + 4 && best >= 4) play(SoundEvents.BEACON_ACTIVATE, 1.4F, 0.6F);
        if (ticks >= END) finish();
    }

    private void play(SoundEvent sound, float pitch, float volume) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    private void play(net.minecraft.core.Holder<SoundEvent> sound, float pitch, float volume) {
        play(sound.value(), pitch, volume);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // our own night sky instead of the blurred world
        float t = ticks + partialTick;
        int a = (int) (Mth.clamp(t / FADE, 0, 1) * 255);
        g.fillGradient(0, 0, width, height, (a << 24) | 0x05060E, (a << 24) | 0x1A1230);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        float t = ticks + partialTick;
        float fade = Mth.clamp(t / FADE, 0, 1);
        drawStars(g, t, fade);

        float cx = width / 2F, cy = height * 0.55F;
        // meteors
        for (int i = 0; i < meteorTiers.size(); i++) {
            float start = FADE + i * 2;
            float p = (t - start) / (FLY - i * 2);
            if (p <= 0 || p >= 1) continue;
            float e = 0.55F * p + 0.45F * p * p; // steady, slightly accelerating into the impact
            float sx = startX[i] * width, sy = startY[i] * height, ex = endX[i] * width;
            float x = sx + (ex - sx) * e, y = sy + (cy - sy) * e;
            drawMeteor(g, x, y, ex - sx, cy - sy, meteorTiers.get(i), i == 0 ? 1.0F : 0.7F);
        }

        // impact: expanding rings, rays and a flash in the best colour
        float it = t - IMPACT;
        if (it >= 0) {
            int c = best < 0 ? 0xB8B8C8 : Tier.color(best);
            float f = Mth.clamp(it / BURST, 0, 1);
            int ringA = (int) ((1 - f) * 230);
            ring(g, cx, cy, 8 + it * 14, 3, (ringA << 24) | c);
            ring(g, cx, cy, 4 + it * 8, 2, (ringA << 24) | 0xFFFFFF);
            drawRays(g, cx, cy, it, c, 1 - f);
            // short flash in the prize colour (brighter for rarer prizes)
            int flash = (int) (Mth.clamp(1 - it / 5F, 0, 1) * (best >= 4 ? 170 : best >= 3 ? 120 : 70));
            if (flash > 0) g.fill(0, 0, width, height, (flash << 24) | c);
            if (best >= 4) {
                // the whole sky keeps a gold / red glow for the top tiers
                int tint = (int) ((1 - f) * 60);
                g.fill(0, 0, width, height, (tint << 24) | c);
            }
            // a glowing core that lingers
            int core = (int) (Mth.clamp(1 - f * 0.8F, 0, 1) * 255);
            int r = (int) (6 + 4 * Mth.sin(t * 0.6F));
            g.fill((int) cx - r, (int) cy - r, (int) cx + r, (int) cy + r, (core / 2 << 24) | c);
            g.fill((int) cx - r / 2, (int) cy - r / 2, (int) cx + r / 2, (int) cy + r / 2, (core << 24) | 0xFFFFFF);
        }
    }

    private void drawStars(GuiGraphics g, float t, float fade) {
        Random r = new Random(seed);
        for (int i = 0; i < 90; i++) {
            int x = r.nextInt(Math.max(1, width)), y = r.nextInt(Math.max(1, height));
            float tw = 0.5F + 0.5F * Mth.sin(t * 0.2F + i);
            int a = (int) (fade * (60 + 150 * tw));
            int s = i % 7 == 0 ? 2 : 1;
            g.fill(x, y, x + s, y + s, (a << 24) | 0xFFFFFF);
        }
    }

    /** A bright head with a fading tail pointing back along (dx, dy). */
    private void drawMeteor(GuiGraphics g, float x, float y, float dx, float dy, int tier, float size) {
        int c = tier < 0 ? 0xB8B8C8 : Tier.color(tier);
        float len = (float) Math.hypot(dx, dy);
        float ux = dx / len, uy = dy / len;
        int segments = 22;
        for (int k = segments; k >= 0; k--) {
            float back = k * 4.5F * size;
            float px = x - ux * back, py = y - uy * back;
            float f = 1 - k / (float) segments;
            int a = (int) (f * f * 220);
            int s = Math.max(1, (int) ((1 + 5 * f) * size));
            g.fill((int) px - s / 2, (int) py - s / 2, (int) px + (s + 1) / 2, (int) py + (s + 1) / 2, (a << 24) | c);
        }
        int glow = (int) (8 * size);
        g.fill((int) x - glow / 2, (int) y - glow / 2, (int) x + glow / 2, (int) y + glow / 2, 0x80000000 | c);
        g.fill((int) x - 2, (int) y - 2, (int) x + 2, (int) y + 2, 0xFFFFFFFF);
    }

    private void drawRays(GuiGraphics g, float cx, float cy, float it, int c, float strength) {
        if (strength <= 0) return;
        int rays = best >= 4 ? 16 : 10;
        float length = 20 + it * 16;
        for (int k = 0; k < rays; k++) {
            double ang = Math.toRadians(k * (360.0 / rays) + it * 3);
            float ux = (float) Math.cos(ang), uy = (float) Math.sin(ang);
            for (float d = 14; d < length; d += 3) {
                float f = 1 - d / length;
                int a = (int) (f * strength * 200);
                int x = (int) (cx + ux * d), y = (int) (cy + uy * d);
                g.fill(x, y, x + 2, y + 2, (a << 24) | c);
            }
        }
    }

    /** A circle outline from one-pixel rows (alpha in argb). */
    private static void ring(GuiGraphics g, float cx, float cy, float radius, int thickness, int argb) {
        int r = (int) radius, inner = Math.max(0, r - thickness);
        int x0 = (int) cx, y0 = (int) cy;
        for (int dy = -r; dy < r; dy++) {
            float yc = dy + 0.5F;
            int xo = (int) Math.round(Math.sqrt(Math.max(0, r * r - yc * yc)));
            if (Math.abs(yc) < inner) {
                int xi = (int) Math.round(Math.sqrt(inner * inner - yc * yc));
                g.fill(x0 - xo, y0 + dy, x0 - xi, y0 + dy + 1, argb);
                g.fill(x0 + xi, y0 + dy, x0 + xo, y0 + dy + 1, argb);
            } else {
                g.fill(x0 - xo, y0 + dy, x0 + xo, y0 + dy + 1, argb);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
