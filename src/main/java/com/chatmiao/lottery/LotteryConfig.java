package com.chatmiao.lottery;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-side config (world/serverconfig/lottery-server.toml). */
public final class LotteryConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue OP_LEVEL;
    public static final ModConfigSpec.IntValue MAX_GENERATE;
    public static final ModConfigSpec.IntValue NUMBER_RANGE;
    public static final ModConfigSpec.IntValue USE_DISTANCE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        OP_LEVEL = b.comment("Permission level needed to use the Lottery Terminal and to place/break the Scratch Table and Lottery Station.")
                .defineInRange("opPermissionLevel", 2, 0, 4);
        MAX_GENERATE = b.comment("Max tickets the terminal can generate in one click.")
                .defineInRange("maxGeneratePerClick", 1000, 1, 100000);
        NUMBER_RANGE = b.comment("Default number range (1..numberRange) for number-match tickets that don't set their own.",
                        "It is raised automatically when a ticket needs more numbers (winning numbers + grid cells).")
                .defineInRange("numberRange", 30, 12, 99);
        USE_DISTANCE = b.comment("Max distance (blocks) between a player and a table/station for its GUI actions to be accepted.")
                .defineInRange("useDistance", 8, 2, 64);
        SPEC = b.build();
    }

    public static int opLevel() {
        return SPEC.isLoaded() ? OP_LEVEL.get() : 2;
    }

    public static int maxGenerate() {
        return SPEC.isLoaded() ? MAX_GENERATE.get() : 1000;
    }

    public static int numberRange() {
        return SPEC.isLoaded() ? NUMBER_RANGE.get() : 30;
    }

    public static double useDistanceSqr() {
        double d = SPEC.isLoaded() ? USE_DISTANCE.get() : 8;
        return d * d;
    }

    private LotteryConfig() {}
}
