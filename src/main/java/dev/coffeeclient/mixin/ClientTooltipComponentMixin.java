package dev.coffeeclient.mixin;

import dev.coffeeclient.gui.tooltip.CoffeeTooltip;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla only knows how to render its own tooltip data; ours renders itself. */
@Mixin(ClientTooltipComponent.class)
interface ClientTooltipComponentMixin {
	@Inject(method = "create(Lnet/minecraft/world/inventory/tooltip/TooltipComponent;)Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;",
		at = @At("HEAD"), cancellable = true)
	private static void coffeeclient$create(TooltipComponent data, CallbackInfoReturnable<ClientTooltipComponent> cir) {
		if (data instanceof CoffeeTooltip tooltip) cir.setReturnValue(tooltip.component());
	}
}
