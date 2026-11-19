package dev.flowscope;

import dev.flowscope.client.ClientPayloadHandlers;
import dev.flowscope.network.LensStatsPayload;
import dev.flowscope.network.OpenLensScreenPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/** Client-only entry point. Never loaded on a dedicated server; keep all client classes behind this. */
@Mod(value = Flowscope.MODID, dist = Dist.CLIENT)
public final class FlowscopeClient {
    public FlowscopeClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(FlowscopeClient::registerClientPayloads);
    }

    private static void registerClientPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(OpenLensScreenPayload.TYPE, ClientPayloadHandlers::openScreen);
        event.register(LensStatsPayload.TYPE, ClientPayloadHandlers::stats);
    }
}
