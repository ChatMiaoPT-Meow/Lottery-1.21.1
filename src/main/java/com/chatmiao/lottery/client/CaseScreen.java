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

    private int reelCount;
    /** Per reel: when it started spinning (0 = idle), small random stop offset, last tile under the pointer. */
    private long[] startMs;
    private float[] jitter;
    private int[] lastTile;
    private long lastTickSound;

    private int reelH, tileSize, tileW, winX, winW;

    public CaseScreen(Payloads.ScratchState state) {
        super(state);
    }

    @Override
    protected void onNewRound() {
        reelCount = round.reels.length;
        startMs = new long[reelCount];
        jitter = new float[reelCount];
        lastTile = new int[reelCount];
        for (int i = 0; i < reelCount; i++) jitter[i] = (rnd.nextFloat() - 0.5F) * 0.7F;
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
        if (spinning()) {
            button(Component.translatable("gui.lottery.case.skip"), left + panelW / 2 - 45, y, 90, b -> skip());
        } else {
            Button open = button(Component.translatable("gui.lottery.case.open"), left + panelW / 2 - 45, y, 90, b -> spin());
            open.active = !waiting;
        }
    }

    private void spin() {
        long now = System.currentTimeMillis();
        int k = 0;
        for (int i = 0; i < reelCount; i++) {
            if (isRevealed(i) || startMs[i] != 0) continue;
            startMs[i] = now + k * 120L; // reels start one after another
            k++;
        }
        play(SoundEvents.CHEST_OPEN, 1.2F, 0.6F);
        rebuildWidgets();
    }

    private void skip() {
        for (int i = 0; i < reelCount; i++) if (!isRevealed(i)) stop(i);
    }

    private long duration(int i) {
        return round.spinSeconds * 1000L + i * STAGGER_MS;
    }

    /** Reel position in tiles: the tile index currently under the pointer (fractional). */
    private float position(int i, long now) {
        float target = Roller.REEL_STOP + jitter[i];
        if (isRevealed(i)) return target;
        if (startMs[i] == 0 || now < startMs[i]) return START_POS;
        float p = Mth.clamp((now - startMs[i]) / (float) duration(i), 0, 1);
        float eased = 1 - (float) Math.pow(1 - p, 4);
        return START_POS + (target - START_POS) * eased;
    }

    private void stop(int i) {
        if (isRevealed(i)) return;
        Draw d = i < round.draws.size() ? round.draws.get(i) : Draw.EMPTY;
        int cx = left + winX + winW / 2, y = top + reelY(i);
        burstAt(cx - tileW / 2.0, y, tileW, reelH, d.won() ? 14 + d.tier() * 6 : 4, Tier.color(d.tier()));
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, Component.translatable("gui.lottery.case.title", round.typeName), left + 8, top + 6, 0xFFFFFFFF, true);
        g.drawString(font, Component.translatable("gui.lottery.scratch.sub", round.typeId, stock), left + 8, top + 18, 0xFFF0F0F0, false);

        long now = System.currentTimeMillis();
        ItemStack tooltip = ItemStack.EMPTY;
        for (int i = 0; i < reelCount; i++) {
            ItemStack t = drawReel(g, i, position(i, now), mouseX, mouseY);
            if (!t.isEmpty()) tooltip = t;
        }
        tickSounds(now);
        renderParticles(g);

        int noteY = top + panelH - 40;
        if (waiting) {
            centered(g, Component.translatable("gui.lottery.scratch.loading"), noteY, 0xFF9AA0AA);
        } else if (finished) {
            centered(g, resultBanner(), noteY, round.won() ? 0xFFFFC94A : 0xFF9AA0AA);
        } else if (!spinning()) {
            centered(g, Component.translatable("gui.lottery.case.hint"), noteY, 0xFF9AA0AA);
        }
        if (!tooltip.isEmpty()) g.renderTooltip(font, tooltip, mouseX, mouseY);
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
