package dev.flowscope;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side resource limits for lens recordings (spec section 4).
 * Defaults are starting points that still need profiling; the upper bounds are hard caps.
 */
public final class LensServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue SAMPLE_INTERVAL_TICKS = BUILDER
            .comment("Ticks between inventory snapshots of a recorded block. 20 = about one read per second.")
            .defineInRange("sampleIntervalTicks", 20, 10, 200);

    public static final ModConfigSpec.IntValue MAX_SESSION_SECONDS = BUILDER
            .comment("A recording stops automatically after this many seconds.")
            .defineInRange("maxSessionSeconds", 300, 10, 1800);

    public static final ModConfigSpec.IntValue MAX_SESSIONS_PER_SERVER = BUILDER
            .comment("Optional cap on active recordings on the whole server (one per player). 0 = unlimited.",
                    "Not needed for performance: inventory reads are budgeted per tick (see the read limits below).")
            .defineInRange("maxSessionsPerServer", 0, 0, 100000);

    public static final ModConfigSpec.IntValue MAX_READS_PER_TICK = BUILDER
            .comment("Maximum inventory snapshots taken in one server tick across all recordings. Further reads wait for the next tick.")
            .defineInRange("maxReadsPerTick", 2, 1, 8);

    public static final ModConfigSpec.IntValue MAX_EVENT_READS_PER_SESSION_TICK = BUILDER
            .comment("Event-based mode: maximum snapshots one recording takes in a tick when its block reports changes.",
                    "Further changes in that tick are combined into one read at the end of the tick.")
            .defineInRange("maxEventReadsPerSessionTick", 8, 1, 64);

    public static final ModConfigSpec.IntValue MAX_EVENT_READS_PER_TICK = BUILDER
            .comment("Event-based mode: maximum change-triggered snapshots per tick across all recordings.")
            .defineInRange("maxEventReadsPerTick", 32, 1, 256);

    public static final ModConfigSpec.IntValue EVERY_TICK_FALLBACK_MAX_SLOTS = BUILDER
            .comment("If a recorded block changes without reporting it (no setChanged), inventories with at most this many",
                    "slots are read every tick instead of every sampleIntervalTicks (still limited by maxReadsPerTick). 0 = off.")
            .defineInRange("everyTickFallbackMaxSlots", 64, 0, 256);

    public static final ModConfigSpec.BooleanValue COUNT_NEIGHBOUR_TRANSFERS = BUILDER
            .comment("While a recorded machine runs its own tick, count what it inserts into / extracts from its 6 adjacent",
                    "blocks by handing it a pass-through wrapper around their item/fluid handlers (committed transfers only).",
                    "Makes auto-input/auto-output visible even with infinite sources or voiding sinks. Only affects the",
                    "recorded machine during its own tick. Set to false if a machine behaves differently while recorded.")
            .define("countNeighbourTransfers", true);

    public static final ModConfigSpec.IntValue MAX_VISIBLE_SLOTS = BUILDER
            .comment("Inventories exposing more slots than this are rejected instead of scanned.")
            .defineInRange("maxVisibleSlots", 256, 1, 4096);

    public static final ModConfigSpec.IntValue CLIENT_UPDATE_INTERVAL_TICKS = BUILDER
            .comment("Ticks between statistics updates sent to an open lens screen.")
            .defineInRange("clientUpdateIntervalTicks", 40, 20, 200);

    public static final ModConfigSpec.IntValue MAX_INTERACTION_DISTANCE = BUILDER
            .comment("Maximum distance in blocks between the player and the recorded block.")
            .defineInRange("maxInteractionDistance", 8, 1, 32);

    public static final ModConfigSpec.BooleanValue ENABLE_PER_ITEM_BREAKDOWN = BUILDER
            .comment("Track which items went in and out, shown as collapsible lists in the lens screen.",
                    "Lists are only sent while the player has a list expanded.")
            .define("enablePerItemBreakdown", true);

    public static final ModConfigSpec.IntValue MAX_TRACKED_ITEMS = BUILDER
            .comment("Per-item breakdown: maximum distinct items tracked per direction per recording. Further items are summed as 'other items'.")
            .defineInRange("maxTrackedItems", 256, 16, 1024);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private LensServerConfig() {
    }
}
