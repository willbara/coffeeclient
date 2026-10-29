package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.player.HotbarLock;
import dev.coffeeclient.module.modules.render.CameraTweaks;
import dev.coffeeclient.module.modules.render.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes in-game mouse input to modules before vanilla handles it. Screens are untouched. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Shadow
	private double xpos;
	@Shadow
	private double ypos;
	@Shadow
	private boolean ignoreFirstMove;

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$mouseClick(long handle, MouseButtonInfo button, int action, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (handle != mc.getWindow().handle() || mc.gui.screen() != null || mc.player == null || CoffeeClient.events() == null) return;
		Events.MouseClick click = CoffeeClient.events().post(new Events.MouseClick(button.button(), action, button.modifiers()));
		if (click.isCancelled()) ci.cancel();
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$scroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (handle != mc.getWindow().handle() || mc.gui.screen() != null || mc.player == null || yoffset == 0) return;
		if (CoffeeClient.events() != null) {
			Events.MouseScroll scroll = CoffeeClient.events().post(new Events.MouseScroll(yoffset));
			if (scroll.isCancelled()) {
				ci.cancel();
				return;
			}
		}
		if (HotbarLock.blocksScroll()) ci.cancel();
	}

	@Inject(method = "onMove", at = @At("HEAD"))
	private void coffeeclient$mouseMove(long handle, double x, double y, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (handle != mc.getWindow().handle() || mc.gui.screen() != null || mc.player == null || ignoreFirstMove) return;
		if (!ModuleManager.on(Freecam.class)) return;
		Freecam freecam = CoffeeClient.modules().get(Freecam.class);
		if (!freecam.isEnabled()) return;
		// 26.2 reports absolute positions; the delta is against the last position vanilla stored.
		double dx = x - xpos, dy = y - ypos;
		if (dx != 0 || dy != 0) {
			freecam.changeLookDirection(dx * 0.15, dy * 0.15);
		}
	}

	/** Zoom: slower turning the further you zoom in. */
	@ModifyExpressionValue(method = "turnPlayer(D)V", at = @At(value = "INVOKE", target = "Ljava/lang/Double;doubleValue()D"))
	private double coffeeclient$zoomSensitivity(double sensitivity) {
		return sensitivity / CameraTweaks.zoomFactor();
	}
}
