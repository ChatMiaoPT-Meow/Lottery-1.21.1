package com.chatmiao.lottery.network;

import com.chatmiao.lottery.LotteryMod;
import com.chatmiao.lottery.data.Prize;
import com.chatmiao.lottery.data.TicketType;
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

    /** Opens (or refreshes) the terminal GUI with the full type list. */
    public record OpenTerminal(List<TicketType> types, int selectId, int maxGenerate) implements CustomPacketPayload {
        public static final Type<OpenTerminal> TYPE = payloadType("open_terminal");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenTerminal> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.types.size());
                    for (TicketType t : p.types) t.write(buf);
                    buf.writeVarInt(p.selectId);
                    buf.writeVarInt(p.maxGenerate);
                },
                buf -> {
                    int n = buf.readVarInt();
                    List<TicketType> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) list.add(TicketType.read(buf));
                    return new OpenTerminal(list, buf.readVarInt(), buf.readVarInt());
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

    private Payloads() {}
}
