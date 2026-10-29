package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.render.BetterTooltips;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/** BetterTooltips: extra tooltip lines, and container previews in place of the tooltip image. */
@Mixin(ItemStack.class)
abstract class ItemStackMixin {
	/** ChatTweaks' filter on item names. */
	@ModifyReturnValue(method = "getHoverName", at = @At("RETURN"))
	private net.minecraft.network.chat.Component coffeeclient$filterName(net.minecraft.network.chat.Component original) {
		return dev.coffeeclient.module.modules.misc.ChatTweaks.filterText(original, dev.coffeeclient.module.modules.misc.ChatTweaks.Where.ITEM);
	}

	@ModifyReturnValue(method = "getTooltipLines", at = @At("RETURN"))
	private List<Component> coffeeclient$betterTooltips(List<Component> original) {
		if (!ModuleManager.on(BetterTooltips.class)) return original;
		return CoffeeClient.modules().get(BetterTooltips.class).append((ItemStack) (Object) this, original);
	}

	@Inject(method = "getTooltipImage", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$preview(CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
		BetterTooltips tooltips = ModuleManager.ifOn(BetterTooltips.class);
		if (tooltips == null) return;
		TooltipComponent preview = tooltips.preview((ItemStack) (Object) this);
		if (preview != null) cir.setReturnValue(Optional.of(preview));
	}
}
