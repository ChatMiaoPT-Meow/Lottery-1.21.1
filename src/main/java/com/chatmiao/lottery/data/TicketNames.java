package com.chatmiao.lottery.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Type number -> display name, used for ticket item names/tooltips.
 * Filled from {@link LotteryData} on the server and from a sync packet on clients.
 */
public final class TicketNames {
    private static final Map<Integer, String> NAMES = new ConcurrentHashMap<>();

    public static String get(int id) {
        return NAMES.get(id);
    }

    public static void put(int id, String name) {
        NAMES.put(id, name);
    }

    public static void replaceAll(Map<Integer, String> names) {
        NAMES.clear();
        NAMES.putAll(names);
    }

    public static void clear() {
        NAMES.clear();
    }

    private TicketNames() {}
}
