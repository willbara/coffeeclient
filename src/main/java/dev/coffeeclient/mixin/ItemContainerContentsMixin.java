package dev.coffeeclient.mixin;

import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.render.BetterTooltips;
import net.minecraft.world.item.component.ItemContainerContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** BetterTooltips: drop vanilla's "item x N" content lines while the visual container preview replaces them. */
@Mixin(ItemContainerContents.class)
abstract class ItemContainerContentsMixin {
	@Inject(method = "addToTooltip", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$hideContentLines(CallbackInfo ci) {
		BetterTooltips tooltips = ModuleManager.ifOn(BetterTooltips.class);
		if (tooltips != null && tooltips.containerPreview.get()) ci.cancel();
	}
}
