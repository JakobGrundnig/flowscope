package dev.flowscope.session;

import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Thin pass-through around a neighbour's item or fluid handler, handed to a watched machine only while it runs its
 * own tick (and only for its 6 adjacent positions). Every call is delegated unchanged; successful insert/extract
 * calls are also noted in the machine's {@link TransferRecorder} (committed transactions only). Never stored by
 * Flowscope beyond that tick, and never handed out outside it.
 *
 * Approved exception to "don't interfere with other mods" (Jakob, 2026-11-19): limited to the recorded machine,
 * adjacent positions and its own tick; switchable via the server config option countNeighbourTransfers.
 */
public final class CountingHandler<T extends Resource> implements ResourceHandler<T> {
    private final ResourceHandler<T> delegate;
    private final TransferRecorder recorder;
    private final long neighbourPos;

    CountingHandler(ResourceHandler<T> delegate, TransferRecorder recorder, long neighbourPos) {
        this.delegate = delegate;
        this.recorder = recorder;
        this.neighbourPos = neighbourPos;
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public T getResource(int index) {
        return delegate.getResource(index);
    }

    @Override
    public long getAmountAsLong(int index) {
        return delegate.getAmountAsLong(index);
    }

    @Override
    public long getCapacityAsLong(int index, T resource) {
        return delegate.getCapacityAsLong(index, resource);
    }

    @Override
    public boolean isValid(int index, T resource) {
        return delegate.isValid(index, resource);
    }

    @Override
    public int insert(int index, T resource, int amount, TransactionContext transaction) {
        int moved = delegate.insert(index, resource, amount, transaction);
        recorder.record(TransferRecorder.OUT, neighbourPos, resource, moved, transaction);
        return moved;
    }

    @Override
    public int insert(T resource, int amount, TransactionContext transaction) {
        int moved = delegate.insert(resource, amount, transaction);
        recorder.record(TransferRecorder.OUT, neighbourPos, resource, moved, transaction);
        return moved;
    }

    @Override
    public int extract(int index, T resource, int amount, TransactionContext transaction) {
        int moved = delegate.extract(index, resource, amount, transaction);
        recorder.record(TransferRecorder.IN, neighbourPos, resource, moved, transaction);
        return moved;
    }

    @Override
    public int extract(T resource, int amount, TransactionContext transaction) {
        int moved = delegate.extract(resource, amount, transaction);
        recorder.record(TransferRecorder.IN, neighbourPos, resource, moved, transaction);
        return moved;
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
