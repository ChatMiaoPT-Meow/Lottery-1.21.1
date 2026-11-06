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
 * lottery types | selected type settings + (tab) prize pools or play style | pool items + search, or a live preview.
 * Edits are local until "保存"; the server assigns the type number on first save.
 */
public class TerminalScreen extends Screen {
    private static final int W = 420, H = 238;
    private static final int TYPE_ROWS = 14, TYPE_ROW_H = 12;
    private static final int POOL_ROWS = 5, POOL_ROW_H = 11, POOL_Y = 112;
    private static final int ITEM_ROWS = 4, ITEM_ROW_H = 18;
    private static final int RESULT_COLS = 8, RESULT_ROWS = 2;
    // style tab rows (relative to top)
    private static final int S_STYLE = 112, S_DRAWS = 128, S_LAYOUT = 144, S_EXTRA = 160, S_PITY = 176, S_GUAR = 192;

    private static final int C_PANEL = 0xF0202329, C_COL = 0xFF2B2F36, C_BORDER = 0xFF4A505C,
            C_TEXT = 0xFFE8E8E8, C_DIM = 0xFF9AA0AA, C_ACCENT = 0xFFFFC94A, C_SEL = 0xFF3D5A80, C_BAD = 0xFFE06060;

    private List<TicketType> types = new ArrayList<>();
    private int maxGenerate = 1000;
    private TicketType edit;
    private boolean dirty;
    private Integer pendingSwitch;
    private int selPool = -1, selItem = -1;
    private int typeScroll, poolScroll, itemScroll, resultScroll;
    private boolean styleTab;
    private Component status = Component.empty();
    private int statusColor = C_DIM;
    private final List<ItemStack> results = new ArrayList<>();
    private boolean loading;

    private EditBox nameBox, emptyBox, poolNameBox, poolWeightBox, itemWeightBox, itemMinBox, itemMaxBox, searchBox, countBox;
    private EditBox drawsBox, winCountBox, colsBox, rowsBox, rangeBox, spinBox, cardColsBox, pityCountBox;
    private Button enableBtn, addPoolBtn, delPoolBtn, delItemBtn, offhandBtn, generateBtn, saveBtn;
    private Button poolTabBtn, styleTabBtn, poolTierBtn, styleBtn, rareBtn, pityTierBtn, guarTierBtn;
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

        poolTabBtn = small(Component.translatable("gui.lottery.terminal.tab_pools"), midX, top + 97, 40, () -> { styleTab = false; refreshWidgets(); });
        styleTabBtn = small(Component.translatable("gui.lottery.terminal.tab_style"), midX + 42, top + 97, 40, () -> { styleTab = true; refreshWidgets(); });

        // ---- pools tab
        addPoolBtn = small(Component.literal("+"), midX + 118, top + 98, 14, this::addPool);
        delPoolBtn = small(Component.literal("-"), midX + 134, top + 98, 14, this::deletePool);
        poolNameBox = textBox(midX + 24, top + 170, 66, TicketType.MAX_NAME, s -> {
            if (pool() != null) { pool().name = s; touch(); }
        });
        poolWeightBox = numBox(midX + 112, top + 170, 36, v -> {
            if (pool() != null) { pool().weight = v; touch(); }
        });
        poolTierBtn = cycle(midX + 40, top + 186, 108, () -> {
            if (pool() != null) { pool().tier = (pool().tier + 1) % Tier.COUNT; touch(); }
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

        // ---- style tab
        styleBtn = cycle(midX + 30, top + S_STYLE, 118, () -> {
            edit.style = edit.playStyle().next().id;
            touch();
        });
        drawsBox = numBox(midX + 26, top + S_DRAWS, 26, v -> { edit.draws = v; touch(); });
        winCountBox = numBox(midX + 96, top + S_DRAWS, 24, v -> { edit.winCount = v; touch(); });
        colsBox = numBox(midX + 26, top + S_LAYOUT, 24, v -> { edit.cols = v; touch(); });
        rowsBox = numBox(midX + 62, top + S_LAYOUT, 24, v -> { edit.rows = v; touch(); });
        spinBox = numBox(midX + 52, top + S_LAYOUT, 26, v -> { edit.spinSeconds = v; touch(); });
        cardColsBox = numBox(midX + 26, top + S_LAYOUT, 24, v -> { edit.cardCols = v; touch(); });
        rangeBox = numBox(midX + 52, top + S_EXTRA, 26, v -> { edit.numberRange = v; touch(); });
        rareBtn = cycle(midX, top + S_EXTRA, 148, () -> { edit.rareEffect = !edit.rareEffect; touch(); });
        pityCountBox = numBox(midX + 26, top + S_PITY, 30, v -> { edit.pityCount = v; touch(); });
        pityTierBtn = cycle(midX + 96, top + S_PITY, 52, () -> { edit.pityTier = (edit.pityTier + 1) % Tier.COUNT; touch(); });
        guarTierBtn = cycle(midX + 52, top + S_GUAR, 96, () -> {
            edit.guaranteeTier = edit.guaranteeTier + 1 >= Tier.COUNT ? -1 : edit.guaranteeTier + 1;
            touch();
        });

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
        nameBox.setValue(edit.name);
        emptyBox.setValue(String.valueOf(edit.emptyWeight));
        enableBtn.setMessage(Component.translatable(edit.enabled ? "gui.lottery.terminal.enabled" : "gui.lottery.terminal.disabled"));
        poolTabBtn.active = styleTab;
        styleTabBtn.active = !styleTab;

        // ---- pools tab
        boolean pools = !styleTab;
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

        // ---- style tab
        PlayStyle s = edit.playStyle();
        styleBtn.visible = drawsBox.visible = pityCountBox.visible = pityTierBtn.visible = guarTierBtn.visible = styleTab;
        winCountBox.visible = colsBox.visible = rowsBox.visible = rangeBox.visible = styleTab && s == PlayStyle.MATCH;
        spinBox.visible = styleTab && s == PlayStyle.CASE;
        cardColsBox.visible = rareBtn.visible = styleTab && s == PlayStyle.CARDS;
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
        if (styleTab) return false;
        // pool list
        row = rowAt(mx, my, midX, top + POOL_Y, 150, POOL_ROW_H, POOL_ROWS);
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
        } else if (styleTab) {
            return super.mouseScrolled(mx, my, sx, sy);
        } else if (mx >= midX && mx < midX + 150 && my >= top + POOL_Y && my < top + POOL_Y + POOL_ROWS * POOL_ROW_H) {
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

        if (styleTab) {
            renderStyleSettings(g);
            renderPreview(g);
        } else {
            tooltip = renderPools(g, mouseX, mouseY, total);
        }

        // ---- bottom bar
        g.drawString(font, Component.translatable("gui.lottery.terminal.count"), left + 8, top + 219, C_TEXT, false);
        g.drawString(font, font.substrByWidth(status, W - 220).getString(), left + 216, top + 219, statusColor, false);

        if (!tooltip.isEmpty()) g.renderTooltip(font, tooltip, mouseX, mouseY);
    }

    /** Pools tab: pool list in the middle, items + search on the right. @return item to show a tooltip for */
    private ItemStack renderPools(GuiGraphics g, int mouseX, int mouseY, long total) {
        ItemStack tooltip = ItemStack.EMPTY;
        for (int r = 0; r < POOL_ROWS && poolScroll + r < edit.pools.size(); r++) {
            int pi = poolScroll + r;
            TicketType.Pool p = edit.pools.get(pi);
            int y = top + POOL_Y + r * POOL_ROW_H;
            if (pi == selPool) g.fill(midX, y, midX + 150, y + POOL_ROW_H, C_SEL);
            g.fill(midX + 2, y + 2, midX + 5, y + 9, 0xFF000000 | Tier.color(p.tier));
            g.drawString(font, font.plainSubstrByWidth(p.name, 64), midX + 8, y + 2, C_TEXT, false);
            g.drawString(font, "w" + p.weight, midX + 76, y + 2, C_DIM, false);
            String share = p.usable() ? pct((double) p.weight / total) : Component.translatable("gui.lottery.terminal.pool_unused").getString();
            g.drawString(font, share, midX + 148 - font.width(share), y + 2, p.usable() ? C_TEXT : C_BAD, false);
        }
        if (edit.pools.isEmpty()) g.drawString(font, Component.translatable("gui.lottery.terminal.no_pools"), midX + 2, top + POOL_Y + 2, C_DIM, false);
        if (pool() != null) {
            g.drawString(font, Component.translatable("gui.lottery.terminal.pool_name_label"), midX, top + 173, C_TEXT, false);
            g.drawString(font, Component.translatable("gui.lottery.terminal.weight"), midX + 92, top + 173, C_TEXT, false);
            g.drawString(font, Component.translatable("gui.lottery.terminal.tier"), midX, top + 189, C_TEXT, false);
        }

        TicketType.Pool p = pool();
        if (p == null) {
            g.drawString(font, Component.translatable("gui.lottery.terminal.select_pool"), rightX, top + 8, C_DIM, false);
            return tooltip;
        }
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
        return tooltip;
    }

    /** Labels of the style tab (the inputs themselves are widgets). */
    private void renderStyleSettings(GuiGraphics g) {
        PlayStyle s = edit.playStyle();
        label(g, "gui.lottery.terminal.style", midX, S_STYLE);
        label(g, "gui.lottery.terminal.draws", midX, S_DRAWS);
        switch (s) {
            case MATCH -> {
                label(g, "gui.lottery.terminal.win_numbers", midX + 58, S_DRAWS);
                label(g, "gui.lottery.terminal.grid", midX, S_LAYOUT);
                g.drawString(font, "×", midX + 53, top + S_LAYOUT + 3, C_TEXT, false);
                label(g, "gui.lottery.terminal.range", midX, S_EXTRA);
                g.drawString(font, Component.translatable("gui.lottery.terminal.range_hint"), midX + 82, top + S_EXTRA + 3, C_DIM, false);
            }
            case CASE -> label(g, "gui.lottery.terminal.spin", midX, S_LAYOUT);
            case CARDS -> label(g, "gui.lottery.terminal.per_row", midX, S_LAYOUT);
        }
        g.drawString(font, Component.translatable("gui.lottery.terminal.max_draws", edit.maxDraws()), midX + (s == PlayStyle.MATCH ? 124 : 58),
                top + S_DRAWS + 3, C_DIM, false);
        label(g, "gui.lottery.terminal.pity", midX, S_PITY);
        label(g, "gui.lottery.terminal.pity_mid", midX + 60, S_PITY);
        label(g, "gui.lottery.terminal.guarantee", midX, S_GUAR);
    }

    private void label(GuiGraphics g, String key, int x, int rowY) {
        g.drawString(font, Component.translatable(key), x, top + rowY + 3, C_TEXT, false);
    }

    /** Style tab, right column: a miniature of the ticket and what the settings mean. */
    private void renderPreview(GuiGraphics g) {
        g.drawString(font, Component.translatable("gui.lottery.terminal.preview"), rightX, top + 8, C_ACCENT, false);
        int x0 = rightX, y0 = top + 20, pw = 146, ph = 76;
        g.fill(x0, y0, x0 + pw, y0 + ph, 0xFF1A1D22);
        PlayStyle s = edit.playStyle();
        int color = 0xFF000000 | TicketColors.of(edit.id);
        int draws = Math.max(1, Math.min(edit.draws, edit.maxDraws()));
        switch (s) {
            case MATCH -> {
                int cols = clamp(edit.cols, 1, TicketType.MAX_COLS), rows = clamp(edit.rows, 1, TicketType.MAX_ROWS);
                int wc = clamp(edit.winCount, 1, TicketType.MAX_WIN_NUMBERS);
                int ww = wc * 12 - 2;
                for (int i = 0; i < wc; i++) {
                    int x = x0 + (pw - ww) / 2 + i * 12;
                    g.fill(x, y0 + 4, x + 10, y0 + 11, 0xFFC9A44A);
                }
                int bw = Math.min(14, (pw - 8 - (cols - 1) * 2) / cols), bh = Math.min(10, (ph - 18 - (rows - 1) * 2) / rows);
                int gw = cols * (bw + 2) - 2;
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        int x = x0 + (pw - gw) / 2 + c * (bw + 2), y = y0 + 15 + r * (bh + 2);
                        g.fill(x, y, x + bw, y + bh, 0xFFC1C1CA);
                    }
                }
            }
            case CASE -> {
                int rh = Math.min(12, (ph - 8 - (draws - 1) * 3) / draws);
                for (int i = 0; i < draws; i++) {
                    int y = y0 + 4 + i * (rh + 3);
                    g.fill(x0 + 4, y, x0 + pw - 4, y + rh, 0xFF2C3038);
                    for (int k = 0; k < 9; k++) {
                        int tx = x0 + 8 + k * 15;
                        int tier = (k * 7 + i * 3) % Tier.COUNT;
                        g.fill(tx, y + 1, tx + 12, y + rh - 1, 0xFF000000 | Tier.color(Math.min(tier, 2)));
                    }
                    g.fill(x0 + pw / 2 - 1, y - 1, x0 + pw / 2 + 1, y + rh + 1, C_ACCENT);
                }
            }
            case CARDS -> {
                int per = clamp(edit.cardCols, 1, TicketType.MAX_COLS);
                per = Math.min(per, draws);
                int rows = (draws + per - 1) / per;
                int ch = Math.min(28, (ph - 8 - (rows - 1) * 3) / rows), cw = ch * 3 / 4;
                for (int i = 0; i < draws; i++) {
                    int r = i / per, c = i % per;
                    int inRow = Math.min(per, draws - r * per);
                    int x = x0 + (pw - (inRow * (cw + 3) - 3)) / 2 + c * (cw + 3), y = y0 + 4 + r * (ch + 3);
                    g.fill(x, y, x + cw, y + ch, color);
                    g.fill(x + 2, y + 2, x + cw - 2, y + ch - 2, 0xFF000000 | TicketColors.dark(edit.id));
                }
            }
        }

        // numbers
        int y = y0 + ph + 4;
        line(g, Component.translatable("gui.lottery.terminal.p_draw", pct(edit.winChance())), y, C_TEXT);
        y += 10;
        TicketType probe = edit.copy();
        probe.draws = draws;
        line(g, Component.translatable("gui.lottery.terminal.p_ticket", pct(probe.ticketWinChance())), y, C_TEXT);
        y += 10;
        line(g, Component.translatable("gui.lottery.terminal.p_expect", String.format(Locale.ROOT, "%.2f", draws * edit.winChance())), y, C_DIM);
        y += 10;
        if (edit.draws > edit.maxDraws()) {
            line(g, Component.translatable("gui.lottery.terminal.w_draws", edit.maxDraws()), y, C_BAD);
            y += 10;
        }
        if (s == PlayStyle.MATCH && edit.numberRange > 0 && edit.numberRange < edit.neededRange()) {
            line(g, Component.translatable("gui.lottery.terminal.w_range", edit.neededRange()), y, C_BAD);
            y += 10;
        }
        if (edit.pityCount > 0) {
            boolean ok = edit.hasTierAtLeast(edit.pityTier);
            line(g, ok ? Component.translatable("gui.lottery.terminal.i_pity", edit.pityCount, Tier.name(edit.pityTier))
                    : Component.translatable("gui.lottery.terminal.w_no_tier", Tier.name(edit.pityTier)), y, ok ? C_DIM : C_BAD);
            y += 10;
        }
        if (edit.guaranteeTier >= 0) {
            boolean ok = edit.hasTierAtLeast(edit.guaranteeTier);
            Component c = !ok ? Component.translatable("gui.lottery.terminal.w_no_tier", Tier.name(edit.guaranteeTier))
                    : draws <= 1 ? Component.translatable("gui.lottery.terminal.w_single")
                    : Component.translatable("gui.lottery.terminal.i_guarantee", Tier.name(edit.guaranteeTier));
            line(g, c, y, ok && draws > 1 ? C_DIM : C_BAD);
        }
    }

    private void line(GuiGraphics g, Component text, int y, int color) {
        // keep the tier colours inside the text while still clipping to the column
        g.drawString(font, net.minecraft.locale.Language.getInstance().getVisualOrder(font.substrByWidth(text, 146)),
                rightX, y, color, false);
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
}
