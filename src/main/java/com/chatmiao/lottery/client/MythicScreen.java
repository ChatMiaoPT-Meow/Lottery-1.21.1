package com.chatmiao.lottery.client;

import com.chatmiao.lottery.data.Draw;
import com.chatmiao.lottery.data.Tier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * 神话降临: a full-screen showcase played when a ticket reveals a mythic (top-tier) prize, before the result
 * summary. Black out → a red crack tears across the screen and opens onto a surging red light → the item rises
 * out of it, huge, framed by rays and a turning rune ring → its name is typed out. Several mythics play one after
 * another. Click (or wait) to continue; "跳过" ends it. Cosmetic only.
 */
public class MythicScreen extends Screen {
    public static final int MYTHIC = Tier.COUNT - 1;
    private static final int BLACK = 10, CRACK = 8, OPEN = 10, RISE = 16, TEXT_START = 40, AUTO_NEXT = 110;

    private final PlayScreen parent;
    private final List<Draw> mythics;
    private int index;
    /** Tick the current item's sequence started. */
    private int itemStart;
    private int ticks;
    private boolean closing;

    public MythicScreen(PlayScreen parent, List<Draw> mythics) {
        super(Component.translatable("gui.lottery.mythic"));
        this.parent = parent;
        this.mythics = mythics;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.summon.skip"), b -> close())
                .bounds(width - 70, height - 26, 60, 18).build());
    }

    /** The first item starts from black; the next ones skip straight to the crack. */
    private int black() {
        return index == 0 ? BLACK : 2;
    }

    private float local(float t) {
        return t - itemStart;
    }

    private void next() {
        if (index + 1 < mythics.size()) {
            index++;
            itemStart = ticks;
        } else {
            close();
        }
    }

    private void close() {
        if (closing) return;
        closing = true;
        minecraft.setScreen(parent);
        parent.afterMythic();
    }

    @Override
    public void onClose() {
        close();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        // a click before the name is out would cut the moment short; after that it continues
        if (ticks - itemStart >= TEXT_START + 8) next();
        return true;
    }

    @Override
    public void tick() {
        ticks++;
        int l = ticks - itemStart, b = black();
        if (index == 0 && l == 2) play(SoundEvents.WARDEN_HEARTBEAT, 0.6F, 1.0F);
        if (index == 0 && l == 7) play(SoundEvents.WARDEN_HEARTBEAT, 0.55F, 1.0F);
        if (l == b) play(SoundEvents.LIGHTNING_BOLT_IMPACT, 0.7F, 0.6F);
        if (l == b + CRACK) play(SoundEvents.END_PORTAL_SPAWN, 1.2F, 0.45F);
        if (l == b + CRACK + OPEN + RISE - 4) {
            play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.9F, 0.8F);
            play(SoundEvents.BEACON_POWER_SELECT, 0.7F, 0.8F);
        }
        if (l >= TEXT_START && l < TEXT_START + 30 && (l - TEXT_START) % 2 == 0) play(SoundEvents.NOTE_BLOCK_HAT, 1.8F, 0.25F);
        if (l >= AUTO_NEXT) next();
    }

    private void play(SoundEvent sound, float pitch, float volume) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    private void play(net.minecraft.core.Holder<SoundEvent> sound, float pitch, float volume) {
        play(sound.value(), pitch, volume);
    }

    /** Drawn in {@link #render} instead: Screen.render calls this again before the widgets, which would paint over us. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF000000);
        float l = local(ticks + partialTick);
        int b = black();
        int red = Tier.color(MYTHIC);
        float cx = width / 2F, cy = height / 2F;

        if (l >= b) {
            float crack = Mth.clamp((l - b) / CRACK, 0, 1);
            float open = Mth.clamp((l - b - CRACK) / OPEN, 0, 1);
            float openE = 1 - (1 - open) * (1 - open);
            int gapHalf = (int) (openE * height / 2F);

            // the light behind the crack (only the opened part is visible)
            if (gapHalf > 0) {
                int y0 = (int) cy - gapHalf, y1 = (int) cy + gapHalf;
                g.enableScissor(0, y0, width, y1);
                drawCurtain(g, l, red);
                drawRays(g, cx, cy, l, red);
                drawRuneRing(g, cx, cy, l, red, openE);
                drawSparks(g, l, red);
                g.disableScissor();
            }
            // the crack itself: a bright line that tears from the centre outwards, then becomes the edges
            int half = (int) (width / 2F * (1 - (1 - crack) * (1 - crack) * (1 - crack)));
            int glowA = (int) (Mth.clamp(1 - open * 0.7F, 0, 1) * 200);
            for (int edge : new int[]{(int) cy - gapHalf, (int) cy + gapHalf}) {
                g.fill((int) cx - half, edge - 3, (int) cx + half, edge + 3, (glowA / 2 << 24) | red);
                g.fill((int) cx - half, edge - 1, (int) cx + half, edge + 1, (glowA << 24) | 0xFFE0E0);
            }
        }

        // the item rises out of the light
        Draw d = mythics.get(index);
        float rise = Mth.clamp((l - b - CRACK - OPEN / 2F) / RISE, 0, 1);
        if (rise > 0) {
            float s = 0.6F + 5.4F * PlayScreen.popScale(rise);
            float sway = Mth.sin(l * 0.08F) * 6;
            float bob = Mth.sin(l * 0.12F) * 4;
            g.pose().pushPose();
            g.pose().translate(cx, cy - 16 + bob, 150);
            g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(sway));
            g.pose().scale(s, s, 1);
            g.renderItem(d.prize().item(), -8, -8);
            g.pose().popPose();
        }
        // flash as it lands
        float flash = l - (b + CRACK + OPEN + RISE - 4);
        if (flash >= 0 && flash < 8) {
            int a = (int) ((1 - flash / 8) * 230);
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.fill(0, 0, width, height, (a << 24) | (flash < 2 ? 0xFFFFFF : red));
            g.pose().popPose();
        }

        // the name, typed out, then the tier
        if (l >= TEXT_START) drawTitle(g, d, l - TEXT_START, cx, red);

        super.render(g, mouseX, mouseY, partialTick); // the skip button on top
    }

    private void drawTitle(GuiGraphics g, Draw d, float tt, float cx, int red) {
        ItemStack item = d.prize().item();
        String name = item.getHoverName().getString();
        int shown = Mth.clamp((int) (tt / 2) + 1, 0, name.length());
        String part = name.substring(0, shown);
        float scale = Math.min(2.5F, (width - 40) / (float) Math.max(1, font.width(name)));
        int y = (int) (height * 0.70F);
        // a dark band behind the text so it reads over the rays
        int bandA = (int) (Mth.clamp(tt / 6, 0, 1) * 150);
        g.pose().pushPose();
        g.pose().translate(0, 0, 310);
        int bandTop = y - 10, bandBottom = y + (int) (10 * scale) + 46;
        g.fillGradient(0, bandTop, width, (bandTop + bandBottom) / 2, 0x00000000, bandA << 24);
        g.fillGradient(0, (bandTop + bandBottom) / 2, width, bandBottom, bandA << 24, 0x00000000);
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(cx - font.width(name) * scale / 2, y, 320);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, part, 1, 1, 0xFF000000 | (red & 0x7F2020), false);
        g.drawString(font, part, 0, 0, 0xFFFFFFFF, false);
        g.pose().popPose();
        if (shown < name.length()) return;
        float after = tt - name.length() * 2;
        int a = (int) (Mth.clamp(after / 6, 0, 1) * 255);
        if (a <= 4) return;
        Component tier = Component.translatable("gui.lottery.mythic.subtitle", Tier.name(MYTHIC), d.prize().count());
        g.pose().pushPose();
        g.pose().translate(0, 0, 320);
        int ty = y + (int) (10 * scale) + 6;
        g.drawString(font, tier, (int) cx - font.width(tier) / 2, ty, (a << 24) | 0xFFD0D0, false);
        int pips = MYTHIC + 1, pw = pips * 6 - 2;
        for (int k = 0; k < pips; k++) {
            int px = (int) cx - pw / 2 + k * 6;
            g.fill(px, ty + 13, px + 4, ty + 17, (a << 24) | red);
        }
        if (mythics.size() > 1) {
            String count = (index + 1) + " / " + mythics.size();
            g.drawString(font, count, (int) cx - font.width(count) / 2, ty + 24, (a / 2 << 24) | 0xFFFFFF, false);
        }
        if (after > 14 && (ticks / 10) % 2 == 0) {
            Component hint = Component.translatable("gui.lottery.mythic.continue");
            g.drawString(font, hint, (int) cx - font.width(hint) / 2, height - 22, 0xA0FFFFFF, false);
        }
        g.pose().popPose();
    }

    /** Surging red light: horizontal bands that swell and drift. */
    private void drawCurtain(GuiGraphics g, float l, int red) {
        g.fillGradient(0, 0, width, height, 0xFF2A0306, 0xFF5A0A10);
        for (int y = 0; y < height; y += 3) {
            float wave = Mth.sin(y * 0.045F + l * 0.18F) + Mth.sin(y * 0.11F - l * 0.07F);
            int a = (int) Mth.clamp(40 + wave * 35, 0, 140);
            g.fill(0, y, width, y + 3, (a << 24) | red);
        }
        // a soft, round core of light behind the item (stacked translucent ellipses)
        float cx = width / 2F, cy = height / 2F - 16;
        float pulse = 1 + 0.06F * Mth.sin(l * 0.15F);
        for (int k = 10; k >= 1; k--) {
            float rx = k * 13 * pulse, ry = k * 10 * pulse;
            fillEllipse(g, cx, cy, rx, ry, (14 << 24) | 0xFFB0B0);
        }
    }

    /** A filled ellipse from one-pixel rows. */
    private static void fillEllipse(GuiGraphics g, float cx, float cy, float rx, float ry, int argb) {
        int r = (int) ry;
        for (int dy = -r; dy <= r; dy++) {
            float f = 1 - (dy * dy) / (ry * ry);
            if (f <= 0) continue;
            int hw = (int) (rx * Math.sqrt(f));
            g.fill((int) cx - hw, (int) cy + dy, (int) cx + hw, (int) cy + dy + 1, argb);
        }
    }

    private void drawRays(GuiGraphics g, float cx, float cy, float l, int red) {
        int rays = 20;
        float length = Math.max(width, height);
        for (int k = 0; k < rays; k++) {
            double ang = Math.toRadians(k * (360.0 / rays) + l * 0.8);
            float ux = (float) Math.cos(ang), uy = (float) Math.sin(ang);
            for (float d = 30; d < length; d += 4) {
                float f = 1 - d / length;
                int a = (int) (f * 120);
                int x = (int) (cx + ux * d), y = (int) (cy - 16 + uy * d);
                g.fill(x, y, x + 2, y + 2, (a << 24) | (k % 2 == 0 ? 0xFFD0D0 : red));
            }
        }
    }

    /** A turning ring of rune marks around the item. */
    private void drawRuneRing(GuiGraphics g, float cx, float cy, float l, int red, float alpha) {
        float r = Math.min(width, height) * 0.30F;
        int marks = 24;
        for (int k = 0; k < marks; k++) {
            double ang = Math.toRadians(k * (360.0 / marks) - l * 1.5);
            int x = (int) (cx + Math.cos(ang) * r), y = (int) (cy - 16 + Math.sin(ang) * r * 0.9);
            int a = (int) (alpha * (k % 3 == 0 ? 230 : 140));
            int s = k % 3 == 0 ? 4 : 2;
            g.fill(x - s / 2, y - s / 2, x + s / 2 + 1, y + s / 2 + 1, (a << 24) | (k % 3 == 0 ? 0xFFE0E0 : red));
        }
        int ringA = (int) (alpha * 90);
        for (int k = 0; k < 120; k++) {
            double ang = Math.toRadians(k * 3 + l * 0.6);
            int x = (int) (cx + Math.cos(ang) * (r + 8)), y = (int) (cy - 16 + Math.sin(ang) * (r + 8) * 0.9);
            g.fill(x, y, x + 1, y + 1, (ringA << 24) | 0xFFC0C0);
        }
    }

    /** Embers rising through the light. */
    private void drawSparks(GuiGraphics g, float l, int red) {
        Random r = new Random(1234);
        for (int k = 0; k < 70; k++) {
            float x = r.nextFloat() * width;
            float speed = 0.8F + r.nextFloat() * 2.2F;
            float offset = r.nextFloat() * (height + 40);
            float y = height + 20 - ((l * speed + offset) % (height + 40));
            int a = 120 + r.nextInt(120);
            int s = 1 + r.nextInt(2);
            g.fill((int) (x + Mth.sin(l * 0.1F + k) * 6), (int) y, (int) (x + Mth.sin(l * 0.1F + k) * 6) + s, (int) y + s,
                    (a << 24) | (k % 4 == 0 ? 0xFFFFFF : red));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
