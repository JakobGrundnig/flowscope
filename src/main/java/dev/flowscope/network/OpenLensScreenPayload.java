package dev.flowscope.network;

import dev.flowscope.Flowscope;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: a recording was started; open the lens screen for it.
 *
 * @param allSides true if the whole inventory is read (capability context null), false if only {@code face}
 */
public record OpenLensScreenPayload(int sessionId, Component blockName, Direction face, boolean allSides,
        int sampleIntervalTicks, int maxSeconds) implements CustomPacketPayload {

    public static final Type<OpenLensScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Flowscope.MODID, "open_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenLensScreenPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.sessionId);
                ComponentSerialization.STREAM_CODEC.encode(buf, p.blockName);
                Direction.STREAM_CODEC.encode(buf, p.face);
                buf.writeBoolean(p.allSides);
                buf.writeVarInt(p.sampleIntervalTicks);
                buf.writeVarInt(p.maxSeconds);
            },
            buf -> new OpenLensScreenPayload(
                    buf.readVarInt(),
                    ComponentSerialization.STREAM_CODEC.decode(buf),
                    Direction.STREAM_CODEC.decode(buf),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
