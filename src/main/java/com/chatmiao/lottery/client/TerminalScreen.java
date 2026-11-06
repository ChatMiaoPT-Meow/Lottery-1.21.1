package com.chatmiao.lottery.client;

import com.chatmiao.lottery.data.PlayStyle;
import com.chatmiao.lottery.data.Tier;
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
import java.util.function.IntConsumer;

/**
 * 彩票终端 GUI (OP only). The window grows with the screen.
 * <pre>
 * ┌ 彩票列表 ┬ [基本信息] [奖池] [票型] ─────────── 编号 #2 * ┐
 * │ #1 …     │                                                │
 * │ #2 …   ▐ │   content of the selected tab                  │
 * │ [+ 新建] │ status …            数量 [64] [生成] [保存]     │
 * └──────────┴────────────────────────────────────────────────┘
 * </pre>
 * Edits are local until "保存"; the server assigns the type number on first save.
 */
public class TerminalScreen extends Screen {
    private static final int LIST_W = 112, TYPE_ROW_H = 12, POOL_ROW_H = 12, ITEM_ROW_H = 18, CELL = 18;
    private static final int TAB_BASIC = 0, TAB_POOLS = 1, TAB_STYLE = 2, TAB_HUB = 3, TAB_W = 60;
    /** Gacha tab: label column width; settings end at colAX + HUB_LABEL_W + 105, the status column starts after. */
    private static final int HUB_LABEL_W = 40, HUB_SETTINGS_W = HUB_LABEL_W + 105 + 10;
    private static final java.time.format.DateTimeFormatter DATE = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int S_TYPES = 0, S_POOLS = 1, S_ITEMS = 2, S_RESULTS = 3;

    private static final int C_PANEL = 0xF0202329, C_COL = 0xFF2B2F36, C_BORDER = 0xFF4A505C, C_INSET = 0xFF1A1D22,
            C_TEXT = 0xFFE8E8E8, C_DIM = 0xFF9AA0AA, C_ACCENT = 0xFFFFC94A, C_SEL = 0xFF3D5A80, C_BAD = 0xFFE06060,
            C_TAB = 0xFF23262C, C_TAB_HOVER = 0xFF30343C;

    private List<TicketType> types = new ArrayList<>();
    private int maxGenerate = 1000;
    private TicketType edit;
    private boolean dirty;
    private Integer pendingSwitch;
    private int selPool = -1, selItem = -1;
    private final int[] scroll = new int[4];
    private int tab = TAB_BASIC;
    private Component status = Component.empty();
    private int statusColor = C_DIM;
    private final List<ItemStack> results = new ArrayList<>();
    private boolean loading;
    /** Scrollbar being dragged (S_*), or -1. */
    private int dragging = -1;

    // layout
    private int W, H, left, top, cx, cw, ct, cb;
    private int typeRows;
    private int colAX, colAW, colBX, colBW;
    private int poolY, poolRows, itemY, itemRows, itemEditY, searchY, resultY, resultCols, resultRows;
    private int previewY, previewH;

    private EditBox nameBox, emptyBox, poolNameBox, poolWeightBox, itemWeightBox, itemMinBox, itemMaxBox, searchBox, countBox;
    private EditBox drawsBox, winCountBox, colsBox, rowsBox, rangeBox, spinBox, cardColsBox, pityCountBox;
    private Button enableBtn, addPoolBtn, delPoolBtn, delItemBtn, offhandBtn, generateBtn, saveBtn;
    private Button poolTierBtn, styleBtn, rareBtn, pityTierBtn, guarTierBtn;
    private final List<TabButton> tabs = new ArrayList<>();
    // gacha pool tab
    private List<com.chatmiao.lottery.data.VoucherType> vouchers = new ArrayList<>();
    private Button hubBtn, voucherBtn, deliveryBtn;
    private EditBox costSingleBox, multiCountBox, costMultiBox, startBox, endBox;
    private final Button[] weekBtns = new Button[7];
    private boolean badStart, badEnd;

    private record IndexEntry(ItemStack stack, String name, String id) {}

    private static List<IndexEntry> index;

    public TerminalScreen(Payloads.OpenTerminal data) {
        super(Component.translatable("gui.lottery.terminal"));
        apply(data);
    }

    public void update(Payloads.OpenTerminal data) {
        apply(data);
        if (minecraft != null) refreshWidgets();
    }

    private void apply(Payloads.OpenTerminal data) {
        types = new ArrayList<>(data.types());
        vouchers = new ArrayList<>(data.vouchers());
        maxGenerate = data.maxGenerate();
        TicketType target = find(data.selectId());
        if (target != null) {
            // a refresh after save/generate: keep the pool/item the user was working on
            boolean keep = edit != null;
            int p = selPool, i = selItem;
            select(target);
            if (keep && p >= 0) {
                selPool = p < edit.pools.size() ? p : (edit.pools.isEmpty() ? -1 : 0);
                selItem = selPool >= 0 && i < edit.pools.get(selPool).items.size() ? i : -1;
            }
        } else if (edit == null) {
            if (types.isEmpty()) newType();
            else select(types.get(0));
        }
    }

    private TicketType find(int id) {
        for (TicketType t : types) if (t.id == id) return t;
        return null;
    }

    private void select(TicketType t) {
        edit = t.copy();
        dirty = false;
        pendingSwitch = null;
        selPool = edit.pools.isEmpty() ? -1 : 0;
        selItem = -1;
        scroll[S_POOLS] = scroll[S_ITEMS] = 0;
    }

    private void newType() {
        edit = new TicketType();
        edit.id = 0;
        edit.name = Component.translatable("gui.lottery.terminal.default_name").getString();
        edit.emptyWeight = 65;
        dirty = true;
        pendingSwitch = null;
        selPool = selItem = -1;
        tab = TAB_BASIC;
    }

    private TicketType.Pool pool() {
        return selPool >= 0 && selPool < edit.pools.size() ? edit.pools.get(selPool) : null;
    }

    private TicketType.PoolItem item() {
        TicketType.Pool p = pool();
        return p != null && selItem >= 0 && selItem < p.items.size() ? p.items.get(selItem) : null;
    }

    private void touch() {
        if (!loading) {
            dirty = true;
            pendingSwitch = null;
        }
    }

    // ================================================================= layout + widgets

    private void layout() {
        W = Mth.clamp(width - 24, 420, 600);
        H = Mth.clamp(height - 24, 238, 340);
        left = (width - W) / 2;
        top = (height - H) / 2;
        typeRows = (H - 70) / TYPE_ROW_H;
        cx = left + LIST_W + 12;
        cw = left + W - 6 - cx;
        ct = top + 24;
        cb = top + H - 30;

        // pools tab: pool list + pool editor | items + item editor + search
        colAX = cx + 4;
        colAW = (cw - 12) * 2 / 5;
        colBX = colAX + colAW + 8;
        colBW = cx + cw - 4 - colBX;
        poolY = ct + 18;
        poolRows = Math.max(3, (cb - 52 - poolY) / POOL_ROW_H);
        itemY = ct + 18;
        itemRows = Mth.clamp((cb - ct) * 36 / 100 / ITEM_ROW_H, 3, 8);
        itemEditY = itemY + itemRows * ITEM_ROW_H + 4;
        searchY = itemEditY + 34;
        resultY = searchY + 18;
        resultCols = Math.max(4, (colBW - 6) / CELL);
        resultRows = Math.max(1, (cb - 20 - resultY) / CELL);

        // style tab preview
        previewY = ct + 18;
        previewH = Mth.clamp((cb - ct) / 2 - 10, 60, 120);
    }

    @Override
    protected void init() {
        layout();
        tabs.clear();
        String[] tabKeys = {"gui.lottery.terminal.tab_basic", "gui.lottery.terminal.tab_pools", "gui.lottery.terminal.tab_style",
                "gui.lottery.terminal.tab_hub"};
        for (int i = 0; i < tabKeys.length; i++) {
            int index = i;
            TabButton t = new TabButton(cx + i * (TAB_W + 2), top + 6, TAB_W, 18, Component.translatable(tabKeys[i]), index, b -> {
                tab = index;
                refreshWidgets();
            });
            tabs.add(addRenderableWidget(t));
        }

        // ---- basic tab (form on the left half)
        int fx = cx + 64;
        nameBox = textBox(fx, ct + 30, Math.min(160, cw / 2 - 70), TicketType.MAX_NAME, s -> { edit.name = s; touch(); });
        emptyBox = numBox(fx, ct + 50, 50, v -> { edit.emptyWeight = v; touch(); });
        enableBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            edit.enabled = !edit.enabled;
            touch();
            refreshWidgets();
        }).bounds(fx, ct + 70, 70, 16).build());

        // ---- pools tab
        addPoolBtn = small(Component.literal("+"), colAX + colAW - 30, ct + 4, 14, this::addPool);
        delPoolBtn = small(Component.literal("-"), colAX + colAW - 14, ct + 4, 14, this::deletePool);
        poolNameBox = textBox(colAX + 30, cb - 48, colAW - 30, TicketType.MAX_NAME, s -> {
            if (pool() != null) { pool().name = s; touch(); }
        });
        poolWeightBox = numBox(colAX + 30, cb - 32, 44, v -> {
            if (pool() != null) { pool().weight = v; touch(); }
        });
        poolTierBtn = cycle(colAX + 30, cb - 16, colAW - 30, () -> {
            if (pool() != null) { pool().tier = (pool().tier + 1) % Tier.COUNT; touch(); }
        });

        itemWeightBox = numBox(colBX + 30, itemEditY, 44, v -> {
            if (item() != null) { item().weight = v; touch(); }
        });
        itemMinBox = numBox(colBX + 30, itemEditY + 16, 36, v -> {
            if (item() != null) { item().min = v; touch(); }
        });
        itemMaxBox = numBox(colBX + 78, itemEditY + 16, 36, v -> {
            if (item() != null) { item().max = v; touch(); }
        });
        delItemBtn = addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.delete"), b -> deleteItem())
                .bounds(colBX + colBW - 40, itemEditY + 16, 40, 14).build());
        searchBox = textBox(colBX, searchY, colBW, 50, s -> updateResults());
        searchBox.setHint(Component.translatable("gui.lottery.terminal.search"));
        offhandBtn = addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.offhand"), b -> addOffhand())
                .bounds(colBX, cb - 16, colBW, 14).build());

        // ---- style tab (settings on the left, preview on the right)
        int sx = colAX;
        int r0 = ct + 22;
        styleBtn = cycle(sx + 40, r0, 120, () -> { edit.style = edit.playStyle().next().id; touch(); });
        drawsBox = numBox(sx + 40, r0 + 18, 28, v -> { edit.draws = v; touch(); });
        winCountBox = numBox(sx + 136, r0 + 18, 24, v -> { edit.winCount = v; touch(); });
        colsBox = numBox(sx + 40, r0 + 36, 26, v -> { edit.cols = v; touch(); });
        rowsBox = numBox(sx + 80, r0 + 36, 26, v -> { edit.rows = v; touch(); });
        spinBox = numBox(sx + 56, r0 + 36, 28, v -> { edit.spinSeconds = v; touch(); });
        cardColsBox = numBox(sx + 40, r0 + 36, 26, v -> { edit.cardCols = v; touch(); });
        rangeBox = numBox(sx + 56, r0 + 54, 28, v -> { edit.numberRange = v; touch(); });
        rareBtn = cycle(sx, r0 + 54, 160, () -> { edit.rareEffect = !edit.rareEffect; touch(); });
        pityCountBox = numBox(sx + 40, r0 + 80, 32, v -> { edit.pityCount = v; touch(); });
        pityTierBtn = cycle(sx + 108, r0 + 80, 52, () -> { edit.pityTier = (edit.pityTier + 1) % Tier.COUNT; touch(); });
        guarTierBtn = cycle(sx + 56, r0 + 98, 104, () -> {
            edit.guaranteeTier = edit.guaranteeTier + 1 >= Tier.COUNT ? -1 : edit.guaranteeTier + 1;
            touch();
        });

        // ---- gacha pool tab (settings left, status preview right)
        int hx = colAX + HUB_LABEL_W, h0 = ct + 22;
        hubBtn = cycle(colAX, h0, HUB_LABEL_W + 105, () -> { edit.inHub = !edit.inHub; touch(); });
        voucherBtn = cycle(hx, h0 + 18, 105, this::cycleVoucher);
        costSingleBox = numBox(hx, h0 + 36, 30, v -> { edit.costSingle = v; touch(); });
        multiCountBox = numBox(hx, h0 + 54, 26, v -> { edit.multiCount = v; touch(); });
        costMultiBox = numBox(hx + 44, h0 + 54, 30, v -> { edit.costMulti = v; touch(); });
        startBox = textBox(hx, h0 + 72, 105, 16, s -> { badStart = !parseTime(s, true); touch(); });
        endBox = textBox(hx, h0 + 90, 105, 16, s -> { badEnd = !parseTime(s, false); touch(); });
        startBox.setHint(Component.translatable("gui.lottery.terminal.time_hint").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        endBox.setHint(Component.translatable("gui.lottery.terminal.time_hint").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        for (int d = 0; d < 7; d++) {
            int day = d;
            weekBtns[d] = cycle(hx + d * 15, h0 + 108, 14, () -> { edit.weekMask ^= 1 << day; touch(); });
        }
        deliveryBtn = cycle(hx, h0 + 126, 105, () -> {
            edit.delivery = edit.delivery == TicketType.DELIVER_TICKET ? TicketType.DELIVER_DIRECT : TicketType.DELIVER_TICKET;
            touch();
        });

        // ---- always visible
        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.vouchers"),
                        b -> PacketDistributor.sendToServer(new Payloads.Request(Payloads.Request.VOUCHERS)))
                .bounds(left + 4, top + H - 46, LIST_W, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.new"), b -> clickNew())
                .bounds(left + 4, top + H - 24, LIST_W, 16).build());
        int bx = left + W - 6;
        saveBtn = addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.save"), b -> save())
                .bounds(bx - 52, top + H - 24, 52, 16).build());
        generateBtn = addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.generate"), b -> generate())
                .bounds(bx - 106, top + H - 24, 50, 16).build());
        countBox = numBox(bx - 154, top + H - 23, 44, v -> {});
        countBox.setValue("64");

        refreshWidgets();
        updateResults();
    }

    private void cycleVoucher() {
        // none -> each voucher type in order -> none
        int next = 0;
        boolean pick = edit.voucherId == 0;
        for (com.chatmiao.lottery.data.VoucherType v : vouchers) {
            if (pick) {
                next = v.id;
                break;
            }
            if (v.id == edit.voucherId) pick = true;
        }
        edit.voucherId = next;
        touch();
    }

    /** Parses a start/end box into {@link #edit}; empty = unbounded. @return false if the text is not a valid time */
    private boolean parseTime(String s, boolean start) {
        long value;
        if (s == null || s.isBlank()) {
            value = 0;
        } else {
            try {
                value = java.time.LocalDateTime.parse(s.strip(), DATE).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (java.time.format.DateTimeParseException e) {
                return false;
            }
        }
        if (start) edit.startTime = value;
        else edit.endTime = value;
        return true;
    }

    private static String formatTime(long millis) {
        return millis <= 0 ? "" : DATE.format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()));
    }

    private com.chatmiao.lottery.data.VoucherType voucherOf(int id) {
        for (com.chatmiao.lottery.data.VoucherType v : vouchers) if (v.id == id) return v;
        return null;
    }

    private Button small(Component text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(text, b -> action.run()).bounds(x, y, w, 12).build());
    }

    /** A button that changes a value and redraws its own label in {@link #refreshWidgets()}. */
    private Button cycle(int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.empty(), b -> {
            action.run();
            refreshWidgets();
        }).bounds(x, y, w, 14).build());
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
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Push model values into the widgets (without marking dirty) and update visibility. */
    private void refreshWidgets() {
        if (nameBox == null) return;
        loading = true;
        boolean basic = tab == TAB_BASIC, pools = tab == TAB_POOLS, style = tab == TAB_STYLE;

        // ---- basic
        nameBox.visible = emptyBox.visible = enableBtn.visible = basic;
        nameBox.setValue(edit.name);
        emptyBox.setValue(String.valueOf(edit.emptyWeight));
        enableBtn.setMessage(Component.translatable(edit.enabled ? "gui.lottery.terminal.enabled" : "gui.lottery.terminal.disabled"));

        // ---- pools
        TicketType.Pool p = pool();
        addPoolBtn.visible = delPoolBtn.visible = pools;
        poolNameBox.visible = poolWeightBox.visible = poolTierBtn.visible = pools && p != null;
        delPoolBtn.active = p != null;
        addPoolBtn.active = edit.pools.size() < TicketType.MAX_POOLS;
        if (p != null) {
            poolNameBox.setValue(p.name);
            poolWeightBox.setValue(String.valueOf(p.weight));
            poolTierBtn.setMessage(Tier.name(p.tier));
        }
        TicketType.PoolItem it = item();
        itemWeightBox.visible = itemMinBox.visible = itemMaxBox.visible = delItemBtn.visible = pools && it != null;
        if (it != null) {
            itemWeightBox.setValue(String.valueOf(it.weight));
            itemMinBox.setValue(String.valueOf(it.min));
            itemMaxBox.setValue(String.valueOf(it.max));
        }
        searchBox.visible = offhandBtn.visible = pools && p != null;

        // ---- style
        PlayStyle s = edit.playStyle();
        styleBtn.visible = drawsBox.visible = pityCountBox.visible = pityTierBtn.visible = guarTierBtn.visible = style;
        winCountBox.visible = colsBox.visible = rowsBox.visible = rangeBox.visible = style && s == PlayStyle.MATCH;
        spinBox.visible = style && s == PlayStyle.CASE;
        cardColsBox.visible = rareBtn.visible = style && s == PlayStyle.CARDS;
        styleBtn.setMessage(s.label());
        drawsBox.setValue(String.valueOf(edit.draws));
        winCountBox.setValue(String.valueOf(edit.winCount));
        colsBox.setValue(String.valueOf(edit.cols));
        rowsBox.setValue(String.valueOf(edit.rows));
        rangeBox.setValue(String.valueOf(edit.numberRange));
        spinBox.setValue(String.valueOf(edit.spinSeconds));
        cardColsBox.setValue(String.valueOf(edit.cardCols));
        rareBtn.setMessage(Component.translatable(edit.rareEffect ? "gui.lottery.terminal.rare_on" : "gui.lottery.terminal.rare_off"));
        pityCountBox.setValue(String.valueOf(edit.pityCount));
        pityTierBtn.setMessage(Tier.name(edit.pityTier));
        guarTierBtn.setMessage(edit.guaranteeTier < 0
                ? Component.translatable("gui.lottery.terminal.off")
                : Component.translatable("gui.lottery.terminal.tier_up", Tier.name(edit.guaranteeTier)));

        // ---- gacha pool
        boolean hub = tab == TAB_HUB;
        hubBtn.visible = voucherBtn.visible = costSingleBox.visible = multiCountBox.visible = costMultiBox.visible = hub;
        startBox.visible = endBox.visible = deliveryBtn.visible = hub;
        for (Button b : weekBtns) b.visible = hub;
        hubBtn.setMessage(Component.translatable(edit.inHub ? "gui.lottery.terminal.hub_on" : "gui.lottery.terminal.hub_off"));
        com.chatmiao.lottery.data.VoucherType v = voucherOf(edit.voucherId);
        voucherBtn.setMessage(v == null ? Component.translatable("gui.lottery.terminal.no_voucher")
                : Component.literal(v.name).withColor(v.color));
        costSingleBox.setValue(String.valueOf(edit.costSingle));
        multiCountBox.setValue(String.valueOf(edit.multiCount));
        costMultiBox.setValue(String.valueOf(edit.costMulti));
        if (!startBox.isFocused()) startBox.setValue(formatTime(edit.startTime));
        if (!endBox.isFocused()) endBox.setValue(formatTime(edit.endTime));
        badStart = badEnd = false;
        for (int d = 0; d < 7; d++) {
            boolean on = (edit.weekMask & (1 << d)) != 0;
            weekBtns[d].setMessage(Component.translatable("gui.lottery.weekday." + d).withColor(on ? 0x7CFC7C : 0x8A8A8A));
        }
        deliveryBtn.setMessage(Component.translatable(edit.delivery == TicketType.DELIVER_TICKET
                ? "gui.lottery.hub.deliver_ticket" : "gui.lottery.hub.deliver_direct"));
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

    private void clickType(TicketType t) {
        if (edit.id == t.id && !dirty) return;
        if (!confirmDiscard(t.id)) return;
        select(t);
        setStatus("gui.lottery.terminal.selected", C_DIM, t.id);
        refreshWidgets();
    }

    private void clickNew() {
        if (!confirmDiscard(-1)) return;
        newType();
        setStatus("gui.lottery.terminal.new_hint", C_DIM);
        refreshWidgets();
    }

    private void addPool() {
        TicketType.Pool p = new TicketType.Pool();
        p.name = Component.translatable("gui.lottery.terminal.pool_name", edit.pools.size() + 1).getString();
        p.weight = 10;
        p.tier = Math.min(Tier.COUNT - 1, edit.pools.size());
        edit.pools.add(p);
        selPool = edit.pools.size() - 1;
        selItem = -1;
        if (selPool >= scroll[S_POOLS] + poolRows) scroll[S_POOLS] = selPool - poolRows + 1;
        touch();
        refreshWidgets();
    }

    private void deletePool() {
        if (pool() == null) return;
        edit.pools.remove(selPool);
        selPool = Math.min(selPool, edit.pools.size() - 1);
        selItem = -1;
        scroll[S_POOLS] = clampScroll(scroll[S_POOLS], edit.pools.size(), poolRows);
        touch();
        refreshWidgets();
    }

    private void addItem(ItemStack stack) {
        TicketType.Pool p = pool();
        if (p == null || stack.isEmpty()) return;
        if (p.items.size() >= TicketType.MAX_ITEMS) {
            setStatus("gui.lottery.terminal.pool_full", C_BAD);
            return;
        }
        TicketType.PoolItem it = new TicketType.PoolItem();
        it.item = stack.copyWithCount(1);
        p.items.add(it);
        selItem = p.items.size() - 1;
        if (selItem >= scroll[S_ITEMS] + itemRows) scroll[S_ITEMS] = selItem - itemRows + 1;
        touch();
        setStatus("gui.lottery.terminal.item_added", C_DIM, stack.getHoverName());
        refreshWidgets();
    }

    private void deleteItem() {
        TicketType.Pool p = pool();
        if (item() == null) return;
        p.items.remove(selItem);
        selItem = -1;
        scroll[S_ITEMS] = clampScroll(scroll[S_ITEMS], p.items.size(), itemRows);
        touch();
        refreshWidgets();
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
        for (TicketType.Pool p : edit.pools) {
            for (TicketType.PoolItem it : p.items) {
                if (it.min > it.max || it.min < 1) {
                    setStatus("gui.lottery.terminal.bad_range", C_BAD, it.item.getHoverName());
                    return;
                }
            }
        }
        // the server clamps everything too; doing it here keeps what the admin sees equal to what is stored
        TicketType out = edit.copy();
        out.sanitize();
        PacketDistributor.sendToServer(new Payloads.SaveType(out));
        setStatus("gui.lottery.terminal.saving", C_DIM);
    }

    private void generate() {
        if (edit.id <= 0 || dirty) {
            setStatus("gui.lottery.terminal.save_first", C_BAD);
            return;
        }
        int n = parse(countBox.getValue());
        if (n <= 0 || n > maxGenerate) {
            setStatus("gui.lottery.terminal.bad_count", C_BAD, maxGenerate);
            return;
        }
        PacketDistributor.sendToServer(new Payloads.Generate(edit.id, n));
    }

    private void updateResults() {
        results.clear();
        scroll[S_RESULTS] = 0;
        if (searchBox == null) return;
        String q = searchBox.getValue().strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return;
        if (index == null) buildIndex();
        for (IndexEntry e : index) {
            if (e.name.contains(q) || e.id.contains(q)) {
                results.add(e.stack);
                if (results.size() >= 400) break;
            }
        }
    }

    private static void buildIndex() {
        List<IndexEntry> list = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            try {
                ItemStack stack = new ItemStack(item);
                list.add(new IndexEntry(stack, stack.getHoverName().getString().toLowerCase(Locale.ROOT),
                        BuiltInRegistries.ITEM.getKey(item).toString()));
            } catch (Exception ignored) {
                // some modded items misbehave without context; just skip them
            }
        }
        index = list;
    }

    /** Dev/demo hook: switch tab, search for items and select an item row, as a user would. */
    public void demo(int tabIndex, String search, int itemIndex) {
        tab = tabIndex;
        if (search != null) searchBox.setValue(search);
        if (pool() != null && itemIndex >= 0 && itemIndex < pool().items.size()) selItem = itemIndex;
        refreshWidgets();
    }

    // ================================================================= scroll regions

    /** {x, y, w, h, rowH, rows visible, total} of a scrollable list, or null when it isn't on screen. */
    private int[] region(int id) {
        return switch (id) {
            case S_TYPES -> new int[]{left + 4, top + 20, LIST_W, typeRows * TYPE_ROW_H, TYPE_ROW_H, typeRows, types.size()};
            case S_POOLS -> tab != TAB_POOLS ? null
                    : new int[]{colAX, poolY, colAW, poolRows * POOL_ROW_H, POOL_ROW_H, poolRows, edit.pools.size()};
            case S_ITEMS -> tab != TAB_POOLS || pool() == null ? null
                    : new int[]{colBX, itemY, colBW, itemRows * ITEM_ROW_H, ITEM_ROW_H, itemRows, pool().items.size()};
            case S_RESULTS -> tab != TAB_POOLS || pool() == null ? null
                    : new int[]{colBX, resultY, colBW, resultRows * CELL, CELL, resultRows, (results.size() + resultCols - 1) / resultCols};
            default -> null;
        };
    }

    private static boolean inside(int[] r, double mx, double my) {
        return r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    /** True when the point is on the region's scrollbar strip (rightmost 4 px) and it can scroll. */
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
        for (int id = 0; id < 4; id++) {
            if (onBar(region(id), mx, my)) {
                dragging = id;
                dragTo(id, my);
                return true;
            }
        }
        int[] r = region(S_TYPES);
        if (inside(r, mx, my)) {
            int row = scroll[S_TYPES] + (int) ((my - r[1]) / TYPE_ROW_H);
            if (row < types.size()) clickType(types.get(row));
            return true;
        }
        if (tab != TAB_POOLS) return false;
        r = region(S_POOLS);
        if (inside(r, mx, my)) {
            int row = scroll[S_POOLS] + (int) ((my - r[1]) / POOL_ROW_H);
            if (row < edit.pools.size()) {
                selPool = row;
                selItem = -1;
                scroll[S_ITEMS] = 0;
                refreshWidgets();
            }
            return true;
        }
        r = region(S_ITEMS);
        if (inside(r, mx, my)) {
            int row = scroll[S_ITEMS] + (int) ((my - r[1]) / ITEM_ROW_H);
            if (row < pool().items.size()) {
                selItem = row;
                refreshWidgets();
            }
            return true;
        }
        int ri = resultAt(mx, my);
        if (pool() != null && ri >= 0) {
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
        for (int id = 0; id < 4; id++) {
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
        g.fill(cx, ct, cx + cw, cb, C_COL);
        g.fill(cx, ct - 1, cx + cw, ct, C_ACCENT); // the active tab sits on this line
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        ItemStack tooltip = ItemStack.EMPTY;

        // ---- left: types
        g.drawString(font, Component.translatable("gui.lottery.terminal.types"), left + 6, top + 8, C_ACCENT, false);
        int[] tr = region(S_TYPES);
        for (int r = 0; r < typeRows && scroll[S_TYPES] + r < types.size(); r++) {
            TicketType t = types.get(scroll[S_TYPES] + r);
            int y = tr[1] + r * TYPE_ROW_H;
            if (t.id == edit.id) g.fill(left + 4, y, left + 4 + LIST_W, y + TYPE_ROW_H, C_SEL);
            else if (inside(new int[]{left + 4, y, LIST_W, TYPE_ROW_H}, mouseX, mouseY)) g.fill(left + 4, y, left + 4 + LIST_W, y + TYPE_ROW_H, C_TAB_HOVER);
            g.fill(left + 6, y + 3, left + 12, y + 9, 0xFF000000 | TicketColors.of(t.id));
            String label = "#" + t.id + " " + t.name;
            g.drawString(font, font.plainSubstrByWidth(label, LIST_W - 16), left + 15, y + 2, t.enabled ? C_TEXT : C_DIM, false);
        }
        if (types.isEmpty()) g.drawString(font, Component.translatable("gui.lottery.terminal.no_types"), left + 6, top + 22, C_DIM, false);
        drawScrollbar(g, S_TYPES);

        // ---- header right of the tabs
        Component header = edit.id > 0
                ? Component.translatable("gui.lottery.terminal.editing_named", edit.id, edit.name)
                : Component.translatable("gui.lottery.terminal.unsaved_new");
        String hs = font.plainSubstrByWidth(header.getString(), Math.max(0, cw - 4 * (TAB_W + 2) - 12));
        int hx = cx + cw - font.width(hs) - (dirty ? 8 : 2);
        g.drawString(font, hs, hx, top + 11, C_ACCENT, false);
        if (dirty) g.drawString(font, "*", cx + cw - 6, top + 11, C_BAD, false);

        switch (tab) {
            case TAB_BASIC -> renderBasic(g);
            case TAB_POOLS -> tooltip = renderPools(g, mouseX, mouseY);
            case TAB_STYLE -> {
                renderStyleSettings(g);
                renderPreview(g);
            }
            case TAB_HUB -> renderHub(g);
        }

        // ---- bottom bar
        g.drawString(font, Component.translatable("gui.lottery.terminal.count"), left + W - 6 - 154 - font.width(
                Component.translatable("gui.lottery.terminal.count")) - 4, top + H - 20, C_TEXT, false);
        int statusW = W - LIST_W - 12 - 154 - 50;
        g.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(status, statusW)), cx, top + H - 20, statusColor, false);

        if (!tooltip.isEmpty()) g.renderTooltip(font, tooltip, mouseX, mouseY);
    }

    private void label(GuiGraphics g, String key, int x, int y) {
        g.drawString(font, Component.translatable(key), x, y + 3, C_TEXT, false);
    }

    /** Basic tab: name / empty weight / enabled on the left, a summary card of the whole ticket on the right. */
    private void renderBasic(GuiGraphics g) {
        int x = cx + 6;
        if (edit.id > 0) {
            g.drawString(font, Component.translatable("gui.lottery.terminal.stats", edit.issued, edit.scratched, edit.wins), x, ct + 8, C_DIM, false);
            if (edit.scratched > 0) {
                Component rate = Component.translatable("gui.lottery.terminal.actual_rate", pct((double) edit.wins / edit.scratched));
                g.drawString(font, rate, x, ct + 18, C_DIM, false);
            }
        } else {
            g.drawString(font, Component.translatable("gui.lottery.terminal.new_hint"), x, ct + 8, C_DIM, false);
        }
        label(g, "gui.lottery.terminal.name", x, ct + 30);
        label(g, "gui.lottery.terminal.empty_weight", x, ct + 50);
        long total = edit.totalWeight();
        g.drawString(font, pct(total > 0 ? (double) edit.emptyWeight / total : 1), cx + 120, ct + 53, C_DIM, false);
        label(g, "gui.lottery.terminal.state", x, ct + 70);

        // summary card
        int sx = cx + cw / 2 + 4, sw = cw / 2 - 10, sy = ct + 6, sh = cb - ct - 12;
        g.fill(sx, sy, sx + sw, sy + sh, C_INSET);
        g.fill(sx, sy, sx + 3, sy + sh, 0xFF000000 | TicketColors.of(edit.id));
        int ly = sy + 6, lx = sx + 8;
        g.drawString(font, Component.translatable("gui.lottery.terminal.summary"), lx, ly, C_ACCENT, false);
        ly += 14;
        PlayStyle s = edit.playStyle();
        int draws = Math.max(1, Math.min(edit.draws, edit.maxDraws()));
        Component layout = switch (s) {
            case MATCH -> Component.translatable("gui.lottery.terminal.sum_match", edit.cols, edit.rows, edit.winCount);
            case CASE -> Component.translatable("gui.lottery.terminal.sum_case", edit.spinSeconds);
            case CARDS -> Component.translatable("gui.lottery.terminal.sum_cards", edit.cardCols);
        };
        ly = summaryLine(g, Component.translatable("gui.lottery.terminal.sum_style", s.label(), draws), lx, ly, sw, C_TEXT);
        ly = summaryLine(g, layout, lx, ly, sw, C_DIM);
        int usable = 0;
        for (TicketType.Pool p : edit.pools) if (p.usable()) usable++;
        ly = summaryLine(g, Component.translatable("gui.lottery.terminal.sum_pools", edit.pools.size(), usable), lx, ly, sw,
                usable > 0 ? C_TEXT : C_BAD);
        ly += 4;
        ly = summaryLine(g, Component.translatable("gui.lottery.terminal.p_draw", pct(edit.winChance())), lx, ly, sw, C_TEXT);
        TicketType probe = edit.copy();
        probe.draws = draws;
        ly = summaryLine(g, Component.translatable("gui.lottery.terminal.p_ticket", pct(probe.ticketWinChance())), lx, ly, sw, C_ACCENT);
        ly += 4;
        Component pity = edit.pityCount > 0
                ? Component.translatable("gui.lottery.terminal.i_pity", edit.pityCount, Tier.name(edit.pityTier))
                : Component.translatable("gui.lottery.terminal.sum_no_pity");
        ly = summaryLine(g, pity, lx, ly, sw, C_DIM);
        if (edit.guaranteeTier >= 0) {
            summaryLine(g, Component.translatable("gui.lottery.terminal.i_guarantee", Tier.name(edit.guaranteeTier)), lx, ly, sw, C_DIM);
        }
    }

    private int summaryLine(GuiGraphics g, Component text, int x, int y, int w, int color) {
        g.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(text, w - 14)), x, y, color, false);
        return y + 11;
    }

    /** Pools tab: pool list + editor on the left, items + editor + search on the right. @return tooltip item */
    private ItemStack renderPools(GuiGraphics g, int mouseX, int mouseY) {
        ItemStack tooltip = ItemStack.EMPTY;
        long total = edit.totalWeight();
        g.drawString(font, Component.translatable("gui.lottery.terminal.pools"), colAX, ct + 6, C_ACCENT, false);
        g.fill(colAX, poolY, colAX + colAW, poolY + poolRows * POOL_ROW_H, C_INSET);
        for (int r = 0; r < poolRows && scroll[S_POOLS] + r < edit.pools.size(); r++) {
            int pi = scroll[S_POOLS] + r;
            TicketType.Pool p = edit.pools.get(pi);
            int y = poolY + r * POOL_ROW_H;
            if (pi == selPool) g.fill(colAX, y, colAX + colAW, y + POOL_ROW_H, C_SEL);
            g.fill(colAX + 2, y + 2, colAX + 5, y + 10, 0xFF000000 | Tier.color(p.tier));
            String share = p.usable() ? pct1((double) p.weight / total) : Component.translatable("gui.lottery.terminal.pool_unused").getString();
            int shareW = font.width(share);
            g.drawString(font, font.plainSubstrByWidth(p.name, colAW - shareW - 40), colAX + 8, y + 2, C_TEXT, false);
            g.drawString(font, "w" + p.weight, colAX + colAW - shareW - 36, y + 2, C_DIM, false);
            g.drawString(font, share, colAX + colAW - 6 - shareW, y + 2, p.usable() ? C_TEXT : C_BAD, false);
        }
        if (edit.pools.isEmpty()) g.drawString(font, Component.translatable("gui.lottery.terminal.no_pools"), colAX + 4, poolY + 3, C_DIM, false);
        drawScrollbar(g, S_POOLS);
        if (pool() != null) {
            label(g, "gui.lottery.terminal.pool_name_label", colAX, cb - 48);
            label(g, "gui.lottery.terminal.weight", colAX, cb - 32);
            label(g, "gui.lottery.terminal.tier", colAX, cb - 16);
        }

        TicketType.Pool p = pool();
        if (p == null) {
            g.drawString(font, Component.translatable("gui.lottery.terminal.select_pool"), colBX, ct + 6, C_DIM, false);
            return tooltip;
        }
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.lottery.terminal.pool_items", p.name).getString(), colBW),
                colBX, ct + 6, C_ACCENT, false);
        g.fill(colBX, itemY, colBX + colBW, itemY + itemRows * ITEM_ROW_H, C_INSET);
        long itemTotal = p.totalItemWeight();
        for (int r = 0; r < itemRows && scroll[S_ITEMS] + r < p.items.size(); r++) {
            int ii = scroll[S_ITEMS] + r;
            TicketType.PoolItem it = p.items.get(ii);
            int y = itemY + r * ITEM_ROW_H;
            if (ii == selItem) g.fill(colBX, y, colBX + colBW, y + ITEM_ROW_H, C_SEL);
            g.renderItem(it.item, colBX + 1, y + 1);
            String share = it.usable() && itemTotal > 0 ? pct1((double) it.weight / itemTotal) : "0%";
            String range = it.min == it.max ? "×" + it.min : it.min + "~" + it.max;
            int shareW = font.width(share);
            int rangeX = colBX + colBW - shareW - 12 - font.width(range);
            g.drawString(font, font.plainSubstrByWidth(it.item.getHoverName().getString(), rangeX - colBX - 24), colBX + 20, y + 5, C_TEXT, false);
            g.drawString(font, range, rangeX, y + 5, it.min > it.max ? C_BAD : C_DIM, false);
            g.drawString(font, share, colBX + colBW - 6 - shareW, y + 5, C_TEXT, false);
            if (mouseX >= colBX + 1 && mouseX < colBX + 17 && mouseY >= y + 1 && mouseY < y + 17) tooltip = it.item;
        }
        if (p.items.isEmpty()) g.drawString(font, Component.translatable("gui.lottery.terminal.no_items"), colBX + 4, itemY + 5, C_DIM, false);
        drawScrollbar(g, S_ITEMS);
        TicketType.PoolItem it = item();
        if (it != null) {
            label(g, "gui.lottery.terminal.weight", colBX, itemEditY);
            double overall = total > 0 && p.usable() && itemTotal > 0 ? (double) p.weight / total * it.weight / itemTotal : 0;
            g.drawString(font, Component.translatable("gui.lottery.terminal.overall", pct(overall)), colBX + 80, itemEditY + 3, C_DIM, false);
            label(g, "gui.lottery.terminal.amount", colBX, itemEditY + 16);
            g.drawString(font, "~", colBX + 69, itemEditY + 19, C_TEXT, false);
        } else {
            g.drawString(font, Component.translatable("gui.lottery.terminal.pick_item"), colBX, itemEditY + 3, C_DIM, false);
        }

        // search results
        int gw = resultCols * CELL;
        g.fill(colBX, resultY, colBX + colBW, resultY + resultRows * CELL, C_INSET);
        for (int i = 0; i < resultCols * resultRows; i++) {
            int idx = scroll[S_RESULTS] * resultCols + i;
            if (idx >= results.size()) break;
            int x = colBX + (i % resultCols) * CELL, y = resultY + (i / resultCols) * CELL;
            boolean hover = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL && mouseX < colBX + gw;
            if (hover) {
                g.fill(x, y, x + CELL, y + CELL, C_SEL);
                tooltip = results.get(idx);
            }
            g.renderItem(results.get(idx), x + 1, y + 1);
        }
        if (results.isEmpty()) {
            g.drawString(font, Component.translatable(searchBox.getValue().isBlank()
                    ? "gui.lottery.terminal.search_hint" : "gui.lottery.terminal.search_none"), colBX + 4, resultY + 5, C_DIM, false);
        }
        drawScrollbar(g, S_RESULTS);
        return tooltip;
    }

    /** Gacha pool tab: labels on the left, what players will see / why it is closed on the right. */
    private void renderHub(GuiGraphics g) {
        int h0 = ct + 22;
        g.drawString(font, Component.translatable("gui.lottery.terminal.tab_hub"), colAX, ct + 6, C_ACCENT, false);
        label(g, "gui.lottery.terminal.hub_voucher", colAX, h0 + 18);
        int hx = colAX + HUB_LABEL_W;
        label(g, "gui.lottery.terminal.hub_single", colAX, h0 + 36);
        g.drawString(font, Component.translatable("gui.lottery.terminal.hub_per_pull"), hx + 34, h0 + 39, C_DIM, false);
        label(g, "gui.lottery.terminal.hub_multi", colAX, h0 + 54);
        g.drawString(font, Component.translatable("gui.lottery.terminal.hub_multi_cost"), hx + 30, h0 + 57, C_DIM, false);
        g.drawString(font, Component.translatable("gui.lottery.terminal.hub_per_pull"), hx + 78, h0 + 57, C_DIM, false);
        label(g, "gui.lottery.terminal.hub_start", colAX, h0 + 72);
        label(g, "gui.lottery.terminal.hub_end", colAX, h0 + 90);
        if (badStart) g.drawString(font, "!", hx + 108, h0 + 75, C_BAD, false);
        if (badEnd) g.drawString(font, "!", hx + 108, h0 + 93, C_BAD, false);
        label(g, "gui.lottery.terminal.hub_week", colAX, h0 + 108);
        label(g, "gui.lottery.terminal.hub_delivery", colAX, h0 + 126);

        // right: status + summary (own column, wrapped, so it never runs into the settings)
        int x = colAX + HUB_SETTINGS_W, y = ct + 6;
        hubColX = x;
        hubColW = cx + cw - 6 - x;
        g.fill(x - 6, ct + 4, x - 5, cb - 4, C_BORDER);
        g.drawString(font, Component.translatable("gui.lottery.terminal.hub_preview"), x, y, C_ACCENT, false);
        y += 14;
        com.chatmiao.lottery.data.VoucherType v = voucherOf(edit.voucherId);
        TicketType.HubStatus st = edit.hubStatus(System.currentTimeMillis(), java.time.ZoneId.systemDefault(), v != null && v.enabled);
        boolean open = st == TicketType.HubStatus.OPEN;
        y = hubLine(g, Component.translatable("gui.lottery.terminal.hub_status." + st.name().toLowerCase(Locale.ROOT)), y, open ? 0xFF7CFC7C : C_BAD);
        y += 4;
        if (v != null) {
            y = hubLine(g, Component.translatable("gui.lottery.terminal.hub_cost_single", v.name, edit.costSingle), y, C_TEXT);
            int multi = edit.effectiveMulti();
            if (multi > 0) {
                y = hubLine(g, Component.translatable("gui.lottery.terminal.hub_cost_multi", multi, v.name, edit.costMulti), y, C_TEXT);
                if (multi < edit.multiCount) {
                    y = hubLine(g, Component.translatable("gui.lottery.terminal.w_draws", multi), y, C_BAD);
                }
            } else {
                y = hubLine(g, Component.translatable("gui.lottery.terminal.hub_no_multi"), y, C_DIM);
            }
        }
        y += 4;
        y = hubLine(g, Component.translatable(edit.delivery == TicketType.DELIVER_TICKET
                ? "gui.lottery.terminal.hub_info_ticket" : "gui.lottery.terminal.hub_info_direct"), y, C_DIM);
        y = hubLine(g, Component.translatable("gui.lottery.terminal.hub_info_unlimited"), y, C_DIM);
        y = hubLine(g, Component.translatable("gui.lottery.terminal.hub_info_open"), y, C_DIM);
        if (vouchers.isEmpty()) {
            y += 4;
            hubLine(g, Component.translatable("gui.lottery.terminal.hub_need_voucher"), y, C_BAD);
        }
    }

    private int hubColX, hubColW;

    /** A wrapped line in the gacha tab's status column. */
    private int hubLine(GuiGraphics g, Component text, int y, int color) {
        for (var line : font.split(text, Math.max(40, hubColW))) {
            if (y + 9 > cb) return y;
            g.drawString(font, line, hubColX, y, color, false);
            y += 10;
        }
        return y + 1;
    }

    /** Labels of the style tab (the inputs themselves are widgets). */
    private void renderStyleSettings(GuiGraphics g) {
        int sx = colAX, r0 = ct + 22;
        g.drawString(font, Component.translatable("gui.lottery.terminal.tab_style"), sx, ct + 6, C_ACCENT, false);
        PlayStyle s = edit.playStyle();
        label(g, "gui.lottery.terminal.style", sx, r0);
        label(g, "gui.lottery.terminal.draws", sx, r0 + 18);
        g.drawString(font, Component.translatable("gui.lottery.terminal.max_draws", edit.maxDraws()), sx + 72, r0 + 21, C_DIM, false);
        switch (s) {
            case MATCH -> {
                label(g, "gui.lottery.terminal.win_numbers", sx + 100, r0 + 18);
                label(g, "gui.lottery.terminal.grid", sx, r0 + 36);
                g.drawString(font, "×", sx + 70, r0 + 39, C_TEXT, false);
                label(g, "gui.lottery.terminal.range", sx, r0 + 54);
                g.drawString(font, Component.translatable("gui.lottery.terminal.range_hint"), sx + 90, r0 + 57, C_DIM, false);
            }
            case CASE -> label(g, "gui.lottery.terminal.spin", sx, r0 + 36);
            case CARDS -> label(g, "gui.lottery.terminal.per_row", sx, r0 + 36);
        }
        g.fill(sx, r0 + 74, sx + 160, r0 + 75, C_BORDER);
        label(g, "gui.lottery.terminal.pity", sx, r0 + 80);
        label(g, "gui.lottery.terminal.pity_mid", sx + 76, r0 + 80);
        label(g, "gui.lottery.terminal.guarantee", sx, r0 + 98);
    }

    /** Style tab, right column: a miniature of the ticket and what the settings mean. */
    private void renderPreview(GuiGraphics g) {
        int x0 = colBX, pw = colBW, ph = previewH, y0 = previewY;
        g.drawString(font, Component.translatable("gui.lottery.terminal.preview"), x0, ct + 6, C_ACCENT, false);
        g.fill(x0, y0, x0 + pw, y0 + ph, C_INSET);
        PlayStyle s = edit.playStyle();
        int color = 0xFF000000 | TicketColors.of(edit.id);
        int draws = Math.max(1, Math.min(edit.draws, edit.maxDraws()));
        switch (s) {
            case MATCH -> {
                int cols = clamp(edit.cols, 1, TicketType.MAX_COLS), rows = clamp(edit.rows, 1, TicketType.MAX_ROWS);
                int wc = clamp(edit.winCount, 1, TicketType.MAX_WIN_NUMBERS);
                int ww = wc * 14 - 2;
                for (int i = 0; i < wc; i++) {
                    int x = x0 + (pw - ww) / 2 + i * 14;
                    g.fill(x, y0 + 5, x + 12, y0 + 13, 0xFFC9A44A);
                }
                int bw = Math.min(18, (pw - 10 - (cols - 1) * 2) / cols), bh = Math.min(14, (ph - 24 - (rows - 1) * 2) / rows);
                int gw = cols * (bw + 2) - 2;
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        int x = x0 + (pw - gw) / 2 + c * (bw + 2), y = y0 + 18 + r * (bh + 2);
                        g.fill(x, y, x + bw, y + bh, 0xFFC1C1CA);
                    }
                }
            }
            case CASE -> {
                int rh = Math.min(16, (ph - 10 - (draws - 1) * 4) / draws);
                int tiles = (pw - 12) / 17;
                for (int i = 0; i < draws; i++) {
                    int y = y0 + 5 + i * (rh + 4);
                    g.fill(x0 + 4, y, x0 + pw - 4, y + rh, 0xFF2C3038);
                    for (int k = 0; k < tiles; k++) {
                        int tx = x0 + 7 + k * 17;
                        int tier = (k * 7 + i * 3) % Tier.COUNT;
                        g.fill(tx, y + 2, tx + 14, y + rh - 2, 0xFF000000 | Tier.color(Math.min(tier, 2)));
                    }
                    g.fill(x0 + pw / 2 - 1, y - 2, x0 + pw / 2 + 1, y + rh + 2, C_ACCENT);
                }
            }
            case CARDS -> {
                int per = Math.min(clamp(edit.cardCols, 1, TicketType.MAX_COLS), draws);
                int rows = (draws + per - 1) / per;
                int ch = Math.min(40, (ph - 10 - (rows - 1) * 4) / rows), cwd = ch * 3 / 4;
                for (int i = 0; i < draws; i++) {
                    int r = i / per, c = i % per;
                    int inRow = Math.min(per, draws - r * per);
                    int x = x0 + (pw - (inRow * (cwd + 4) - 4)) / 2 + c * (cwd + 4), y = y0 + 5 + r * (ch + 4);
                    g.fill(x, y, x + cwd, y + ch, color);
                    g.fill(x + 2, y + 2, x + cwd - 2, y + ch - 2, 0xFF000000 | TicketColors.dark(edit.id));
                }
            }
        }

        int y = y0 + ph + 6;
        y = previewLine(g, Component.translatable("gui.lottery.terminal.p_draw", pct(edit.winChance())), y, C_TEXT);
        TicketType probe = edit.copy();
        probe.draws = draws;
        y = previewLine(g, Component.translatable("gui.lottery.terminal.p_ticket", pct(probe.ticketWinChance())), y, C_ACCENT);
        y = previewLine(g, Component.translatable("gui.lottery.terminal.p_expect", String.format(Locale.ROOT, "%.2f", draws * edit.winChance())), y, C_DIM);
        if (edit.draws > edit.maxDraws()) {
            y = previewLine(g, Component.translatable("gui.lottery.terminal.w_draws", edit.maxDraws()), y, C_BAD);
        }
        if (s == PlayStyle.MATCH && edit.numberRange > 0 && edit.numberRange < edit.neededRange()) {
            y = previewLine(g, Component.translatable("gui.lottery.terminal.w_range", edit.neededRange()), y, C_BAD);
        }
        if (edit.pityCount > 0) {
            boolean ok = edit.hasTierAtLeast(edit.pityTier);
            y = previewLine(g, ok ? Component.translatable("gui.lottery.terminal.i_pity", edit.pityCount, Tier.name(edit.pityTier))
                    : Component.translatable("gui.lottery.terminal.w_no_tier", Tier.name(edit.pityTier)), y, ok ? C_DIM : C_BAD);
        }
        if (edit.guaranteeTier >= 0) {
            boolean ok = edit.hasTierAtLeast(edit.guaranteeTier);
            Component c = !ok ? Component.translatable("gui.lottery.terminal.w_no_tier", Tier.name(edit.guaranteeTier))
                    : draws <= 1 ? Component.translatable("gui.lottery.terminal.w_single")
                    : Component.translatable("gui.lottery.terminal.i_guarantee", Tier.name(edit.guaranteeTier));
            previewLine(g, c, y, ok && draws > 1 ? C_DIM : C_BAD);
        }
    }

    private int previewLine(GuiGraphics g, Component text, int y, int color) {
        if (y + 9 > cb) return y;
        // keep the tier colours inside the text while still clipping to the column
        g.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(text, colBW)), colBX, y, color, false);
        return y + 11;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String pct1(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.2f%%", v * 100);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Flat tab: the active one is lit and joins the content panel below it. */
    private class TabButton extends Button {
        private final int index;

        TabButton(int x, int y, int w, int h, Component text, int index, OnPress press) {
            super(x, y, w, h, text, press, DEFAULT_NARRATION);
            this.index = index;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean active = tab == index;
            int bg = active ? C_COL : isHovered() ? C_TAB_HOVER : C_TAB;
            g.fill(getX(), getY(), getX() + width, getY() + height, bg);
            if (active) {
                g.fill(getX(), getY(), getX() + width, getY() + 2, C_ACCENT);
                g.fill(getX(), getY() + height - 1, getX() + width, getY() + height + 1, C_COL); // break the accent line
            }
            Component text = getMessage();
            g.drawString(font, text, getX() + (width - font.width(text)) / 2, getY() + (height - 8) / 2 + 1,
                    active ? C_ACCENT : isHovered() ? C_TEXT : C_DIM, false);
        }
    }
}
