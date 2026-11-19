package dev.flowscope.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import dev.flowscope.session.EventSampling;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapability;

/**
 * NeoForge's single choke point for block capability lookups (Level#getCapability and BlockCapabilityCache both end
 * here). While a recorded machine runs its own tick, item/fluid handlers of its adjacent blocks are handed out
 * wrapped in a counting pass-through (see EventSampling#maybeWrap). No event or API exposes these transfers, so a
 * NeoForge mixin is used (AGENTS.md). Cost otherwise: one static boolean read per lookup; no allocation
 * (@ModifyReturnValue needs no callback object).
 */
@Mixin(value = BlockCapability.class, remap = false)
public abstract class BlockCapabilityMixin {
    @ModifyReturnValue(method = "getCapability(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;"
            + "Ljava/lang/Object;)Ljava/lang/Object;", at = @At("RETURN"))
    private Object flowscope$wrapForRecording(Object original, @Local(argsOnly = true) Level level,
            @Local(argsOnly = true) BlockPos pos) {
        if (!EventSampling.wrapping || original == null || EventSampling.inCacheLookup > 0) {
            return original;
        }
        return EventSampling.maybeWrap(this, level, pos, original);
    }
}
