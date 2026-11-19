package dev.flowscope.client;

import dev.flowscope.network.LensStatsPayload;
import dev.flowscope.network.OpenLensScreenPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client-side handlers for server-to-client payloads. Run on the client main thread. */
public final class ClientPayloadHandlers {
    private ClientPayloadHandlers() {
    }

    public static void openScreen(OpenLensScreenPayload payload, IPayloadContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof LensScreen old && old.sessionId() == payload.sessionId()) {
            // Reopened for the same recording: closing the old instance must not tell the server "closed".
            old.suppressCloseNotice();
        }
        mc.setScreen(new LensScreen(payload));
    }

    public static void stats(LensStatsPayload payload, IPayloadContext context) {
        // Updates for any other (old) session are ignored.
        if (Minecraft.getInstance().screen instanceof LensScreen screen && screen.sessionId() == payload.sessionId()) {
            screen.acceptStats(payload);
        }
    }
}
