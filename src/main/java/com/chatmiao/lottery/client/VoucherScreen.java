package com.chatmiao.lottery.client;

import com.chatmiao.lottery.data.VoucherType;
import com.chatmiao.lottery.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 卡券管理 (OP only): create voucher types (name, colour, enabled) and generate vouchers. Vouchers are plain
 * items, so they can be sold in the Module Shop mall (hold them and add them there) or handed out any way.
 */
public class VoucherScreen extends Screen {
    private static final int W = 360, H = 210, LIST_W = 112, ROW_H = 14, SWATCH = 12;
    private static final int C_PANEL = 0xF0202329, C_COL = 0xFF2B2F36, C_BORDER = 0xFF4A505C, C_TEXT = 0xFFE8E8E8,
            C_DIM = 0xFF9AA0AA, C_ACCENT = 0xFFFFC94A, C_SEL = 0xFF3D5A80, C_BAD = 0xFFE06060;

    private List<VoucherType> vouchers;
    private int maxGenerate;
    private VoucherType edit;
    private boolean dirty;
    private Component status = Component.empty();
    private int statusColor = C_DIM;
    private boolean loading;

    private EditBox nameBox, countBox;
    private Button enableBtn;
    private int left, top, rx;

    public VoucherScreen(Payloads.OpenVouchers data) {
        super(Component.translatable("gui.lottery.vouchers"));
        apply(data);
    }

    public void update(Payloads.OpenVouchers data) {
        apply(data);
        if (minecraft != null) refresh();
    }

    private void apply(Payloads.OpenVouchers data) {
        vouchers = new ArrayList<>(data.vouchers());
        maxGenerate = data.maxGenerate();
        VoucherType target = null;
        for (VoucherType v : vouchers) if (v.id == data.selectId()) target = v;
        if (target == null && edit != null && edit.id > 0) {
            for (VoucherType v : vouchers) if (v.id == edit.id) target = v;
        }
        if (target == null && !vouchers.isEmpty() && (edit == null || edit.id > 0)) target = vouchers.get(0);
        if (target != null) {
            edit = target.copy();
            dirty = false;
        } else if (edit == null) {
            newVoucher();
        }
    }

    private void newVoucher() {
        edit = new VoucherType();
        edit.name = Component.translatable("gui.lottery.vouchers.default_name").getString();
        edit.color = VoucherType.PALETTE[vouchers.size() % VoucherType.PALETTE.length];
        dirty = true;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        rx = left + LIST_W + 14;
        nameBox = new EditBox(font, rx + 40, top + 44, 140, 14, Component.empty());
        nameBox.setMaxLength(VoucherType.MAX_NAME);
        nameBox.setResponder(s -> { if (!loading) { edit.name = s; dirty = true; } });
        addRenderableWidget(nameBox);
        enableBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            edit.enabled = !edit.enabled;
            dirty = true;
            refresh();
        }).bounds(rx + 40, top + 84, 70, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.save"), b -> save())
                .bounds(rx + 150, top + 84, 60, 14).build());
        countBox = new EditBox(font, rx + 52, top + 122, 44, 14, Component.empty());
        countBox.setMaxLength(6);
        countBox.setFilter(s -> s.matches("\\d*"));
        countBox.setValue("64");
        addRenderableWidget(countBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.terminal.generate"), b -> generate())
                .bounds(rx + 100, top + 122, 60, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.vouchers.new"), b -> {
            newVoucher();
            refresh();
        }).bounds(left + 6, top + H - 40, LIST_W, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.lottery.vouchers.back"), b ->
                PacketDistributor.sendToServer(new Payloads.Request(Payloads.Request.MENU)))
                .bounds(left + 6, top + H - 22, LIST_W, 14).build());
        refresh();
    }

    private void refresh() {
        if (nameBox == null) return;
        loading = true;
        nameBox.setValue(edit.name);
        enableBtn.setMessage(Component.translatable(edit.enabled ? "gui.lottery.terminal.enabled" : "gui.lottery.terminal.disabled"));
        loading = false;
    }

    private void save() {
        PacketDistributor.sendToServer(new Payloads.SaveVoucher(edit.copy()));
        status = Component.translatable("gui.lottery.terminal.saving");
        statusColor = C_DIM;
    }

    private void generate() {
        if (edit.id <= 0 || dirty) {
            status = Component.translatable("gui.lottery.terminal.save_first");
            statusColor = C_BAD;
            return;
        }
        int n;
        try {
            n = Integer.parseInt(countBox.getValue());
        } catch (NumberFormatException e) {
            n = 0;
        }
        if (n <= 0 || n > maxGenerate) {
            status = Component.translatable("gui.lottery.terminal.bad_count", maxGenerate);
            statusColor = C_BAD;
            return;
        }
        PacketDistributor.sendToServer(new Payloads.GenerateVoucher(edit.id, n));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return false;
        int listTop = top + 22;
        if (mx >= left + 6 && mx < left + 6 + LIST_W && my >= listTop && my < top + H - 44) {
            int i = (int) ((my - listTop) / ROW_H);
            if (i < vouchers.size()) {
                edit = vouchers.get(i).copy();
                dirty = false;
                refresh();
            }
            return true;
        }
        // colour swatches
        for (int i = 0; i < VoucherType.PALETTE.length; i++) {
            int x = rx + 40 + i * (SWATCH + 2), y = top + 64;
            if (mx >= x && mx < x + SWATCH && my >= y && my < y + SWATCH) {
                edit.color = VoucherType.PALETTE[i];
                dirty = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, C_BORDER);
        g.fill(left, top, left + W, top + H, C_PANEL);
        g.fill(left + 4, top + 4, left + LIST_W + 8, top + H - 4, C_COL);
        g.fill(rx - 4, top + 4, left + W - 4, top + H - 4, C_COL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, Component.translatable("gui.lottery.vouchers"), left + 8, top + 9, C_ACCENT, false);
        for (int i = 0; i < vouchers.size(); i++) {
            VoucherType v = vouchers.get(i);
            int y = top + 22 + i * ROW_H;
            if (y + ROW_H > top + H - 44) break;
            if (v.id == edit.id) g.fill(left + 6, y, left + 6 + LIST_W, y + ROW_H, C_SEL);
            g.fill(left + 8, y + 3, left + 16, y + 11, 0xFF000000 | v.color);
            g.drawString(font, font.plainSubstrByWidth(v.name, LIST_W - 16), left + 20, y + 3, v.enabled ? C_TEXT : C_DIM, false);
        }
        if (vouchers.isEmpty()) g.drawString(font, Component.translatable("gui.lottery.vouchers.none"), left + 8, top + 24, C_DIM, false);

        Component header = edit.id > 0 ? Component.translatable("gui.lottery.terminal.editing_named", edit.id, edit.name)
                : Component.translatable("gui.lottery.vouchers.unsaved");
        g.drawString(font, header, rx, top + 9, C_ACCENT, false);
        if (dirty) g.drawString(font, "*", rx + font.width(header) + 3, top + 9, C_BAD, false);
        if (edit.id > 0) {
            g.drawString(font, Component.translatable("gui.lottery.vouchers.stats", edit.issued, edit.spent), rx, top + 24, C_DIM, false);
        }
        g.drawString(font, Component.translatable("gui.lottery.terminal.name"), rx, top + 47, C_TEXT, false);
        g.drawString(font, Component.translatable("gui.lottery.vouchers.color"), rx, top + 66, C_TEXT, false);
        for (int i = 0; i < VoucherType.PALETTE.length; i++) {
            int x = rx + 40 + i * (SWATCH + 2), y = top + 64;
            boolean selected = VoucherType.PALETTE[i] == edit.color;
            g.fill(x - 1, y - 1, x + SWATCH + 1, y + SWATCH + 1, selected ? 0xFFFFFFFF : 0xFF15171B);
            g.fill(x, y, x + SWATCH, y + SWATCH, 0xFF000000 | VoucherType.PALETTE[i]);
        }
        g.drawString(font, Component.translatable("gui.lottery.terminal.state"), rx, top + 87, C_TEXT, false);
        g.fill(rx, top + 108, left + W - 8, top + 109, C_BORDER);
        g.drawString(font, Component.translatable("gui.lottery.terminal.count"), rx, top + 125, C_TEXT, false);

        int y = top + 146;
        for (String key : new String[]{"gui.lottery.vouchers.help1", "gui.lottery.vouchers.help2", "gui.lottery.vouchers.help3"}) {
            for (var line : font.split(Component.translatable(key), left + W - 8 - rx)) {
                g.drawString(font, line, rx, y, C_DIM, false);
                y += 10;
            }
        }
        g.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(status, W - LIST_W - 24)), rx, top + H - 14, statusColor, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
