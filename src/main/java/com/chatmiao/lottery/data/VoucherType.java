package com.chatmiao.lottery.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * A kind of gacha voucher (卡券), e.g. 普通券 / 限定券. Vouchers are plain stackable items carrying this id;
 * gacha pools say which voucher they take and how many per pull. Admins create them in the terminal and can
 * sell them anywhere items can be sold (e.g. the Module Shop mall).
 */
public final class VoucherType {
    public static final int MAX_NAME = 24;
    /** Default colours offered by the terminal (same palette as ticket colours). */
    public static final int[] PALETTE = {
            0xE8463C, 0x2F8FE0, 0x3DBB5A, 0xF0A020, 0x9B59D0, 0x1FB5AD,
            0xE0507F, 0x7A8C2E, 0xD9663A, 0x4C6CD9, 0xC9A227, 0x5C5C70
    };

    public int id;
    public String name = "";
    public int color = PALETTE[3];
    public boolean enabled = true;
    // statistics, server only
    public long issued;
    public long spent;

    public VoucherType copy() {
        VoucherType v = new VoucherType();
        v.id = id;
        v.name = name;
        v.color = color;
        v.enabled = enabled;
        v.issued = issued;
        v.spent = spent;
        return v;
    }

    public void sanitize() {
        name = name == null ? "" : name.strip();
        if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
        if (name.isEmpty()) name = "卡券";
        color &= 0xFFFFFF;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeUtf(name, 128);
        buf.writeInt(color);
        buf.writeBoolean(enabled);
        buf.writeVarLong(issued);
        buf.writeVarLong(spent);
    }

    public static VoucherType read(RegistryFriendlyByteBuf buf) {
        VoucherType v = new VoucherType();
        v.id = buf.readVarInt();
        v.name = buf.readUtf(128);
        v.color = buf.readInt();
        v.enabled = buf.readBoolean();
        v.issued = buf.readVarLong();
        v.spent = buf.readVarLong();
        return v;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("name", name);
        tag.putInt("color", color);
        tag.putBoolean("enabled", enabled);
        tag.putLong("issued", issued);
        tag.putLong("spent", spent);
        return tag;
    }

    public static VoucherType load(CompoundTag tag) {
        VoucherType v = new VoucherType();
        v.id = tag.getInt("id");
        v.name = tag.getString("name");
        v.color = tag.getInt("color");
        v.enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
        v.issued = tag.getLong("issued");
        v.spent = tag.getLong("spent");
        return v;
    }
}
