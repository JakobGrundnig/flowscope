package dev.flowscope.session;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.neoforged.neoforge.transfer.resource.Resource;

/**
 * Per-item and per-fluid totals over one recording (items counted in items, fluids in mB; never summed together), in four categories: items that entered from outside (IN), left to the
 * outside (OUT), were made by the block itself (PRODUCED) or used up by it (CONSUMED). Each item identity
 * (item + components) has one entry per category. Distinct items are capped; the rest goes into an "other" amount.
 * Server thread only.
 */
public final class ItemBreakdown {
    public static final int IN = 0;
    public static final int OUT = 1;
    public static final int PRODUCED = 2;
    public static final int CONSUMED = 3;
    /** Derived when summarising: in + produced - out - consumed per item (signed). Not stored. */
    public static final int NET = 4;
    /** Stored categories. */
    public static final int CATEGORIES = 4;
    /** Lists sent to the client (stored categories plus NET). */
    public static final int LISTS = 5;

    /** One aggregated line of the summary. */
    /** {@code resource} is an ItemResource (counted in items) or a FluidResource (counted in mB). */
    public record Entry(Resource resource, long amount) {
    }

    /** Top entries plus what did not fit. */
    public record Summary(List<Entry> entries, int moreItems, long moreAmount) {
        public static final Summary EMPTY = new Summary(List.of(), 0, 0);
    }

    @SuppressWarnings("unchecked")
    private final Object2LongOpenHashMap<Resource>[] maps = new Object2LongOpenHashMap[CATEGORIES];
    private final long[] otherAmount = new long[CATEGORIES];
    private final boolean[] otherUsed = new boolean[CATEGORIES];
    private final int maxTracked;

    public ItemBreakdown(int maxTracked) {
        this.maxTracked = maxTracked;
        for (int i = 0; i < CATEGORIES; i++) {
            maps[i] = new Object2LongOpenHashMap<>();
        }
    }

    public void clear() {
        for (int i = 0; i < CATEGORIES; i++) {
            maps[i].clear();
            otherAmount[i] = 0;
            otherUsed[i] = false;
        }
    }

    /** Adds a positive amount to one category. */
    void record(int category, Resource item, long amount) {
        if (amount <= 0) {
            return;
        }
        Object2LongOpenHashMap<Resource> map = maps[category];
        if (map.containsKey(item) || map.size() < maxTracked) {
            map.put(item, SnapshotDiff.saturatedAdd(map.getLong(item), amount));
        } else {
            otherAmount[category] = SnapshotDiff.saturatedAdd(otherAmount[category], amount);
            otherUsed[category] = true;
        }
    }

    public Summary summarize(int category, int limit) {
        if (category == NET) {
            return summarizeNet(limit);
        }
        return summarize(maps[category], limit, otherUsed[category] ? 1 : 0, otherAmount[category]);
    }

    /**
     * Net change per item over the recording (signed), sorted by absolute value, items with net 0 left out.
     * Items that only fell into an "other" bucket cannot be attributed and are not included.
     */
    private Summary summarizeNet(int limit) {
        Object2LongOpenHashMap<Resource> net = new Object2LongOpenHashMap<>();
        addAll(net, maps[IN], 1);
        addAll(net, maps[PRODUCED], 1);
        addAll(net, maps[OUT], -1);
        addAll(net, maps[CONSUMED], -1);
        ArrayList<Entry> all = new ArrayList<>(net.size());
        for (ObjectIterator<Object2LongMap.Entry<Resource>> it = net.object2LongEntrySet().fastIterator(); it.hasNext(); ) {
            Object2LongMap.Entry<Resource> e = it.next();
            if (e.getLongValue() != 0) {
                all.add(new Entry(e.getKey(), e.getLongValue()));
            }
        }
        if (all.isEmpty()) {
            return Summary.EMPTY;
        }
        all.sort((a, b) -> Long.compare(abs(b.amount()), abs(a.amount())));
        int n = Math.min(limit, all.size());
        long moreAmount = 0;
        for (int i = n; i < all.size(); i++) {
            moreAmount = SnapshotDiff.saturatedAdd(moreAmount, all.get(i).amount());
        }
        return new Summary(List.copyOf(all.subList(0, n)), all.size() - n, moreAmount);
    }

    private static void addAll(Object2LongOpenHashMap<Resource> into, Object2LongOpenHashMap<Resource> from, int sign) {
        for (ObjectIterator<Object2LongMap.Entry<Resource>> it = from.object2LongEntrySet().fastIterator(); it.hasNext(); ) {
            Object2LongMap.Entry<Resource> e = it.next();
            long v = sign > 0 ? e.getLongValue() : -e.getLongValue();
            into.put(e.getKey(), SnapshotDiff.saturatedAdd(into.getLong(e.getKey()), v));
        }
    }

    private static long abs(long v) {
        return v == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(v);
    }

    /** Builds a sorted top-N list. Runs at most once per stats update (~2 s), not per tick. */
    private static Summary summarize(Object2LongOpenHashMap<Resource> map, int limit, int otherItems, long other) {
        if (map.isEmpty() && other == 0) {
            return Summary.EMPTY;
        }
        ArrayList<Entry> all = new ArrayList<>(map.size());
        for (ObjectIterator<Object2LongMap.Entry<Resource>> it = map.object2LongEntrySet().fastIterator(); it.hasNext(); ) {
            Object2LongMap.Entry<Resource> e = it.next();
            all.add(new Entry(e.getKey(), e.getLongValue()));
        }
        all.sort((a, b) -> Long.compare(b.amount(), a.amount()));
        int n = Math.min(limit, all.size());
        long moreAmount = other;
        for (int i = n; i < all.size(); i++) {
            moreAmount = SnapshotDiff.saturatedAdd(moreAmount, all.get(i).amount());
        }
        int moreItems = all.size() - n + otherItems;
        return new Summary(List.copyOf(all.subList(0, n)), moreItems, moreAmount);
    }
}
