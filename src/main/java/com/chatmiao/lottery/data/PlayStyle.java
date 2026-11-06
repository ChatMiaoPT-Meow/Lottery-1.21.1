package com.chatmiao.lottery.data;

import net.minecraft.network.chat.Component;

/** How a ticket is presented. The draw itself (pools, weights, pity) is the same for every style. */
public enum PlayStyle {
    /** 对号刮奖: winning numbers + a cols×rows grid of my numbers under a scratch coating. */
    MATCH(0, 10),
    /** 开箱转盘: one vertical reel per draw (side by side) that spins and stops on the prize. */
    CASE(1, 10),
    /** 卡牌抽卡: one face-down card per draw (e.g. a ten-pull). */
    CARDS(2, 10);

    public final int id;
    public final int maxDraws;

    PlayStyle(int id, int maxDraws) {
        this.id = id;
        this.maxDraws = maxDraws;
    }

    public static PlayStyle byId(int id) {
        for (PlayStyle s : values()) if (s.id == id) return s;
        return MATCH;
    }

    public PlayStyle next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public Component label() {
        return Component.translatable("style.lottery." + name().toLowerCase(java.util.Locale.ROOT));
    }
}
