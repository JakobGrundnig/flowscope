package dev.flowscope.session;

/**
 * Fixed ring of one-second buckets holding observed increase/decrease per real-time second.
 * Allocation-free after construction. Not thread-safe; used only on the server thread.
 */
public final class RollingRateWindow {
    public static final int SECONDS = 60;

    private final long[] increase = new long[SECONDS];
    private final long[] decrease = new long[SECONDS];
    /** Absolute second index (since recording start) of the newest bucket, or -1 if empty. */
    private long headSecond = -1;

    public void reset() {
        java.util.Arrays.fill(increase, 0L);
        java.util.Arrays.fill(decrease, 0L);
        headSecond = -1;
    }

    /** Records deltas observed at the given second since recording start. */
    public void add(long second, long inc, long dec) {
        advanceTo(second);
        int i = (int) (second % SECONDS);
        increase[i] = SnapshotDiff.saturatedAdd(increase[i], inc);
        decrease[i] = SnapshotDiff.saturatedAdd(decrease[i], dec);
    }

    /** Moves the head forward, clearing buckets for seconds without samples. Bounded to SECONDS iterations. */
    public void advanceTo(long second) {
        if (second <= headSecond) {
            return;
        }
        long gap = headSecond < 0 ? SECONDS : Math.min(second - headSecond, SECONDS);
        for (long s = second - gap + 1; s <= second; s++) {
            int i = (int) (Math.floorMod(s, (long) SECONDS));
            increase[i] = 0L;
            decrease[i] = 0L;
        }
        headSecond = second;
    }

    public long sumIncrease() {
        long sum = 0;
        for (long v : increase) {
            sum = SnapshotDiff.saturatedAdd(sum, v);
        }
        return sum;
    }

    public long sumDecrease() {
        long sum = 0;
        for (long v : decrease) {
            sum = SnapshotDiff.saturatedAdd(sum, v);
        }
        return sum;
    }

    /**
     * Copies buckets oldest-to-newest into the given arrays (length SECONDS), saturated to int.
     * Seconds before the recording started are zero.
     */
    public void copyOrdered(int[] incOut, int[] decOut) {
        for (int k = 0; k < SECONDS; k++) {
            long s = headSecond - (SECONDS - 1) + k;
            if (headSecond < 0 || s < 0) {
                incOut[k] = 0;
                decOut[k] = 0;
            } else {
                int i = (int) (s % SECONDS);
                incOut[k] = (int) Math.min(increase[i], Integer.MAX_VALUE);
                decOut[k] = (int) Math.min(decrease[i], Integer.MAX_VALUE);
            }
        }
    }
}
