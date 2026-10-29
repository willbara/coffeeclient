package dev.coffeeclient.gui.tooltip;

import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;

/** A single line shown where a tooltip image would go (e.g. "No data yet."). */
public final class TextTooltip extends ClientTextTooltip implements CoffeeTooltip {
	public TextTooltip(Component text) {
		super(text.getVisualOrderText());
	}

	@Override
	public ClientTooltipComponent component() {
		return this;
	}
}
