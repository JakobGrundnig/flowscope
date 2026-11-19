package dev.flowscope.session;

/**
 * Added to every BlockEntity by {@code dev.flowscope.mixin.BlockEntityMixin}: true while a recording watches
 * this block entity. Lets the block-entity tick hook skip unwatched blocks with one field check instead of comparing
 * against all recordings. Only touched on the server thread.
 */
public interface LensWatchedFlag {
    boolean flowscope$isWatched();

    void flowscope$setWatched(boolean watched);
}
