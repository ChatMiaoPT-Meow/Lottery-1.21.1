package com.chatmiao.lottery.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Voucher id -> display name and colour, for voucher item names, tint and the gacha screen.
 * Filled from {@link LotteryData} on the server and from a sync packet on clients.
 */
public final class VoucherNames {
    public record Info(String name, int color) {}

    private static final Map<Integer, Info> INFO = new ConcurrentHashMap<>();

    public static Info get(int id) {
        return INFO.get(id);
    }

    public static String name(int id) {
        Info i = INFO.get(id);
        return i == null ? "?" : i.name();
    }

    public static int color(int id) {
        Info i = INFO.get(id);
        return i == null ? 0x9A9A9A : i.color();
    }

    public static void replaceAll(Map<Integer, Info> info) {
        INFO.clear();
        INFO.putAll(info);
    }

    public static void clear() {
        INFO.clear();
    }

    private VoucherNames() {}
}
