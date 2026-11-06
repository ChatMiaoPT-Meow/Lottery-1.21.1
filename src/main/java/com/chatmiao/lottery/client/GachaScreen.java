package com.chatmiao.lottery.client;

import com.chatmiao.lottery.data.Draw;
import com.chatmiao.lottery.data.HubEntry;
import com.chatmiao.lottery.data.PlayStyle;
import com.chatmiao.lottery.data.PullRecord;
import com.chatmiao.lottery.data.Tier;
import com.chatmiao.lottery.data.TicketType;
import com.chatmiao.lottery.data.VoucherNames;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 卡池 (gacha screen): open pools on the left; the selected pool's banner, featured prizes, pity progress and
 * pull buttons on the right; odds table (概率公示) and pull history (抽卡记录) as overlays. Everything shown comes
 * from the server; a pull is just a request — the result arrives as a locked round and plays in its ticket GUI.
 */
public class GachaScreen extends Screen {
    private static final int LIST_W = 124, ROW_H = 30, TILE = 22;
    private static final int C_BG = 0xF01A1C22, C_PANEL = 0xFF23262D, C_INSET = 0xFF16181D, C_BORDER = 0xFF3A3F4A,
            C_TEXT = 0xFFE8E8E8, C_DIM = 0xFF9AA0AA, C_ACCENT = 0xFFFFC94A, C_SEL = 0xFF2F3A52, C_BAD = 0xFFE06060;
    private static final int OVERLAY_NONE = 0, OVERLAY_ODDS = 1, OVERLAY_HISTORY = 2;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** Remembered across reopen so coming back from a pull keeps the same pool selected. */
    private static int lastTypeId;

    private List<HubEntry> entries;
    private Map<Integer, Integer> balances;
    private int sel;
    private int listScroll;
    private boolean waiting;
    private int overlay = OVERLAY_NONE;
    private int overlayScroll;
    private List<PullRecord> history;
    private int ticks;

    private int W, H, left, top, rx, rw, bodyTop, bodyBottom;
    /** Voucher-fly animation before a pull is sent: start tick (-1 = idle), chips, multi flag, endpoints. */
    private int flyStart = -1, flyCount;
    private boolean flyMulti;
    private float flyFromX, flyFromY, flyToX, flyToY;
    private final Map<Integer, int[]> chipPos = new java.util.HashMap<>();
    private int[] singleCenter = new int[2], multiCenter = new int[2];
    private static final int FLY_TICKS = 9;

    public GachaScreen(Payloads.OpenHub data) {
        super(Component.translatable("gui.lottery.hub"));
        apply(data);
    }

    public void update(Payloads.OpenHub data) {
        apply(data);
        if (minecraft != null) rebuildWidgets();
    }

    private void apply(Payloads.OpenHub data) {
        entries = new ArrayList<>(data.entries());
        balances = data.balances();
        waiting = false;
        flyStart = -1;
        sel = 0;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).typeId == lastTypeId) sel = i;
    }

    public void setHistory(List<PullRecord> records) {
        history = records;
    }

    private HubEntry current() {
        return sel >= 0 && sel < entries.size() ? entries.get(sel) : null;
    }

    private int balance(int voucherId) {
        return balances.getOrDefault(voucherId, 0);
    }

    // ================================================================= layout / widgets

    @Override
    protected void init() {
        W = Mth.clamp(width - 24, 380, 560);
        H = Mth.clamp(height - 24, 230, 320);
        left = (width - W) / 2;
        top = (height - H) / 2;
        rx = left + LIST_W + 12;
        rw = left + W - 8 - rx;
        bodyTop = top + 32;
        bodyBottom = top + H - 8;

        addRenderableWidget(Button.builder(Component.literal("×"), b -> onClose()).bounds(left + W - 16, top + 6, 12, 12).build());
        if (overlay != OVERLAY_NONE) {
            return; // the overlay handles its own clicks
        }
        HubEntry e = current();
        if (e == null) return;
        int by = bodyBottom - 24;
        int bw = Math.min(110, (rw - 120) / 2);
        Button single = Button.builder(Component.translatable("gui.lottery.hub.pull_one", e.costSingle), b -> pull(false))
                .bounds(rx + rw - bw * (e.multi > 0 ? 2 : 1) - (e.multi > 0 ? 4 : 0), by, bw, 20).build();
        single.active = !waiting && balance(e.voucherId) >= e.costSingle;
        addRenderableWidget(single);
        singleCenter = new int[]{single.getX() + single.getWidth() / 2, single.getY() + 10};
        if (e.multi > 0) {
            Button multi = Button.builder(Component.translatable("gui.lottery.hub.pull_multi", e.multi, e.costMulti), b -> pull(true))
                    .bounds(rx + rw - bw, by, bw, 20).build();
            multi.active = !waiting && balance(e.voucherId) >= e.costMulti;
            addRenderableWidget(multi);
            multiCenter = new int[]{multi.getX() + multi.getWidth() / 2, multi.getY() + 10};
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.hub.odds"), b -> openOverlay(OVERLAY_ODDS))
                .bounds(rx, by + 4, 54, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.hub.history"), b -> openOverlay(OVERLAY_HISTORY))
                .bounds(rx + 58, by + 4, 54, 16).build());
    }

    /** The vouchers fly from the balance into the button first; the request goes out when they land. */
    private void pull(boolean multi) {
        HubEntry e = current();
        if (e == null || waiting) return;
        waiting = true;
        lastTypeId = e.typeId;
        flyStart = ticks;
        flyMulti = multi;
        flyCount = Mth.clamp(multi ? e.costMulti : e.costSingle, 1, 10);
        int[] from = chipPos.getOrDefault(e.voucherId, new int[]{left + W - 40, top + 13});
        int[] to = multi ? multiCenter : singleCenter;
        flyFromX = from[0];
        flyFromY = from[1];
        flyToX = to[0];
        flyToY = to[1];
        rebuildWidgets();
    }

    private void sendPull() {
        HubEntry e = current();
        if (e != null) PacketDistributor.sendToServer(new Payloads.HubPull(e.typeId, flyMulti));
    }

    private void drawFlyingVouchers(GuiGraphics g, float t) {
        if (flyStart < 0) return;
        HubEntry e = current();
        int c = 0xFF000000 | VoucherNames.color(e == null ? 0 : e.voucherId);
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        for (int k = 0; k < flyCount; k++) {
            float p = (t - flyStart - k * 0.6F) / (FLY_TICKS - 3);
            if (p <= 0 || p >= 1) continue;
            // arc over the panel: quadratic bezier with a control point above the midpoint
            float mx = (flyFromX + flyToX) / 2, my = Math.min(flyFromY, flyToY) - 40;
            float u = 1 - p;
            float x = u * u * flyFromX + 2 * u * p * mx + p * p * flyToX;
            float y = u * u * flyFromY + 2 * u * p * my + p * p * flyToY;
            g.fill((int) x - 5, (int) y - 4, (int) x + 5, (int) y + 4, 0xFFFFFFFF);
            g.fill((int) x - 4, (int) y - 3, (int) x + 4, (int) y + 3, c);
        }
        // a pulse on the button as they land
        float land = (t - flyStart - (FLY_TICKS - 3)) / 4F;
        if (land > 0 && land < 1) {
            int a = (int) ((1 - land) * 160);
            int r = (int) (10 + land * 30);
            g.fill((int) flyToX - r, (int) flyToY - r / 3, (int) flyToX + r, (int) flyToY + r / 3, (a << 24) | (c & 0xFFFFFF));
        }
        g.pose().popPose();
    }

    private void openOverlay(int which) {
        overlay = which;
        overlayScroll = 0;
        if (which == OVERLAY_HISTORY) {
            history = null;
            PacketDistributor.sendToServer(new Payloads.Request(Payloads.Request.HISTORY));
        }
        rebuildWidgets();
    }

    // ================================================================= input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return false;
        if (overlay != OVERLAY_NONE) {
            int[] o = overlayRect();
            boolean onClose = mx >= o[0] + o[2] - 16 && mx < o[0] + o[2] - 4 && my >= o[1] + 4 && my < o[1] + 16;
            boolean outside = mx < o[0] || mx >= o[0] + o[2] || my < o[1] || my >= o[1] + o[3];
            if (onClose || outside) {
                overlay = OVERLAY_NONE;
                rebuildWidgets();
            }
            return true;
        }
        if (mx >= left + 6 && mx < left + 6 + LIST_W && my >= bodyTop && my < bodyBottom) {
            int i = listScroll + (int) ((my - bodyTop) / ROW_H);
            if (i < entries.size() && i != sel) {
                sel = i;
                lastTypeId = entries.get(i).typeId;
                rebuildWidgets();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int d = sy > 0 ? -1 : 1;
        if (overlay != OVERLAY_NONE) {
            overlayScroll = Math.max(0, overlayScroll + d);
            return true;
        }
        if (mx >= left + 6 && mx < left + 6 + LIST_W) {
            int visible = (bodyBottom - bodyTop) / ROW_H;
            listScroll = Mth.clamp(listScroll + d, 0, Math.max(0, entries.size() - visible));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    // ================================================================= rendering

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left - 2, top - 2, left + W + 2, top + H + 2, 0xFF0C0D10);
        g.fill(left, top, left + W, top + H, C_BG);
        g.fill(left, top, left + W, top + 26, 0xFF2A2440);
        g.fill(left, top + 26, left + W, top + 27, C_ACCENT);
        g.fill(left + 4, bodyTop - 2, left + 8 + LIST_W, bodyBottom + 2, C_PANEL);
        g.fill(rx - 2, bodyTop - 2, rx + rw + 2, bodyBottom + 2, C_PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean ov = overlay != OVERLAY_NONE;
        int mx = ov ? -10_000 : mouseX, my = ov ? -10_000 : mouseY;
        super.render(g, mx, my, partialTick);
        ItemStack tooltip = ItemStack.EMPTY;

        // ---- header: title + voucher balances
        g.drawString(font, Component.translatable("gui.lottery.hub.title"), left + 8, top + 9, C_ACCENT, true);
        drawBalances(g);

        // ---- left: pools
        int visible = (bodyBottom - bodyTop) / ROW_H;
        for (int r = 0; r < visible && listScroll + r < entries.size(); r++) {
            int i = listScroll + r;
            HubEntry e = entries.get(i);
            int y = bodyTop + r * ROW_H;
            boolean hover = mx >= left + 6 && mx < left + 6 + LIST_W && my >= y && my < y + ROW_H;
            if (i == sel) g.fill(left + 6, y, left + 6 + LIST_W, y + ROW_H - 2, C_SEL);
            else if (hover) g.fill(left + 6, y, left + 6 + LIST_W, y + ROW_H - 2, 0xFF2A2E36);
            g.fill(left + 6, y, left + 9, y + ROW_H - 2, 0xFF000000 | TicketColors.of(e.typeId));
            g.drawString(font, font.plainSubstrByWidth(e.name, LIST_W - 10), left + 13, y + 4, i == sel ? C_TEXT : 0xFFC8CCD4, false);
            Component sub = e.endTime > 0 ? remaining(e.endTime) : PlayStyle.byId(e.style).label();
            g.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(sub, LIST_W - 10)), left + 13, y + 16,
                    e.endTime > 0 ? 0xFFE0A050 : C_DIM, false);
        }

        HubEntry e = current();
        if (e == null) {
            Component none = Component.translatable("gui.lottery.hub.empty");
            g.drawString(font, none, rx + (rw - font.width(none)) / 2, (bodyTop + bodyBottom) / 2 - 4, C_DIM, false);
        } else {
            tooltip = drawBanner(g, e, mx, my);
        }
        if (waiting) {
            Component w = Component.translatable("gui.lottery.hub.pulling");
            g.drawString(font, w, rx + rw - font.width(w), bodyBottom - 36, C_ACCENT, false);
        }

        drawFlyingVouchers(g, ticks + partialTick);
        if (ov) {
            drawOverlay(g, mouseX, mouseY);
        } else if (!tooltip.isEmpty()) {
            g.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private void drawBalances(GuiGraphics g) {
        // vouchers the player carries, plus the ones the open pools use (shown as 0)
        Map<Integer, Integer> shown = new TreeMap<>();
        for (HubEntry e : entries) shown.put(e.voucherId, 0);
        balances.forEach((k, v) -> { if (v > 0) shown.put(k, v); });
        int x = left + W - 22;
        for (Map.Entry<Integer, Integer> b : shown.entrySet()) {
            String text = VoucherNames.name(b.getKey()) + " ×" + b.getValue();
            int w = font.width(text) + 12;
            if (x - w < left + 60) break;
            x -= w;
            g.fill(x, top + 9, x + 8, top + 17, 0xFF000000 | VoucherNames.color(b.getKey()));
            chipPos.put(b.getKey(), new int[]{x + 4, top + 13});
            g.drawString(font, text, x + 11, top + 9, b.getValue() > 0 ? C_TEXT : C_DIM, false);
            x -= 8;
        }
    }

    /** The selected pool. @return item under the mouse (for a tooltip) */
    private ItemStack drawBanner(GuiGraphics g, HubEntry e, int mx, int my) {
        ItemStack tooltip = ItemStack.EMPTY;
        int color = 0xFF000000 | TicketColors.of(e.typeId);
        int dark = 0xFF000000 | TicketColors.dark(e.typeId);
        int bh = 54;
        g.fill(rx, bodyTop, rx + rw, bodyTop + bh, dark);
        g.fillGradient(rx, bodyTop, rx + rw, bodyTop + bh, (0x60 << 24) | (color & 0xFFFFFF), 0x00000000);
        g.fill(rx, bodyTop, rx + rw, bodyTop + 3, color);
        // a slanted band of light sweeping across the banner every few seconds
        float sweep = ((ticks + minecraft.getTimer().getGameTimeDeltaPartialTick(false)) % 70) / 70F;
        int bandX = rx - 40 + (int) (sweep * (rw + 80));
        g.enableScissor(rx, bodyTop, rx + rw, bodyTop + bh);
        for (int row = 0; row < bh; row += 2) {
            int shift = (bh - row) / 3; // slant
            for (int dx = 0; dx < 18; dx += 2) {
                int a = (int) (55 * (1 - Math.abs(dx - 9) / 9F));
                int x = bandX + dx + shift;
                g.fill(x, bodyTop + row, x + 2, bodyTop + row + 2, (a << 24) | 0xFFFFFF);
            }
        }
        g.disableScissor();
        // drifting sparkles on the banner
        for (int k = 0; k < 14; k++) {
            int sx = rx + (int) ((k * 53 + ticks * (0.3 + k % 3 * 0.2)) % rw);
            int sy = bodyTop + 6 + (k * 17) % (bh - 10);
            int a = (int) (90 + 80 * Math.sin((ticks + k * 9) * 0.15));
            g.fill(sx, sy, sx + 1 + k % 2, sy + 1 + k % 2, (a << 24) | 0xFFFFFF);
        }
        g.pose().pushPose();
        g.pose().translate(rx + 8, bodyTop + 8, 0);
        g.pose().scale(1.6F, 1.6F, 1);
        g.drawString(font, font.plainSubstrByWidth(e.name, (int) ((rw - 16) / 1.6F)), 0, 0, 0xFFFFFFFF, true);
        g.pose().popPose();
        Component tags = Component.translatable("gui.lottery.hub.tags", PlayStyle.byId(e.style).label(),
                Component.translatable(e.delivery == TicketType.DELIVER_TICKET ? "gui.lottery.hub.deliver_ticket" : "gui.lottery.hub.deliver_direct"));
        g.drawString(font, tags, rx + 8, bodyTop + 26, 0xFFE0E0E0, false);
        Component when = e.endTime > 0
                ? Component.translatable("gui.lottery.hub.until", TIME.format(Instant.ofEpochMilli(e.endTime).atZone(ZoneId.systemDefault())),
                remaining(e.endTime))
                : Component.translatable("gui.lottery.hub.permanent");
        if (e.weekMask != 0) when = Component.empty().append(when).append("  ").append(weekdays(e.weekMask));
        g.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(when, rw - 16)), rx + 8, bodyTop + 38, 0xFFC0C4CC, false);

        // featured prizes: the rarest items first
        int y = bodyTop + bh + 6;
        g.drawString(font, Component.translatable("gui.lottery.hub.featured"), rx, y, C_ACCENT, false);
        y += 11;
        List<HubEntry.OddsItem> items = new ArrayList<>(e.items);
        items.sort((a, b) -> Integer.compare(b.tier(), a.tier()));
        int perRow = Math.max(1, (rw + 3) / (TILE + 3));
        for (int i = 0; i < Math.min(items.size(), perRow); i++) {
            HubEntry.OddsItem it = items.get(i);
            int x = rx + i * (TILE + 3);
            int tc = 0xFF000000 | Tier.color(it.tier());
            g.fill(x, y, x + TILE, y + TILE, tc);
            g.fill(x + 1, y + 1, x + TILE - 1, y + TILE - 1, C_INSET);
            g.fill(x + 1, y + TILE - 3, x + TILE - 1, y + TILE - 1, tc);
            g.renderItem(it.item(), x + (TILE - 16) / 2, y + (TILE - 16) / 2 - 1);
            if (mx >= x && mx < x + TILE && my >= y && my < y + TILE) tooltip = it.item();
        }
        if (items.isEmpty()) g.drawString(font, Component.translatable("gui.lottery.hub.no_items"), rx, y + 6, C_DIM, false);
        y += TILE + 8;

        // odds
        String p1 = pct(e.winChance);
        Component odds = e.multi > 0
                ? Component.translatable("gui.lottery.hub.odds_line_multi", p1, e.multi, pct(multiChance(e)))
                : Component.translatable("gui.lottery.hub.odds_line", p1);
        g.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(odds, rw)), rx, y, C_TEXT, false);
        y += 12;

        // pity progress
        if (e.pityActive()) {
            int pullsLeft = Math.max(1, e.pityCount - e.pityNow);
            String count = e.pityNow + "/" + e.pityCount;
            Component pity = Component.translatable("gui.lottery.hub.pity", pullsLeft, Tier.name(e.pityTier));
            g.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(pity, rw - font.width(count) - 6)), rx, y, C_DIM, false);
            g.drawString(font, count, rx + rw - font.width(count), y, C_DIM, false);
            y += 11;
            int tc = Tier.color(e.pityTier);
            boolean close = pullsLeft <= Math.max(3, e.pityCount / 10);
            if (close) {
                // nearly there: the bar glows and pulses
                int a = (int) (90 + 90 * Mth.sin(ticks * 0.35F));
                g.fill(rx - 2, y - 2, rx + rw + 2, y + 7, (a << 24) | tc);
            }
            g.fill(rx, y, rx + rw, y + 5, C_INSET);
            int fill = (int) (rw * Mth.clamp(e.pityNow / (double) e.pityCount, 0, 1));
            g.fill(rx, y, rx + fill, y + 5, 0xFF000000 | tc);
            if (fill > 6) {
                // a highlight running along the filled part
                int hx = rx + (int) ((ticks * 2.5F) % fill);
                g.fill(hx, y, Math.min(rx + fill, hx + 6), y + 5, 0x90FFFFFF);
            }
            y += 8;
        }
        if (e.guaranteeTier >= 0 && e.multi > 1) {
            g.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(
                    Component.translatable("gui.lottery.hub.guarantee", e.multi, Tier.name(e.guaranteeTier)), rw)), rx, y + 2, C_DIM, false);
        }

        // what a pull costs and what the player has
        int have = balance(e.voucherId);
        Component cost = Component.translatable("gui.lottery.hub.have", VoucherNames.name(e.voucherId), have);
        int cy = bodyBottom - 36;
        int cx = rx + rw - font.width(cost);
        if (!waiting) {
            g.fill(cx - 11, cy, cx - 3, cy + 8, 0xFF000000 | VoucherNames.color(e.voucherId));
            g.drawString(font, cost, cx, cy, have >= e.costSingle ? C_TEXT : C_BAD, false);
        }
        return tooltip;
    }

    private static double multiChance(HubEntry e) {
        if (e.guaranteeTier >= 0 && e.multi > 1) return 1;
        return 1 - Math.pow(1 - e.winChance, e.multi);
    }

    private Component weekdays(int mask) {
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d < 7; d++) {
            if ((mask & (1 << d)) == 0) continue;
            if (!sb.isEmpty()) sb.append(Component.translatable("gui.lottery.hub.sep").getString());
            sb.append(Component.translatable("gui.lottery.weekday." + d).getString());
        }
        return Component.translatable("gui.lottery.hub.weekly", sb.toString());
    }

    private static Component remaining(long end) {
        long ms = Math.max(0, end - System.currentTimeMillis());
        long minutes = ms / 60_000, hours = minutes / 60, days = hours / 24;
        if (days > 0) return Component.translatable("gui.lottery.hub.left_days", days, hours % 24);
        if (hours > 0) return Component.translatable("gui.lottery.hub.left_hours", hours, minutes % 60);
        return Component.translatable("gui.lottery.hub.left_minutes", Math.max(1, minutes));
    }

    // ================================================================= overlays

    private int[] overlayRect() {
        int w = Math.min(W - 20, 360), h = H - 20;
        return new int[]{left + (W - w) / 2, top + 10, w, h};
    }

    private void drawOverlay(GuiGraphics g, int mouseX, int mouseY) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(0, 0, width, height, 0xA0000000);
        int[] o = overlayRect();
        int x = o[0], y = o[1], w = o[2], h = o[3];
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, C_ACCENT);
        g.fill(x, y, x + w, y + h, 0xFF1A1C22);
        HubEntry e = current();
        Component title = overlay == OVERLAY_ODDS
                ? Component.translatable("gui.lottery.hub.odds_title", e == null ? "" : e.name)
                : Component.translatable("gui.lottery.hub.history_title");
        g.drawString(font, title, x + 8, y + 6, C_ACCENT, false);
        boolean hoverClose = mouseX >= x + w - 16 && mouseX < x + w - 4 && mouseY >= y + 4 && mouseY < y + 16;
        g.drawString(font, "×", x + w - 12, y + 6, hoverClose ? 0xFFFFFFFF : C_DIM, false);
        g.fill(x + 6, y + 18, x + w - 6, y + 19, C_BORDER);

        int listTop = y + 22, listBottom = y + h - 6;
        g.enableScissor(x, listTop, x + w, listBottom);
        ItemStack tooltip = ItemStack.EMPTY;
        if (overlay == OVERLAY_ODDS && e != null) {
            tooltip = drawOdds(g, e, x + 8, listTop, w - 16, listBottom, mouseX, mouseY);
        } else if (overlay == OVERLAY_HISTORY) {
            tooltip = drawHistory(g, x + 8, listTop, w - 16, listBottom, mouseX, mouseY);
        }
        g.disableScissor();
        if (!tooltip.isEmpty()) g.renderTooltip(font, tooltip, mouseX, mouseY);
        g.pose().popPose();
    }

    private ItemStack drawOdds(GuiGraphics g, HubEntry e, int x, int top0, int w, int bottom, int mx, int my) {
        ItemStack tooltip = ItemStack.EMPTY;
        int y = top0 - overlayScroll * 12;
        double empty = 1 - e.winChance;
        g.drawString(font, Component.translatable("gui.lottery.hub.odds_empty", pct(empty)), x, y, C_DIM, false);
        y += 14;
        for (HubEntry.OddsPool p : e.pools) {
            int tc = 0xFF000000 | Tier.color(p.tier());
            g.fill(x, y, x + 3, y + 9, tc);
            g.drawString(font, Component.empty().append(Tier.name(p.tier())).append("  " + p.name()), x + 6, y + 1, C_TEXT, false);
            String pc = pct(p.chance());
            g.drawString(font, pc, x + w - font.width(pc), y + 1, tc, false);
            y += 13;
            for (HubEntry.OddsItem it : e.items) {
                if (it.tier() != p.tier()) continue;
                if (y > top0 - 18 && y < bottom) {
                    g.renderItem(it.item(), x + 8, y);
                    String amount = it.min() == it.max() ? "×" + it.min() : "×" + it.min() + "~" + it.max();
                    String name = font.plainSubstrByWidth(it.item().getHoverName().getString(), w - 120);
                    g.drawString(font, name, x + 28, y + 4, 0xFFC8CCD4, false);
                    g.drawString(font, amount, x + w - 90, y + 4, C_DIM, false);
                    String ic = pct(it.chance());
                    g.drawString(font, ic, x + w - font.width(ic), y + 4, C_TEXT, false);
                    if (mx >= x + 8 && mx < x + 24 && my >= y && my < y + 16) tooltip = it.item();
                }
                y += 18;
            }
            y += 4;
        }
        if (e.pityActive()) {
            g.drawString(font, Component.translatable("gui.lottery.hub.pity_rule", e.pityCount, Tier.name(e.pityTier)), x, y + 2, C_DIM, false);
        }
        return tooltip;
    }

    private ItemStack drawHistory(GuiGraphics g, int x, int top0, int w, int bottom, int mx, int my) {
        if (history == null) {
            g.drawString(font, Component.translatable("gui.lottery.scratch.loading"), x, top0 + 4, C_DIM, false);
            return ItemStack.EMPTY;
        }
        if (history.isEmpty()) {
            g.drawString(font, Component.translatable("gui.lottery.hub.history_empty"), x, top0 + 4, C_DIM, false);
            return ItemStack.EMPTY;
        }
        ItemStack tooltip = ItemStack.EMPTY;
        int y = top0 - overlayScroll * 20;
        for (PullRecord r : history) {
            if (y > top0 - 20 && y < bottom) {
                g.fill(x, y, x + w, y + 19, 0xFF20232A);
                String t = TIME.format(Instant.ofEpochMilli(r.time).atZone(ZoneId.systemDefault()));
                g.drawString(font, t, x + 3, y + 6, C_DIM, false);
                String name = font.plainSubstrByWidth(r.typeName, 70) + " ×" + r.draws;
                g.drawString(font, name, x + 70, y + 6, C_TEXT, false);
                int ix = x + 160;
                if (r.wins.isEmpty()) {
                    g.drawString(font, Component.translatable("gui.lottery.cards.empty"), ix, y + 6, C_DIM, false);
                }
                List<Draw> wins = new ArrayList<>(r.wins);
                wins.sort((a, b) -> Integer.compare(b.tier(), a.tier()));
                for (Draw d : wins) {
                    if (ix + 17 > x + w) break;
                    g.fill(ix, y + 1, ix + 17, y + 18, 0xFF000000 | Tier.color(d.tier()));
                    g.fill(ix + 1, y + 2, ix + 16, y + 17, C_INSET);
                    g.renderItem(d.prize().item(), ix, y + 1);
                    if (mx >= ix && mx < ix + 17 && my >= y && my < y + 19) tooltip = d.prize().item();
                    ix += 18;
                }
            }
            y += 20;
        }
        return tooltip;
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, v < 0.01 ? "%.3f%%" : "%.2f%%", v * 100);
    }

    @Override
    public void tick() {
        ticks++;
        if (flyStart >= 0) {
            int k = ticks - flyStart;
            if (k <= flyCount) minecraft.getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(),
                            1.2F + k * 0.08F, 0.35F));
            if (k == FLY_TICKS) sendPull();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
