package dev.flowscope.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import dev.flowscope.session.EventSampling;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;

/**
 * Machines that keep a BlockCapabilityCache get their neighbour handler from the cache. The handler the cache stores
 * must stay the original (so nothing of ours lingers in another mod's cache), so the lookup inside the cache is not
 * wrapped; only the value returned to the machine during the recorded tick is. Cost otherwise: one static boolean
 * read per call; no allocation (the HEAD handler never touches its callback object, @ModifyReturnValue needs none).
 */
@Mixin(value = BlockCapabilityCache.class, remap = false)
public abstract class BlockCapabilityCacheMixin {
    @Shadow
    @Final
    private BlockCapability<?, ?> capability;
    @Shadow
    @Final
    private ServerLevel level;
    @Shadow
    @Final
    private BlockPos pos;

    @Inject(method = "getCapability()Ljava/lang/Object;", at = @At("HEAD"))
    private void flowscope$enterCache(CallbackInfoReturnable<Object> cir) {
        if (EventSampling.wrapping) {
            EventSampling.inCacheLookup++;
        }
    }

    @ModifyReturnValue(method = "getCapability()Ljava/lang/Object;", at = @At("RETURN"))
    private Object flowscope$wrapCached(Object original) {
        if (!EventSampling.wrapping) {
            return original;
        }
        if (EventSampling.inCacheLookup > 0) {
            EventSampling.inCacheLookup--;
        }
        return original == null ? null : EventSampling.maybeWrap(capability, level, pos, original);
    }
}
