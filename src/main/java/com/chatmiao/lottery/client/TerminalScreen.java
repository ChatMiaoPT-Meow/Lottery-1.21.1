package com.chatmiao.lottery.client;

import com.chatmiao.lottery.data.TicketType;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * 彩票终端 GUI (OP only). Three columns:
 * lottery types | selected type settings + prize pools | items of the selected pool + item search.
 * Edits are local until "保存"; the server assigns the type number on first save.
 */
public class TerminalScreen extends Screen {
    private static final int W = 420, H = 238;
    private static final int TYPE_ROWS = 14, TYPE_ROW_H = 12;
    private static final int POOL_ROWS = 6, POOL_ROW_H = 11;
    private static final int ITEM_ROWS = 4, ITEM_ROW_H = 18;
    private static final int RESULT_COLS = 8, RESULT_ROWS = 2;

    private static final int C_PANEL = 0xF0202329, C_COL = 0xFF2B2F36, C_BORDER = 0xFF4A505C,
            C_TEXT = 0xFFE8E8E8, C_DIM = 0xFF9AA0AA, C_ACCENT = 0xFFFFC94A, C_SEL = 0xFF3D5A80, C_BAD = 0xFFE06060;

    private List<TicketType> types = new ArrayList<>();
    private int maxGenerate = 1000;
    private TicketType edit;
    private boolean dirty;
    private Integer pendingSwitch;
    private int selPool = -1, selItem = -1;
    private int typeScroll, poolScroll, itemScroll, resultScroll;
    private Component status = Component.empty();
    private int statusColor = C_DIM;
    private final List<ItemStack> results = new ArrayList<>();
    private boolean loading;

    private EditBox nameBox, emptyBox, poolNameBox, poolWeightBox, itemWeightBox, itemMinBox, itemMaxBox, searchBox, countBox;
    private Button enableBtn, addPoolBtn, delPoolBtn, delItemBtn, offhandBtn, generateBtn, saveBtn;
    private int left, top, midX, rightX;

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
        poolScroll = itemScroll = 0;
    }

    private void newType() {
        edit = new TicketType();
        edit.id = 0;
        edit.name = Component.translatable("gui.lottery.terminal.default_name").getString();
        edit.emptyWeight = 65;
        dirty = true;
        pendingSwitch = null;
        selPool = selItem = -1;
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

    // ================================================================= widgets

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        midX = left + 112;
        rightX = left + 268;

        nameBox = textBox(midX + 30, top + 44, 118, TicketType.MAX_NAME, s -> { edit.name = s; touch(); });
        emptyBox = numBox(midX + 52, top + 62, 44, v -> { edit.emptyWeight = v; touch(); });
        enableBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            edit.enabled = !edit.enabled;
            touch();
            refreshWidgets();
        }).bounds(midX, top + 80, 60, 14).build());
        addPoolBtn = addRenderableWidget(Button.builder(Component.literal("+"), b -> addPool()).bounds(midX + 118, top + 98, 14, 12).build());
        delPoolBtn = addRenderableWidget(Button.builder(Component.literal("-"), b -> deletePool()).bounds(midX + 134, top + 98, 14, 12).build());
        poolNameBox = textBox(midX + 24, top + 184, 66, TicketType.MAX_NAME, s -> {
            if (pool() != null) { pool().name = s; touch(); }
        });
        poolWeightBox = numBox(midX + 112, top + 184, 36, v -> {
            if (pool() != null) { pool().weight = v; touch(); }
        });

        itemWeightBox = numBox(rightX + 26, top + 96, 40, v -> {
            if (item() != null) { item().weight = v; touch(); }
        });
        itemMinBox = numBox(rightX + 26, top + 112, 34, v -> {
            if (item() != null) { item().min = v; touch(); }
        });
        itemMaxBox = numBox(rightX + 70, top + 112, 34, v -> {
            if (item() != null) { item().max = v; touch(); }
        });
        delItemBtn = addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.delete"), b -> deleteItem())
                .bounds(rightX + 108, top + 112, 38, 14).build());
        searchBox = textBox(rightX, top + 130, 146, 50, s -> updateResults());
        searchBox.setHint(Component.translatable("gui.lottery.terminal.search"));
        offhandBtn = addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.offhand"), b -> addOffhand())
                .bounds(rightX, top + 188, 146, 14).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.new"), b -> clickNew())
                .bounds(left + 6, top + 194, 100, 14).build());
        countBox = numBox(left + 52, top + 216, 44, v -> {});
        countBox.setValue("64");
        generateBtn = addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.generate"), b -> generate())
                .bounds(left + 100, top + 215, 54, 16).build());
        saveBtn = addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.save"), b -> save())
                .bounds(left + 158, top + 215, 54, 16).build());

        refreshWidgets();
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
        nameBox.setValue(edit.name);
        emptyBox.setValue(String.valueOf(edit.emptyWeight));
        enableBtn.setMessage(Component.translatable(edit.enabled ? "gui.lottery.terminal.enabled" : "gui.lottery.terminal.disabled"));
        TicketType.Pool p = pool();
        poolNameBox.visible = poolWeightBox.visible = p != null;
        delPoolBtn.active = p != null;
        addPoolBtn.active = edit.pools.size() < TicketType.MAX_POOLS;
        if (p != null) {
            poolNameBox.setValue(p.name);
            poolWeightBox.setValue(String.valueOf(p.weight));
        }
        TicketType.PoolItem it = item();
        itemWeightBox.visible = itemMinBox.visible = itemMaxBox.visible = delItemBtn.visible = it != null;
        if (it != null) {
            itemWeightBox.setValue(String.valueOf(it.weight));
            itemMinBox.setValue(String.valueOf(it.min));
            itemMaxBox.setValue(String.valueOf(it.max));
        }
        searchBox.visible = offhandBtn.visible = p != null;
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
        edit.pools.add(p);
        selPool = edit.pools.size() - 1;
        selItem = -1;
        if (selPool >= poolScroll + POOL_ROWS) poolScroll = selPool - POOL_ROWS + 1;
        touch();
        refreshWidgets();
    }

    private void deletePool() {
        if (pool() == null) return;
        edit.pools.remove(selPool);
        selPool = Math.min(selPool, edit.pools.size() - 1);
        selItem = -1;
        poolScroll = Math.max(0, Math.min(poolScroll, edit.pools.size() - POOL_ROWS));
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
        if (selItem >= itemScroll + ITEM_ROWS) itemScroll = selItem - ITEM_ROWS + 1;
        touch();
        setStatus("gui.lottery.terminal.item_added", C_DIM, stack.getHoverName());
        refreshWidgets();
    }

    private void deleteItem() {
        TicketType.Pool p = pool();
        if (item() == null) return;
        p.items.remove(selItem);
        selItem = -1;
        itemScroll = Math.max(0, Math.min(itemScroll, p.items.size() - ITEM_ROWS));
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
        PacketDistributor.sendToServer(new Payloads.SaveType(edit.copy()));
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
        resultScroll = 0;
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

    // ================================================================= input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return false;

        // type list
        int row = rowAt(mx, my, left + 6, top + 20, 100, TYPE_ROW_H, TYPE_ROWS);
        if (row >= 0 && typeScroll + row < types.size()) {
            clickType(types.get(typeScroll + row));
            return true;
        }
        // pool list
        row = rowAt(mx, my, midX, top + 114, 150, POOL_ROW_H, POOL_ROWS);
        if (row >= 0 && poolScroll + row < edit.pools.size()) {
            selPool = poolScroll + row;
            selItem = -1;
            itemScroll = 0;
            refreshWidgets();
            return true;
        }
        // item list
        TicketType.Pool p = pool();
        row = rowAt(mx, my, rightX, top + 20, 146, ITEM_ROW_H, ITEM_ROWS);
        if (p != null && row >= 0 && itemScroll + row < p.items.size()) {
            selItem = itemScroll + row;
            refreshWidgets();
            return true;
        }
        // search results
        int ri = resultAt(mx, my);
        if (p != null && ri >= 0) {
            addItem(results.get(ri));
            return true;
        }
        return false;
    }

    private static int rowAt(double mx, double my, int x, int y, int w, int rowH, int rows) {
        if (mx < x || mx >= x + w || my < y || my >= y + rowH * rows) return -1;
        return (int) ((my - y) / rowH);
    }

    private int resultAt(double mx, double my) {
        int x0 = rightX + 1, y0 = top + 148;
        if (mx < x0 || mx >= x0 + RESULT_COLS * 18 || my < y0 || my >= y0 + RESULT_ROWS * 18) return -1;
        int i = (resultScroll + (int) ((my - y0) / 18)) * RESULT_COLS + (int) ((mx - x0) / 18);
        return i < results.size() ? i : -1;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int d = sy > 0 ? -1 : 1;
        if (mx >= left + 6 && mx < left + 106 && my >= top + 20 && my < top + 20 + TYPE_ROWS * TYPE_ROW_H) {
            typeScroll = clampScroll(typeScroll + d, types.size(), TYPE_ROWS);
        } else if (mx >= midX && mx < midX + 150 && my >= top + 114 && my < top + 114 + POOL_ROWS * POOL_ROW_H) {
            poolScroll = clampScroll(poolScroll + d, edit.pools.size(), POOL_ROWS);
        } else if (pool() != null && mx >= rightX && mx < rightX + 146 && my >= top + 20 && my < top + 20 + ITEM_ROWS * ITEM_ROW_H) {
            itemScroll = clampScroll(itemScroll + d, pool().items.size(), ITEM_ROWS);
        } else if (mx >= rightX && mx < rightX + 146 && my >= top + 148 && my < top + 148 + RESULT_ROWS * 18) {
            int rows = (results.size() + RESULT_COLS - 1) / RESULT_COLS;
            resultScroll = clampScroll(resultScroll + d, rows, RESULT_ROWS);
        } else {
            return super.mouseScrolled(mx, my, sx, sy);
        }
        return true;
    }

    private static int clampScroll(int v, int size, int visible) {
        return Math.max(0, Math.min(v, size - visible));
    }

    // ================================================================= rendering

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, C_BORDER);
        g.fill(left, top, left + W, top + H, C_PANEL);
        g.fill(left + 4, top + 4, left + 108, top + 210, C_COL);
        g.fill(midX - 2, top + 4, midX + 152, top + 210, C_COL);
        g.fill(rightX - 2, top + 4, rightX + 148, top + 210, C_COL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        ItemStack tooltip = ItemStack.EMPTY;

        // ---- left: types
        g.drawString(font, Component.translatable("gui.lottery.terminal.types"), left + 8, top + 8, C_ACCENT, false);
        for (int r = 0; r < TYPE_ROWS && typeScroll + r < types.size(); r++) {
            TicketType t = types.get(typeScroll + r);
            int y = top + 20 + r * TYPE_ROW_H;
            if (t.id == edit.id) g.fill(left + 6, y, left + 106, y + TYPE_ROW_H, C_SEL);
            g.fill(left + 8, y + 3, left + 14, y + 9, 0xFF000000 | TicketColors.of(t.id));
            String label = "#" + t.id + " " + t.name;
            g.drawString(font, font.plainSubstrByWidth(label, 88), left + 17, y + 2, t.enabled ? C_TEXT : C_DIM, false);
        }
        if (types.isEmpty()) g.drawString(font, Component.translatable("gui.lottery.terminal.no_types"), left + 8, top + 22, C_DIM, false);

        // ---- middle: settings
        Component header = edit.id > 0
                ? Component.translatable("gui.lottery.terminal.editing", edit.id)
                : Component.translatable("gui.lottery.terminal.unsaved_new");
        g.drawString(font, header, midX, top + 8, C_ACCENT, false);
        if (dirty) g.drawString(font, "*", midX + font.width(header) + 3, top + 8, C_BAD, false);
        if (edit.id > 0) {
            g.drawString(font, Component.translatable("gui.lottery.terminal.stats", edit.issued, edit.scratched, edit.wins),
                    midX, top + 20, C_DIM, false);
            if (edit.scratched > 0) {
                g.drawString(font, Component.translatable("gui.lottery.terminal.actual_rate",
                        pct((double) edit.wins / edit.scratched)), midX, top + 30, C_DIM, false);
            }
        }
        g.drawString(font, Component.translatable("gui.lottery.terminal.name"), midX, top + 47, C_TEXT, false);
        g.drawString(font, Component.translatable("gui.lottery.terminal.empty_weight"), midX, top + 65, C_TEXT, false);
        long total = edit.totalWeight();
        g.drawString(font, pct(total > 0 ? (double) edit.emptyWeight / total : 1), midX + 100, top + 65, C_DIM, false);
        g.drawString(font, Component.translatable("gui.lottery.terminal.win_chance", pct(edit.winChance())),
                midX + 64, top + 83, edit.winChance() > 0 ? C_ACCENT : C_BAD, false);

        g.drawString(font, Component.translatable("gui.lottery.terminal.pools"), midX, top + 100, C_ACCENT, false);
        for (int r = 0; r < POOL_ROWS && poolScroll + r < edit.pools.size(); r++) {
            int pi = poolScroll + r;
            TicketType.Pool p = edit.pools.get(pi);
            int y = top + 114 + r * POOL_ROW_H;
            if (pi == selPool) g.fill(midX, y, midX + 150, y + POOL_ROW_H, C_SEL);
            g.drawString(font, font.plainSubstrByWidth(p.name, 70), midX + 2, y + 2, C_TEXT, false);
            g.drawString(font, "w" + p.weight, midX + 76, y + 2, C_DIM, false);
            String share = p.usable() ? pct((double) p.weight / total) : Component.translatable("gui.lottery.terminal.pool_unused").getString();
            g.drawString(font, share, midX + 148 - font.width(share), y + 2, p.usable() ? C_TEXT : C_BAD, false);
        }
        if (edit.pools.isEmpty()) g.drawString(font, Component.translatable("gui.lottery.terminal.no_pools"), midX + 2, top + 116, C_DIM, false);
        if (pool() != null) {
            g.drawString(font, Component.translatable("gui.lottery.terminal.pool_name_label"), midX, top + 187, C_TEXT, false);
            g.drawString(font, Component.translatable("gui.lottery.terminal.weight"), midX + 92, top + 187, C_TEXT, false);
        }

        // ---- right: pool items
        TicketType.Pool p = pool();
        if (p == null) {
            g.drawString(font, Component.translatable("gui.lottery.terminal.select_pool"), rightX, top + 8, C_DIM, false);
        } else {
            g.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.lottery.terminal.pool_items", p.name).getString(), 146),
                    rightX, top + 8, C_ACCENT, false);
            long itemTotal = p.totalItemWeight();
            for (int r = 0; r < ITEM_ROWS && itemScroll + r < p.items.size(); r++) {
                int ii = itemScroll + r;
                TicketType.PoolItem it = p.items.get(ii);
                int y = top + 20 + r * ITEM_ROW_H;
                if (ii == selItem) g.fill(rightX, y, rightX + 146, y + ITEM_ROW_H, C_SEL);
                g.renderItem(it.item, rightX + 1, y + 1);
                g.drawString(font, font.plainSubstrByWidth(it.item.getHoverName().getString(), 48), rightX + 20, y + 5, C_TEXT, false);
                String range = it.min == it.max ? "×" + it.min : it.min + "~" + it.max;
                g.drawString(font, range, rightX + 70, y + 5, it.min > it.max ? C_BAD : C_DIM, false);
                String share = it.usable() && itemTotal > 0 ? pct1((double) it.weight / itemTotal) : "0%";
                g.drawString(font, share, rightX + 145 - font.width(share), y + 5, C_TEXT, false);
                if (mouseX >= rightX + 1 && mouseX < rightX + 17 && mouseY >= y + 1 && mouseY < y + 17) tooltip = it.item;
            }
            if (p.items.isEmpty()) g.drawString(font, Component.translatable("gui.lottery.terminal.no_items"), rightX + 2, top + 24, C_DIM, false);
            TicketType.PoolItem it = item();
            if (it != null) {
                g.drawString(font, Component.translatable("gui.lottery.terminal.weight"), rightX, top + 99, C_TEXT, false);
                double overall = total > 0 && p.usable() && itemTotal > 0
                        ? (double) p.weight / total * it.weight / itemTotal : 0;
                g.drawString(font, Component.translatable("gui.lottery.terminal.overall", pct(overall)), rightX + 70, top + 99, C_DIM, false);
                g.drawString(font, Component.translatable("gui.lottery.terminal.amount"), rightX, top + 115, C_TEXT, false);
                g.drawString(font, "~", rightX + 62, top + 115, C_TEXT, false);
            }
            // search results
            int x0 = rightX + 1, y0 = top + 148;
            g.fill(x0 - 1, y0 - 1, x0 + RESULT_COLS * 18, y0 + RESULT_ROWS * 18, 0xFF1A1D22);
            for (int i = 0; i < RESULT_COLS * RESULT_ROWS; i++) {
                int idx = resultScroll * RESULT_COLS + i;
                if (idx >= results.size()) break;
                int x = x0 + (i % RESULT_COLS) * 18, y = y0 + (i / RESULT_COLS) * 18;
                boolean hover = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
                if (hover) {
                    g.fill(x, y, x + 18, y + 18, C_SEL);
                    tooltip = results.get(idx);
                }
                g.renderItem(results.get(idx), x + 1, y + 1);
            }
            if (results.isEmpty()) {
                g.drawString(font, Component.translatable(searchBox.getValue().isBlank()
                        ? "gui.lottery.terminal.search_hint" : "gui.lottery.terminal.search_none"), x0 + 2, y0 + 4, C_DIM, false);
            } else if (results.size() > RESULT_COLS * RESULT_ROWS) {
                g.drawString(font, Component.translatable("gui.lottery.terminal.results", results.size()), x0, y0 + 38, C_DIM, false);
            }
        }

        // ---- bottom bar
        g.drawString(font, Component.translatable("gui.lottery.terminal.count"), left + 8, top + 219, C_TEXT, false);
        g.drawString(font, font.substrByWidth(status, W - 220).getString(), left + 216, top + 219, statusColor, false);

        if (!tooltip.isEmpty()) g.renderTooltip(font, tooltip, mouseX, mouseY);
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
}
