package dev.flowscope.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import dev.flowscope.LensServerConfig;
import dev.flowscope.Flowscope;
import dev.flowscope.network.LensActionPayload;
import dev.flowscope.network.LensStatsPayload;
import dev.flowscope.network.OpenLensScreenPayload;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Owns every recording (one per player) and every watched block (one reader per block, shared by all recordings of
 * that block). Driven by a single server tick hook that returns immediately when nothing is recorded.
 * All methods run on the server thread.
 *
 * Scaling: there is no limit on the number of recordings by default. Work is bounded per tick instead: inventory
 * reads have per-block and global per-tick budgets; reads over budget are coalesced round-robin, so counts are
 * merged, never lost. Per-recording bookkeeping per tick is a few field comparisons.
 */
public final class LensSessionManager {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long LOG_INTERVAL_NANOS = 30L * NANOS_PER_SECOND;
    private static final int SLOW_SAFETY_NET_TICKS = 100;
    /** Own ticks in a row with neighbour activity but nothing measurable before the window shows a hint. */
    private static final int HIDDEN_TRANSFER_TICKS = 20;
    /** Player/timeout checks run once per second per recording (staggered by id). */
    private static final int CHECK_INTERVAL_TICKS = 20;

    /** Regular interval read (safety net); changes count as in/out. */
    static final int READ_TIMER = 0;
    /** Change-triggered read attributed to an outside cause; changes count as in/out. */
    static final int READ_TRANSFER = 1;
    /** Read at the end of the block's own tick with no neighbour involved; changes count as produced/consumed. */
    static final int READ_PROCESS = 2;

    private static final ArrayList<LensSession> SESSIONS = new ArrayList<>();
    private static final HashMap<UUID, LensSession> BY_PLAYER = new HashMap<>();
    private static final Int2ObjectOpenHashMap<LensSession> BY_ID = new Int2ObjectOpenHashMap<>();
    private static final HashMap<UUID, LastResult> LAST_RESULTS = new HashMap<>();
    private static int nextId = 1;
    private static long lastErrorLogNanos = Long.MIN_VALUE / 2;
    /** Rotates which block is considered first so read budgets are shared fairly. */
    private static int roundRobin;

    /** Final screen state of a finished recording, kept (one per player) so the screen can be reopened. */
    private record LastResult(OpenLensScreenPayload open, LensStatsPayload stats, ServerLevel level, BlockPos pos,
            Direction face, boolean wholeInventory) {
    }

    private LensSessionManager() {
    }

    // ---------------------------------------------------------------- start

    /**
     * Validates and starts a recording, or reopens the screen. On failure an action-bar message is sent.
     * Called from the lens item on the server.
     */
    public static boolean tryStart(ServerPlayer player, ServerLevel level, BlockPos pos, Direction face) {
        return start(player, level, pos, face, !player.isSecondaryUseActive(), false);
    }

    /**
     * @param wholeInventory read the whole inventory (normal use) instead of only the clicked face (sneak use)
     * @param restart        started by Restart in an ended window: failures always show their reason
     */
    private static boolean start(ServerPlayer player, ServerLevel level, BlockPos pos, Direction face,
            boolean wholeInventory, boolean restart) {
        if (player.isSpectator()) {
            return failOrReopen(player, "denied", restart);
        }
        if (!level.isLoaded(pos)) {
            return failOrReopen(player, "chunk_unloaded", restart);
        }
        if (!level.mayInteract(player, pos)) {
            return failOrReopen(player, "denied", restart);
        }
        int maxDistance = LensServerConfig.MAX_INTERACTION_DISTANCE.getAsInt();
        if (!player.isWithinBlockInteractionRange(pos, 1.0) || !withinDistance(player, pos, maxDistance)) {
            return failOrReopen(player, "out_of_range", restart);
        }
        BlockState state = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);
        // Vanilla container locks: never reveal a locked inventory to someone who could not open it.
        if (be instanceof BaseContainerBlockEntity container && !container.canOpen(player)) {
            return failOrReopen(player, "denied", restart);
        }

        // Clicking the block that is already being recorded just reopens the screen (no reset; Restart does that).
        LensSession current = activeFor(player);
        if (current != null && current.block.matches(level, pos, wholeInventory)) {
            reopen(player);
            return true;
        }

        // Item storage and fluid storage (tanks), looked up the same way: the whole block first (context null =
        // "all sides / internal" by NeoForge convention), so machines show input, fuel and output together and
        // per-face configuration does not matter; the clicked face as fallback. Sneak-use records only the clicked
        // face. Faces are never summed. A block qualifies if it has either kind.
        ResourceHandler<ItemResource> itemHandler;
        ResourceHandler<FluidResource> fluidHandler;
        Direction itemContext;
        Direction fluidContext;
        try {
            Context ic = pickContext(level, pos, state, be, Capabilities.Item.BLOCK, face, wholeInventory);
            itemContext = ic != null ? ic.side() : null;
            itemHandler = ic == null ? null : level.getCapability(Capabilities.Item.BLOCK, pos, state, be, itemContext);
            Context fc = pickContext(level, pos, state, be, Capabilities.Fluid.BLOCK, face, wholeInventory);
            fluidContext = fc != null ? fc.side() : null;
            fluidHandler = fc == null ? null : level.getCapability(Capabilities.Fluid.BLOCK, pos, state, be, fluidContext);
        } catch (RuntimeException e) {
            logError("capability lookup", pos, e);
            return failOrReopen(player, "error", restart);
        }
        if (itemHandler == null && fluidHandler == null) {
            return failOrReopen(player, "no_inventory", restart);
        }
        int maxSlots = LensServerConfig.MAX_VISIBLE_SLOTS.getAsInt();
        int size;
        try {
            size = (itemHandler != null ? itemHandler.size() : 0) + (fluidHandler != null ? fluidHandler.size() : 0);
        } catch (RuntimeException e) {
            logError("capability size", pos, e);
            return failOrReopen(player, "error", restart);
        }
        if ((itemHandler != null && itemHandler.size() > maxSlots)
                || (fluidHandler != null && fluidHandler.size() > maxSlots)) {
            return failOrReopen(player, "too_large", restart);
        }

        // One recording per player: a new eligible block replaces the current recording.
        if (current != null) {
            end(current, SessionState.REPLACED, false);
        }
        int serverLimit = LensServerConfig.MAX_SESSIONS_PER_SERVER.getAsInt();
        if (serverLimit > 0 && BY_PLAYER.size() >= serverLimit) {
            sweep();
            return fail(player, "server_full");
        }

        long now = System.nanoTime();
        long tick = level.getServer().getTickCount();
        int interval = LensServerConfig.SAMPLE_INTERVAL_TICKS.getAsInt();

        // Share the reader if someone already watches this inventory; otherwise create it and take the baseline.
        WatchedBlock block = EventSampling.find(level, pos, wholeInventory);
        if (block == null) {
            block = new WatchedBlock(level, pos, wholeInventory, state.getBlock(), interval,
                    itemHandler != null, itemContext, fluidHandler != null, fluidContext);
            try {
                if ((itemHandler != null && block.itemDiff.read(itemHandler, maxSlots) == SnapshotDiff.TOO_LARGE)
                        || (fluidHandler != null && block.fluidDiff.read(fluidHandler, maxSlots) == SnapshotDiff.TOO_LARGE)) {
                    block.removed = true;
                    return fail(player, "too_large");
                }
            } catch (RuntimeException e) {
                block.removed = true;
                logError("baseline snapshot", pos, e);
                return fail(player, "error");
            }
            block.lastSize = size;
            block.timerStartNanos = now;
            block.lastTimerNanos = now;
            block.nextTimerTick = tick + interval;
            EventSampling.register(block); // event sampling
        }

        int maxSeconds = LensServerConfig.MAX_SESSION_SECONDS.getAsInt();
        LensSession session = new LensSession(nextId(), player, block, face, maxSeconds * NANOS_PER_SECOND,
                LensServerConfig.ENABLE_PER_ITEM_BREAKDOWN.getAsBoolean()
                        ? new ItemBreakdown(LensServerConfig.MAX_TRACKED_ITEMS.getAsInt()) : null);
        session.resetCounters(now);
        session.nextUpdateTick = tick + LensServerConfig.CLIENT_UPDATE_INTERVAL_TICKS.getAsInt();
        block.addSession(session);
        SESSIONS.add(session);
        BY_PLAYER.put(player.getUUID(), session);
        BY_ID.put(session.id, session);
        LAST_RESULTS.remove(player.getUUID());
        sweep();

        boolean allSides = (block.items == null || block.items.context() == null)
                && (block.fluids == null || block.fluids.context() == null);
        session.openPayload = new OpenLensScreenPayload(session.id, displayName(state, be), face, allSides,
                interval, maxSeconds);
        PacketDistributor.sendToPlayer(player, session.openPayload);
        sendStats(session, SessionState.RECORDING);
        return true;
    }

    /**
     * Reopens the lens screen for the player's current recording, or for the last finished one.
     * Returns false if there is nothing to show. Called when the lens is used on air or on an ineligible block.
     */
    public static boolean reopen(ServerPlayer player) {
        LensSession s = activeFor(player);
        if (s != null) {
            s.viewOpen = true;
            s.breakdownRequested = false;
            PacketDistributor.sendToPlayer(player, s.openPayload);
            sendStats(s, SessionState.RECORDING);
            s.nextUpdateTick = s.block.level.getServer().getTickCount()
                    + LensServerConfig.CLIENT_UPDATE_INTERVAL_TICKS.getAsInt();
            return true;
        }
        LastResult last = LAST_RESULTS.get(player.getUUID());
        if (last != null) {
            PacketDistributor.sendToPlayer(player, last.open(), last.stats());
            return true;
        }
        return false;
    }

    private static LensSession activeFor(ServerPlayer player) {
        LensSession s = BY_PLAYER.get(player.getUUID());
        return s != null && !s.ended && s.player == player ? s : null;
    }

    /**
     * Ineligible target: reopen the existing recording's screen if there is one, otherwise explain why.
     * For Restart in an ended window the reason is always shown (the window is already open).
     */
    private static boolean failOrReopen(ServerPlayer player, String reason, boolean restart) {
        if (!restart && reopen(player)) {
            return false;
        }
        return fail(player, reason);
    }

    /** A chosen capability context; {@code side} null means the whole block. */
    private record Context(Direction side) {
    }

    /**
     * Chooses the capability context to read: the whole block if it exposes slots there, else the clicked face if
     * that exposes slots, else null (this kind of storage is not present). Sneak use (wholeInventory false) only
     * considers the clicked face.
     */
    private static <T extends ResourceHandler<?>> Context pickContext(ServerLevel level, BlockPos pos,
            BlockState state, BlockEntity be, net.neoforged.neoforge.capabilities.BlockCapability<T, Direction> cap,
            Direction face, boolean wholeInventory) {
        if (wholeInventory) {
            T h = level.getCapability(cap, pos, state, be, null);
            if (h != null && h.size() > 0) {
                return new Context(null);
            }
        }
        T h = level.getCapability(cap, pos, state, be, face);
        return h != null && h.size() > 0 ? new Context(face) : null;
    }

    private static Component displayName(BlockState state, BlockEntity be) {
        if (be instanceof Nameable nameable) {
            return nameable.getDisplayName();
        }
        return state.getBlock().getName();
    }

    private static boolean fail(ServerPlayer player, String reason) {
        player.sendOverlayMessage(Component.translatable("flowscope.start." + reason));
        return false;
    }

    private static int nextId() {
        int id = nextId++;
        if (nextId == Integer.MAX_VALUE) {
            nextId = 1;
        }
        return id;
    }

    private static boolean withinDistance(ServerPlayer player, BlockPos pos, int maxDistance) {
        Vec3 eye = player.getEyePosition();
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        double max = maxDistance + 0.5;
        return dx * dx + dy * dy + dz * dz <= max * max;
    }

    // ---------------------------------------------------------------- tick

    public static void onServerTick(ServerTickEvent.Post event) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        EventSampling.resetOwnTick(); // event sampling
        EventSampling.refreshLimits(); // event sampling
        long tick = event.getServer().getTickCount();
        long now = System.nanoTime();
        int maxSlots = EventSampling.maxSlots();

        // Blocks: coalesced change reads and safety-net reads, each within a per-tick budget, round-robin.
        ArrayList<WatchedBlock> blocks = EventSampling.BLOCKS;
        int n = blocks.size();
        if (n > 0) {
            int coalesceBudget = LensServerConfig.MAX_EVENT_READS_PER_TICK.getAsInt();
            int timerBudget = LensServerConfig.MAX_READS_PER_TICK.getAsInt();
            int start = Math.floorMod(roundRobin++, n);
            for (int k = 0; k < n; k++) {
                WatchedBlock b = blocks.get((start + k) % n);
                if (b.removed) {
                    continue;
                }
                SessionState reason = b.pendingEnd;
                if (reason == null && b.dirty && coalesceBudget > 0) {
                    coalesceBudget--;
                    b.dirty = false;
                    reason = read(b, now, maxSlots, READ_TRANSFER);
                }
                if (reason == null && tick >= b.nextTimerTick && timerBudget > 0) {
                    timerBudget--;
                    // Schedule from the current tick: a late read never causes a catch-up burst.
                    b.nextTimerTick = tick + timerInterval(b);
                    EventSampling.refreshAttachment(b); // event sampling
                    reason = read(b, now, maxSlots, READ_TIMER);
                }
                if (reason != null) {
                    endBlock(b, reason);
                }
            }
        }

        // Recordings: a few field comparisons each; player/timeout checks once per second (staggered).
        int updateInterval = LensServerConfig.CLIENT_UPDATE_INTERVAL_TICKS.getAsInt();
        for (int i = 0, size = SESSIONS.size(); i < size; i++) {
            LensSession s = SESSIONS.get(i);
            if (s.ended) {
                continue;
            }
            if ((tick + s.id) % CHECK_INTERVAL_TICKS == 0) {
                if (s.player.hasDisconnected() || s.player.isRemoved()) {
                    end(s, SessionState.CLOSED, false);
                    continue;
                }
                if (now - s.startNanos >= s.maxNanos) {
                    end(s, SessionState.TIMEOUT, true);
                    continue;
                }
            }
            if (s.viewOpen && tick >= s.nextUpdateTick) {
                s.nextUpdateTick = tick + updateInterval;
                sendStats(s, SessionState.RECORDING);
            }
        }
        sweep();
    }

    /** Ticks until the block's next safety-net read. */
    private static int timerInterval(WatchedBlock b) {
        if (b.everyTickFallback) {
            return 1;
        }
        // The block reports its changes and the safety net never found anything unreported: check less often.
        // Counting is unaffected, since reported changes are read as they happen.
        if (b.reportedChanges > 0 && !b.hasUnreported) {
            return Math.max(b.sampleIntervalTicks, SLOW_SAFETY_NET_TICKS);
        }
        return b.sampleIntervalTicks;
    }

    /**
     * Reads the block once and adds the changes to every recording of it. Returns a reason to end, or null.
     *
     * @param mode READ_TIMER, READ_TRANSFER or READ_PROCESS
     */
    static SessionState read(WatchedBlock b, long now, int maxSlots, int mode) {
        boolean timer = mode == READ_TIMER;
        boolean process = mode == READ_PROCESS;
        if (!b.level.isLoaded(b.pos)) {
            return SessionState.CHUNK_UNLOADED; // never force-load
        }
        if (b.level.getBlockState(b.pos).getBlock() != b.block) {
            return SessionState.TARGET_REMOVED;
        }
        // One read covers both kinds; the cause (transfer vs. processing) was decided once by the caller.
        b.reading = true;
        int itemResult = -1;
        int fluidResult = -1;
        try {
            ResourceHandler<ItemResource> ih = b.items != null ? b.items.getCapability() : null;
            ResourceHandler<FluidResource> fh = b.fluids != null ? b.fluids.getCapability() : null;
            if (ih == null && fh == null) {
                return SessionState.NO_INVENTORY;
            }
            int size = 0;
            if (ih != null) {
                itemResult = b.itemDiff.read(ih, maxSlots);
                if (itemResult == SnapshotDiff.TOO_LARGE) {
                    return SessionState.TOO_LARGE;
                }
                size += ih.size();
            }
            if (fh != null) {
                fluidResult = b.fluidDiff.read(fh, maxSlots);
                if (fluidResult == SnapshotDiff.TOO_LARGE) {
                    return SessionState.TOO_LARGE;
                }
                size += fh.size();
            }
            b.lastSize = size;
        } catch (RuntimeException e) {
            logError("snapshot", b.pos, e);
            return SessionState.ERROR;
        } finally {
            b.reading = false;
        }
        if (timer) {
            b.timerSamples++;
            b.lastTimerNanos = now;
        }
        boolean itemsCompared = itemResult == SnapshotDiff.COMPARED;
        boolean fluidsCompared = fluidResult == SnapshotDiff.COMPARED;
        if (!itemsCompared && !fluidsCompared) {
            return null;
        }
        long inc = itemsCompared ? b.itemDiff.lastIncrease() : 0;
        long dec = itemsCompared ? b.itemDiff.lastDecrease() : 0;
        long fInc = fluidsCompared ? b.fluidDiff.lastIncrease() : 0;
        long fDec = fluidsCompared ? b.fluidDiff.lastDecrease() : 0;
        boolean changed = (inc | dec | fInc | fDec) != 0;
        if (changed && !timer) {
            b.reportedChanges++;
        }
        if (changed && timer) {
            // A change no setChanged() reported: this block is only observable by snapshots.
            b.hasUnreported = true;
            b.lastUnreportedNanos = now;
            // General fallback for machines that change contents without setChanged(): read small
            // inventories every tick (still within maxReadsPerTick) so fewer changes cancel out.
            if (b.lastSize <= LensServerConfig.EVERY_TICK_FALLBACK_MAX_SLOTS.getAsInt()) {
                b.everyTickFallback = true;
            }
        }
        LensSession[] sessions = b.sessions;
        for (LensSession s : sessions) {
            if (!s.ended) {
                apply(s, b, process, itemsCompared && (inc | dec) != 0, fluidsCompared && (fInc | fDec) != 0,
                        inc, dec, fInc, fDec, now);
            }
        }
        return null;
    }

    // Reused working maps for attributed reads (server thread only).
    private static final Object2LongOpenHashMap<Resource> ATTR_IN = new Object2LongOpenHashMap<>();
    private static final Object2LongOpenHashMap<Resource> ATTR_OUT = new Object2LongOpenHashMap<>();
    private static final Object2LongOpenHashMap<Resource> ATTR_RESIDUAL = new Object2LongOpenHashMap<>();

    /**
     * End of a machine's own tick in which it or its neighbours changed. Reads the machine and every neighbour that
     * changed during the tick, then splits per item/fluid identity:
     * <ul>
     * <li>a neighbour's loss went into the machine (Input), a neighbour's gain came out of it (Output);</li>
     * <li>the rest of the machine's own change is its processing: machine change = Input - Output + Made - Used up,
     * so Made - Used up = machine change + neighbours' net change (positive = Made, negative = Used up).</li>
     * </ul>
     * If something else changed during the tick that cannot be measured (a non-adjacent block, a neighbour without
     * storage, a neighbour whose shadow was outdated), the rest counts as transfers instead (the plain rule).
     * This catches machines that process, auto-push and auto-pull within one tick (whose own inventory nets to 0).
     */
    static SessionState readAttributed(WatchedBlock b, long now, int maxSlots, boolean foreign) {
        if (!b.level.isLoaded(b.pos)) {
            return SessionState.CHUNK_UNLOADED;
        }
        if (b.level.getBlockState(b.pos).getBlock() != b.block) {
            return SessionState.TARGET_REMOVED;
        }
        boolean itemsCompared;
        boolean fluidsCompared;
        b.reading = true;
        try {
            ResourceHandler<ItemResource> ih = b.items != null ? b.items.getCapability() : null;
            ResourceHandler<FluidResource> fh = b.fluids != null ? b.fluids.getCapability() : null;
            if (ih == null && fh == null) {
                return SessionState.NO_INVENTORY;
            }
            int itemResult = ih != null ? b.itemDiff.read(ih, maxSlots) : -1;
            int fluidResult = fh != null ? b.fluidDiff.read(fh, maxSlots) : -1;
            if (itemResult == SnapshotDiff.TOO_LARGE || fluidResult == SnapshotDiff.TOO_LARGE) {
                return SessionState.TOO_LARGE;
            }
            itemsCompared = itemResult == SnapshotDiff.COMPARED;
            fluidsCompared = fluidResult == SnapshotDiff.COMPARED;
        } catch (RuntimeException e) {
            logError("snapshot", b.pos, e);
            return SessionState.ERROR;
        } finally {
            b.reading = false;
        }

        ATTR_IN.clear();
        ATTR_OUT.clear();
        ATTR_RESIDUAL.clear();
        if (itemsCompared) {
            addDeltas(ATTR_RESIDUAL, b.itemDiff.deltas());
        }
        if (fluidsCompared) {
            addDeltas(ATTR_RESIDUAL, b.fluidDiff.deltas());
        }
        // Exact transfers seen through counting handlers (committed only). A far-away change during the tick is then
        // the consequence of such a transfer (e.g. a pipe passing items on), not an unmeasured move.
        TransferRecorder rec = b.recorder;
        int captured = rec.committedCount();
        for (int i = 0; i < captured; i++) {
            Resource r = rec.resource(i);
            long amount = rec.amount(i);
            if (rec.direction(i) == TransferRecorder.IN) {
                ATTR_IN.addTo(r, amount);
                ATTR_RESIDUAL.addTo(r, -amount);
            } else {
                ATTR_OUT.addTo(r, amount);
                ATTR_RESIDUAL.addTo(r, amount);
            }
        }
        boolean unmeasured = foreign && captured == 0;
        boolean interacted = foreign || captured > 0;
        Neighbour[] ns = b.neighbours;
        if (ns != null) {
            for (Neighbour n : ns) {
                if (!n.changedThisTick) {
                    continue;
                }
                n.changedThisTick = false;
                interacted = true;
                boolean wasStale = n.stale;
                n.stale = !n.read(maxSlots);
                if (captured > 0 && rec.touched(n.posKey)) {
                    continue; // already counted exactly through its handler; the read only refreshed the shadow
                }
                if (wasStale || n.stale) {
                    unmeasured = true; // its change during this tick cannot be told apart from earlier changes
                    continue;
                }
                addNeighbourDeltas(n.itemDiff);
                addNeighbourDeltas(n.fluidDiff);
            }
        }
        rec.reset();

        // Totals per category and kind (items vs. mB), computed once for all recordings of this block.
        long[] totals = new long[8]; // itemIn, itemOut, itemMade, itemUsed, fluidIn, fluidOut, fluidMade, fluidUsed
        sumInto(ATTR_IN, totals, 0, 4, true);
        sumInto(ATTR_OUT, totals, 1, 5, true);
        boolean residualIsProcess = !unmeasured;
        for (ObjectIterator<Object2LongMap.Entry<Resource>> it = ATTR_RESIDUAL.object2LongEntrySet().fastIterator(); it.hasNext(); ) {
            Object2LongMap.Entry<Resource> e = it.next();
            long v = e.getLongValue();
            boolean fluid = e.getKey() instanceof FluidResource;
            if (v > 0) {
                int idx = (residualIsProcess ? 2 : 0) + (fluid ? 4 : 0);
                totals[idx] = SnapshotDiff.saturatedAdd(totals[idx], v);
            } else if (v < 0) {
                int idx = (residualIsProcess ? 3 : 1) + (fluid ? 4 : 0);
                totals[idx] = SnapshotDiff.saturatedAdd(totals[idx], v == Long.MIN_VALUE ? Long.MAX_VALUE : -v);
            }
        }
        boolean counted = false;
        for (long t : totals) {
            counted |= t != 0;
        }
        if (counted) {
            b.reportedChanges++;
            b.silentInteractTicks = 0;
        } else if (interacted) {
            b.silentInteractTicks++; // it moved things around, but nothing measurable changed
        }

        for (LensSession s : b.sessions) {
            if (s.ended) {
                continue;
            }
            s.samples++;
            long second = (now - s.startNanos) / NANOS_PER_SECOND;
            s.totalIncrease = SnapshotDiff.saturatedAdd(s.totalIncrease, totals[0]);
            s.totalDecrease = SnapshotDiff.saturatedAdd(s.totalDecrease, totals[1]);
            s.totalProduced = SnapshotDiff.saturatedAdd(s.totalProduced, totals[2]);
            s.totalConsumed = SnapshotDiff.saturatedAdd(s.totalConsumed, totals[3]);
            s.fluidIn = SnapshotDiff.saturatedAdd(s.fluidIn, totals[4]);
            s.fluidOut = SnapshotDiff.saturatedAdd(s.fluidOut, totals[5]);
            s.fluidMade = SnapshotDiff.saturatedAdd(s.fluidMade, totals[6]);
            s.fluidUsed = SnapshotDiff.saturatedAdd(s.fluidUsed, totals[7]);
            s.window.add(second, totals[0], totals[1]);
            s.processWindow.add(second, totals[2], totals[3]);
            s.fluidWindow.add(second, totals[4], totals[5]);
            s.fluidProcessWindow.add(second, totals[6], totals[7]);
            if (counted && s.breakdown != null) {
                recordMap(s.breakdown, ATTR_IN, ItemBreakdown.IN, ItemBreakdown.IN);
                recordMap(s.breakdown, ATTR_OUT, ItemBreakdown.OUT, ItemBreakdown.OUT);
                recordMap(s.breakdown, ATTR_RESIDUAL,
                        residualIsProcess ? ItemBreakdown.PRODUCED : ItemBreakdown.IN,
                        residualIsProcess ? ItemBreakdown.CONSUMED : ItemBreakdown.OUT);
            }
            s.lastSampleNanos = now;
        }
        return null;
    }

    private static void addDeltas(Object2LongOpenHashMap<Resource> into, Object2LongOpenHashMap<Resource> deltas) {
        for (ObjectIterator<Object2LongMap.Entry<Resource>> it = deltas.object2LongEntrySet().fastIterator(); it.hasNext(); ) {
            Object2LongMap.Entry<Resource> e = it.next();
            if (e.getLongValue() != 0) {
                into.addTo(e.getKey(), e.getLongValue());
            }
        }
    }

    /** A neighbour's loss is the machine's Input, its gain the machine's Output; both also balance the residual. */
    private static void addNeighbourDeltas(SnapshotDiff<?> diff) {
        if (diff.lastIncrease() == 0 && diff.lastDecrease() == 0) {
            return;
        }
        for (ObjectIterator<Object2LongMap.Entry<Resource>> it = diff.deltas().object2LongEntrySet().fastIterator(); it.hasNext(); ) {
            Object2LongMap.Entry<Resource> e = it.next();
            long d = e.getLongValue();
            if (d < 0) {
                ATTR_IN.addTo(e.getKey(), d == Long.MIN_VALUE ? Long.MAX_VALUE : -d);
            } else if (d > 0) {
                ATTR_OUT.addTo(e.getKey(), d);
            } else {
                continue;
            }
            ATTR_RESIDUAL.addTo(e.getKey(), d);
        }
    }

    /** Adds positive values of a map to totals[itemIdx] or totals[fluidIdx]. */
    private static void sumInto(Object2LongOpenHashMap<Resource> map, long[] totals, int itemIdx, int fluidIdx,
            boolean positive) {
        for (ObjectIterator<Object2LongMap.Entry<Resource>> it = map.object2LongEntrySet().fastIterator(); it.hasNext(); ) {
            Object2LongMap.Entry<Resource> e = it.next();
            long v = e.getLongValue();
            if (v > 0) {
                int idx = e.getKey() instanceof FluidResource ? fluidIdx : itemIdx;
                totals[idx] = SnapshotDiff.saturatedAdd(totals[idx], v);
            }
        }
    }

    /** Positive values go to {@code posCategory}, negative ones (as amounts) to {@code negCategory}. */
    private static void recordMap(ItemBreakdown breakdown, Object2LongOpenHashMap<Resource> map, int posCategory,
            int negCategory) {
        for (ObjectIterator<Object2LongMap.Entry<Resource>> it = map.object2LongEntrySet().fastIterator(); it.hasNext(); ) {
            Object2LongMap.Entry<Resource> e = it.next();
            long v = e.getLongValue();
            if (v > 0) {
                breakdown.record(posCategory, e.getKey(), v);
            } else if (v < 0) {
                breakdown.record(negCategory, e.getKey(), v == Long.MIN_VALUE ? Long.MAX_VALUE : -v);
            }
        }
    }

    /** Adds one read's changes (items and fluids, kept apart) to one recording. */
    private static void apply(LensSession s, WatchedBlock b, boolean process, boolean itemsChanged,
            boolean fluidsChanged, long inc, long dec, long fInc, long fDec, long now) {
        s.samples++;
        long second = (now - s.startNanos) / NANOS_PER_SECOND;
        int incCategory = process ? ItemBreakdown.PRODUCED : ItemBreakdown.IN;
        int decCategory = process ? ItemBreakdown.CONSUMED : ItemBreakdown.OUT;
        if (process) {
            s.totalProduced = SnapshotDiff.saturatedAdd(s.totalProduced, inc);
            s.totalConsumed = SnapshotDiff.saturatedAdd(s.totalConsumed, dec);
            s.processWindow.add(second, inc, dec);
            s.window.advanceTo(second);
            s.fluidMade = SnapshotDiff.saturatedAdd(s.fluidMade, fInc);
            s.fluidUsed = SnapshotDiff.saturatedAdd(s.fluidUsed, fDec);
            s.fluidProcessWindow.add(second, fInc, fDec);
            s.fluidWindow.advanceTo(second);
        } else {
            s.totalIncrease = SnapshotDiff.saturatedAdd(s.totalIncrease, inc);
            s.totalDecrease = SnapshotDiff.saturatedAdd(s.totalDecrease, dec);
            s.window.add(second, inc, dec);
            s.fluidIn = SnapshotDiff.saturatedAdd(s.fluidIn, fInc);
            s.fluidOut = SnapshotDiff.saturatedAdd(s.fluidOut, fDec);
            s.fluidWindow.add(second, fInc, fDec);
        }
        if (s.breakdown != null) {
            if (itemsChanged) {
                b.itemDiff.recordInto(s.breakdown, incCategory, decCategory);
            }
            if (fluidsChanged) {
                b.fluidDiff.recordInto(s.breakdown, incCategory, decCategory);
            }
        }
        s.lastSampleNanos = now;
    }

    /** Items-or-mB per minute from a window and total, as in the header rows. */
    private static double perMin(RollingRateWindow w, long total, boolean increase, boolean warming, double minutes) {
        if (warming) {
            return minutes > 0 ? total / minutes : 0;
        }
        return increase ? w.sumIncrease() : w.sumDecrease();
    }

    // ---------------------------------------------------------------- stats

    private static LensStatsPayload buildStats(LensSession s, SessionState state, boolean includeLists) {
        WatchedBlock b = s.block;
        long now = System.nanoTime();
        long elapsed = Math.min(now - s.startNanos, s.maxNanos);
        long measured = s.lastSampleNanos - s.startNanos;
        long windowNanos = RollingRateWindow.SECONDS * NANOS_PER_SECOND;

        double[] rates = new double[8]; // items in/out/made/used, then fluids (mB) in/out/made/used
        boolean warming = measured < windowNanos;
        if (s.samples > 0 && measured > 0) {
            double minutes = measured / (60.0 * NANOS_PER_SECOND);
            if (!warming) {
                // Sum over the last 60 one-second buckets = per minute.
                long second = (s.lastSampleNanos - s.startNanos) / NANOS_PER_SECOND;
                s.window.advanceTo(second);
                s.processWindow.advanceTo(second);
                s.fluidWindow.advanceTo(second);
                s.fluidProcessWindow.advanceTo(second);
            }
            rates[0] = perMin(s.window, s.totalIncrease, true, warming, minutes);
            rates[1] = perMin(s.window, s.totalDecrease, false, warming, minutes);
            rates[2] = perMin(s.processWindow, s.totalProduced, true, warming, minutes);
            rates[3] = perMin(s.processWindow, s.totalConsumed, false, warming, minutes);
            rates[4] = perMin(s.fluidWindow, s.fluidIn, true, warming, minutes);
            rates[5] = perMin(s.fluidWindow, s.fluidOut, false, warming, minutes);
            rates[6] = perMin(s.fluidProcessWindow, s.fluidMade, true, warming, minutes);
            rates[7] = perMin(s.fluidProcessWindow, s.fluidUsed, false, warming, minutes);
        }
        boolean processing = s.totalProduced != 0 || s.totalConsumed != 0 || s.fluidMade != 0 || s.fluidUsed != 0;
        long timerSpan = b.lastTimerNanos - b.timerStartNanos;
        int effectiveMs = b.timerSamples > 0 ? (int) Math.min(timerSpan / b.timerSamples / 1_000_000L, Integer.MAX_VALUE) : 0;
        boolean eventMode = EventSampling.isEventMode(b, now); // event sampling

        // Graph: item transfers; for a block without item storage (a tank), fluid transfers in mB.
        boolean hasItems = b.items != null;
        boolean hasFluids = b.fluids != null;
        RollingRateWindow graphWindow = hasItems ? s.window : s.fluidWindow;
        int[] inc = new int[RollingRateWindow.SECONDS];
        int[] dec = new int[RollingRateWindow.SECONDS];
        if (s.samples > 0) {
            graphWindow.advanceTo(elapsed / NANOS_PER_SECOND);
        }
        graphWindow.copyOrdered(inc, dec);

        // Per-item lists only while the player has one expanded (or for the stored final result).
        List<ItemBreakdown.Summary> items = LensStatsPayload.NO_ITEMS;
        if (s.breakdown != null && includeLists) {
            ItemBreakdown.Summary[] arr = new ItemBreakdown.Summary[ItemBreakdown.LISTS];
            for (int c = 0; c < ItemBreakdown.LISTS; c++) {
                arr[c] = s.breakdown.summarize(c, LensStatsPayload.MAX_ITEM_ENTRIES);
            }
            items = List.of(arr);
        }

        return new LensStatsPayload(
                s.id, state.ordinal(),
                (int) Math.min(elapsed / 1_000_000L, Integer.MAX_VALUE),
                (int) Math.min(s.maxNanos / 1_000_000L, Integer.MAX_VALUE),
                s.samples, warming, rates[0], rates[1], effectiveMs, eventMode, inc, dec,
                s.breakdown != null, processing, rates[2], rates[3], items,
                hasItems, hasFluids, rates[4], rates[5], rates[6], rates[7],
                b.silentInteractTicks >= HIDDEN_TRANSFER_TICKS);
    }

    /** Sends current stats, but only while the player has the screen open. */
    private static void sendStats(LensSession s, SessionState state) {
        if (!s.viewOpen || s.player.hasDisconnected()) {
            return;
        }
        s.lastStatsTick = s.block.level.getServer().getTickCount();
        PacketDistributor.sendToPlayer(s.player, buildStats(s, state, s.breakdownRequested));
    }

    // ---------------------------------------------------------------- end

    /** Ends every recording of a block (block-level reason: unloaded, removed, unreadable, ...). */
    private static void endBlock(WatchedBlock b, SessionState reason) {
        LensSession[] sessions = b.sessions;
        for (LensSession s : sessions) {
            end(s, reason, true);
        }
        if (!b.removed) {
            EventSampling.unregister(b); // event sampling
        }
    }

    /**
     * Ends one recording, keeps its final result so the screen can be reopened, and tells the player why: in the
     * open screen, or as an action-bar message if the screen is closed. The block's reader is released when its
     * last recording ends. Lists are cleaned up by {@link #sweep()}.
     */
    private static void end(LensSession s, SessionState reason, boolean notify) {
        if (s.ended) {
            return;
        }
        boolean online = !s.player.hasDisconnected() && !s.player.isRemoved();
        if (online && reason != SessionState.REPLACED && reason != SessionState.CLOSED
                && reason != SessionState.SERVER_STOPPING && s.openPayload != null) {
            // Final result with all lists, bounded (top entries per list), one per player.
            LensStatsPayload last = buildStats(s, reason, true);
            WatchedBlock b = s.block;
            LAST_RESULTS.put(s.player.getUUID(), new LastResult(s.openPayload, last, b.level, b.pos, s.face,
                    b.wholeInventory));
            if (notify) {
                if (s.viewOpen) {
                    PacketDistributor.sendToPlayer(s.player, last);
                } else if (reason != SessionState.STOPPED) {
                    s.player.sendOverlayMessage(Component.translatable("flowscope.ended",
                            Component.translatable(reason.reasonKey())));
                }
            }
        }
        s.ended = true;
        BY_ID.remove(s.id);
        if (BY_PLAYER.get(s.player.getUUID()) == s) {
            BY_PLAYER.remove(s.player.getUUID());
        }
        WatchedBlock b = s.block;
        b.removeSession(s);
        if (b.sessions.length == 0 && !b.removed) {
            EventSampling.unregister(b); // event sampling
        }
    }

    /** Drops ended recordings and released blocks from the lists (outside of any iteration). */
    private static void sweep() {
        for (int i = SESSIONS.size() - 1; i >= 0; i--) {
            if (SESSIONS.get(i).ended) {
                SESSIONS.remove(i);
            }
        }
        EventSampling.sweep();
    }

    // ---------------------------------------------------------------- events / packets

    public static void handleAction(LensActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        LensSession s = BY_ID.get(payload.sessionId());
        if (s == null || s.ended) {
            // Restart in the window of a finished recording: start a fresh one on the same block, re-validated
            // exactly like a new right-click (reach, permissions, inventory).
            LastResult last = LAST_RESULTS.get(player.getUUID());
            if (payload.action() == LensActionPayload.RESTART && last != null
                    && last.open().sessionId() == payload.sessionId()) {
                start(player, last.level(), last.pos(), last.face(), last.wholeInventory(), true);
            }
            return;
        }
        // Only the owner may control a recording; anything else is ignored.
        if (s.player != player) {
            return;
        }
        switch (payload.action()) {
            case LensActionPayload.STOP -> end(s, SessionState.STOPPED, true);
            case LensActionPayload.CLOSE -> {
                // Screen closed: the recording keeps running; stop sending stats until it is reopened.
                s.viewOpen = false;
                s.breakdownRequested = false;
            }
            case LensActionPayload.RESTART -> restart(s);
            case LensActionPayload.LISTS_SHOWN, LensActionPayload.LISTS_HIDDEN -> {
                boolean show = payload.action() == LensActionPayload.LISTS_SHOWN;
                if (s.breakdown != null && show != s.breakdownRequested) {
                    s.breakdownRequested = show;
                    if (show) {
                        // Send the lists soon, but never more than one extra packet per 10 ticks.
                        long tick = s.block.level.getServer().getTickCount();
                        s.nextUpdateTick = Math.min(s.nextUpdateTick, Math.max(tick + 1, s.lastStatsTick + 10));
                    }
                }
            }
            default -> {
            }
        }
        sweep();
    }

    private static void restart(LensSession s) {
        long now = System.nanoTime();
        s.resetCounters(now);
        long tick = s.block.level.getServer().getTickCount();
        // The shared reader's baseline is always current; just make the first numbers appear soon.
        s.block.nextTimerTick = Math.min(s.block.nextTimerTick, tick + 1);
        s.nextUpdateTick = tick + LensServerConfig.CLIENT_UPDATE_INTERVAL_TICKS.getAsInt();
        sendStats(s, SessionState.RECORDING);
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_RESULTS.remove(event.getEntity().getUUID());
        LensSession s = BY_PLAYER.get(event.getEntity().getUUID());
        if (s != null) {
            end(s, SessionState.CLOSED, false);
            sweep();
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        for (int i = 0; i < SESSIONS.size(); i++) {
            SESSIONS.get(i).ended = true;
        }
        EventSampling.clear(); // event sampling
        SESSIONS.clear();
        BY_PLAYER.clear();
        BY_ID.clear();
        LAST_RESULTS.clear();
        nextId = 1;
    }

    private static void logError(String what, BlockPos pos, RuntimeException e) {
        long now = System.nanoTime();
        if (now - lastErrorLogNanos >= LOG_INTERVAL_NANOS) {
            lastErrorLogNanos = now;
            Flowscope.LOGGER.warn("Flowscope: inventory at {} failed during {}; recording stopped. "
                    + "(Further errors are suppressed for 30 s.)", pos, what, e);
        }
    }
}
