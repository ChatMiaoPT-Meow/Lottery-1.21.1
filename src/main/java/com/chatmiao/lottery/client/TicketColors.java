package com.chatmiao.lottery.client;

/** Each lottery type gets its own ticket colour so different kinds are easy to tell apart. */
public final class TicketColors {
    private static final int[] PALETTE = {
            0xE8463C, 0x2F8FE0, 0x3DBB5A, 0xF0A020, 0x9B59D0, 0x1FB5AD,
            0xE0507F, 0x7A8C2E, 0xD9663A, 0x4C6CD9, 0xC9A227, 0x5C5C70
    };

    public static int of(int typeId) {
        if (typeId <= 0) return 0x9A9A9A;
        return PALETTE[(typeId - 1) % PALETTE.length];
    }

    /** Darker variant for borders/headers. */
    public static int dark(int typeId) {
        int c = of(typeId);
        int r = (c >> 16 & 0xFF) * 3 / 5, g = (c >> 8 & 0xFF) * 3 / 5, b = (c & 0xFF) * 3 / 5;
        return r << 16 | g << 8 | b;
    }

    private TicketColors() {}
}
