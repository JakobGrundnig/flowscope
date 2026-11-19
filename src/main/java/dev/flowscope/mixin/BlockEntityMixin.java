package dev.flowscope.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.flowscope.session.EventSampling;
import dev.flowscope.session.LensWatchedFlag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Event-based sampling hook on {@code BlockEntity.setChanged(Level, BlockPos, BlockState)}. Every block entity's
 * setChanged() calls it, and machines (e.g. the vanilla furnace) also call it directly when their tick changes the
 * inventory. NeoForge has no event for inventory changes, so a vanilla mixin is used.
 * Cost while nobody is recording: one static field read and a null check. The handler never touches its
 * CallbackInfo, so Mixin passes null instead of allocating one (non-cancellable inject).
 *
 * Also adds the "watched" flag used by BlockEntityTickerMixin (a boolean usually fits in existing object padding).
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin implements LensWatchedFlag {
    @Unique
    private boolean flowscope$watched;

    @Override
    public boolean flowscope$isWatched() {
        return flowscope$watched;
    }

    @Override
    public void flowscope$setWatched(boolean watched) {
        flowscope$watched = watched;
    }

    @Inject(method = "setChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD"))
    private static void flowscope$onSetChanged(Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (EventSampling.anyActive) {
            EventSampling.onBlockChanged(level, pos);
        }
    }
}
