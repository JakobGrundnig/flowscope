package dev.flowscope.session;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * One watched block. Owns the readers for its item storage and/or fluid storage; every recording of the same block
 * shares them, so several players watching one machine do not multiply the reads. One reported change reads both
 * kinds, and the in/out vs. processing decision is made once and applied to both.
 * Owned and mutated only by {@link LensSessionManager} / {@link EventSampling} on the server thread.
 */
public final class WatchedBlock {
    final ServerLevel level;
    final BlockPos pos;
    final long posKey;
    /** Whole-block view (normal use) vs. clicked-face view (sneak use). */
    final boolean wholeInventory;
    final Block block;
    final Thread serverThread = Thread.currentThread();

    /** Item storage, or null if the block has none. Context null = whole inventory, otherwise one face. */
    final BlockCapabilityCache<ResourceHandler<ItemResource>, Direction> items;
    final SnapshotDiff<ItemResource> itemDiff = new SnapshotDiff<>();
    /** Fluid storage (tanks), or null if the block has none. */
    final BlockCapabilityCache<ResourceHandler<FluidResource>, Direction> fluids;
    final SnapshotDiff<FluidResource> fluidDiff = new SnapshotDiff<>();

    /** Recordings sharing this reader (copy-on-write; changed only when a recording starts or ends). */
    LensSession[] sessions = new LensSession[0];
    boolean removed;

    // ---- change detection ----
    /** Other half of a double chest (BlockPos.asLong), or Long.MIN_VALUE. */
    long partnerKey = Long.MIN_VALUE;
    /** Block entity carrying the "watched" flag for the tick hook. */
    BlockEntity flaggedBlockEntity;
    /** True while reading, so a setChanged() caused by reading is ignored. */
    boolean reading;
    /** Changed during its own tick; read once when that tick ends. */
    boolean ownChanged;
    /** A change was reported over budget; read later (coalesced). Never dropped, only merged. */
    boolean dirty;
    int eventReads;
    long eventTick = -1;
    /** Set when a change-triggered read failed; the tick handler ends the recordings (never from foreign code). */
    SessionState pendingEnd;

    // ---- neighbour attribution (auto-input/auto-output during the block's own tick) ----
    /** Adjacent inventories, created once the block changes during its own tick (null = not yet / not a machine). */
    Neighbour[] neighbours;
    /** The block changed during its last own tick: keep neighbour shadows fresh before its next tick. */
    boolean activeMachine;
    /** Own ticks in a row in which the block interacted with neighbours but nothing could be counted. */
    int silentInteractTicks;
    /** Block entities next to the block when the neighbours were created (detects placed/removed neighbours). */
    int neighbourBlockEntities;
    /**
     * The container behind the item handler when it is not this block's own block entity (a shared/linked storage,
     * e.g. a frequency-linked chest). Its committed changes are reported via VanillaContainerWrapperMixin. Null if none.
     */
    net.minecraft.world.Container linkedContainer;
    /** Transfers to/from adjacent blocks seen through counting handlers during the current own tick. */
    final TransferRecorder recorder = new TransferRecorder();

    Neighbour neighbour(long key) {
        Neighbour[] ns = neighbours;
        if (ns != null) {
            for (Neighbour n : ns) {
                if (n.posKey == key) {
                    return n;
                }
            }
        }
        return null;
    }

    // ---- safety-net timer ----
    long nextTimerTick;
    int sampleIntervalTicks;
    boolean everyTickFallback;
    boolean hasUnreported;
    long lastUnreportedNanos;
    /** Change-triggered reads that found a change: the block reports its own changes. */
    int reportedChanges;
    int timerSamples;
    long timerStartNanos;
    long lastTimerNanos;
    /** Slots + tanks seen at the last read. */
    int lastSize;

    WatchedBlock(ServerLevel level, BlockPos pos, boolean wholeInventory, Block block, int sampleIntervalTicks,
            boolean hasItems, Direction itemContext, boolean hasFluids, Direction fluidContext) {
        this.level = level;
        this.pos = pos.immutable();
        this.posKey = this.pos.asLong();
        this.wholeInventory = wholeInventory;
        this.block = block;
        this.sampleIntervalTicks = sampleIntervalTicks;
        // Listeners stay registered only while the block is watched (isValid returns false afterwards).
        this.items = hasItems ? BlockCapabilityCache.create(Capabilities.Item.BLOCK, level, this.pos, itemContext,
                () -> !removed, () -> { }) : null;
        this.fluids = hasFluids ? BlockCapabilityCache.create(Capabilities.Fluid.BLOCK, level, this.pos, fluidContext,
                () -> !removed, () -> { }) : null;
    }

    boolean matches(ServerLevel level, BlockPos pos, boolean wholeInventory) {
        return this.level == level && this.posKey == pos.asLong() && this.wholeInventory == wholeInventory;
    }

    void addSession(LensSession s) {
        LensSession[] next = java.util.Arrays.copyOf(sessions, sessions.length + 1);
        next[sessions.length] = s;
        sessions = next;
    }

    void removeSession(LensSession s) {
        int idx = -1;
        for (int i = 0; i < sessions.length; i++) {
            if (sessions[i] == s) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return;
        }
        LensSession[] next = new LensSession[sessions.length - 1];
        System.arraycopy(sessions, 0, next, 0, idx);
        System.arraycopy(sessions, idx + 1, next, idx, sessions.length - idx - 1);
        sessions = next;
    }
}
