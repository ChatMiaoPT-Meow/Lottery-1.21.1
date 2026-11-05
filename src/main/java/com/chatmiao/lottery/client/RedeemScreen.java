package com.chatmiao.lottery.client;

import com.chatmiao.lottery.LotteryMod;
import com.chatmiao.lottery.data.Prize;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 彩票站 GUI: a gift box; clicking it redeems the tickets and shows what was inside. */
public class RedeemScreen extends Screen {
    private static final ResourceLocation GIFT = LotteryMod.id("textures/gui/gift_box.png");
    private static final int W = 210, H = 196, BOX = 64;

    private enum State { CLOSED, WAITING, OPENED }

    private final Payloads.OpenRedeem request;
    private State state = State.CLOSED;
    private List<Prize> prizes = List.of();
    private List<Component> errors = List.of();
    private int left, top, ticks, openedAt;
    private final List<float[]> confetti = new ArrayList<>(); // x, y, vx, vy, life, color
    private final Random rnd = new Random();

    public RedeemScreen(Payloads.OpenRedeem request) {
        super(Component.translatable("gui.lottery.redeem"));
        this.request = request;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        addRenderableWidget(Button.builder(Component.literal("×"), b -> onClose()).bounds(left + W - 16, top + 4, 12, 12).build());
        if (state == State.OPENED) {
            addRenderableWidget(Button.builder(Component.translatable("gui.lottery.redeem.done"), b -> onClose())
                    .bounds(left + W / 2 - 40, top + H - 24, 80, 18).build());
        }
    }

    public void onResult(Payloads.RedeemResult result) {
        prizes = result.prizes();
        errors = result.errors();
        state = State.OPENED;
        openedAt = ticks;
        if (!prizes.isEmpty()) {
            for (int i = 0; i < 70; i++) {
                confetti.add(new float[]{left + W / 2f, top + 70, (float) rnd.nextGaussian() * 3f, -2f - rnd.nextFloat() * 4f,
                        30 + rnd.nextInt(20), CONFETTI[rnd.nextInt(CONFETTI.length)]});
            }
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.2F, 0.8F));
        }
        rebuildWidgets();
    }

    private static final int[] CONFETTI = {0xFFE8463C, 0xFF2F8FE0, 0xFF3DBB5A, 0xFFF0C020, 0xFF9B59D0, 0xFFFF7AB0};

    private boolean overBox(double mx, double my) {
        int bx = left + (W - BOX) / 2, by = top + 44;
        return mx >= bx && mx < bx + BOX && my >= by && my < by + BOX;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (state == State.CLOSED && (button == 0 || button == 1) && overBox(mx, my)) {
            state = State.WAITING;
            List<String> ids = new ArrayList<>();
            for (Payloads.RedeemEntry e : request.entries()) ids.add(e.winId());
            PacketDistributor.sendToServer(new Payloads.Redeem(request.pos(), ids));
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.CHEST_OPEN, 1.2F, 0.6F));
            return true;
        }
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left - 2, top - 2, left + W + 2, top + H + 2, 0xFF7A1E1E);
        g.fill(left, top, left + W, top + H, 0xFFFFF7EA);
        g.fill(left, top, left + W, top + 22, 0xFFC8302E);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, Component.translatable("gui.lottery.redeem.title"), left + 8, top + 7, 0xFFFFFFFF, true);

        int n = request.entries().size();
        String names = request.entries().get(0).typeName() + (n > 1 ? " …" : "");
        centered(g, Component.translatable("gui.lottery.redeem.count", n, names), top + 30, 0xFF6A4A30);

        int bx = left + (W - BOX) / 2, by = top + 44;
        float t = ticks + partialTick;
        if (state == State.OPENED && !prizes.isEmpty()) {
            g.blit(GIFT, bx, by, BOX, BOX, 32, 0, 32, 32, 64, 32);
            drawPrizes(g, mouseX, mouseY);
        } else if (state == State.OPENED) {
            g.blit(GIFT, bx, by, BOX, BOX, 0, 0, 32, 32, 64, 32);
        } else {
            boolean hover = overBox(mouseX, mouseY);
            float bob = state == State.WAITING ? Mth.sin(t * 2.5F) * 3 : Mth.sin(t * 0.15F) * 2;
            g.pose().pushPose();
            g.pose().translate(bx + BOX / 2f, by + BOX / 2f + (state == State.WAITING ? 0 : bob), 0);
            if (state == State.WAITING) g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(bob * 2));
            float s = hover ? 1.08F : 1.0F;
            g.pose().scale(s, s, 1);
            g.blit(GIFT, -BOX / 2, -BOX / 2, BOX, BOX, 0, 0, 32, 32, 64, 32);
            g.pose().popPose();
            centered(g, Component.translatable(state == State.WAITING ? "gui.lottery.redeem.opening" : "gui.lottery.redeem.click"),
                    by + BOX + 8, 0xFF8A6A40);
        }

        int ey = top + H - 40 - errors.size() * 10;
        for (Component err : errors) {
            centered(g, Component.literal(font.substrByWidth(err, W - 12).getString()), ey, 0xFFC03030);
            ey += 10;
        }

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        for (float[] c : confetti) {
            g.fill((int) c[0], (int) c[1], (int) c[0] + 2, (int) c[1] + 3, (int) c[5]);
        }
        g.pose().popPose();
    }

    private void drawPrizes(GuiGraphics g, int mouseX, int mouseY) {
        int y0 = top + 112;
        centered(g, Component.translatable("gui.lottery.redeem.got"), y0, 0xFF3A8A3A);
        Prize hovered = null;
        if (prizes.size() <= 3) {
            // a few prizes: one row each with icon, name and amount
            for (int i = 0; i < prizes.size(); i++) {
                if (ticks - openedAt < i * 4) continue; // pop in one by one
                Prize p = prizes.get(i);
                Component line = p.describe();
                int rowW = 20 + font.width(line);
                int x = left + (W - rowW) / 2, y = y0 + 12 + i * 18;
                g.renderItem(p.item(), x, y);
                g.drawString(font, line, x + 20, y + 4, 0xFF3A3020, false);
                if (mouseX >= x && mouseX < x + rowW && mouseY >= y && mouseY < y + 16) hovered = p;
            }
        } else {
            // many prizes: icon grid with amounts
            int perRow = 7, shown = Math.min(prizes.size(), 14);
            for (int i = 0; i < shown; i++) {
                if (ticks - openedAt < i * 2) continue;
                int row = i / perRow, col = i % perRow;
                int inRow = Math.min(perRow, shown - row * perRow);
                int x = left + (W - inRow * 26) / 2 + col * 26 + 5, y = y0 + 12 + row * 20;
                Prize p = prizes.get(i);
                g.renderItem(p.item(), x, y);
                g.renderItemDecorations(font, p.item(), x, y, p.count() > 1 ? String.valueOf(p.count()) : null);
                if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) hovered = p;
            }
            if (prizes.size() > shown) {
                centered(g, Component.literal("+" + (prizes.size() - shown)), y0 + 54, 0xFF6A6A6A);
            }
        }
        if (hovered != null) {
            g.renderTooltip(font, hovered.item(), mouseX, mouseY);
        }
    }

    private void centered(GuiGraphics g, Component text, int y, int color) {
        g.drawString(font, text, left + (W - font.width(text)) / 2, y, color, false);
    }

    @Override
    public void tick() {
        ticks++;
        confetti.removeIf(c -> {
            c[0] += c[2];
            c[1] += c[3];
            c[3] += 0.25F;
            c[2] *= 0.97F;
            return --c[4] <= 0;
        });
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
