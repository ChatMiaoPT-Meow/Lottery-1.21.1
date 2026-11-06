package com.chatmiao.lottery.data;

import net.minecraft.network.chat.Component;

/**
 * Prize-pool rarity. Purely a label + colour for the GUIs, plus the target of pity rules
 * ("guarantee a pool of tier >= X"). Tier -1 means "谢谢参与" (no prize).
 */
public final class Tier {
    public static final int COUNT = 6;
    public static final int NONE = -1;
    private static final int[] COLORS = {0xB4B4B4, 0x55C855, 0x4B8BFF, 0xB05CFF, 0xFFB000, 0xFF4848};

    public static int clamp(int tier) {
        return Math.max(0, Math.min(COUNT - 1, tier));
    }

    /** RGB without alpha. */
    public static int color(int tier) {
        return tier < 0 ? 0x707070 : COLORS[clamp(tier)];
    }

    public static Component name(int tier) {
        return Component.translatable(tier < 0 ? "tier.lottery.none" : "tier.lottery." + clamp(tier)).withColor(color(tier));
    }

    private Tier() {}
}
