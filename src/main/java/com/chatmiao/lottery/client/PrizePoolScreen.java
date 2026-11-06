package com.chatmiao.lottery.client;

import com.chatmiao.lottery.data.PrizePool;
import com.chatmiao.lottery.data.TicketType;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * 奖池设置 (OP only): reusable prize pools. A pool is a rarity and a weighted item list; 卡池设置 then picks
 * which pools a ticket / gacha pool draws from and how likely each one is.
 * <pre>
 * ┌ 奖池列表 ┬ #3 · 金色奖池 *                                   ┐
 * │ ■ 普通…  │ 名称 [________]      稀有度 [◀ 传说 ▶]             │
 * │ ■ 稀有…  │ 被 2 个卡池使用：#1 …, #4 …                          │
 * │          │ 物品              │ [搜索物品]                      │
 * │ [+ 新建] │ …                 │ ▦▦▦▦▦▦                         │
 * │ [← 菜单] │ 权重/数量          │ [添加副手物品]                   │
 * └──────────┴ status …                      [删除奖池] [保存]     ┘
 * </pre>
 */
public class PrizePoolScreen extends Screen {
    private static final int LIST_W = 112, ROW_H = 18, ITEM_ROW_H = 18, CELL = 18;
    private static final int S_LIST = 0, S_ITEMS = 1, S_RESULTS = 2;
    private static final int C_PANEL = 0xF0202329, C_COL = 0xFF2B2F36, C_BORDER = 0xFF4A505C, C_INSET = 0xFF1A1D22,
            C_TEXT = 0xFFE8E8E8, C_DIM = 0xFF9AA0AA, C_ACCENT = 0xFFFFC94A, C_SEL = 0xFF3D5A80, C_BAD = 0xFFE06060,
            C_HOVER = 0xFF30343C;

    private List<PrizePool> pools = new ArrayList<>();
    private Map<Integer, List<String>> usage = Map.of();
    private PrizePool edit;
    private boolean dirty, loading, pendingDelete;
    private Integer pendingSwitch;
    private int selItem = -1;
    private final int[] scroll = new int[3];
    private int dragging = -1;
    private final List<ItemStack> results = new ArrayList<>();
    private Component status = Component.empty();
    private int statusColor = C_DIM;

    // layout
    private int W, H, left, top, cx, cw, cb, listRows;
    private int colAX, colAW, colBX, colBW, y0, itemY, itemRows, editY, resultY, resultCols, resultRows;

    private EditBox nameBox, weightBox, minBox, maxBox, searchBox;

    private Button delItemBtn, offhandBtn, deleteBtn;

    public PrizePoolScreen(Payloads.OpenPrizePools data) {
        super(Component.translatable("gui.lottery.pools"));
        apply(data);
    }

    public void update(Payloads.OpenPrizePools data) {
        apply(data);
        if (minecraft != null) refresh();
    }

    private void apply(Payloads.OpenPrizePools data) {
        pools = new ArrayList<>(data.pools());
        usage = data.usage();
        PrizePool target = find(data.selectId());
        if (target == null && edit != null && edit.id > 0 && !dirty) target = find(edit.id);
        if (target != null) {
            int keep = edit != null && edit.id == target.id ? selItem : -1;
            select(target);
            selItem = keep < edit.items.size() ? keep : -1;
        } else if (edit == null || (edit.id > 0 && find(edit.id) == null)) {
            // first open, or the pool being shown was just deleted
            if (pools.isEmpty()) newPool();
            else select(pools.get(0));
        }
    }

    private PrizePool find(int id) {
        for (PrizePool p : pools) if (p.id == id) return p;
        return null;
    }

    private void select(PrizePool p) {
        edit = p.copy();
        dirty = pendingDelete = false;
        pendingSwitch = null;
        selItem = -1;
        scroll[S_ITEMS] = 0;
    }

    private void newPool() {
        edit = new PrizePool();
        edit.name = Component.translatable("gui.lottery.pools.default_name").getString();
        dirty = true;
        pendingSwitch = null;
        pendingDelete = false;
        selItem = -1;
    }

    private TicketType.PoolItem item() {
        return selItem >= 0 && selItem < edit.items.size() ? edit.items.get(selItem) : null;
    }

    private List<String> users() {
        return edit.id > 0 ? usage.getOrDefault(edit.id, List.of()) : List.of();
    }

    private void touch() {
        if (!loading) {
            dirty = true;
            pendingSwitch = null;
            pendingDelete = false;
        }
    }

    // ================================================================= layout + widgets

    @Override
    protected void init() {
        W = Mth.clamp(width - 24, 420, 600);
        H = Mth.clamp(height - 24, 238, 340);
        left = (width - W) / 2;
        top = (height - H) / 2;
        listRows = (H - 70) / ROW_H;
        cx = left + LIST_W + 12;
        cw = left + W - 6 - cx;
        cb = top + H - 30;
        y0 = top + 56;
        colAX = cx + 4;
        colAW = (cw - 12) * 11 / 20;
        colBX = colAX + colAW + 8;
        colBW = cx + cw - 4 - colBX;
        itemY = y0 + 12;
        editY = cb - 34;
        itemRows = Math.max(2, (editY - 4 - itemY) / ITEM_ROW_H);
        resultY = y0 + 18;
        resultCols = Math.max(4, (colBW - 6) / CELL);
        resultRows = Math.max(1, (cb - 20 - resultY) / CELL);

        nameBox = textBox(cx + 30, top + 24, Math.min(200, cw - 40), TicketType.MAX_NAME, s -> { edit.name = s; touch(); });

        weightBox = numBox(colAX + 30, editY, 44, v -> { if (item() != null) { item().weight = v; touch(); } });
        minBox = numBox(colAX + 30, editY + 17, 36, v -> { if (item() != null) { item().min = v; touch(); } });
        maxBox = numBox(colAX + 78, editY + 17, 36, v -> { if (item() != null) { item().max = v; touch(); } });
        delItemBtn = addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.delete"), b -> deleteItem())
                .bounds(colAX + colAW - 40, editY + 17, 40, 14).build());
        searchBox = textBox(colBX, y0, colBW, 50, s -> updateResults());
        searchBox.setHint(Component.translatable("gui.lottery.terminal.search"));
        offhandBtn = addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.offhand"), b -> addOffhand())
                .bounds(colBX, cb - 16, colBW, 14).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.pools.new"), b -> clickNew())
                .bounds(left + 4, top + H - 46, LIST_W, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.menu.back"),
                        b -> PacketDistributor.sendToServer(new Payloads.Request(Payloads.Request.MENU)))
                .bounds(left + 4, top + H - 24, LIST_W, 16).build());
        int bx = left + W - 6;
        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.save"), b -> save())
                .bounds(bx - 52, top + H - 24, 52, 16).build());
        deleteBtn = addRenderableWidget(Button.builder(Component.translatable("gui.lottery.pools.delete"), b -> deletePool())
                .bounds(bx - 126, top + H - 24, 70, 16).build());

        refresh();
        updateResults();
    }

    private EditBox textBox(int x, int y, int w, int max, java.util.function.Consumer<String> onChange) {
        EditBox box = new EditBox(font, x, y, w, 14, Component.empty());
        box.setMaxLength(max);
        box.setResponder(s -> { if (!loading) onChange.accept(s); });
        return addRenderableWidget(box);
    }

    private EditBox numBox(int x, int y, int w, IntConsumer onChange) {
        EditBox box = new EditBox(font, x, y, w, 14, Component.empty());
        box.setMaxLength(7);
        box.setFilter(s -> s.matches("\\d*"));
        box.setResponder(s -> { if (!loading) onChange.accept(parse(s)); });
        return addRenderableWidget(box);
    }

    private static int parse(String s) {
        try {
            return s == null || s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void refresh() {
        if (nameBox == null) return;
        loading = true;
        nameBox.setValue(edit.name);

        TicketType.PoolItem it = item();
        weightBox.visible = minBox.visible = maxBox.visible = delItemBtn.visible = it != null;
        if (it != null) {
            weightBox.setValue(String.valueOf(it.weight));
            minBox.setValue(String.valueOf(it.min));
            maxBox.setValue(String.valueOf(it.max));
        }
        boolean used = !users().isEmpty();
        deleteBtn.active = edit.id > 0 && !used;
        deleteBtn.setMessage(Component.translatable(pendingDelete ? "gui.lottery.pools.delete_confirm" : "gui.lottery.pools.delete"));
        deleteBtn.setTooltip(used ? net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("gui.lottery.pools.delete_in_use")) : null);
        loading = false;
    }

    // ================================================================= actions

    private void setStatus(String key, int color, Object... args) {
        status = Component.translatable(key, args);
        statusColor = color;
    }

    private boolean confirmDiscard(int target) {
        if (!dirty) return true;
        if (pendingSwitch != null && pendingSwitch == target) return true;
        pendingSwitch = target;
        setStatus("gui.lottery.terminal.discard", C_BAD);
        return false;
    }

    private void clickPool(PrizePool p) {
        if (edit.id == p.id && !dirty) return;
        if (!confirmDiscard(p.id)) return;
        select(p);
        setStatus("gui.lottery.terminal.selected", C_DIM, p.id);
        refresh();
    }

    private void clickNew() {
        if (!confirmDiscard(-1)) return;
        newPool();
        setStatus("gui.lottery.pools.new_hint", C_DIM);
        refresh();
    }

    private void addItem(ItemStack stack) {
        if (stack.isEmpty()) return;
        if (edit.items.size() >= TicketType.MAX_ITEMS) {
            setStatus("gui.lottery.terminal.pool_full", C_BAD);
            return;
        }
        TicketType.PoolItem it = new TicketType.PoolItem();
        it.item = stack.copyWithCount(1);
        edit.items.add(it);
        selItem = edit.items.size() - 1;
        if (selItem >= scroll[S_ITEMS] + itemRows) scroll[S_ITEMS] = selItem - itemRows + 1;
        touch();
        setStatus("gui.lottery.terminal.item_added", C_DIM, stack.getHoverName());
        refresh();
    }

    private void deleteItem() {
        if (item() == null) return;
        edit.items.remove(selItem);
        selItem = -1;
        scroll[S_ITEMS] = clampScroll(scroll[S_ITEMS], edit.items.size(), itemRows);
        touch();
        refresh();
    }

    private void addOffhand() {
        ItemStack off = minecraft.player.getOffhandItem();
        if (off.isEmpty()) {
            setStatus("gui.lottery.terminal.offhand_empty", C_BAD);
            return;
        }
        addItem(off);
    }

    private void save() {
        for (TicketType.PoolItem it : edit.items) {
            if (it.min > it.max || it.min < 1) {
                setStatus("gui.lottery.terminal.bad_range", C_BAD, it.item.getHoverName());
                return;
            }
        }
        PrizePool out = edit.copy();
        out.sanitize();
        PacketDistributor.sendToServer(new Payloads.SavePrizePool(out));
        setStatus("gui.lottery.terminal.saving", C_DIM);
    }

    private void deletePool() {
        if (edit.id <= 0 || !users().isEmpty()) return;
        if (!pendingDelete) {
            pendingDelete = true;
            setStatus("gui.lottery.pools.delete_hint", C_BAD, edit.name);
            refresh();
            return;
        }
        PacketDistributor.sendToServer(new Payloads.DeletePrizePool(edit.id));
        pendingDelete = false;
    }

    private void updateResults() {
        results.clear();
        scroll[S_RESULTS] = 0;
        if (searchBox != null) results.addAll(ItemSearch.find(searchBox.getValue(), 400));
    }

    /** Dev/demo hook: search for items and select an item row, as a user would. */
    public void demo(String search, int itemIndex) {
        if (search != null) searchBox.setValue(search);
        if (itemIndex >= 0 && itemIndex < edit.items.size()) selItem = itemIndex;
        refresh();
    }

    // ================================================================= scroll regions

    /** {x, y, w, h, rowH, rows visible, total} */
    private int[] region(int id) {
        return switch (id) {
            case S_LIST -> new int[]{left + 4, top + 20, LIST_W, listRows * ROW_H, ROW_H, listRows, pools.size()};
            case S_ITEMS -> new int[]{colAX, itemY, colAW, itemRows * ITEM_ROW_H, ITEM_ROW_H, itemRows, edit.items.size()};
            case S_RESULTS -> new int[]{colBX, resultY, colBW, resultRows * CELL, CELL, resultRows, (results.size() + resultCols - 1) / resultCols};
            default -> null;
        };
    }

    private static boolean inside(int[] r, double mx, double my) {
        return r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private static boolean onBar(int[] r, double mx, double my) {
        return inside(r, mx, my) && r[6] > r[5] && mx >= r[0] + r[2] - 5;
    }

    private void dragTo(int id, double my) {
        int[] r = region(id);
        if (r == null || r[6] <= r[5]) return;
        double f = Mth.clamp((my - r[1]) / r[3], 0, 1);
        scroll[id] = clampScroll((int) Math.round(f * (r[6] - r[5])), r[6], r[5]);
    }

    private static int clampScroll(int v, int size, int visible) {
        return Math.max(0, Math.min(v, size - visible));
    }

    private void drawScrollbar(GuiGraphics g, int id) {
        int[] r = region(id);
        if (r == null || r[6] <= r[5]) return;
        int x = r[0] + r[2] - 3, y = r[1], h = r[3];
        g.fill(x, y, x + 3, y + h, 0xFF15171B);
        int thumb = Math.max(8, h * r[5] / r[6]);
        int pos = (h - thumb) * scroll[id] / Math.max(1, r[6] - r[5]);
        g.fill(x, y + pos, x + 3, y + pos + thumb, dragging == id ? C_ACCENT : 0xFF6A7080);
    }

    // ================================================================= input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return false;
        for (int id = 0; id < 3; id++) {
            if (onBar(region(id), mx, my)) {
                dragging = id;
                dragTo(id, my);
                return true;
            }
        }
        int[] r = region(S_LIST);
        if (inside(r, mx, my)) {
            int row = scroll[S_LIST] + (int) ((my - r[1]) / ROW_H);
            if (row < pools.size()) clickPool(pools.get(row));
            return true;
        }
        r = region(S_ITEMS);
        if (inside(r, mx, my)) {
            int row = scroll[S_ITEMS] + (int) ((my - r[1]) / ITEM_ROW_H);
            if (row < edit.items.size()) {
                selItem = row;
                refresh();
            }
            return true;
        }
        int ri = resultAt(mx, my);
        if (ri >= 0) {
            addItem(results.get(ri));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging >= 0) {
            dragTo(dragging, my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging >= 0) {
            dragging = -1;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    private int resultAt(double mx, double my) {
        int[] r = region(S_RESULTS);
        if (!inside(r, mx, my) || mx >= colBX + resultCols * CELL) return -1;
        int i = (scroll[S_RESULTS] + (int) ((my - resultY) / CELL)) * resultCols + (int) ((mx - colBX) / CELL);
        return i < results.size() ? i : -1;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int d = sy > 0 ? -1 : 1;
        for (int id = 0; id < 3; id++) {
            int[] r = region(id);
            if (inside(r, mx, my)) {
                scroll[id] = clampScroll(scroll[id] + d, r[6], r[5]);
                return true;
            }
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    // ================================================================= rendering

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, C_BORDER);
        g.fill(left, top, left + W, top + H, C_PANEL);
        g.fill(left + 2, top + 2, left + LIST_W + 6, top + H - 28, C_COL);
        g.fill(cx, top + 4, cx + cw, cb, C_COL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        ItemStack tooltip = ItemStack.EMPTY;

        // ---- left: pool list
        g.drawString(font, Component.translatable("gui.lottery.pools.list"), left + 6, top + 8, C_ACCENT, false);
        int[] lr = region(S_LIST);
        for (int r = 0; r < listRows && scroll[S_LIST] + r < pools.size(); r++) {
            PrizePool p = pools.get(scroll[S_LIST] + r);
            int y = lr[1] + r * ROW_H;
            if (p.id == edit.id) g.fill(left + 4, y, left + 4 + LIST_W, y + ROW_H, C_SEL);
            else if (inside(new int[]{left + 4, y, LIST_W, ROW_H}, mouseX, mouseY)) g.fill(left + 4, y, left + 4 + LIST_W, y + ROW_H, C_HOVER);
            g.renderItem(p.items.isEmpty() ? new ItemStack(Items.BARRIER) : p.items.get(0).item, left + 5, y + 1);
            String n = String.valueOf(p.items.size());
            g.drawString(font, font.plainSubstrByWidth(p.name, LIST_W - 32 - font.width(n)), left + 23, y + 5, C_TEXT, false);
            g.drawString(font, n, left + LIST_W - 6 - font.width(n), y + 5, p.items.isEmpty() ? C_BAD : C_DIM, false);
        }
        if (pools.isEmpty()) g.drawString(font, Component.translatable("gui.lottery.pools.none"), left + 6, top + 22, C_DIM, false);
        drawScrollbar(g, S_LIST);

        // ---- header
        Component header = edit.id > 0 ? Component.translatable("gui.lottery.terminal.editing_named", edit.id, edit.name)
                : Component.translatable("gui.lottery.pools.unsaved");
        g.drawString(font, font.plainSubstrByWidth(header.getString(), cw - 16), cx + 4, top + 9, C_ACCENT, false);
        if (dirty) g.drawString(font, "*", cx + cw - 8, top + 9, C_BAD, false);
        g.drawString(font, Component.translatable("gui.lottery.terminal.name"), cx + 4, top + 27, C_TEXT, false);

        List<String> users = users();
        Component use = edit.id <= 0 ? Component.translatable("gui.lottery.pools.unsaved_hint")
                : users.isEmpty() ? Component.translatable("gui.lottery.pools.unused")
                : Component.translatable("gui.lottery.pools.used_by", users.size(), String.join("，", users));
        g.drawString(font, font.plainSubstrByWidth(use.getString(), cw - 8), cx + 4, top + 43, users.isEmpty() ? C_DIM : 0xFF7CC4FF, false);

        // ---- items
        g.drawString(font, Component.translatable("gui.lottery.pools.items", edit.items.size(), TicketType.MAX_ITEMS), colAX, y0 + 1, C_ACCENT, false);
        g.fill(colAX, itemY, colAX + colAW, itemY + itemRows * ITEM_ROW_H, C_INSET);
        long itemTotal = edit.totalItemWeight();
        for (int r = 0; r < itemRows && scroll[S_ITEMS] + r < edit.items.size(); r++) {
            int ii = scroll[S_ITEMS] + r;
            TicketType.PoolItem it = edit.items.get(ii);
            int y = itemY + r * ITEM_ROW_H;
            if (ii == selItem) g.fill(colAX, y, colAX + colAW, y + ITEM_ROW_H, C_SEL);
            g.renderItem(it.item, colAX + 1, y + 1);
            String share = it.usable() && itemTotal > 0 ? pct1((double) it.weight / itemTotal) : "0%";
            String range = it.min == it.max ? "×" + it.min : it.min + "~" + it.max;
            int shareW = font.width(share);
            int rangeX = colAX + colAW - shareW - 12 - font.width(range);
            g.drawString(font, font.plainSubstrByWidth(it.item.getHoverName().getString(), rangeX - colAX - 24), colAX + 20, y + 5, C_TEXT, false);
            g.drawString(font, range, rangeX, y + 5, it.min > it.max ? C_BAD : C_DIM, false);
            g.drawString(font, share, colAX + colAW - 6 - shareW, y + 5, C_TEXT, false);
            if (mouseX >= colAX + 1 && mouseX < colAX + 17 && mouseY >= y + 1 && mouseY < y + 17) tooltip = it.item;
        }
        if (edit.items.isEmpty()) g.drawString(font, Component.translatable("gui.lottery.pools.no_items"), colAX + 4, itemY + 5, C_DIM, false);
        drawScrollbar(g, S_ITEMS);
        if (item() != null) {
            g.drawString(font, Component.translatable("gui.lottery.terminal.weight"), colAX, editY + 3, C_TEXT, false);
            g.drawString(font, Component.translatable("gui.lottery.pools.in_pool", pct1(itemTotal > 0 && item().usable()
                    ? (double) item().weight / itemTotal : 0)), colAX + 80, editY + 3, C_DIM, false);
            g.drawString(font, Component.translatable("gui.lottery.terminal.amount"), colAX, editY + 20, C_TEXT, false);
            g.drawString(font, "~", colAX + 69, editY + 20, C_TEXT, false);
        } else if (!edit.items.isEmpty()) {
            g.drawString(font, Component.translatable("gui.lottery.terminal.pick_item"), colAX, editY + 3, C_DIM, false);
        }

        // ---- search results
        g.fill(colBX - 5, y0, colBX - 4, cb - 4, C_BORDER);
        int gw = resultCols * CELL;
        g.fill(colBX, resultY, colBX + colBW, resultY + resultRows * CELL, C_INSET);
        for (int i = 0; i < resultCols * resultRows; i++) {
            int idx = scroll[S_RESULTS] * resultCols + i;
            if (idx >= results.size()) break;
            int x = colBX + (i % resultCols) * CELL, y = resultY + (i / resultCols) * CELL;
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL && mouseX < colBX + gw) {
                g.fill(x, y, x + CELL, y + CELL, C_SEL);
                tooltip = results.get(idx);
            }
            g.renderItem(results.get(idx), x + 1, y + 1);
        }
        if (results.isEmpty()) {
            for (var line : font.split(Component.translatable(searchBox.getValue().isBlank()
                    ? "gui.lottery.pools.search_hint" : "gui.lottery.terminal.search_none"), colBW - 8)) {
                g.drawString(font, line, colBX + 4, resultY + 5, C_DIM, false);
                break;
            }
        }
        drawScrollbar(g, S_RESULTS);

        int statusW = W - LIST_W - 12 - 130;
        g.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(status, statusW)), cx, top + H - 20, statusColor, false);
        if (!tooltip.isEmpty()) g.renderTooltip(font, tooltip, mouseX, mouseY);
    }

    private static String pct1(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Registry search shared by the terminal screens. */
    static final class ItemSearch {
        private record Entry(ItemStack stack, String name, String id) {}

        private static List<Entry> index;

        static List<ItemStack> find(String query, int limit) {
            List<ItemStack> out = new ArrayList<>();
            String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
            if (q.isEmpty()) return out;
            if (index == null) build();
            for (Entry e : index) {
                if (e.name.contains(q) || e.id.contains(q)) {
                    out.add(e.stack);
                    if (out.size() >= limit) break;
                }
            }
            return out;
        }

        private static void build() {
            List<Entry> list = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR) continue;
                try {
                    ItemStack stack = new ItemStack(item);
                    list.add(new Entry(stack, stack.getHoverName().getString().toLowerCase(Locale.ROOT),
                            BuiltInRegistries.ITEM.getKey(item).toString()));
                } catch (Exception ignored) {
                    // some modded items misbehave without context; just skip them
                }
            }
            index = list;
        }
    }
}
