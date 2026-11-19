package dev.flowscope.session;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * An inventory directly next to a watched machine. Used to attribute what the machine moves in or out during its own
 * tick (auto-input/auto-output): whatever a neighbour loses during that tick went into the machine, whatever it gains
 * came out of it. Holds the same slot-by-slot shadow as the machine's own reader. Server thread only.
 */
final class Neighbour {
    final BlockPos pos;
    final long posKey;
    /** The block entity this was created for; a different one at the position means the neighbour was replaced. */
    final BlockEntity blockEntity;
    BlockCapabilityCache<ResourceHandler<ItemResource>, Direction> items;
    BlockCapabilityCache<ResourceHandler<FluidResource>, Direction> fluids;
    final SnapshotDiff<ItemResource> itemDiff = new SnapshotDiff<>();
    final SnapshotDiff<FluidResource> fluidDiff = new SnapshotDiff<>();
    /** The neighbour changed outside the machine's tick (or has no baseline yet); refresh before attributing. */
    boolean stale = true;
    /** The neighbour reported a change during the machine's current own tick. */
    boolean changedThisTick;
    /** Released (replaced or recording ended): its capability listeners unregister on the next invalidation. */
    boolean discarded;

    private Neighbour(BlockPos pos, BlockEntity blockEntity) {
        this.pos = pos;
        this.posKey = pos.asLong();
        this.blockEntity = blockEntity;
    }

    /**
     * Creates a neighbour if the block at {@code pos} exposes item or fluid storage: the whole block (null side) if
     * available, else the face touching the machine. Returns null if it has no storage (e.g. a pipe without a
     * buffer); changes there cannot be measured and the machine falls back to the plain rule.
     */
    static Neighbour create(WatchedBlock b, Direction towardsNeighbour) {
        ServerLevel level = b.level;
        BlockPos pos = b.pos.relative(towardsNeighbour).immutable();
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        Direction touching = towardsNeighbour.getOpposite();
        int itemPick = pick(level, pos, true, touching);
        int fluidPick = pick(level, pos, false, touching);
        if (itemPick == NONE && fluidPick == NONE) {
            return null;
        }
        Neighbour n = new Neighbour(pos, be);
        if (itemPick != NONE) {
            n.items = BlockCapabilityCache.create(Capabilities.Item.BLOCK, level, pos,
                    itemPick == WHOLE ? null : touching, () -> !b.removed && !n.discarded, () -> { });
        }
        if (fluidPick != NONE) {
            n.fluids = BlockCapabilityCache.create(Capabilities.Fluid.BLOCK, level, pos,
                    fluidPick == WHOLE ? null : touching, () -> !b.removed && !n.discarded, () -> { });
        }
        return n;
    }

    private static final int NONE = 0;
    private static final int WHOLE = 1;
    private static final int FACE = 2;

    /** Which view of the neighbour's storage to read: the whole block, the face touching the machine, or none. */
    private static int pick(ServerLevel level, BlockPos pos, boolean itemsKind, Direction touching) {
        ResourceHandler<?> whole = itemsKind
                ? level.getCapability(Capabilities.Item.BLOCK, pos, null)
                : level.getCapability(Capabilities.Fluid.BLOCK, pos, null);
        if (whole != null && whole.size() > 0) {
            return WHOLE;
        }
        ResourceHandler<?> face = itemsKind
                ? level.getCapability(Capabilities.Item.BLOCK, pos, touching)
                : level.getCapability(Capabilities.Fluid.BLOCK, pos, touching);
        return face != null && face.size() > 0 ? FACE : NONE;
    }

    /**
     * Reads the neighbour. Returns false if it cannot be read (unloaded, gone, too large, or the handler threw);
     * the caller then treats it as unmeasurable. Third-party exceptions are swallowed here on purpose: a broken
     * neighbour must not end the machine's recording.
     */
    boolean read(int maxSlots) {
        try {
            ResourceHandler<ItemResource> ih = items != null ? items.getCapability() : null;
            ResourceHandler<FluidResource> fh = fluids != null ? fluids.getCapability() : null;
            if (ih == null && fh == null) {
                return false;
            }
            if (ih != null && itemDiff.read(ih, maxSlots) == SnapshotDiff.TOO_LARGE) {
                return false;
            }
            if (fh != null && fluidDiff.read(fh, maxSlots) == SnapshotDiff.TOO_LARGE) {
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
