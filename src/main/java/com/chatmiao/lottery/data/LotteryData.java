package com.chatmiao.lottery.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The server ledger, saved in {@code <world>/data/lottery.dat}:
 * lottery types (编号) with their prize config and stats, and every winning ticket ever issued.
 * <p>
 * This is the only thing the three modules share: the terminal writes types, the scratch table
 * reads types and writes win records, the lottery station reads and closes win records.
 */
public final class LotteryData extends SavedData {
    private static final String FILE = "lottery";

    private int nextTypeId = 1;
    private final TreeMap<Integer, TicketType> types = new TreeMap<>();
    private final Map<String, WinRecord> wins = new HashMap<>();

    public static LotteryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(LotteryData::new, LotteryData::load, null), FILE);
    }

    // ---------------- types ----------------

    public Collection<TicketType> types() {
        return types.values();
    }

    public TicketType type(int id) {
        return types.get(id);
    }

    /**
     * Create (id <= 0) or update a type from terminal input. Statistics are never taken from the input.
     * @return the stored type
     */
    public TicketType saveType(TicketType input) {
        input.sanitize();
        TicketType stored;
        if (input.id <= 0 || !types.containsKey(input.id)) {
            stored = input.copy();
            stored.id = nextTypeId++;
            stored.issued = stored.scratched = stored.wins = 0;
        } else {
            TicketType old = types.get(input.id);
            stored = input.copy();
            stored.issued = old.issued;
            stored.scratched = old.scratched;
            stored.wins = old.wins;
        }
        types.put(stored.id, stored);
        TicketNames.put(stored.id, stored.name);
        setDirty();
        return stored;
    }

    public Map<Integer, String> typeNames() {
        Map<Integer, String> map = new HashMap<>();
        for (TicketType t : types.values()) map.put(t.id, t.name);
        return map;
    }

    // ---------------- wins ----------------

    public WinRecord win(String winId) {
        return wins.get(winId);
    }

    public void addWin(WinRecord record) {
        wins.put(record.winId, record);
        setDirty();
    }

    /** A fresh, never-used win id like {@code W7-K3F9Q2XA}. */
    public String newWinId(int typeId, RandomSource random) {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        while (true) {
            StringBuilder sb = new StringBuilder("W").append(typeId).append('-');
            for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String id = sb.toString().toUpperCase(Locale.ROOT);
            if (!wins.containsKey(id)) return id;
        }
    }

    // ---------------- persistence ----------------

    private static LotteryData load(CompoundTag tag, HolderLookup.Provider provider) {
        LotteryData data = new LotteryData();
        data.nextTypeId = Math.max(1, tag.getInt("nextTypeId"));
        for (Tag t : tag.getList("types", Tag.TAG_COMPOUND)) {
            TicketType type = TicketType.load((CompoundTag) t, provider);
            data.types.put(type.id, type);
            data.nextTypeId = Math.max(data.nextTypeId, type.id + 1);
        }
        for (Tag t : tag.getList("wins", Tag.TAG_COMPOUND)) {
            WinRecord r = WinRecord.load((CompoundTag) t, provider);
            data.wins.put(r.winId, r);
        }
        TicketNames.replaceAll(data.typeNames());
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt("nextTypeId", nextTypeId);
        ListTag typeList = new ListTag();
        for (TicketType t : types.values()) typeList.add(t.save(provider));
        tag.put("types", typeList);
        ListTag winList = new ListTag();
        for (WinRecord r : wins.values()) winList.add(r.save(provider));
        tag.put("wins", winList);
        return tag;
    }
}
