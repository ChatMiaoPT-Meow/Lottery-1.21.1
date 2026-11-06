package com.chatmiao.lottery.network;

import com.chatmiao.lottery.LotteryMod;
import com.chatmiao.lottery.data.Prize;
import com.chatmiao.lottery.data.HubEntry;
import com.chatmiao.lottery.data.PrizePool;
import com.chatmiao.lottery.data.PullRecord;
import com.chatmiao.lottery.data.TicketType;
import com.chatmiao.lottery.data.VoucherNames;
import com.chatmiao.lottery.data.VoucherType;
import com.chatmiao.lottery.logic.ScratchRound;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** All custom packets. S2C = server to client, C2S = client to server. */
public final class Payloads {

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String name) {
        return new CustomPacketPayload.Type<>(LotteryMod.id(name));
    }

    // =============== server -> client ===============

    /** Type number -> name, for ticket item names. Sent on login and whenever a type is saved. */
    public record SyncNames(Map<Integer, String> names) implements CustomPacketPayload {
        public static final Type<SyncNames> TYPE = payloadType("sync_names");
        public static final StreamCodec<ByteBuf, SyncNames> CODEC = StreamCodec.composite(
                ByteBufCodecs.map(HashMap::new, ByteBufCodecs.VAR_INT, ByteBufCodecs.STRING_UTF8), SyncNames::names,
                SyncNames::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Opens (or refreshes) the terminal GUI with the full type list (and voucher types for the pool tab). */
    public record OpenTerminal(List<TicketType> types, List<VoucherType> vouchers, List<PrizePool> prizePools, int selectId,
                               int maxGenerate) implements CustomPacketPayload {
        public static final Type<OpenTerminal> TYPE = payloadType("open_terminal");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenTerminal> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.types.size());
                    for (TicketType t : p.types) t.write(buf);
                    writeVouchers(buf, p.vouchers);
                    writePools(buf, p.prizePools);
                    buf.writeVarInt(p.selectId);
                    buf.writeVarInt(p.maxGenerate);
                },
                buf -> {
                    int n = Math.min(buf.readVarInt(), 4096);
                    List<TicketType> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) list.add(TicketType.read(buf));
                    return new OpenTerminal(list, readVouchers(buf), readPools(buf), buf.readVarInt(), buf.readVarInt());
                });

        /** Older call sites / tests that don't care about vouchers. */
        public OpenTerminal(List<TicketType> types, int selectId, int maxGenerate) {
            this(types, List.of(), List.of(), selectId, maxGenerate);
        }

        public OpenTerminal(List<TicketType> types, List<VoucherType> vouchers, int selectId, int maxGenerate) {
            this(types, vouchers, List.of(), selectId, maxGenerate);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The terminal's start menu: three entries, with how many things each one already has. */
    public record OpenMenu(int vouchers, int prizePools, int types) implements CustomPacketPayload {
        public static final Type<OpenMenu> TYPE = payloadType("open_menu");
        public static final StreamCodec<ByteBuf, OpenMenu> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OpenMenu::vouchers,
                ByteBufCodecs.VAR_INT, OpenMenu::prizePools,
                ByteBufCodecs.VAR_INT, OpenMenu::types,
                OpenMenu::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 奖池设置: every shared pool, and which types use each one ("#3 名称"). */
    public record OpenPrizePools(List<PrizePool> pools, Map<Integer, List<String>> usage, int selectId) implements CustomPacketPayload {
        public static final Type<OpenPrizePools> TYPE = payloadType("open_prize_pools");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenPrizePools> CODEC = StreamCodec.of(
                (buf, p) -> {
                    writePools(buf, p.pools);
                    buf.writeVarInt(p.usage.size());
                    for (Map.Entry<Integer, List<String>> e : p.usage.entrySet()) {
                        buf.writeVarInt(e.getKey());
                        buf.writeVarInt(e.getValue().size());
                        for (String s : e.getValue()) buf.writeUtf(s, 256);
                    }
                    buf.writeVarInt(p.selectId);
                },
                buf -> {
                    List<PrizePool> pools = readPools(buf);
                    int n = Math.min(buf.readVarInt(), 4096);
                    Map<Integer, List<String>> usage = new HashMap<>();
                    for (int i = 0; i < n; i++) {
                        int id = buf.readVarInt();
                        int m = Math.min(buf.readVarInt(), 4096);
                        List<String> list = new ArrayList<>(m);
                        for (int j = 0; j < m; j++) list.add(buf.readUtf(256));
                        usage.put(id, list);
                    }
                    return new OpenPrizePools(pools, usage, buf.readVarInt());
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void writePools(RegistryFriendlyByteBuf buf, List<PrizePool> list) {
        buf.writeVarInt(list.size());
        for (PrizePool p : list) p.write(buf);
    }

    private static List<PrizePool> readPools(RegistryFriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), PrizePool.MAX_POOLS * 4);
        List<PrizePool> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(PrizePool.read(buf));
        return list;
    }

    private static void writeVouchers(RegistryFriendlyByteBuf buf, List<VoucherType> list) {
        buf.writeVarInt(list.size());
        for (VoucherType v : list) v.write(buf);
    }

    private static List<VoucherType> readVouchers(RegistryFriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), 1024);
        List<VoucherType> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(VoucherType.read(buf));
        return list;
    }

    /** Voucher id -> name/colour, for voucher items. Sent on login and whenever a voucher type is saved. */
    public record SyncVouchers(Map<Integer, VoucherNames.Info> info) implements CustomPacketPayload {
        public static final Type<SyncVouchers> TYPE = payloadType("sync_vouchers");
        public static final StreamCodec<ByteBuf, SyncVouchers> CODEC = StreamCodec.composite(
                ByteBufCodecs.map(HashMap::new, ByteBufCodecs.VAR_INT, StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, VoucherNames.Info::name,
                        ByteBufCodecs.INT, VoucherNames.Info::color,
                        VoucherNames.Info::new)), SyncVouchers::info,
                SyncVouchers::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Admin voucher screen. */
    public record OpenVouchers(List<VoucherType> vouchers, int selectId, int maxGenerate) implements CustomPacketPayload {
        public static final Type<OpenVouchers> TYPE = payloadType("open_vouchers");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenVouchers> CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeVouchers(buf, p.vouchers);
                    buf.writeVarInt(p.selectId);
                    buf.writeVarInt(p.maxGenerate);
                },
                buf -> new OpenVouchers(readVouchers(buf), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The gacha screen: open pools and how many vouchers of each type the player carries. */
    public record OpenHub(List<HubEntry> entries, Map<Integer, Integer> balances) implements CustomPacketPayload {
        public static final Type<OpenHub> TYPE = payloadType("open_hub");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenHub> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.entries.size());
                    for (HubEntry e : p.entries) e.write(buf);
                    buf.writeVarInt(p.balances.size());
                    p.balances.forEach((k, v) -> {
                        buf.writeVarInt(k);
                        buf.writeVarInt(v);
                    });
                },
                buf -> {
                    int n = Math.min(buf.readVarInt(), 1024);
                    List<HubEntry> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) list.add(HubEntry.read(buf));
                    int m = Math.min(buf.readVarInt(), 1024);
                    Map<Integer, Integer> bal = new HashMap<>();
                    for (int i = 0; i < m; i++) bal.put(buf.readVarInt(), buf.readVarInt());
                    return new OpenHub(list, bal);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A locked gacha pull to animate. {@code direct}: prizes go to the inventory (else a winning ticket). */
    public record HubState(ScratchRound round, boolean direct, int voucherId, int againCost, boolean canAgain, long newMask)
            implements CustomPacketPayload {
        public static final Type<HubState> TYPE = payloadType("hub_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, HubState> CODEC = StreamCodec.of(
                (buf, p) -> {
                    p.round.write(buf);
                    buf.writeBoolean(p.direct);
                    buf.writeVarInt(p.voucherId);
                    buf.writeVarInt(p.againCost);
                    buf.writeBoolean(p.canAgain);
                    buf.writeLong(p.newMask);
                },
                buf -> new HubState(ScratchRound.read(buf), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                        buf.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record HubHistory(List<PullRecord> records) implements CustomPacketPayload {
        public static final Type<HubHistory> TYPE = payloadType("hub_history");
        public static final StreamCodec<RegistryFriendlyByteBuf, HubHistory> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.records.size());
                    for (PullRecord r : p.records) r.write(buf);
                },
                buf -> {
                    int n = Math.min(buf.readVarInt(), 256);
                    List<PullRecord> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) list.add(PullRecord.read(buf));
                    return new HubHistory(list);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Opens/updates the scratch GUI. {@code round == null} means "nothing to scratch, close". */
    public record ScratchState(BlockPos pos, ScratchRound round, int stock) implements CustomPacketPayload {
        public static final Type<ScratchState> TYPE = payloadType("scratch_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, ScratchState> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBlockPos(p.pos);
                    buf.writeBoolean(p.round != null);
                    if (p.round != null) p.round.write(buf);
                    buf.writeVarInt(p.stock);
                },
                buf -> new ScratchState(buf.readBlockPos(), buf.readBoolean() ? ScratchRound.read(buf) : null, buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RedeemEntry(String winId, String typeName) {
        public static final StreamCodec<ByteBuf, RedeemEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RedeemEntry::winId,
                ByteBufCodecs.STRING_UTF8, RedeemEntry::typeName,
                RedeemEntry::new);
    }

    /** Opens the gift-box GUI for the validated winning tickets. */
    public record OpenRedeem(BlockPos pos, List<RedeemEntry> entries) implements CustomPacketPayload {
        public static final Type<OpenRedeem> TYPE = payloadType("open_redeem");
        public static final StreamCodec<ByteBuf, OpenRedeem> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenRedeem::pos,
                RedeemEntry.CODEC.apply(ByteBufCodecs.list(256)), OpenRedeem::entries,
                OpenRedeem::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** What came out of the gift box. */
    public record RedeemResult(List<Prize> prizes, List<Component> errors) implements CustomPacketPayload {
        public static final Type<RedeemResult> TYPE = payloadType("redeem_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, RedeemResult> CODEC = StreamCodec.composite(
                Prize.STREAM_CODEC.apply(ByteBufCodecs.list(256)), RedeemResult::prizes,
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(256)), RedeemResult::errors,
                RedeemResult::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // =============== client -> server ===============

    public record SaveType(TicketType ticketType) implements CustomPacketPayload {
        public static final Type<SaveType> TYPE = payloadType("save_type");
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveType> CODEC = StreamCodec.of(
                (buf, p) -> p.ticketType.write(buf),
                buf -> new SaveType(TicketType.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Generate(int typeId, int count) implements CustomPacketPayload {
        public static final Type<Generate> TYPE = payloadType("generate");
        public static final StreamCodec<ByteBuf, Generate> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Generate::typeId,
                ByteBufCodecs.VAR_INT, Generate::count,
                Generate::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Reveal(BlockPos pos, int roundId, long mask) implements CustomPacketPayload {
        public static final Type<Reveal> TYPE = payloadType("reveal");
        public static final StreamCodec<ByteBuf, Reveal> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Reveal::pos,
                ByteBufCodecs.VAR_INT, Reveal::roundId,
                ByteBufCodecs.VAR_LONG, Reveal::mask,
                Reveal::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Finish(BlockPos pos, int roundId, boolean next) implements CustomPacketPayload {
        public static final Type<Finish> TYPE = payloadType("finish");
        public static final StreamCodec<ByteBuf, Finish> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Finish::pos,
                ByteBufCodecs.VAR_INT, Finish::roundId,
                ByteBufCodecs.BOOL, Finish::next,
                Finish::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Redeem(BlockPos pos, List<String> winIds) implements CustomPacketPayload {
        public static final Type<Redeem> TYPE = payloadType("redeem");
        public static final StreamCodec<ByteBuf, Redeem> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Redeem::pos,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(256)), Redeem::winIds,
                Redeem::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- gacha / vouchers (client -> server)

    /** Simple request without data: open the gacha screen / history / voucher admin / terminal. */
    public record Request(int what) implements CustomPacketPayload {
        public static final int HUB = 0, HISTORY = 1, VOUCHERS = 2, TERMINAL = 3, MENU = 4, PRIZE_POOLS = 5;
        public static final Type<Request> TYPE = payloadType("request");
        public static final StreamCodec<ByteBuf, Request> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Request::what, Request::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record HubPull(int typeId, boolean multi) implements CustomPacketPayload {
        public static final Type<HubPull> TYPE = payloadType("hub_pull");
        public static final StreamCodec<ByteBuf, HubPull> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, HubPull::typeId,
                ByteBufCodecs.BOOL, HubPull::multi,
                HubPull::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record HubReveal(int roundId, long mask) implements CustomPacketPayload {
        public static final Type<HubReveal> TYPE = payloadType("hub_reveal");
        public static final StreamCodec<ByteBuf, HubReveal> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, HubReveal::roundId,
                ByteBufCodecs.VAR_LONG, HubReveal::mask,
                HubReveal::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record HubFinish(int roundId, boolean again) implements CustomPacketPayload {
        public static final Type<HubFinish> TYPE = payloadType("hub_finish");
        public static final StreamCodec<ByteBuf, HubFinish> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, HubFinish::roundId,
                ByteBufCodecs.BOOL, HubFinish::again,
                HubFinish::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SaveVoucher(VoucherType voucher) implements CustomPacketPayload {
        public static final Type<SaveVoucher> TYPE = payloadType("save_voucher");
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveVoucher> CODEC = StreamCodec.of(
                (buf, p) -> p.voucher.write(buf), buf -> new SaveVoucher(VoucherType.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record GenerateVoucher(int voucherId, int count) implements CustomPacketPayload {
        public static final Type<GenerateVoucher> TYPE = payloadType("generate_voucher");
        public static final StreamCodec<ByteBuf, GenerateVoucher> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, GenerateVoucher::voucherId,
                ByteBufCodecs.VAR_INT, GenerateVoucher::count,
                GenerateVoucher::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SavePrizePool(PrizePool pool) implements CustomPacketPayload {
        public static final Type<SavePrizePool> TYPE = payloadType("save_prize_pool");
        public static final StreamCodec<RegistryFriendlyByteBuf, SavePrizePool> CODEC = StreamCodec.of(
                (buf, p) -> p.pool.write(buf), buf -> new SavePrizePool(PrizePool.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record DeletePrizePool(int poolId) implements CustomPacketPayload {
        public static final Type<DeletePrizePool> TYPE = payloadType("delete_prize_pool");
        public static final StreamCodec<ByteBuf, DeletePrizePool> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, DeletePrizePool::poolId, DeletePrizePool::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private Payloads() {}
}
