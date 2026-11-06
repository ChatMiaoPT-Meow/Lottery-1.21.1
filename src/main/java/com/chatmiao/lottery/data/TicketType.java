package com.chatmiao.lottery.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One kind of lottery ticket ("编号 #id"). Every ticket carrying this id is identical and stacks.
 * <p>
 * Probability model (two-level normalisation), applied once per draw:
 * <ul>
 *   <li>Ticket level: {@link #emptyWeight} competes with each pool's {@link Pool#weight}.</li>
 *   <li>Pool level: each {@link PoolItem#weight} competes with the other items in the same pool,
 *       then the amount is uniform in [{@link PoolItem#min}, {@link PoolItem#max}].</li>
 * </ul>
 * Pools with weight 0 or without any usable item are ignored (they never steal probability).
 * <p>
 * A ticket performs {@link #draws} draws. How they are shown is decided by {@link #style}; optional pity
 * rules can force a draw into a pool of a minimum {@link Pool#tier}.
 */
public final class TicketType {
    public static final int MAX_NAME = 32;
    public static final int MAX_POOLS = 16;
    public static final int MAX_ITEMS = 45;
    public static final int MAX_WEIGHT = 1_000_000;
    public static final int MAX_AMOUNT = 64 * 36;
    public static final int MAX_COLS = 10, MAX_ROWS = 5, MAX_WIN_NUMBERS = 5, MAX_RANGE = 99;
    public static final int MIN_SPIN = 2, MAX_SPIN = 15, MAX_PITY = 1000;

    public int id;
    public String name = "";
    public int emptyWeight = 65;
    public boolean enabled = true;
    public final List<Pool> pools = new ArrayList<>();

    // ---- play style (all optional in old saves; defaults reproduce the original 3×3 ticket)
    public int style = PlayStyle.MATCH.id;
    public int draws = 1;
    /** MATCH: my-number grid. */
    public int cols = 3, rows = 3;
    /** MATCH: how many winning numbers are printed. */
    public int winCount = 3;
    /** MATCH: numbers come from 1..range; 0 = server default (lottery-server.toml). */
    public int numberRange = 0;
    /** CASE: how long the reel spins. */
    public int spinSeconds = 5;
    /** CARDS: cards per row. */
    public int cardCols = 5;
    /** CARDS / CASE: rare prizes (tier >= 3) get their own build-up and reveal effects. */
    public boolean rareEffect = true;

    // ---- pity
    /** Every {@code pityCount}-th draw without a tier >= {@link #pityTier} prize is forced into one. 0 = off. */
    public int pityCount = 0;
    public int pityTier = 4;
    /** Multi-draw tickets contain at least one prize of tier >= this. -1 = off. */
    public int guaranteeTier = -1;

    // ---- gacha pool (卡池): pulled with vouchers from the gacha screen instead of physical tickets
    public static final int DELIVER_DIRECT = 0, DELIVER_TICKET = 1;
    public static final int MAX_COST = 10_000;
    /** Shown in the gacha screen (when its schedule allows). */
    public boolean inHub;
    /** Voucher type a pull costs; 0 = none chosen (pool can't be pulled). */
    public int voucherId;
    /** Vouchers for a single pull (one draw). */
    public int costSingle = 1;
    /** Draws of a multi pull (0 = no multi pull) and its voucher cost. */
    public int multiCount = 10;
    public int costMulti = 10;
    /** Open window in epoch millis; 0 = unbounded. */
    public long startTime, endTime;
    /** Weekdays the pool is open, bit 0 = Monday … bit 6 = Sunday; 0 = every day. */
    public int weekMask;
    /** {@link #DELIVER_DIRECT}: prizes go straight to the inventory; {@link #DELIVER_TICKET}: a winning ticket. */
    public int delivery = DELIVER_DIRECT;

    // statistics, maintained by the server only
    public int issued;
    public int scratched;
    public int wins;

    public enum HubStatus { OPEN, NOT_LISTED, DISABLED, NO_VOUCHER, NOT_STARTED, ENDED, CLOSED_TODAY }

    /**
     * Whether the pool can be pulled right now.
     *
     * @param voucherOk the chosen voucher type exists and is enabled
     */
    public HubStatus hubStatus(long nowMillis, java.time.ZoneId zone, boolean voucherOk) {
        if (!inHub) return HubStatus.NOT_LISTED;
        if (!enabled) return HubStatus.DISABLED;
        if (voucherId <= 0 || !voucherOk) return HubStatus.NO_VOUCHER;
        if (startTime > 0 && nowMillis < startTime) return HubStatus.NOT_STARTED;
        if (endTime > 0 && nowMillis >= endTime) return HubStatus.ENDED;
        if (weekMask != 0) {
            int dow = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).getDayOfWeek().getValue() - 1; // Monday = 0
            if ((weekMask & (1 << dow)) == 0) return HubStatus.CLOSED_TODAY;
        }
        return HubStatus.OPEN;
    }

    /** Draws a multi pull really gets (capped by the style), or 0 when multi pulls are off. */
    public int effectiveMulti() {
        if (multiCount <= 1 || costMulti <= 0) return 0;
        PlayStyle s = playStyle();
        int max = s == PlayStyle.MATCH ? Math.min(s.maxDraws, cells()) : s.maxDraws;
        return Math.min(multiCount, max);
    }

    /**
     * One prize pool used by this type: a reference to a shared {@link PrizePool} ({@link #poolId}) plus this type's
     * weight and rarity ({@link #tier}) for it. {@link #name}/{@link #items} are a snapshot of the referenced pool, refreshed by
     * {@link LotteryData} whenever pools change (and sent to the terminal for previews). A slot with poolId 0 carries
     * its own items (saves from before shared pools, tests); the server turns those into shared pools on save/load.
     */
    public static final class Pool {
        public int poolId;
        public String name = "";
        public int weight;
        public int tier;
        public final List<PoolItem> items = new ArrayList<>();

        /** Refresh the snapshot from the referenced pool ({@code null} = the pool was deleted). */
        public void link(PrizePool src) {
            items.clear();
            if (src == null) {
                name = "?";
                return;
            }
            name = src.name;
            for (PoolItem it : src.items) items.add(it.copy());
        }

        public boolean usable() {
            return weight > 0 && totalItemWeight() > 0;
        }

        public long totalItemWeight() {
            long sum = 0;
            for (PoolItem it : items) {
                if (it.usable()) sum += it.weight;
            }
            return sum;
        }

        public Pool copy() {
            Pool p = new Pool();
            p.poolId = poolId;
            p.name = name;
            p.weight = weight;
            p.tier = tier;
            for (PoolItem it : items) p.items.add(it.copy());
            return p;
        }
    }

    public static final class PoolItem {
        public ItemStack item = ItemStack.EMPTY;
        public int weight = 10;
        public int min = 1;
        public int max = 1;

        public boolean usable() {
            return weight > 0 && !item.isEmpty();
        }

        void sanitize() {
            item = item.copyWithCount(1);
            weight = clamp(weight, 0, MAX_WEIGHT);
            min = clamp(min, 1, MAX_AMOUNT);
            max = clamp(max, min, MAX_AMOUNT);
        }

        public PoolItem copy() {
            PoolItem p = new PoolItem();
            p.item = item.copy();
            p.weight = weight;
            p.min = min;
            p.max = max;
            return p;
        }
    }

    public PlayStyle playStyle() {
        return PlayStyle.byId(style);
    }

    public int cells() {
        return cols * rows;
    }

    /** Most draws the current style/layout allows (MATCH can't hit more cells than it has). */
    public int maxDraws() {
        PlayStyle s = playStyle();
        return s == PlayStyle.MATCH ? Math.min(s.maxDraws, cells()) : s.maxDraws;
    }

    /** Numbers needed so winning numbers and my numbers can all be distinct. */
    public int neededRange() {
        return winCount + cells();
    }

    public int effectiveRange(int serverDefault) {
        int r = numberRange > 0 ? numberRange : serverDefault;
        return Math.max(neededRange(), Math.min(MAX_RANGE, r));
    }

    /** True if some usable pool has tier >= {@code tier}, i.e. a pity rule targeting it can be honoured. */
    public boolean hasTierAtLeast(int tier) {
        for (Pool p : pools) if (p.usable() && p.tier >= tier) return true;
        return false;
    }

    /** Sum of the ticket-level weights that actually take part in a roll. */
    public long totalWeight() {
        long sum = Math.max(0, emptyWeight);
        for (Pool p : pools) {
            if (p.usable()) sum += p.weight;
        }
        return sum;
    }

    /** Chance (0..1) that one draw wins anything (ignoring pity). */
    public double winChance() {
        long total = totalWeight();
        return total <= 0 ? 0 : (double) (total - Math.max(0, emptyWeight)) / total;
    }

    /** Chance (0..1) that a whole ticket wins at least one prize (ignoring pity). */
    public double ticketWinChance() {
        double p = winChance();
        if (guaranteeTier >= 0 && draws > 1 && hasTierAtLeast(guaranteeTier)) return 1;
        return 1 - Math.pow(1 - p, Math.max(1, draws));
    }

    public TicketType copy() {
        TicketType t = new TicketType();
        t.id = id;
        t.name = name;
        t.emptyWeight = emptyWeight;
        t.enabled = enabled;
        for (Pool p : pools) t.pools.add(p.copy());
        t.copyStyleFrom(this);
        t.issued = issued;
        t.scratched = scratched;
        t.wins = wins;
        return t;
    }

    private void copyStyleFrom(TicketType o) {
        style = o.style;
        draws = o.draws;
        cols = o.cols;
        rows = o.rows;
        winCount = o.winCount;
        numberRange = o.numberRange;
        spinSeconds = o.spinSeconds;
        cardCols = o.cardCols;
        rareEffect = o.rareEffect;
        pityCount = o.pityCount;
        pityTier = o.pityTier;
        guaranteeTier = o.guaranteeTier;
        inHub = o.inHub;
        voucherId = o.voucherId;
        costSingle = o.costSingle;
        multiCount = o.multiCount;
        costMulti = o.costMulti;
        startTime = o.startTime;
        endTime = o.endTime;
        weekMask = o.weekMask;
        delivery = o.delivery;
    }

    /** Clamp everything a (trusted, but possibly buggy) client sent into sane bounds. */
    public void sanitize() {
        name = name == null ? "" : name.strip();
        if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
        if (name.isEmpty()) name = "彩票";
        emptyWeight = clamp(emptyWeight, 0, MAX_WEIGHT);
        while (pools.size() > MAX_POOLS) pools.remove(pools.size() - 1);
        for (Pool p : pools) {
            p.poolId = Math.max(0, p.poolId);
            p.name = p.name == null ? "" : p.name.strip();
            if (p.name.length() > MAX_NAME) p.name = p.name.substring(0, MAX_NAME);
            p.weight = clamp(p.weight, 0, MAX_WEIGHT);
            p.tier = Tier.clamp(p.tier);
            p.items.removeIf(it -> it.item == null || it.item.isEmpty());
            while (p.items.size() > MAX_ITEMS) p.items.remove(p.items.size() - 1);
            for (PoolItem it : p.items) it.sanitize();
        }
        sanitizeStyleOnly();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------------- network ----------------

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeUtf(name, 256);
        buf.writeVarInt(emptyWeight);
        buf.writeBoolean(enabled);
        buf.writeVarInt(issued);
        buf.writeVarInt(scratched);
        buf.writeVarInt(wins);
        buf.writeVarInt(pools.size());
        for (Pool p : pools) {
            buf.writeVarInt(p.poolId);
            buf.writeUtf(p.name, 256);
            buf.writeVarInt(p.weight);
            buf.writeVarInt(p.tier);
            writeItems(buf, p.items);
        }
        buf.writeVarInt(style);
        buf.writeVarInt(draws);
        buf.writeVarInt(cols);
        buf.writeVarInt(rows);
        buf.writeVarInt(winCount);
        buf.writeVarInt(numberRange);
        buf.writeVarInt(spinSeconds);
        buf.writeVarInt(cardCols);
        buf.writeBoolean(rareEffect);
        buf.writeVarInt(pityCount);
        buf.writeVarInt(pityTier);
        buf.writeVarInt(guaranteeTier + 1);
        writeHub(buf);
    }

    public static TicketType read(RegistryFriendlyByteBuf buf) {
        TicketType t = new TicketType();
        t.id = buf.readVarInt();
        t.name = buf.readUtf(256);
        t.emptyWeight = buf.readVarInt();
        t.enabled = buf.readBoolean();
        t.issued = buf.readVarInt();
        t.scratched = buf.readVarInt();
        t.wins = buf.readVarInt();
        int poolCount = Math.min(buf.readVarInt(), MAX_POOLS * 4);
        for (int i = 0; i < poolCount; i++) {
            Pool p = new Pool();
            p.poolId = buf.readVarInt();
            p.name = buf.readUtf(256);
            p.weight = buf.readVarInt();
            p.tier = buf.readVarInt();
            readItems(buf, p.items);
            t.pools.add(p);
        }
        t.style = buf.readVarInt();
        t.draws = buf.readVarInt();
        t.cols = buf.readVarInt();
        t.rows = buf.readVarInt();
        t.winCount = buf.readVarInt();
        t.numberRange = buf.readVarInt();
        t.spinSeconds = buf.readVarInt();
        t.cardCols = buf.readVarInt();
        t.rareEffect = buf.readBoolean();
        t.pityCount = buf.readVarInt();
        t.pityTier = buf.readVarInt();
        t.guaranteeTier = buf.readVarInt() - 1;
        t.readHub(buf);
        return t;
    }

    // ---------------- NBT ----------------

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("name", name);
        tag.putInt("emptyWeight", emptyWeight);
        tag.putBoolean("enabled", enabled);
        tag.putInt("issued", issued);
        tag.putInt("scratched", scratched);
        tag.putInt("wins", wins);
        ListTag poolList = new ListTag();
        for (Pool p : pools) {
            CompoundTag pt = new CompoundTag();
            pt.putInt("weight", p.weight);
            pt.putInt("tier", p.tier);
            if (p.poolId > 0) {
                pt.putInt("pool", p.poolId);
            } else {
                // a self-contained slot (not yet turned into a shared pool) keeps its contents
                pt.putString("name", p.name);
                pt.put("items", saveItems(p.items, provider));
            }
            poolList.add(pt);
        }
        tag.put("pools", poolList);
        CompoundTag st = new CompoundTag();
        st.putInt("style", style);
        st.putInt("draws", draws);
        st.putInt("cols", cols);
        st.putInt("rows", rows);
        st.putInt("winCount", winCount);
        st.putInt("numberRange", numberRange);
        st.putInt("spinSeconds", spinSeconds);
        st.putInt("cardCols", cardCols);
        st.putBoolean("rareEffect", rareEffect);
        st.putInt("pityCount", pityCount);
        st.putInt("pityTier", pityTier);
        st.putInt("guaranteeTier", guaranteeTier);
        tag.put("play", st);
        tag.put("hub", saveHub());
        return tag;
    }

    public static TicketType load(CompoundTag tag, HolderLookup.Provider provider) {
        TicketType t = new TicketType();
        t.id = tag.getInt("id");
        t.name = tag.getString("name");
        t.emptyWeight = tag.getInt("emptyWeight");
        t.enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
        t.issued = tag.getInt("issued");
        t.scratched = tag.getInt("scratched");
        t.wins = tag.getInt("wins");
        for (Tag pTag : tag.getList("pools", Tag.TAG_COMPOUND)) {
            CompoundTag pt = (CompoundTag) pTag;
            Pool p = new Pool();
            p.poolId = pt.getInt("pool"); // 0 in saves from before shared pools: contents are inline
            p.name = pt.getString("name");
            p.weight = pt.getInt("weight");
            // missing in pre-tier saves -> 0 (普通); missing on a pool reference (0.4.0-beta.1/2) -> -1, filled in from the pool
            p.tier = pt.contains("tier") ? pt.getInt("tier") : p.poolId > 0 ? -1 : 0;
            loadItems(pt.getList("items", Tag.TAG_COMPOUND), provider, p.items);
            t.pools.add(p);
        }
        // types saved before play styles existed have no "play" tag and keep the 3×3 / 1-draw defaults
        if (tag.contains("play", Tag.TAG_COMPOUND)) {
            CompoundTag st = tag.getCompound("play");
            t.style = st.getInt("style");
            t.draws = st.getInt("draws");
            t.cols = st.getInt("cols");
            t.rows = st.getInt("rows");
            t.winCount = st.getInt("winCount");
            t.numberRange = st.getInt("numberRange");
            t.spinSeconds = st.getInt("spinSeconds");
            t.cardCols = st.getInt("cardCols");
            t.rareEffect = st.getBoolean("rareEffect");
            t.pityCount = st.getInt("pityCount");
            t.pityTier = st.getInt("pityTier");
            t.guaranteeTier = st.getInt("guaranteeTier");
        }
        // types saved before gacha pools existed are simply not listed in the gacha screen
        if (tag.contains("hub", Tag.TAG_COMPOUND)) t.loadHub(tag.getCompound("hub"));
        t.sanitizeStyleOnly();
        return t;
    }

    /** Bound the play-style fields without touching name/pools (used on load). */
    private void sanitizeStyleOnly() {
        style = playStyle().id;
        cols = clamp(cols, 1, MAX_COLS);
        rows = clamp(rows, 1, MAX_ROWS);
        winCount = clamp(winCount, 1, MAX_WIN_NUMBERS);
        numberRange = numberRange <= 0 ? 0 : clamp(numberRange, neededRange(), MAX_RANGE);
        spinSeconds = clamp(spinSeconds, MIN_SPIN, MAX_SPIN);
        cardCols = clamp(cardCols, 1, MAX_COLS);
        draws = clamp(draws, 1, maxDraws());
        pityCount = clamp(pityCount, 0, MAX_PITY);
        pityTier = Tier.clamp(pityTier);
        guaranteeTier = guaranteeTier < 0 ? -1 : Tier.clamp(guaranteeTier);
        voucherId = Math.max(0, voucherId);
        costSingle = clamp(costSingle, 0, MAX_COST);
        multiCount = clamp(multiCount, 0, TicketType.MAX_COLS * TicketType.MAX_ROWS);
        costMulti = clamp(costMulti, 0, MAX_COST);
        startTime = Math.max(0, startTime);
        endTime = Math.max(0, endTime);
        weekMask &= 0x7F;
        delivery = delivery == DELIVER_TICKET ? DELIVER_TICKET : DELIVER_DIRECT;
    }

    private void writeHub(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(inHub);
        buf.writeVarInt(voucherId);
        buf.writeVarInt(costSingle);
        buf.writeVarInt(multiCount);
        buf.writeVarInt(costMulti);
        buf.writeVarLong(startTime);
        buf.writeVarLong(endTime);
        buf.writeVarInt(weekMask);
        buf.writeVarInt(delivery);
    }

    private void readHub(RegistryFriendlyByteBuf buf) {
        inHub = buf.readBoolean();
        voucherId = buf.readVarInt();
        costSingle = buf.readVarInt();
        multiCount = buf.readVarInt();
        costMulti = buf.readVarInt();
        startTime = buf.readVarLong();
        endTime = buf.readVarLong();
        weekMask = buf.readVarInt();
        delivery = buf.readVarInt();
    }

    private CompoundTag saveHub() {
        CompoundTag h = new CompoundTag();
        h.putBoolean("inHub", inHub);
        h.putInt("voucher", voucherId);
        h.putInt("costSingle", costSingle);
        h.putInt("multiCount", multiCount);
        h.putInt("costMulti", costMulti);
        h.putLong("start", startTime);
        h.putLong("end", endTime);
        h.putInt("weekMask", weekMask);
        h.putInt("delivery", delivery);
        return h;
    }

    // ---------------- item list helpers (shared with PrizePool) ----------------

    static void writeItems(RegistryFriendlyByteBuf buf, List<PoolItem> items) {
        buf.writeVarInt(items.size());
        for (PoolItem it : items) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, it.item);
            buf.writeVarInt(it.weight);
            buf.writeVarInt(it.min);
            buf.writeVarInt(it.max);
        }
    }

    static void readItems(RegistryFriendlyByteBuf buf, List<PoolItem> out) {
        int n = Math.min(buf.readVarInt(), MAX_ITEMS * 4);
        for (int j = 0; j < n; j++) {
            PoolItem it = new PoolItem();
            it.item = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            it.weight = buf.readVarInt();
            it.min = buf.readVarInt();
            it.max = buf.readVarInt();
            out.add(it);
        }
    }

    static ListTag saveItems(List<PoolItem> items, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (PoolItem it : items) {
            if (it.item.isEmpty()) continue;
            CompoundTag itTag = new CompoundTag();
            itTag.put("item", it.item.save(provider));
            itTag.putInt("weight", it.weight);
            itTag.putInt("min", it.min);
            itTag.putInt("max", it.max);
            list.add(itTag);
        }
        return list;
    }

    static void loadItems(ListTag list, HolderLookup.Provider provider, List<PoolItem> out) {
        for (Tag iTag : list) {
            CompoundTag it = (CompoundTag) iTag;
            PoolItem pi = new PoolItem();
            pi.item = ItemStack.parseOptional(provider, it.getCompound("item"));
            pi.weight = it.getInt("weight");
            pi.min = it.getInt("min");
            pi.max = it.getInt("max");
            // items from a removed mod parse as EMPTY; keep the row out so it can't be rolled
            if (!pi.item.isEmpty()) out.add(pi);
        }
    }

    private void loadHub(CompoundTag h) {
        inHub = h.getBoolean("inHub");
        voucherId = h.getInt("voucher");
        costSingle = h.getInt("costSingle");
        multiCount = h.getInt("multiCount");
        costMulti = h.getInt("costMulti");
        startTime = h.getLong("start");
        endTime = h.getLong("end");
        weekMask = h.getInt("weekMask");
        delivery = h.getInt("delivery");
    }
}
