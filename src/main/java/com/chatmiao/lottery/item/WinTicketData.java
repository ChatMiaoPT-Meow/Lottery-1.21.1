package com.chatmiao.lottery.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Stored on a winning ticket. Only {@link #winId} matters to the server; everything else is a copy
 * for the tooltip. The real prize is always read from the server ledger at redemption.
 */
public record WinTicketData(String winId, int typeId, String typeName, String winner, Component prize) {
    public static final Codec<WinTicketData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("win_id").forGetter(WinTicketData::winId),
            Codec.INT.fieldOf("type_id").forGetter(WinTicketData::typeId),
            Codec.STRING.fieldOf("type_name").forGetter(WinTicketData::typeName),
            Codec.STRING.fieldOf("winner").forGetter(WinTicketData::winner),
            ComponentSerialization.CODEC.fieldOf("prize").forGetter(WinTicketData::prize)
    ).apply(i, WinTicketData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, WinTicketData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, WinTicketData::winId,
            ByteBufCodecs.VAR_INT, WinTicketData::typeId,
            ByteBufCodecs.STRING_UTF8, WinTicketData::typeName,
            ByteBufCodecs.STRING_UTF8, WinTicketData::winner,
            ComponentSerialization.STREAM_CODEC, WinTicketData::prize,
            WinTicketData::new);
}
