package dev.flowscope.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.flowscope.session.EventSampling;
import dev.flowscope.session.LinkedContainerAccess;
import net.minecraft.world.Container;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;

/**
 * NeoForge commits every capability transfer into a vanilla-style Container through VanillaContainerWrapper and then
 * calls the container's setChanged(). When that container is not a block entity (e.g. a shared storage behind a
 * "dimensional"/linked chest), the block's own setChanged() never runs, so the block-entity hook cannot see the
 * change. This hook reports the commit for watched containers. No event exists for it (AGENTS.md).
 * Cost while nothing is watched: one static boolean read per committed transfer; the handler never touches its
 * callback object, so Mixin passes null instead of allocating one.
 */
@Mixin(value = VanillaContainerWrapper.class, remap = false)
public abstract class VanillaContainerWrapperMixin implements LinkedContainerAccess {
    @Shadow
    @Final
    private Container container;

    @Override
    public Container flowscope$container() {
        return container;
    }

    @Inject(method = "onRootCommit()V", at = @At("HEAD"))
    private void flowscope$onCommit(CallbackInfo ci) {
        if (EventSampling.anyActive) {
            EventSampling.onContainerCommitted(container);
        }
    }
}
