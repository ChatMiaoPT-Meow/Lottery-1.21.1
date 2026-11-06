package com.chatmiao.lottery.logic;

import com.chatmiao.lottery.LotteryMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Operations log, one file per day in {@code <world>/lottery/logs/}.
 * Categories: ISSUE (terminal generated tickets), CONFIG, ROLL, WIN, REDEEM, WARN.
 */
public final class LotteryLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long WARN_INTERVAL_MS = 2000;
    private static final Map<UUID, Long> LAST_WARN = new HashMap<>();

    public static void log(MinecraftServer server, String category, String message) {
        String line = "[" + LocalDateTime.now().format(TIME) + "] [" + category + "] " + message;
        LotteryMod.LOGGER.info("[lottery] [{}] {}", category, message);
        try {
            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("lottery").resolve("logs");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("lottery-" + LocalDate.now() + ".log"), line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LotteryMod.LOGGER.error("Failed to write lottery log", e);
        }
    }

    /** Warning about suspicious activity. Rate-limited per player so it can't be used to flood the log. */
    public static void warn(ServerPlayer player, String message) {
        long now = System.currentTimeMillis();
        Long last = LAST_WARN.get(player.getUUID());
        if (last != null && now - last < WARN_INTERVAL_MS) return;
        LAST_WARN.put(player.getUUID(), now);
        log(player.server, "WARN", who(player) + " " + message);
    }

    public static String who(ServerPlayer player) {
        return player.getGameProfile().getName() + "(" + player.getUUID() + ")";
    }

    /** "minecraft:diamond x5, minecraft:iron_ingot x12" */
    public static String prizes(java.util.List<com.chatmiao.lottery.data.Prize> prizes) {
        StringBuilder sb = new StringBuilder();
        for (com.chatmiao.lottery.data.Prize p : prizes) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(p.item().getItem()).append(" x").append(p.count());
        }
        return sb.toString();
    }

    private LotteryLog() {}
}
