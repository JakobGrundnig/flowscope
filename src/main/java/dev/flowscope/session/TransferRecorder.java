package dev.flowscope.session;

import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Records what a watched machine inserts into / extracts from its neighbours during its own tick, as seen through
 * {@link CountingHandler}. Uses NeoForge's transaction journal so only <b>committed</b> transfers count: entries made
 * inside a transaction that is rolled back are dropped again. Reused across ticks; server thread only.
 */
final class TransferRecorder extends SnapshotJournal<Integer> {
    static final byte IN = 0;   // extracted from a neighbour = went into the machine
    static final byte OUT = 1;  // inserted into a neighbour = came out of the machine

    private Resource[] resources = new Resource[16];
    private long[] amounts = new long[16];
    private byte[] directions = new byte[16];
    private long[] positions = new long[16];
    private int size;
    /** Entries below this index belong to committed root transactions. */
    private int committed;

    void reset() {
        for (int i = 0; i < size; i++) {
            resources[i] = null;
        }
        size = 0;
        committed = 0;
    }

    /** Called by a counting handler after a successful insert/extract inside {@code transaction}. */
    void record(byte direction, long neighbourPos, Resource resource, long amount, TransactionContext transaction) {
        if (amount <= 0 || resource == null || resource.isEmpty()) {
            return;
        }
        updateSnapshots(transaction);
        if (size == resources.length) {
            int cap = size * 2;
            resources = java.util.Arrays.copyOf(resources, cap);
            amounts = java.util.Arrays.copyOf(amounts, cap);
            directions = java.util.Arrays.copyOf(directions, cap);
            positions = java.util.Arrays.copyOf(positions, cap);
        }
        resources[size] = resource;
        amounts[size] = amount;
        directions[size] = direction;
        positions[size] = neighbourPos;
        size++;
    }

    @Override
    protected Integer createSnapshot() {
        return size;
    }

    @Override
    protected void revertToSnapshot(Integer snapshot) {
        // Rolled back: forget everything recorded since the transaction started.
        for (int i = snapshot; i < size; i++) {
            resources[i] = null;
        }
        size = snapshot;
    }

    @Override
    protected void onRootCommit(Integer originalState) {
        committed = size;
    }

    int committedCount() {
        return committed;
    }

    Resource resource(int i) {
        return resources[i];
    }

    long amount(int i) {
        return amounts[i];
    }

    byte direction(int i) {
        return directions[i];
    }

    long position(int i) {
        return positions[i];
    }

    /** True if a committed transfer involved the neighbour at {@code posKey}. */
    boolean touched(long posKey) {
        for (int i = 0; i < committed; i++) {
            if (positions[i] == posKey) {
                return true;
            }
        }
        return false;
    }
}
