package dev.flowscope.client;

import dev.flowscope.Flowscope;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/** Vanilla button with the lens screen's copper/teal sprites. Client-only; behaviour is unchanged. */
final class LensButton extends Button {
    private static final WidgetSprites SPRITES = new WidgetSprites(
            Identifier.fromNamespaceAndPath(Flowscope.MODID, "widget/lens_button"),
            Identifier.fromNamespaceAndPath(Flowscope.MODID, "widget/lens_button_disabled"),
            Identifier.fromNamespaceAndPath(Flowscope.MODID, "widget/lens_button_highlighted"));

    LensButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SPRITES.get(active, isHoveredOrFocused()),
                getX(), getY(), getWidth(), getHeight(), ARGB.white(alpha));
        extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
    }
}
