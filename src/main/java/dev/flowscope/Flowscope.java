package dev.flowscope;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.flowscope.item.FlowscopeItem;
import dev.flowscope.network.LensActionPayload;
import dev.flowscope.network.LensStatsPayload;
import dev.flowscope.network.OpenLensScreenPayload;
import dev.flowscope.session.LensSessionManager;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Entry point for Flowscope.
 *
 * Ground rule: nothing in this mod may tick, scan or listen while no player is recording.
 * The only game-bus listeners are LensSessionManager's, and each returns immediately when no session exists.
 */
@Mod(Flowscope.MODID)
public final class Flowscope {
    public static final String MODID = "flowscope";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredItem<FlowscopeItem> FLOWSCOPE =
            ITEMS.registerItem("flowscope", FlowscopeItem::new, p -> p.stacksTo(1));

    public Flowscope(IEventBus modEventBus, ModContainer modContainer) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(Flowscope::addCreative);
        modEventBus.addListener(Flowscope::registerPayloads);

        NeoForge.EVENT_BUS.addListener(LensSessionManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(LensSessionManager::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(LensSessionManager::onServerStopping);

        // SERVER config: limits are enforced server-side and synced to clients by NeoForge.
        modContainer.registerConfig(ModConfig.Type.SERVER, LensServerConfig.SPEC);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        // Client-bound handlers are registered by FlowscopeClient (client-only classes).
        registrar.playToClient(OpenLensScreenPayload.TYPE, OpenLensScreenPayload.STREAM_CODEC);
        registrar.playToClient(LensStatsPayload.TYPE, LensStatsPayload.STREAM_CODEC);
        registrar.playToServer(LensActionPayload.TYPE, LensActionPayload.STREAM_CODEC, LensSessionManager::handleAction);
    }

    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(FLOWSCOPE);
        }
    }
}
