package dev.coffeeclient.gui.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** A 9x3 container panel with its items, drawn in an item tooltip. */
public final class ContainerTooltip implements ClientTooltipComponent, CoffeeTooltip {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("coffeeclient", "textures/container.png");
	private static final int WIDTH = 176;
	private static final int HEIGHT = 67;

	private final List<ItemStack> items;
	private final int tint;

	/** @param tint ARGB multiplied over the panel (white = vanilla grey) */
	public ContainerTooltip(List<ItemStack> items, int tint) {
		this.items = items;
		this.tint = tint;
	}

	@Override
	public ClientTooltipComponent component() {
		return this;
	}

	@Override
	public int getHeight(Font font) {
		return HEIGHT + 2;
	}

	@Override
	public int getWidth(Font font) {
		return WIDTH;
	}

	@Override
	public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor g) {
		g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT, tint);
		for (int i = 0; i < Math.min(27, items.size()); i++) {
			ItemStack stack = items.get(i);
			if (stack.isEmpty()) continue;
			int sx = x + 8 + (i % 9) * 18, sy = y + 7 + (i / 9) * 18;
			g.item(stack, sx, sy);
			g.itemDecorations(font, stack, sx, sy);
		}
	}
}
