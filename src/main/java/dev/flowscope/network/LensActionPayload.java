package dev.flowscope.network;

import dev.flowscope.Flowscope;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: a button in the lens screen was used. Validated against the sender on the server. */
public record LensActionPayload(int sessionId, int action) implements CustomPacketPayload {
    public static final int STOP = 0;
    public static final int RESTART = 1;
    public static final int CLOSE = 2;
    /** The player expanded the in or out list; per-item lists are included in stats from now on. */
    public static final int LISTS_SHOWN = 3;
    /** Both lists are collapsed again; stop sending per-item lists. */
    public static final int LISTS_HIDDEN = 4;

    public static final Type<LensActionPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Flowscope.MODID, "action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LensActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.sessionId);
                buf.writeVarInt(p.action);
            },
            buf -> new LensActionPayload(buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
