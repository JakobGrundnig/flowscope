package dev.flowscope.network;

import java.util.ArrayList;
import java.util.List;

import dev.flowscope.Flowscope;
import dev.flowscope.session.ItemBreakdown;
import dev.flowscope.session.RollingRateWindow;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: compact aggregated statistics for one session. Bounded size (two arrays of
 * {@link RollingRateWindow#SECONDS} varints plus a few scalars).
 *
 * @param samples            compared samples since the baseline; 0 means "collecting data"
 * @param warmingUp          true while less than a full window of data exists
 * @param increasePerMin     observed increase normalised to items per minute
 * @param decreasePerMin     observed decrease normalised to items per minute
 * @param effectiveIntervalMs measured real time between timer samples
 * @param eventMode          true if changes are counted when the block reports them (setChanged), false = snapshots
 * @param graphIncrease      per-second observed increase, oldest first
 * @param graphDecrease      per-second observed decrease, oldest first
 * @param processing         true once the block produced or consumed anything in its own tick (a machine)
 * @param producedPerMin     items made by the block per minute
 * @param consumedPerMin     items used up by the block per minute
 * @param items              per-item summaries indexed by ItemBreakdown.IN/OUT/PRODUCED/CONSUMED/NET, or empty when
 *                           no list is expanded; entries are items or fluids
 * @param hasItems           the block has item storage (item rates apply)
 * @param hasFluids          the block has fluid storage (fluid rates apply; graph shows fluids if !hasItems)
 * @param fluidInPerMin      fluid rates in mB per minute (never summed with items)
 * @param hiddenTransfers    the block keeps moving things to/from neighbours but nothing can be measured
 */
public record LensStatsPayload(int sessionId, int state, int elapsedMs, int maxMs, int samples, boolean warmingUp,
        double increasePerMin, double decreasePerMin, int effectiveIntervalMs, boolean eventMode,
        int[] graphIncrease, int[] graphDecrease,
        boolean breakdownAvailable, boolean processing, double producedPerMin, double consumedPerMin,
        List<ItemBreakdown.Summary> items,
        boolean hasItems, boolean hasFluids, double fluidInPerMin, double fluidOutPerMin, double fluidMadePerMin,
        double fluidUsedPerMin, boolean hiddenTransfers)
        implements CustomPacketPayload {

    private static final int KIND_ITEM = 0;
    private static final int KIND_FLUID = 1;

    /** Hard cap on per-item entries per list in one packet. */
    public static final int MAX_ITEM_ENTRIES = 64;

    /** No per-item lists in this packet. */
    public static final List<ItemBreakdown.Summary> NO_ITEMS = List.of();

    /** The summary for a category, or an empty one if lists were not sent. */
    public ItemBreakdown.Summary items(int category) {
        return category < items.size() ? items.get(category) : ItemBreakdown.Summary.EMPTY;
    }

    public static final Type<LensStatsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Flowscope.MODID, "stats"));

    private static final StreamCodec<RegistryFriendlyByteBuf, ItemBreakdown.Summary> SUMMARY_CODEC = StreamCodec.of(
            (buf, s) -> {
                int n = Math.min(s.entries().size(), MAX_ITEM_ENTRIES);
                buf.writeVarInt(n);
                for (int i = 0; i < n; i++) {
                    ItemBreakdown.Entry e = s.entries().get(i);
                    if (e.resource() instanceof FluidResource fluid) {
                        buf.writeByte(KIND_FLUID);
                        FluidResource.STREAM_CODEC.encode(buf, fluid);
                    } else {
                        buf.writeByte(KIND_ITEM);
                        ItemResource.STREAM_CODEC.encode(buf, (ItemResource) e.resource());
                    }
                    buf.writeVarLong(e.amount());
                }
                buf.writeVarInt(s.moreItems());
                buf.writeVarLong(s.moreAmount());
            },
            buf -> {
                int n = buf.readVarInt();
                if (n < 0 || n > MAX_ITEM_ENTRIES) {
                    throw new IllegalArgumentException("Too many item entries: " + n);
                }
                List<ItemBreakdown.Entry> entries = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    int kind = buf.readByte();
                    Resource resource = kind == KIND_FLUID
                            ? FluidResource.STREAM_CODEC.decode(buf) : ItemResource.STREAM_CODEC.decode(buf);
                    entries.add(new ItemBreakdown.Entry(resource, buf.readVarLong()));
                }
                return new ItemBreakdown.Summary(entries, buf.readVarInt(), buf.readVarLong());
            });

    public static final StreamCodec<RegistryFriendlyByteBuf, LensStatsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.sessionId);
                buf.writeVarInt(p.state);
                buf.writeVarInt(p.elapsedMs);
                buf.writeVarInt(p.maxMs);
                buf.writeVarInt(p.samples);
                buf.writeBoolean(p.warmingUp);
                buf.writeDouble(p.increasePerMin);
                buf.writeDouble(p.decreasePerMin);
                buf.writeVarInt(p.effectiveIntervalMs);
                buf.writeBoolean(p.eventMode);
                buf.writeVarIntArray(p.graphIncrease);
                buf.writeVarIntArray(p.graphDecrease);
                buf.writeBoolean(p.breakdownAvailable);
                buf.writeBoolean(p.processing);
                buf.writeDouble(p.producedPerMin);
                buf.writeDouble(p.consumedPerMin);
                int lists = Math.min(p.items.size(), ItemBreakdown.LISTS);
                buf.writeVarInt(lists);
                for (int i = 0; i < lists; i++) {
                    SUMMARY_CODEC.encode(buf, p.items.get(i));
                }
                buf.writeBoolean(p.hasItems);
                buf.writeBoolean(p.hasFluids);
                buf.writeDouble(p.fluidInPerMin);
                buf.writeDouble(p.fluidOutPerMin);
                buf.writeDouble(p.fluidMadePerMin);
                buf.writeDouble(p.fluidUsedPerMin);
                buf.writeBoolean(p.hiddenTransfers);
            },
            buf -> new LensStatsPayload(
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readVarInt(),
                    buf.readBoolean(),
                    buf.readVarIntArray(RollingRateWindow.SECONDS),
                    buf.readVarIntArray(RollingRateWindow.SECONDS),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readDouble(),
                    buf.readDouble(),
                    readSummaries(buf),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readBoolean()));

    private static List<ItemBreakdown.Summary> readSummaries(RegistryFriendlyByteBuf buf) {
        int lists = buf.readVarInt();
        if (lists < 0 || lists > ItemBreakdown.LISTS) {
            throw new IllegalArgumentException("Too many item lists: " + lists);
        }
        if (lists == 0) {
            return NO_ITEMS;
        }
        List<ItemBreakdown.Summary> out = new ArrayList<>(lists);
        for (int i = 0; i < lists; i++) {
            out.add(SUMMARY_CODEC.decode(buf));
        }
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
