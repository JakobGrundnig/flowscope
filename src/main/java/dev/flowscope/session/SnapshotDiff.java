package dev.flowscope.session;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.resource.Resource;

/**
 * Read-only, slot-by-slot change detection for one inventory (items or fluids: R is ItemResource or FluidResource).
 *
 * Keeps a shadow copy of every slot (item identity + amount) from the last read. A read walks the handler once and
 * only does work for slots that differ from the shadow: unchanged slots cost two handler calls and an identity
 * compare, with no hashing or allocation. Changed slots add their difference per item identity into a reused map,
 * so an item moving between slots within one read nets out exactly as with whole-inventory totals.
 *
 * Only calls size(), getResource() and getAmountAsLong(); never inserts, extracts or opens a transaction.
 */
public final class SnapshotDiff<R extends Resource> {
    /** {@link #read} results. */
    public static final int BASELINE = 0;
    public static final int COMPARED = 1;
    public static final int TOO_LARGE = 2;

    private Resource[] slotItem = new Resource[0];
    private long[] slotAmount = new long[0];
    private int slots;
    private boolean hasBaseline;
    /** Net change per item identity in the current read; reused. */
    private final Object2LongOpenHashMap<Resource> deltas = new Object2LongOpenHashMap<>();

    private long lastIncrease;
    private long lastDecrease;

    public void reset() {
        java.util.Arrays.fill(slotItem, null);
        java.util.Arrays.fill(slotAmount, 0L);
        slots = 0;
        hasBaseline = false;
        deltas.clear();
        lastIncrease = 0;
        lastDecrease = 0;
    }

    public boolean hasBaseline() {
        return hasBaseline;
    }

    /**
     * Reads the handler and computes the change per item identity since the last read ({@link #deltas()}).
     * Any exception from the third-party handler propagates; the caller ends the recordings.
     *
     * @return BASELINE (first read, nothing counted), COMPARED, or TOO_LARGE (nothing changed)
     */
    public int read(ResourceHandler<R> handler, int maxSlots) {
        int size = handler.size();
        if (size < 0 || size > maxSlots) {
            return TOO_LARGE;
        }
        if (slotItem.length < size) {
            int cap = Math.max(size, Math.min(maxSlots, slotItem.length * 2));
            slotItem = java.util.Arrays.copyOf(slotItem, cap);
            slotAmount = java.util.Arrays.copyOf(slotAmount, cap);
        }
        boolean baseline = !hasBaseline;
        clearDeltas();
        for (int i = 0; i < size; i++) {
            Resource item = handler.getResource(i);
            long amount = handler.getAmountAsLong(i);
            if (item == null || item.isEmpty() || amount <= 0) {
                item = null;
                amount = 0;
            }
            Resource old = slotItem[i];
            long oldAmount = slotAmount[i];
            if (old == item || (old != null && item != null && old.equals(item))) {
                if (oldAmount != amount) {
                    if (!baseline) {
                        deltas.addTo(old, saturatedSub(amount, oldAmount));
                    }
                    slotAmount[i] = amount;
                }
                continue;
            }
            if (!baseline) {
                if (old != null) {
                    deltas.addTo(old, -oldAmount);
                }
                if (item != null) {
                    deltas.addTo(item, amount);
                }
            }
            slotItem[i] = item;
            slotAmount[i] = amount;
        }
        // Slots that disappeared because the inventory got smaller.
        for (int i = size; i < slots; i++) {
            if (!baseline && slotItem[i] != null) {
                deltas.addTo(slotItem[i], -slotAmount[i]);
            }
            slotItem[i] = null;
            slotAmount[i] = 0;
        }
        slots = size;

        if (baseline) {
            hasBaseline = true;
            lastIncrease = 0;
            lastDecrease = 0;
            return BASELINE;
        }
        long inc = 0;
        long dec = 0;
        if (!deltas.isEmpty()) {
            for (ObjectIterator<Object2LongMap.Entry<Resource>> it = deltas.object2LongEntrySet().fastIterator(); it.hasNext(); ) {
                Object2LongMap.Entry<Resource> e = it.next();
                long delta = e.getLongValue();
                if (delta > 0) {
                    inc = saturatedAdd(inc, delta);
                } else if (delta < 0) {
                    dec = saturatedAdd(dec, delta == Long.MIN_VALUE ? Long.MAX_VALUE : -delta);
                }
            }
        }
        lastIncrease = inc;
        lastDecrease = dec;
        return COMPARED;
    }

    /**
     * Empties the per-read map by removing only the keys used in the last read. A plain clear() would wipe the
     * whole backing table, which stays large after one read with many different items.
     */
    private void clearDeltas() {
        int n = deltas.size();
        if (n == 0) {
            return;
        }
        if (touched.length < n) {
            touched = new Resource[Math.max(n, touched.length * 2)];
        }
        int k = 0;
        for (ObjectIterator<Object2LongMap.Entry<Resource>> it = deltas.object2LongEntrySet().fastIterator(); it.hasNext(); ) {
            touched[k++] = it.next().getKey();
        }
        for (int i = 0; i < k; i++) {
            deltas.removeLong(touched[i]);
            touched[i] = null;
        }
    }

    private Resource[] touched = new Resource[16];

    /** Change per item identity of the last COMPARED read (entries may be 0). Valid until the next read. */
    Object2LongOpenHashMap<Resource> deltas() {
        return deltas;
    }

    /** Adds the last read's changes to a recording's per-item totals. */
    void recordInto(ItemBreakdown breakdown, int incCategory, int decCategory) {
        for (ObjectIterator<Object2LongMap.Entry<Resource>> it = deltas.object2LongEntrySet().fastIterator(); it.hasNext(); ) {
            Object2LongMap.Entry<Resource> e = it.next();
            long delta = e.getLongValue();
            if (delta > 0) {
                breakdown.record(incCategory, e.getKey(), delta);
            } else if (delta < 0) {
                breakdown.record(decCategory, e.getKey(), delta == Long.MIN_VALUE ? Long.MAX_VALUE : -delta);
            }
        }
    }

    public long lastIncrease() {
        return lastIncrease;
    }

    public long lastDecrease() {
        return lastDecrease;
    }

    public static long saturatedAdd(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return r;
    }

    public static long saturatedSub(long a, long b) {
        long r = a - b;
        if (((a ^ b) & (a ^ r)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return r;
    }
}
