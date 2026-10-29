package dev.coffeeclient.mixin;

import dev.coffeeclient.gui.GuiElementSink;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Lets Draw submit batched elements (pixel lines) straight to the GUI render state. */
@Mixin(GuiGraphicsExtractor.class)
abstract class GuiGraphicsExtractorMixin implements GuiElementSink {
	@Shadow
	@Final
	private GuiRenderState guiRenderState;

	@Shadow
	@Final
	private GuiGraphicsExtractor.ScissorStack scissorStack;

	@Override
	public void coffeeclient$addElement(GuiElementRenderState element) {
		guiRenderState.addGuiElement(element);
	}

	@Override
	public ScreenRectangle coffeeclient$scissor() {
		return scissorStack.peek();
	}
}
