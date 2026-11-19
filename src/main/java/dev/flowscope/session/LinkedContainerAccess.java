package dev.flowscope.session;

import net.minecraft.world.Container;

/**
 * Added to NeoForge's VanillaContainerWrapper by {@code dev.flowscope.mixin.VanillaContainerWrapperMixin}: exposes
 * the wrapped container, so a watched block whose item handler wraps a container that is not the block itself (e.g.
 * a shared, frequency-linked storage) can be recognised when that container is changed through any wrapper.
 */
public interface LinkedContainerAccess {
    Container flowscope$container();
}
