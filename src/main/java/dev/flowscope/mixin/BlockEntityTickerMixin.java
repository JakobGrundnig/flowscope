package dev.flowscope.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.flowscope.session.EventSampling;
import dev.flowscope.session.LensWatchedFlag;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Marks the span in which a recorded block entity runs its own ticker, so changes it makes to its own inventory can
 * be told apart from changes made by others (hoppers, pipes, players). Used to separate machine processing (made /
 * used up) from transfers (in / out). NeoForge has no event around a block entity's tick, so a vanilla mixin is used
 * as the only option.
 *
 * Cost per block-entity tick: one static field read while nobody records; plus one field read on the block entity
 * while someone does. Handlers never touch their CallbackInfo, so Mixin passes null instead of allocating one.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BlockEntityTickerMixin {
    @Shadow
    @Final
    private BlockEntity blockEntity;

    private static final String TICK =
            "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/block/entity/BlockEntity;)V";

    @Inject(method = "tick()V", at = @At(value = "INVOKE", target = TICK))
    private void flowscope$beforeTick(CallbackInfo ci) {
        if (EventSampling.anyActive && ((LensWatchedFlag) blockEntity).flowscope$isWatched()) {
            EventSampling.beginOwnTick(blockEntity.getLevel(), blockEntity.getBlockPos());
        }
    }

    @Inject(method = "tick()V", at = @At(value = "INVOKE", target = TICK, shift = At.Shift.AFTER))
    private void flowscope$afterTick(CallbackInfo ci) {
        if (EventSampling.ownTickActive) {
            EventSampling.endOwnTick();
        }
    }
}
