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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

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
    /** Pity counters: player -> type id -> draws in a row without a prize of the type's pity tier. */
    private final Map<UUID, Map<Integer, Integer>> pity = new HashMap<>();
    private int nextPoolId = 1;
    private final TreeMap<Integer, PrizePool> prizePools = new TreeMap<>();
    private int nextVoucherId = 1;
    private final TreeMap<Integer, VoucherType> vouchers = new TreeMap<>();
    /** Paid-for but not yet collected gacha pulls, one per player. */
    private final Map<UUID, com.chatmiao.lottery.logic.HubSession> sessions = new HashMap<>();
    /** Gacha history per player, newest first, at most {@link #HISTORY_LIMIT} entries. */
    private final Map<UUID, java.util.ArrayDeque<PullRecord>> history = new HashMap<>();
    public static final int HISTORY_LIMIT = 100;
    private int nextHubRoundId = 1;
    /** Item ids each player has ever pulled from a gacha pool (for the "NEW" badge). */
    private final Map<UUID, java.util.Set<String>> collection = new HashMap<>();

    /** Records that {@code player} got {@code itemKey}. @return true if this is the first time */
    public boolean markObtained(UUID player, String itemKey) {
        boolean fresh = collection.computeIfAbsent(player, k -> new java.util.HashSet<>()).add(itemKey);
        if (fresh) setDirty();
        return fresh;
    }

    // ---------------- vouchers ----------------

    public Collection<VoucherType> vouchers() {
        return vouchers.values();
    }

    public VoucherType voucher(int id) {
        return vouchers.get(id);
    }

    /** Create (id <= 0) or update a voucher type. Statistics are never taken from the input. */
    public VoucherType saveVoucher(VoucherType input) {
        input.sanitize();
        VoucherType stored = input.copy();
        VoucherType old = input.id > 0 ? vouchers.get(input.id) : null;
        if (old == null) {
            stored.id = nextVoucherId++;
            stored.issued = stored.spent = 0;
        } else {
            stored.issued = old.issued;
            stored.spent = old.spent;
        }
        vouchers.put(stored.id, stored);
        VoucherNames.replaceAll(voucherInfo());
        setDirty();
        return stored;
    }

    public Map<Integer, VoucherNames.Info> voucherInfo() {
        Map<Integer, VoucherNames.Info> map = new HashMap<>();
        for (VoucherType v : vouchers.values()) map.put(v.id, new VoucherNames.Info(v.name, v.color));
        return map;
    }

    // ---------------- gacha sessions / history ----------------

    public com.chatmiao.lottery.logic.HubSession session(UUID player) {
        return sessions.get(player);
    }

    public void setSession(UUID player, com.chatmiao.lottery.logic.HubSession session) {
        if (session == null) sessions.remove(player);
        else sessions.put(player, session);
        setDirty();
    }

    public int nextHubRoundId() {
        setDirty();
        return nextHubRoundId++;
    }

    public void addHistory(UUID player, PullRecord record) {
        java.util.ArrayDeque<PullRecord> h = history.computeIfAbsent(player, k -> new java.util.ArrayDeque<>());
        h.addFirst(record);
        while (h.size() > HISTORY_LIMIT) h.removeLast();
        setDirty();
    }

    public List<PullRecord> history(UUID player) {
        java.util.ArrayDeque<PullRecord> h = history.get(player);
        return h == null ? List.of() : new java.util.ArrayList<>(h);
    }

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
        adoptInline(stored);
        relink(stored);
        types.put(stored.id, stored);
        TicketNames.put(stored.id, stored.name);
        setDirty();
        return stored;
    }

    // ---------------- shared prize pools (奖池) ----------------

    public Collection<PrizePool> prizePools() {
        return prizePools.values();
    }

    public PrizePool prizePool(int id) {
        return prizePools.get(id);
    }

    /** Create (id <= 0) or update a shared pool; every type using it sees the change right away. @return null if full */
    public PrizePool savePrizePool(PrizePool input) {
        input.sanitize();
        PrizePool stored = input.copy();
        if (input.id <= 0 || !prizePools.containsKey(input.id)) {
            if (prizePools.size() >= PrizePool.MAX_POOLS) return null;
            stored.id = nextPoolId++;
        }
        prizePools.put(stored.id, stored);
        relinkAll();
        setDirty();
        return stored;
    }

    /** Types (by id) whose slots reference the pool. */
    public List<TicketType> usersOf(int poolId) {
        List<TicketType> out = new java.util.ArrayList<>();
        for (TicketType t : types.values()) {
            for (TicketType.Pool p : t.pools) {
                if (p.poolId == poolId) {
                    out.add(t);
                    break;
                }
            }
        }
        return out;
    }

    /** Deletes an unused pool. @return false if it doesn't exist or some type still uses it */
    public boolean deletePrizePool(int id) {
        if (!prizePools.containsKey(id) || !usersOf(id).isEmpty()) return false;
        prizePools.remove(id);
        setDirty();
        return true;
    }

    /** Pool id -> "#3 名称" of every type using it (for the pool screen). */
    public Map<Integer, List<String>> poolUsage() {
        Map<Integer, List<String>> map = new HashMap<>();
        for (TicketType t : types.values()) {
            for (TicketType.Pool p : t.pools) {
                if (p.poolId <= 0) continue;
                List<String> list = map.computeIfAbsent(p.poolId, k -> new java.util.ArrayList<>());
                String label = "#" + t.id + " " + t.name;
                if (!list.contains(label)) list.add(label);
            }
        }
        return map;
    }

    /**
     * Turns self-contained slots (poolId 0 with items: saves from before shared pools, or code that builds a type
     * by hand) into shared pools named "类型名·池名". Empty self-contained slots are dropped.
     */
    private boolean adoptInline(TicketType t) {
        boolean changed = false;
        for (java.util.Iterator<TicketType.Pool> it = t.pools.iterator(); it.hasNext(); ) {
            TicketType.Pool slot = it.next();
            if (slot.poolId > 0) continue;
            changed = true;
            if (slot.items.isEmpty()) {
                it.remove();
                continue;
            }
            PrizePool pool = new PrizePool();
            pool.id = nextPoolId++;
            String base = slot.name == null || slot.name.isBlank() ? "奖池" : slot.name.strip();
            pool.name = t.name == null || t.name.isBlank() ? base : t.name.strip() + "·" + base;
            pool.tier = slot.tier;
            for (TicketType.PoolItem item : slot.items) pool.items.add(item.copy());
            pool.sanitize();
            prizePools.put(pool.id, pool);
            slot.poolId = pool.id;
        }
        return changed;
    }

    private void relink(TicketType t) {
        for (TicketType.Pool slot : t.pools) {
            if (slot.poolId > 0) slot.link(prizePools.get(slot.poolId));
        }
    }

    private void relinkAll() {
        for (TicketType t : types.values()) relink(t);
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

    // ---------------- pity ----------------

    public int pity(UUID player, int typeId) {
        Map<Integer, Integer> m = pity.get(player);
        return m == null ? 0 : m.getOrDefault(typeId, 0);
    }

    public void setPity(UUID player, int typeId, int value) {
        if (value <= 0) {
            Map<Integer, Integer> m = pity.get(player);
            if (m != null && m.remove(typeId) != null) setDirty();
            return;
        }
        pity.computeIfAbsent(player, k -> new HashMap<>()).put(typeId, value);
        setDirty();
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
        data.nextPoolId = Math.max(1, tag.getInt("nextPoolId"));
        for (Tag t : tag.getList("prizePools", Tag.TAG_COMPOUND)) {
            PrizePool p = PrizePool.load((CompoundTag) t, provider);
            data.prizePools.put(p.id, p);
            data.nextPoolId = Math.max(data.nextPoolId, p.id + 1);
        }
        boolean migrated = false;
        for (TicketType type : data.types.values()) migrated |= data.adoptInline(type);
        data.relinkAll();
        if (migrated) {
            com.chatmiao.lottery.LotteryMod.LOGGER.info("Moved per-type prize pools into shared pools ({} pools)", data.prizePools.size());
            data.setDirty();
        }
        for (Tag t : tag.getList("wins", Tag.TAG_COMPOUND)) {
            WinRecord r = WinRecord.load((CompoundTag) t, provider);
            data.wins.put(r.winId, r);
        }
        for (Tag t : tag.getList("pity", Tag.TAG_COMPOUND)) {
            CompoundTag p = (CompoundTag) t;
            if (!p.hasUUID("player")) continue;
            int n = p.getInt("count");
            if (n > 0) data.pity.computeIfAbsent(p.getUUID("player"), k -> new HashMap<>()).put(p.getInt("type"), n);
        }
        data.nextVoucherId = Math.max(1, tag.getInt("nextVoucherId"));
        for (Tag t : tag.getList("vouchers", Tag.TAG_COMPOUND)) {
            VoucherType v = VoucherType.load((CompoundTag) t);
            data.vouchers.put(v.id, v);
            data.nextVoucherId = Math.max(data.nextVoucherId, v.id + 1);
        }
        data.nextHubRoundId = Math.max(1, tag.getInt("nextHubRoundId"));
        for (Tag t : tag.getList("sessions", Tag.TAG_COMPOUND)) {
            CompoundTag s = (CompoundTag) t;
            if (!s.hasUUID("player")) continue;
            try {
                data.sessions.put(s.getUUID("player"), com.chatmiao.lottery.logic.HubSession.load(s.getCompound("session"), provider));
            } catch (RuntimeException e) {
                com.chatmiao.lottery.LotteryMod.LOGGER.error("Dropping unreadable gacha session of {}", s.getUUID("player"), e);
            }
        }
        for (Tag t : tag.getList("history", Tag.TAG_COMPOUND)) {
            CompoundTag h = (CompoundTag) t;
            if (!h.hasUUID("player")) continue;
            java.util.ArrayDeque<PullRecord> list = new java.util.ArrayDeque<>();
            for (Tag r : h.getList("records", Tag.TAG_COMPOUND)) list.addLast(PullRecord.load((CompoundTag) r, provider));
            data.history.put(h.getUUID("player"), list);
        }
        for (Tag t : tag.getList("collection", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            if (!c.hasUUID("player")) continue;
            java.util.Set<String> set = new java.util.HashSet<>();
            for (Tag s : c.getList("items", Tag.TAG_STRING)) set.add(s.getAsString());
            data.collection.put(c.getUUID("player"), set);
        }
        TicketNames.replaceAll(data.typeNames());
        VoucherNames.replaceAll(data.voucherInfo());
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt("nextTypeId", nextTypeId);
        ListTag typeList = new ListTag();
        for (TicketType t : types.values()) typeList.add(t.save(provider));
        tag.put("types", typeList);
        tag.putInt("nextPoolId", nextPoolId);
        ListTag poolList = new ListTag();
        for (PrizePool p : prizePools.values()) poolList.add(p.save(provider));
        tag.put("prizePools", poolList);
        ListTag winList = new ListTag();
        for (WinRecord r : wins.values()) winList.add(r.save(provider));
        tag.put("wins", winList);
        ListTag pityList = new ListTag();
        for (Map.Entry<UUID, Map<Integer, Integer>> e : pity.entrySet()) {
            for (Map.Entry<Integer, Integer> c : e.getValue().entrySet()) {
                CompoundTag p = new CompoundTag();
                p.putUUID("player", e.getKey());
                p.putInt("type", c.getKey());
                p.putInt("count", c.getValue());
                pityList.add(p);
            }
        }
        tag.put("pity", pityList);
        tag.putInt("nextVoucherId", nextVoucherId);
        ListTag voucherList = new ListTag();
        for (VoucherType v : vouchers.values()) voucherList.add(v.save());
        tag.put("vouchers", voucherList);
        tag.putInt("nextHubRoundId", nextHubRoundId);
        ListTag sessionList = new ListTag();
        for (Map.Entry<UUID, com.chatmiao.lottery.logic.HubSession> e : sessions.entrySet()) {
            CompoundTag s = new CompoundTag();
            s.putUUID("player", e.getKey());
            s.put("session", e.getValue().save(provider));
            sessionList.add(s);
        }
        tag.put("sessions", sessionList);
        ListTag historyList = new ListTag();
        for (Map.Entry<UUID, java.util.ArrayDeque<PullRecord>> e : history.entrySet()) {
            CompoundTag h = new CompoundTag();
            h.putUUID("player", e.getKey());
            ListTag records = new ListTag();
            for (PullRecord r : e.getValue()) records.add(r.save(provider));
            h.put("records", records);
            historyList.add(h);
        }
        tag.put("history", historyList);
        ListTag collectionList = new ListTag();
        for (Map.Entry<UUID, java.util.Set<String>> e : collection.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("player", e.getKey());
            ListTag items = new ListTag();
            for (String s : e.getValue()) items.add(net.minecraft.nbt.StringTag.valueOf(s));
            c.put("items", items);
            collectionList.add(c);
        }
        tag.put("collection", collectionList);
        return tag;
    }
}
