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
 * 召唤预兆: shown right after a gacha pull, before the ticket animation. One meteor per draw curves in from the
 * edge of a night sky, one after another, rarest last: each hit adds a beat (a ring and a rising note) and feeds a
 * glowing orb in the middle. Before the last meteor the sky dims and the orb throbs; then the rarest one comes in
 * slower and bigger and sets off the big impact in its colour. Purely cosmetic: the result is already locked.
 */
public class SummonScreen extends Screen {
    private static final int FADE = 6, FIRST_FLIGHT = 12, FLIGHT = 12, LAST_FLIGHT = 16, PAUSE = 9, BURST = 14;
    private static final int COMPANION = -2;

    private final Payloads.HubState state;
    private final int best;
    /** Meteor tiers in arrival order: lowest first, the best last. COMPANION = faint filler for single pulls. */
    private final List<Integer> meteorTiers = new ArrayList<>();
    /** Per meteor: start angle (radians), start distance (× half-diagonal), sideways swirl of the curve. */
    private final float[] angle, dist, swirl;
    /** Per meteor: tick it starts and tick it hits the centre. */
    private final int[] start, arrive;
    private final int impact, end;
    private final long seed;
    private int ticks;
    private boolean done;

    public SummonScreen(Payloads.HubState state) {
        super(Component.translatable("gui.lottery.summon"));
        this.state = state;
        List<Integer> tiers = new ArrayList<>();
        for (Draw d : state.round().draws) tiers.add(d.won() ? d.tier() : Tier.NONE);
        tiers.sort(Integer::compare); // ascending: the build-up ends on the best one
        if (tiers.size() > 10) tiers = tiers.subList(tiers.size() - 10, tiers.size());
        best = tiers.isEmpty() ? Tier.NONE : tiers.get(tiers.size() - 1);
        // short pulls (a single pull above all) get faint silver lead-in meteors so they have the same beat and
        // build-up as a ten-pull; only real draws are coloured, so the lead-ins never hint at a result
        while (tiers.size() < 6) tiers.add(0, COMPANION);
        meteorTiers.addAll(tiers);
        int n = meteorTiers.size();

        seed = state.round().roundId * 31L + 7;
        Random r = new Random(seed);
        angle = new float[n];
        dist = new float[n];
        swirl = new float[n];
        // each next meteor comes from a different side (golden-angle steps), all bending the same way (a vortex)
        float base = r.nextFloat() * Mth.TWO_PI;
        float dir = r.nextBoolean() ? 1 : -1;
        for (int i = 0; i < n; i++) {
            angle[i] = base + i * 2.39996F + (r.nextFloat() - 0.5F) * 0.3F;
            dist[i] = 0.85F + r.nextFloat() * 0.15F;
            swirl[i] = dir * (0.9F + r.nextFloat() * 0.5F);
        }

        // rhythm: steady beats, a held breath, then the last one
        int gap = n <= 3 ? 7 : Mth.clamp(Math.round(34F / (n - 1)), 3, 6);
        start = new int[n];
        arrive = new int[n];
        for (int i = 0; i < n - 1; i++) {
            arrive[i] = FADE + FIRST_FLIGHT + i * gap;
            start[i] = arrive[i] - FLIGHT;
        }
        int lastBeat = n >= 2 ? arrive[n - 2] : FADE + FIRST_FLIGHT;
        arrive[n - 1] = lastBeat + PAUSE + LAST_FLIGHT / 2;
        start[n - 1] = arrive[n - 1] - LAST_FLIGHT;
        impact = arrive[n - 1];
        end = impact + BURST + 8;
    }

    private boolean isLast(int i) {
        return i == meteorTiers.size() - 1;
    }

    private int colorOf(int tier) {
        return tier == COMPANION ? 0xC8CCDA : tier < 0 ? 0xB8B8C8 : Tier.color(tier);
    }

    /** Hits so far (not counting the last meteor) and the tier of the latest. */
    private int hitsAt(float t) {
        int k = 0;
        for (int i = 0; i < meteorTiers.size() - 1; i++) if (t >= arrive[i]) k++;
        return k;
    }

    /**
     * Position of meteor {@code i} at progress {@code p} (0 = start, 1 = centre): a quadratic bezier from the screen
     * edge whose control point is the start rotated sideways, so every meteor curves in.
     */
    private float[] pathPoint(int i, float p, float cx, float cy) {
        float half = (float) Math.hypot(width, height) / 2;
        float sx = cx + Mth.cos(angle[i]) * dist[i] * half, sy = cy + Mth.sin(angle[i]) * dist[i] * half;
        float ca = angle[i] + swirl[i];
        float kx = cx + Mth.cos(ca) * dist[i] * half * 0.75F, ky = cy + Mth.sin(ca) * dist[i] * half * 0.75F;
        float u = 1 - p;
        return new float[]{u * u * sx + 2 * u * p * kx + p * p * cx, u * u * sy + 2 * u * p * ky + p * p * cy};
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
        int n = meteorTiers.size();
        for (int i = 0; i < n - 1; i++) {
            if (ticks != arrive[i]) continue;
            int tier = meteorTiers.get(i);
            if (tier == COMPANION) {
                play(SoundEvents.NOTE_BLOCK_PLING, 0.7F + i * 0.12F, 0.35F); // soft rising beat
            } else if (tier < 0) {
                play(SoundEvents.NOTE_BLOCK_HAT, 1.0F + i * 0.05F, 0.45F);
            } else {
                // a rising scale: each beat a little higher than the last
                play(SoundEvents.NOTE_BLOCK_CHIME, 0.6F + i * 0.1F, 0.7F);
                if (tier >= 3) play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F + i * 0.05F, 1.0F);
            }
        }
        // the held breath: a slow heartbeat until the last meteor lands
        int lastBeat = n >= 2 ? arrive[n - 2] : 0;
        if (ticks > lastBeat && ticks < impact - 2 && (ticks - lastBeat) % 5 == 1) play(SoundEvents.NOTE_BLOCK_BASEDRUM, 0.7F, 0.8F);
        if (ticks == start[n - 1]) play(SoundEvents.FIREWORK_ROCKET_LAUNCH, best >= 4 ? 0.6F : 0.8F, 0.8F);
        if (ticks == impact - 1) {
            play(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, best >= 4 ? 0.7F : 1.0F, 0.9F);
            if (best >= 4) play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.6F);
            else if (best >= 3) play(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 1.0F);
        }
        if (ticks == impact + 4 && best >= 4) play(SoundEvents.BEACON_ACTIVATE, 1.4F, 0.6F);
        if (ticks >= end) finish();
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
        drawStars(g, t, Mth.clamp(t / FADE, 0, 1));
        float cx = width / 2F, cy = height * 0.55F;
        int n = meteorTiers.size();

        // the held breath before the last meteor: the sky dims and sparks are drawn into the orb
        float lastBeat = n >= 2 ? arrive[n - 2] : FADE;
        if (t > lastBeat && t < impact) {
            float f = Mth.clamp((t - lastBeat) / (impact - lastBeat), 0, 1);
            g.fill(0, 0, width, height, ((int) (f * 90) << 24));
            drawConvergingSparks(g, cx, cy, t - lastBeat, f, colorOf(best));
        }

        // beats: a ring at the centre for every meteor that has landed
        for (int i = 0; i < n - 1; i++) {
            float age = t - arrive[i];
            if (age < 0 || age > 9) continue;
            int a = (int) ((1 - age / 9) * 220);
            ring(g, cx, cy, 5 + age * 4.5F, 2, (a << 24) | colorOf(meteorTiers.get(i)));
        }

        // the orb: grows with every hit, coloured by the latest hit, throbbing faster while waiting
        if (t < impact) drawOrb(g, cx, cy, t, lastBeat);

        // meteors in flight
        for (int i = 0; i < n; i++) {
            float p = (t - start[i]) / (arrive[i] - start[i]);
            if (p <= 0 || p >= 1) continue;
            float e = isLast(i) ? p * p * p * 0.5F + p * 0.5F : 0.35F * p + 0.65F * p * p;
            drawMeteor(g, i, e, cx, cy);
        }

        // the big impact in the best colour
        float it = t - impact;
        if (it >= 0) {
            int c = colorOf(best);
            float f = Mth.clamp(it / BURST, 0, 1);
            int ringA = (int) ((1 - f) * 230);
            ring(g, cx, cy, 8 + it * 14, 3, (ringA << 24) | c);
            ring(g, cx, cy, 4 + it * 8, 2, (ringA << 24) | 0xFFFFFF);
            drawRays(g, cx, cy, it, c, 1 - f);
            int flash = (int) (Mth.clamp(1 - it / 5F, 0, 1) * (best >= 4 ? 170 : best >= 3 ? 120 : 70));
            if (flash > 0) g.fill(0, 0, width, height, (flash << 24) | c);
            if (best >= 4) {
                int tint = (int) ((1 - f) * 60);
                g.fill(0, 0, width, height, (tint << 24) | c);
            }
            int core = (int) (Mth.clamp(1 - f * 0.8F, 0, 1) * 255);
            int r = (int) (6 + 4 * Mth.sin(t * 0.6F));
            g.fill((int) cx - r, (int) cy - r, (int) cx + r, (int) cy + r, (core / 2 << 24) | c);
            g.fill((int) cx - r / 2, (int) cy - r / 2, (int) cx + r / 2, (int) cy + r / 2, (core << 24) | 0xFFFFFF);
        }
    }

    private void drawOrb(GuiGraphics g, float cx, float cy, float t, float lastBeat) {
        int hits = hitsAt(t);
        if (hits == 0) return;
        int c = colorOf(meteorTiers.get(hits - 1));
        boolean waiting = t > lastBeat;
        float speed = waiting ? 0.9F : 0.4F;
        float pulse = 0.5F + 0.5F * Mth.sin(t * speed);
        float r = 2 + hits * 0.7F + pulse * (waiting ? 3 : 1.5F);
        int x = (int) cx, y = (int) cy, ri = (int) r;
        g.fill(x - ri - 3, y - ri - 3, x + ri + 3, y + ri + 3, ((int) (50 + 40 * pulse) << 24) | c);
        g.fill(x - ri, y - ri, x + ri, y + ri, (0xC0 << 24) | c);
        int core = Math.max(1, ri / 2);
        g.fill(x - core, y - core, x + core, y + core, 0xFFFFFFFF);
    }

    private void drawConvergingSparks(GuiGraphics g, float cx, float cy, float age, float f, int c) {
        Random r = new Random(seed ^ 0x5EEDL);
        for (int k = 0; k < 18; k++) {
            float a0 = r.nextFloat() * Mth.TWO_PI;
            float speed = 0.6F + r.nextFloat() * 0.6F;
            float phase = (age * 0.06F * speed + r.nextFloat()) % 1F; // each spark loops inward
            float radius = (1 - phase) * Math.min(width, height) * 0.45F;
            float ang = a0 + phase * 1.2F;
            int x = (int) (cx + Mth.cos(ang) * radius), y = (int) (cy + Mth.sin(ang) * radius);
            int a = (int) (f * phase * 220);
            g.fill(x, y, x + 2, y + 2, (a << 24) | c);
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

    /** Meteor {@code i} at path progress {@code e}: a bright head and a tail that follows the curve behind it. */
    private void drawMeteor(GuiGraphics g, int i, float e, float cx, float cy) {
        int tier = meteorTiers.get(i);
        boolean companion = tier == COMPANION;
        boolean last = isLast(i);
        float size = companion ? 0.65F : last ? 1.4F : 0.8F;
        int c = colorOf(tier);
        int alpha = companion ? 170 : 235;
        int segments = last ? 34 : 24;
        float step = last ? 0.011F : 0.013F;
        for (int k = segments; k >= 0; k--) {
            float pe = e - k * step;
            if (pe <= 0) continue;
            float[] p = pathPoint(i, pe, cx, cy);
            float f = 1 - k / (float) segments;
            int a = (int) (f * f * alpha);
            int s = Math.max(1, (int) ((1 + 5 * f) * size));
            g.fill((int) p[0] - s / 2, (int) p[1] - s / 2, (int) p[0] + (s + 1) / 2, (int) p[1] + (s + 1) / 2, (a << 24) | c);
        }
        float[] head = pathPoint(i, e, cx, cy);
        int x = (int) head[0], y = (int) head[1];
        int glow = (int) (9 * size);
        g.fill(x - glow / 2, y - glow / 2, x + glow / 2, y + glow / 2, ((companion ? 0x50 : 0x80) << 24) | c);
        int core = companion ? 1 : last ? 3 : 2;
        g.fill(x - core, y - core, x + core, y + core, companion ? 0xC0FFFFFF : 0xFFFFFFFF);
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
