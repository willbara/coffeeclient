package dev.coffeeclient.gui;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.jspecify.annotations.Nullable;

/** Added to {@code GuiGraphicsExtractor} by a mixin: submit a whole custom element in one go. */
public interface GuiElementSink {
	void coffeeclient$addElement(GuiElementRenderState element);

	/** The active scissor rectangle, or null for none. */
	@Nullable ScreenRectangle coffeeclient$scissor();
}
