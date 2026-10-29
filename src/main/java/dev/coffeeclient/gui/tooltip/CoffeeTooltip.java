package dev.coffeeclient.gui.tooltip;

import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/** Tooltip data that renders itself, so vanilla's data-to-renderer lookup can hand it straight back. */
public interface CoffeeTooltip extends TooltipComponent {
	ClientTooltipComponent component();
}
