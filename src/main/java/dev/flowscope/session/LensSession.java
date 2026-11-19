package dev.flowscope.session;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;

/**
 * One player's recording of one block. Holds only that player's counters and view state; reading the inventory is
 * done once per change by the shared {@link WatchedBlock}. Owned by {@link LensSessionManager} (server thread).
 */
public final class LensSession {
    final int id;
    final ServerPlayer player;
    final WatchedBlock block;
    /** The clicked face (shown in the UI). */
    final Direction face;

    /** Transfers: items in (increase) / out (decrease) per second. */
    final RollingRateWindow window = new RollingRateWindow();
    /** Processing in the block's own tick: produced (increase) / consumed (decrease) per second. */
    final RollingRateWindow processWindow = new RollingRateWindow();
    long totalIncrease;
    long totalDecrease;
    long totalProduced;
    long totalConsumed;

    // Fluids, in mB: kept separate from items everywhere (never summed).
    final RollingRateWindow fluidWindow = new RollingRateWindow();
    final RollingRateWindow fluidProcessWindow = new RollingRateWindow();
    long fluidIn;
    long fluidOut;
    long fluidMade;
    long fluidUsed;

    final long maxNanos;
    long startNanos;
    long lastSampleNanos;
    long nextUpdateTick;
    /** Reads since this recording (re)started; 0 means "collecting data". */
    int samples;
    boolean ended;

    // ---- view ----
    /** True while the player has the lens screen open; stats are only sent then. */
    boolean viewOpen = true;
    /** Sent again to reopen the screen for this recording. */
    dev.flowscope.network.OpenLensScreenPayload openPayload;

    // ---- per-item breakdown ----
    /** Null when the breakdown is disabled in the server config. */
    final ItemBreakdown breakdown;
    /** True while the client has a list expanded; lists are only sent then. */
    boolean breakdownRequested;
    /** Tick of the last stats packet, to rate-limit immediate updates after a toggle. */
    long lastStatsTick = Long.MIN_VALUE / 2;

    LensSession(int id, ServerPlayer player, WatchedBlock block, Direction face, long maxNanos, ItemBreakdown breakdown) {
        this.id = id;
        this.player = player;
        this.block = block;
        this.face = face;
        this.maxNanos = maxNanos;
        this.breakdown = breakdown;
    }

    void resetCounters(long nowNanos) {
        window.reset();
        processWindow.reset();
        totalIncrease = 0;
        totalDecrease = 0;
        totalProduced = 0;
        totalConsumed = 0;
        fluidWindow.reset();
        fluidProcessWindow.reset();
        fluidIn = 0;
        fluidOut = 0;
        fluidMade = 0;
        fluidUsed = 0;
        startNanos = nowNanos;
        lastSampleNanos = nowNanos;
        samples = 0;
        if (breakdown != null) {
            breakdown.clear();
        }
    }

    public int id() {
        return id;
    }
}
