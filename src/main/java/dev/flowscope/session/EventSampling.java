package dev.flowscope.session;

import java.util.ArrayList;

import dev.flowscope.LensServerConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
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
    private static WatchedBlock ownBlock;

    private EventSampling() {
    }

    static void refreshLimits() {
        perBlockCap = LensServerConfig.MAX_EVENT_READS_PER_SESSION_TICK.getAsInt();
        globalCap = LensServerConfig.MAX_EVENT_READS_PER_TICK.getAsInt();
        maxSlots = LensServerConfig.MAX_VISIBLE_SLOTS.getAsInt();
        countTransfers = LensServerConfig.COUNT_NEIGHBOUR_TRANSFERS.getAsBoolean();
    }

    static int maxSlots() {
        return maxSlots;
    }

    // ------------------------------------------------------------------ hooks

    // ---- counting handlers (capability wrapping during a recorded machine's own tick) ----
    /** True only while a recorded machine runs its own tick and wrapping is enabled. Read by the capability mixins. */
    public static boolean wrapping;
    /** Nesting depth of BlockCapabilityCache lookups (their internal lookup must not be wrapped). */
    public static int inCacheLookup;
    private static boolean countTransfers = true;

    /**
     * Called by the capability mixins while {@link #wrapping}. Returns a counting pass-through for item/fluid handlers
     * of blocks adjacent to the ticking recorded machine, otherwise the original object unchanged.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object maybeWrap(Object capability, Level level, BlockPos pos, Object original) {
        WatchedBlock b = ownBlock;
        if (b == null || level != ownLevel || !(original instanceof ResourceHandler<?> handler)
                || original instanceof CountingHandler<?> || Thread.currentThread() != b.serverThread) {
            return original;
        }
        if (capability != Capabilities.Item.BLOCK && capability != Capabilities.Fluid.BLOCK) {
            return original;
        }
        long key = pos.asLong();
        if (key == b.posKey || key == b.partnerKey
                || Math.abs(pos.getX() - b.pos.getX()) + Math.abs(pos.getY() - b.pos.getY())
                        + Math.abs(pos.getZ() - b.pos.getZ()) != 1) {
            return original; // only the machine's 6 direct neighbours, never the machine itself
        }
        return new CountingHandler(handler, b.recorder, key);
    }

    /** Called by the ticker mixin right before a flagged block entity ticks. */
    public static void beginOwnTick(Level level, BlockPos pos) {
        ownTickActive = false;
        wrapping = false;
        if (level.isClientSide()) {
            return;
        }
        long key = pos.asLong();
        WatchedBlock[] blocks = BY_POS.get(key);
        if (blocks == null) {
            return;
        }
        for (WatchedBlock b : blocks) {
            if (b.level == level && !b.removed && (b.posKey == key || b.ownsKey(key))) {
                ownTickActive = true;
                ownLevel = level;
                ownKey = key;
                ownBlock = b;
                neighbourChanged = false;
                prepareNeighbours(b);
                b.recorder.reset();
                inCacheLookup = 0;
                wrapping = countTransfers;
                return;
            }
        }
    }

    /**
     * Before an active machine ticks: bring the shadows of neighbours that changed since (stale) up to date, so the
     * changes they show during this tick are exactly what the machine moved. Budgeted; a neighbour left stale is
     * treated as unmeasurable for this tick.
     */
    private static void prepareNeighbours(WatchedBlock b) {
        Neighbour[] ns = b.neighbours;
        if (ns == null) {
            return;
        }
        for (Neighbour n : ns) {
            n.changedThisTick = false;
            if (n.stale && b.activeMachine && takeBudget(b)) {
                n.stale = !n.read(maxSlots);
            }
        }
    }

    /** Called by the ticker mixin right after a watched block entity ticked. */
    public static void endOwnTick() {
        if (!ownTickActive) {
            return;
        }
        ownTickActive = false;
        wrapping = false;
        inCacheLookup = 0;
        WatchedBlock b = ownBlock;
        ownBlock = null;
        ownLevel = null;
        if (b == null || b.removed || b.pendingEnd != null) {
            return;
        }
        boolean neighboursChanged = false;
        Neighbour[] ns = b.neighbours;
        if (ns != null) {
            for (Neighbour n : ns) {
                neighboursChanged |= n.changedThisTick;
            }
        }
        boolean captured = b.recorder.committedCount() > 0;
        boolean active = b.ownChanged || neighboursChanged || captured;
        b.activeMachine = b.ownChanged;
        if (!active) {
            return;
        }
        b.ownChanged = false;
        attributedRead(b, neighbourChanged);
        if (ns == null && b.activeMachine) {
            createNeighbours(b); // first time this block changed in its own tick: start watching its neighbours
        }
    }

    /**
     * Reads the machine and the neighbours that changed during its tick, and splits the change into in/out and
     * made/used up (see LensSessionManager.readAttributed). Needs one read plus one per changed neighbour from the
     * budget; without enough budget it falls back to the plain rule and the neighbours are refreshed later.
     */
    private static void attributedRead(WatchedBlock b, boolean foreign) {
        Neighbour[] ns = b.neighbours;
        int needed = 1;
        if (ns != null) {
            for (Neighbour n : ns) {
                if (n.changedThisTick) {
                    needed++;
                }
            }
        }
        boolean captured = b.recorder.committedCount() > 0;
        if ((needed == 1 && !captured) || !hasBudget(b, needed)) {
            boolean anyNeighbour = needed > 1;
            if (ns != null) {
                for (Neighbour n : ns) {
                    if (n.changedThisTick) {
                        n.stale = true;
                        n.changedThisTick = false;
                    }
                }
            }
            int before = b.reportedChanges;
            eventRead(b, foreign || anyNeighbour ? LensSessionManager.READ_TRANSFER : LensSessionManager.READ_PROCESS);
            if (b.reportedChanges != before) {
                b.silentInteractTicks = 0;
            } else if (foreign || anyNeighbour) {
                b.silentInteractTicks++; // it moved things around, but nothing measurable changed (for the hint)
            }
            return;
        }
        for (int i = 0; i < needed; i++) {
            takeBudget(b);
        }
        SessionState reason = LensSessionManager.readAttributed(b, System.nanoTime(), maxSlots, foreign);
        if (reason != null) {
            b.pendingEnd = reason;
        }
    }

    private static void syncBudgetTick(WatchedBlock b) {
        long tick = b.level.getServer().getTickCount();
        if (tick != b.eventTick) {
            b.eventTick = tick;
            b.eventReads = 0;
        }
        if (tick != globalTick) {
            globalTick = tick;
            globalReads = 0;
        }
    }

    private static boolean hasBudget(WatchedBlock b, int reads) {
        syncBudgetTick(b);
        return b.eventReads + reads <= perBlockCap + 6 && globalReads + reads <= globalCap;
    }

    /** Takes one read from the per-tick budgets (neighbour reads get 6 extra per block). */
    private static boolean takeBudget(WatchedBlock b) {
        if (!hasBudget(b, 1)) {
            return false;
        }
        b.eventReads++;
        globalReads++;
        return true;
    }

    /** Looks up the up to 6 adjacent inventories and takes their baseline. */
    private static void createNeighbours(WatchedBlock b) {
        ArrayList<Neighbour> list = new ArrayList<>(6);
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            long key = b.pos.relative(d).asLong();
            if (key == b.partnerKey) {
                continue; // the other half of a double chest is part of the block itself
            }
            Neighbour n;
            try {
                n = Neighbour.create(b, d);
            } catch (RuntimeException e) {
                n = null;
            }
            if (n != null) {
                list.add(n);
            }
        }
        Neighbour[] ns = list.toArray(new Neighbour[0]);
        for (Neighbour n : ns) {
            index(b, n.posKey);
        }
        b.neighbours = ns;
        b.neighbourBlockEntities = countNeighbourBlockEntities(b);
    }

    /** Re-creates neighbours whose block entity was replaced or removed (checked on safety-net reads). */
    private static void refreshNeighbours(WatchedBlock b) {
        Neighbour[] ns = b.neighbours;
        if (ns == null) {
            return;
        }
        boolean changed = false;
        for (Neighbour n : ns) {
            if (!b.level.isLoaded(n.pos) || b.level.getBlockEntity(n.pos) != n.blockEntity) {
                changed = true;
                break;
            }
        }
        if (!changed) {
            // A block entity may also have appeared or disappeared next to the machine.
            changed = countNeighbourBlockEntities(b) != b.neighbourBlockEntities;
        }
        if (changed) {
            releaseNeighbours(b);
            createNeighbours(b);
        }
    }

    private static int countNeighbourBlockEntities(WatchedBlock b) {
        int present = 0;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos p = b.pos.relative(d);
            if (p.asLong() != b.partnerKey && b.level.isLoaded(p) && b.level.getBlockEntity(p) != null) {
                present++;
            }
        }
        return present;
    }

    private static void releaseNeighbours(WatchedBlock b) {
        Neighbour[] ns = b.neighbours;
        if (ns == null) {
            return;
        }
        for (Neighbour n : ns) {
            n.discarded = true;
            if (n.posKey != b.posKey && n.posKey != b.partnerKey) {
                unindex(b, n.posKey);
            }
        }
        b.neighbours = null;
    }

    /** Safety reset at the end of every server tick (e.g. if a ticker threw before endOwnTick ran). */
    static void resetOwnTick() {
        ownTickActive = false;
        wrapping = false;
        inCacheLookup = 0;
        ownBlock = null;
        ownLevel = null;
    }

    /** Entry point from BlockEntityMixin while anything is watched. */
    public static void onBlockChanged(Level level, BlockPos pos) {
        long key = pos.asLong();
        if (ownTickActive && level == ownLevel && key != ownKey) {
            // Another block changed during the watched block's own tick: a measured neighbour (attributed exactly
            // at the end of the tick) or something else (then the plain rule applies: it moved items itself).
            Neighbour n = ownBlock != null ? ownBlock.neighbour(key) : null;
            if (n != null) {
                n.changedThisTick = true;
            } else if (ownBlock == null || !ownBlock.ownsKey(key)) {
                neighbourChanged = true;
            }
        }
        WatchedBlock[] blocks = BY_POS.get(key);
        if (blocks == null) {
            return;
        }
        for (WatchedBlock b : blocks) {
            if (b.level != level) {
                continue;
            }
            if (!b.ownsKey(key)) {
                // A neighbour of a watched machine changed outside the machine's tick: refresh it before the next one.
                Neighbour n = b.neighbour(key);
                if (n != null && !(ownTickActive && b == ownBlock)) {
                    n.stale = true;
                }
                continue;
            }
            if (b.removed || b.pendingEnd != null || b.reading || Thread.currentThread() != b.serverThread) {
                continue; // off-thread or re-entrant: the timer read catches it
            }
            if (ownTickActive && level == ownLevel && b == ownBlock) {
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
        syncBudgetTick(b);
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
        releaseNeighbours(b);
        unindex(b, b.posKey);
        unindex(b, b.partnerKey);
        setFlag(b, null);
        setLinkedContainer(b, null);
        clearAliases(b);
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
            for (BlockEntity alias : b.aliasBlockEntities) {
                if (alias instanceof LensWatchedFlag flag) {
                    flag.flowscope$setWatched(false);
                }
            }
            if (b.neighbours != null) {
                for (Neighbour n : b.neighbours) {
                    n.discarded = true;
                }
                b.neighbours = null;
            }
        }
        BLOCKS.clear();
        BY_POS.clear();
        BY_CONTAINER.clear();
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
        refreshNeighbours(b);
        refreshLinkedContainer(b);
    }

    // ------------------------------------------------------------------ linked containers

    /** Watched blocks whose item handler wraps a container that is not their own block entity (identity map). */
    private static final it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<Object, WatchedBlock[]> BY_CONTAINER =
            new it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<>();

    /**
     * Finds out whether the block's item handler is NeoForge's wrapper around a container other than the block entity
     * itself (e.g. a shared, frequency-linked storage). Such containers never make the block report a change, so
     * commits on them are reported through VanillaContainerWrapperMixin instead. Every block exposing the same
     * container (all chests on one frequency) then sees the change.
     */
    private static void refreshLinkedContainer(WatchedBlock b) {
        net.minecraft.world.Container container = null;
        try {
            Object handler = b.items != null ? b.items.getCapability() : null;
            if (handler instanceof LinkedContainerAccess access) {
                net.minecraft.world.Container c = access.flowscope$container();
                if (!(c instanceof BlockEntity)) {
                    container = c;
                }
            }
        } catch (RuntimeException e) {
            container = null; // unreadable here; the regular reads will report the error
        }
        setLinkedContainer(b, container);
    }

    private static void setLinkedContainer(WatchedBlock b, net.minecraft.world.Container container) {
        net.minecraft.world.Container old = b.linkedContainer;
        if (old == container) {
            return;
        }
        if (old != null) {
            WatchedBlock[] list = BY_CONTAINER.get(old);
            if (list != null) {
                WatchedBlock[] next = remove(list, b);
                if (next.length == 0) {
                    BY_CONTAINER.remove(old);
                } else {
                    BY_CONTAINER.put(old, next);
                }
            }
        }
        b.linkedContainer = container;
        if (container != null) {
            WatchedBlock[] list = BY_CONTAINER.get(container);
            if (list == null) {
                BY_CONTAINER.put(container, new WatchedBlock[] {b});
            } else {
                WatchedBlock[] next = java.util.Arrays.copyOf(list, list.length + 1);
                next[list.length] = b;
                BY_CONTAINER.put(container, next);
            }
        }
    }

    private static WatchedBlock[] remove(WatchedBlock[] list, WatchedBlock b) {
        int idx = -1;
        for (int i = 0; i < list.length; i++) {
            if (list[i] == b) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return list;
        }
        WatchedBlock[] next = new WatchedBlock[list.length - 1];
        System.arraycopy(list, 0, next, 0, idx);
        System.arraycopy(list, idx + 1, next, idx, list.length - idx - 1);
        return next;
    }

    /** Entry point from VanillaContainerWrapperMixin: a transfer into/out of {@code container} was committed. */
    public static void onContainerCommitted(net.minecraft.world.Container container) {
        if (BY_CONTAINER.isEmpty()) {
            return;
        }
        WatchedBlock[] blocks = BY_CONTAINER.get(container);
        if (blocks == null) {
            return;
        }
        for (WatchedBlock b : blocks) {
            if (b.removed || b.pendingEnd != null || b.reading || Thread.currentThread() != b.serverThread) {
                continue;
            }
            if (ownTickActive && b == ownBlock) {
                b.ownChanged = true; // read once when the block's own tick ends
                continue;
            }
            eventRead(b, LensSessionManager.READ_TRANSFER);
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
        WatchedBlock[] blocks = BY_POS.get(be.getBlockPos().asLong());
        if (blocks != null) {
            for (WatchedBlock other : blocks) {
                if (other == except || other.removed) {
                    continue;
                }
                if (other.flaggedBlockEntity == be) {
                    return true;
                }
                for (BlockEntity alias : other.aliasBlockEntities) {
                    if (alias == be) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ multiblock parts (aliases)

    /** Search radius around the recorded block, in blocks, and the most block entities examined per search. */
    private static final int ALIAS_RADIUS = 8;
    private static final int ALIAS_MAX_CHECKED = 512;
    private static final int ALIAS_MAX = 64;
    private static final long ALIAS_RETRY_NANOS = 60L * NANOS_PER_SECOND;

    /**
     * Multiblocks often let every part hand out the controller's storage, while only the controller ticks and reports
     * changes. When a recorded block shows changes it never reported, look once (bounded) for nearby block entities
     * whose item/fluid handler is the very same object (identity, so unrelated blocks never match) and treat them as
     * part of the recorded block: their setChanged() and own ticks then count as the block's own. No mod-specific
     * code. Runs at most once per tick server-wide and at most once per minute per recording.
     */
    static void discoverAliases(WatchedBlock b, long now) {
        b.discoveryPending = false;
        b.nextDiscoveryNanos = now + ALIAS_RETRY_NANOS;
        if (b.removed || !b.level.isLoaded(b.pos)) {
            return;
        }
        Object items;
        Object fluids;
        try {
            items = b.items != null ? b.items.getCapability() : null;
            fluids = b.fluids != null ? b.fluids.getCapability() : null;
        } catch (RuntimeException e) {
            return;
        }
        if (items == null && fluids == null) {
            return;
        }
        // What the recorded block's handler forwards to (e.g. a part's thin wrapper around the controller's storage).
        Object innerItems = items != null ? HandlerIdentity.inner(items, true) : null;
        Object innerFluids = fluids != null ? HandlerIdentity.inner(fluids, true) : null;
        ArrayList<BlockEntity> found = new ArrayList<>();
        int checked = 0;
        int minCx = (b.pos.getX() - ALIAS_RADIUS) >> 4;
        int maxCx = (b.pos.getX() + ALIAS_RADIUS) >> 4;
        int minCz = (b.pos.getZ() - ALIAS_RADIUS) >> 4;
        int maxCz = (b.pos.getZ() + ALIAS_RADIUS) >> 4;
        outer:
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = b.level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue; // never load chunks
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    long key = p.asLong();
                    if (key == b.posKey || key == b.partnerKey
                            || Math.abs(p.getX() - b.pos.getX()) > ALIAS_RADIUS
                            || Math.abs(p.getY() - b.pos.getY()) > ALIAS_RADIUS
                            || Math.abs(p.getZ() - b.pos.getZ()) > ALIAS_RADIUS) {
                        continue;
                    }
                    if (++checked > ALIAS_MAX_CHECKED) {
                        break outer;
                    }
                    try {
                        if ((items != null && HandlerIdentity.sameStorage(items, innerItems,
                                        b.level.getCapability(Capabilities.Item.BLOCK, p, be.getBlockState(), be, null)))
                                || (fluids != null && HandlerIdentity.sameStorage(fluids, innerFluids,
                                        b.level.getCapability(Capabilities.Fluid.BLOCK, p, be.getBlockState(), be, null)))) {
                            found.add(be);
                            if (found.size() >= ALIAS_MAX) {
                                break outer;
                            }
                        }
                    } catch (RuntimeException e) {
                        // an untrusted capability provider failed: skip that block
                    }
                }
            }
        }
        setAliases(b, found);
        if (!found.isEmpty()) {
            // Changes are now reported through the other parts: give event mode a fresh chance.
            b.hasUnreported = false;
            b.everyTickFallback = false;
            b.reportedChanges = 0;
        }
    }

    private static void setAliases(WatchedBlock b, java.util.List<BlockEntity> aliases) {
        clearAliases(b);
        int n = aliases.size();
        long[] keys = new long[n];
        BlockEntity[] bes = new BlockEntity[n];
        for (int i = 0; i < n; i++) {
            BlockEntity be = aliases.get(i);
            bes[i] = be;
            keys[i] = be.getBlockPos().asLong();
        }
        b.aliasKeys = keys;
        b.aliasBlockEntities = bes;
        for (int i = 0; i < n; i++) {
            index(b, keys[i]);
            if (bes[i] instanceof LensWatchedFlag flag) {
                flag.flowscope$setWatched(true);
            }
        }
    }

    private static void clearAliases(WatchedBlock b) {
        long[] keys = b.aliasKeys;
        BlockEntity[] bes = b.aliasBlockEntities;
        b.aliasKeys = new long[0];
        b.aliasBlockEntities = new BlockEntity[0];
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != b.posKey && keys[i] != b.partnerKey) {
                unindex(b, keys[i]);
            }
            if (bes[i] instanceof LensWatchedFlag flag && bes[i] != b.flaggedBlockEntity && !flaggedByOther(bes[i], b)) {
                flag.flowscope$setWatched(false);
            }
        }
    }

    /** True if changes are being caught through setChanged() (no unreported change found recently). */
    static boolean isEventMode(WatchedBlock b, long now) {
        return !b.hasUnreported || now - b.lastUnreportedNanos > UNREPORTED_HOLD_NANOS;
    }
}
