package dev.flowscope.session;

import java.util.ArrayList;

import dev.flowscope.LensServerConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Event-based sampling: a watched block is read whenever it reports a change through
 * {@code BlockEntity.setChanged(Level, BlockPos, BlockState)} (every setChanged() ends up there; machines such as the
 * vanilla furnace also call it directly from their tick). Changes during the block's own tick are read once when
 * that tick ends and attributed to processing unless a neighbour changed in the same tick.
 *
 * Scaling: watched blocks are found by position in a hash map (O(1) per setChanged, independent of how many
 * recordings exist); the tick hook only reacts to block entities carrying the "watched" flag. Reads are budgeted per
 * block and per tick; reads over budget are coalesced (the block is marked dirty and read later, round-robin), so
 * counts are never lost, only merged (same-tick cancellation is then not visible).
 *
 * Server thread only. To revert to pure snapshot mode, delete this class, the mixin package,
 * flowscope.mixins.json and the [[mixins]] entry, and remove the calls marked "event sampling".
 */
public final class EventSampling {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    /** A block counts as event-based if no unreported change was seen for this long. */
    private static final long UNREPORTED_HOLD_NANOS = 60L * NANOS_PER_SECOND;

    /** True while at least one block is watched. Read by both mixins on every call; nothing else is touched when false. */
    public static boolean anyActive;
    /** Read by the ticker mixin after every block-entity tick; true only inside a watched block's own tick. */
    public static boolean ownTickActive;

    /** All watched blocks (for the tick handler). */
    static final ArrayList<WatchedBlock> BLOCKS = new ArrayList<>();
    /** Position (asLong) -> watched blocks at that position or with that double-chest half. */
    private static final Long2ObjectOpenHashMap<WatchedBlock[]> BY_POS = new Long2ObjectOpenHashMap<>();

    // Limits cached once per tick by refreshLimits(), so the hooks never touch the config.
    private static int perBlockCap = 8;
    private static int globalCap = 32;
    private static int maxSlots = 256;

    private static long globalTick = -1;
    private static int globalReads;

    // ---- own-tick attribution ----
    private static Level ownLevel;
    private static long ownKey;
    private static boolean neighbourChanged;

    private EventSampling() {
    }

    static void refreshLimits() {
        perBlockCap = LensServerConfig.MAX_EVENT_READS_PER_SESSION_TICK.getAsInt();
        globalCap = LensServerConfig.MAX_EVENT_READS_PER_TICK.getAsInt();
        maxSlots = LensServerConfig.MAX_VISIBLE_SLOTS.getAsInt();
    }

    static int maxSlots() {
        return maxSlots;
    }

    // ------------------------------------------------------------------ hooks

    /** Called by the ticker mixin right before a flagged block entity ticks. */
    public static void beginOwnTick(Level level, BlockPos pos) {
        ownTickActive = false;
        if (level.isClientSide()) {
            return;
        }
        long key = pos.asLong();
        WatchedBlock[] blocks = BY_POS.get(key);
        if (blocks == null) {
            return;
        }
        for (WatchedBlock b : blocks) {
            if (b.level == level && b.posKey == key) {
                ownTickActive = true;
                ownLevel = level;
                ownKey = key;
                neighbourChanged = false;
                return;
            }
        }
    }

    /** Called by the ticker mixin right after a watched block entity ticked. */
    public static void endOwnTick() {
        if (!ownTickActive) {
            return;
        }
        ownTickActive = false;
        WatchedBlock[] blocks = BY_POS.get(ownKey);
        Level level = ownLevel;
        ownLevel = null;
        if (blocks == null) {
            return;
        }
        int mode = neighbourChanged ? LensSessionManager.READ_TRANSFER : LensSessionManager.READ_PROCESS;
        for (WatchedBlock b : blocks) {
            if (b.ownChanged && b.level == level && b.posKey == ownKey) {
                b.ownChanged = false;
                eventRead(b, mode);
            }
        }
    }

    /** Safety reset at the end of every server tick (e.g. if a ticker threw before endOwnTick ran). */
    static void resetOwnTick() {
        ownTickActive = false;
        ownLevel = null;
    }

    /** Entry point from BlockEntityMixin while anything is watched. */
    public static void onBlockChanged(Level level, BlockPos pos) {
        long key = pos.asLong();
        if (ownTickActive && level == ownLevel && key != ownKey) {
            neighbourChanged = true;
        }
        WatchedBlock[] blocks = BY_POS.get(key);
        if (blocks == null) {
            return;
        }
        for (WatchedBlock b : blocks) {
            if (b.level != level || (b.posKey != key && b.partnerKey != key)) {
                continue;
            }
            if (b.removed || b.pendingEnd != null || b.reading || Thread.currentThread() != b.serverThread) {
                continue; // off-thread or re-entrant: the timer read catches it
            }
            if (ownTickActive && level == ownLevel && b.posKey == ownKey) {
                b.ownChanged = true; // read once when the block's own tick ends
                continue;
            }
            eventRead(b, LensSessionManager.READ_TRANSFER);
        }
    }

    /** A change-triggered read within the per-block and global per-tick budgets; otherwise coalesced. */
    private static void eventRead(WatchedBlock b, int mode) {
        if (b.removed || b.pendingEnd != null) {
            return;
        }
        long tick = b.level.getServer().getTickCount();
        if (tick != b.eventTick) {
            b.eventTick = tick;
            b.eventReads = 0;
        }
        if (tick != globalTick) {
            globalTick = tick;
            globalReads = 0;
        }
        if (b.eventReads < perBlockCap && globalReads < globalCap) {
            b.eventReads++;
            globalReads++;
            SessionState reason = LensSessionManager.read(b, System.nanoTime(), maxSlots, mode);
            if (reason != null) {
                b.pendingEnd = reason; // never end recordings from inside another mod's call stack
            }
        } else {
            b.dirty = true;
        }
    }

    // ------------------------------------------------------------------ registry

    /** Watched blocks not yet released. */
    private static int activeBlocks;

    static void register(WatchedBlock b) {
        BLOCKS.add(b);
        activeBlocks++;
        refreshAttachment(b);
        updateActive();
    }

    /** Releases a block: stops change detection at once; the list entry is dropped by {@link #sweep()}. */
    static void unregister(WatchedBlock b) {
        if (b.removed) {
            return;
        }
        b.removed = true;
        activeBlocks--;
        unindex(b, b.posKey);
        unindex(b, b.partnerKey);
        setFlag(b, null);
        updateActive();
    }

    /** Drops released blocks from the list (never called while the list is being iterated). */
    static void sweep() {
        for (int i = BLOCKS.size() - 1; i >= 0; i--) {
            if (BLOCKS.get(i).removed) {
                BLOCKS.remove(i);
            }
        }
    }

    static void clear() {
        for (WatchedBlock b : BLOCKS) {
            b.removed = true;
            setFlag(b, null);
        }
        BLOCKS.clear();
        BY_POS.clear();
        activeBlocks = 0;
        updateActive();
    }

    static WatchedBlock find(Level level, BlockPos pos, boolean wholeInventory) {
        WatchedBlock[] blocks = BY_POS.get(pos.asLong());
        if (blocks != null) {
            for (WatchedBlock b : blocks) {
                if (!b.removed && b.posKey == pos.asLong() && b.level == level && b.wholeInventory == wholeInventory) {
                    return b;
                }
            }
        }
        return null;
    }

    private static void updateActive() {
        anyActive = activeBlocks > 0;
    }

    /**
     * Keeps the position index and the "watched" flag current: the double-chest partner can change, and the block
     * entity is a new object if the block was broken and placed again. Called on registration and timer reads.
     */
    static void refreshAttachment(WatchedBlock b) {
        if (b.removed) {
            return;
        }
        index(b, b.posKey);
        long partner = Long.MIN_VALUE;
        if (b.level.isLoaded(b.pos)) {
            BlockState state = b.level.getBlockState(b.pos);
            if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                    && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                partner = b.pos.relative(ChestBlock.getConnectedDirection(state)).asLong();
            }
            setFlag(b, b.level.getBlockEntity(b.pos));
        }
        if (partner != b.partnerKey) {
            unindex(b, b.partnerKey);
            b.partnerKey = partner;
            index(b, partner);
        }
    }

    private static void index(WatchedBlock b, long key) {
        if (key == Long.MIN_VALUE) {
            return;
        }
        WatchedBlock[] old = BY_POS.get(key);
        if (old == null) {
            BY_POS.put(key, new WatchedBlock[] {b});
            return;
        }
        for (WatchedBlock existing : old) {
            if (existing == b) {
                return;
            }
        }
        WatchedBlock[] next = java.util.Arrays.copyOf(old, old.length + 1);
        next[old.length] = b;
        BY_POS.put(key, next);
    }

    private static void unindex(WatchedBlock b, long key) {
        if (key == Long.MIN_VALUE) {
            return;
        }
        WatchedBlock[] old = BY_POS.get(key);
        if (old == null) {
            return;
        }
        int idx = -1;
        for (int i = 0; i < old.length; i++) {
            if (old[i] == b) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return;
        }
        if (old.length == 1) {
            BY_POS.remove(key);
            return;
        }
        WatchedBlock[] next = new WatchedBlock[old.length - 1];
        System.arraycopy(old, 0, next, 0, idx);
        System.arraycopy(old, idx + 1, next, idx, old.length - idx - 1);
        BY_POS.put(key, next);
    }

    /** Moves the "watched" flag to {@code be} (or clears it); stays set while another watched block uses it. */
    private static void setFlag(WatchedBlock b, BlockEntity be) {
        BlockEntity old = b.flaggedBlockEntity;
        if (old == be) {
            return;
        }
        b.flaggedBlockEntity = be;
        if (old instanceof LensWatchedFlag oldFlag && !flaggedByOther(old, b)) {
            oldFlag.flowscope$setWatched(false);
        }
        if (be instanceof LensWatchedFlag flag) {
            flag.flowscope$setWatched(true);
        }
    }

    private static boolean flaggedByOther(BlockEntity be, WatchedBlock except) {
        WatchedBlock[] blocks = BY_POS.get(except.posKey);
        if (blocks != null) {
            for (WatchedBlock other : blocks) {
                if (other != except && !other.removed && other.flaggedBlockEntity == be) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if changes are being caught through setChanged() (no unreported change found recently). */
    static boolean isEventMode(WatchedBlock b, long now) {
        return !b.hasUnreported || now - b.lastUnreportedNanos > UNREPORTED_HOLD_NANOS;
    }
}
