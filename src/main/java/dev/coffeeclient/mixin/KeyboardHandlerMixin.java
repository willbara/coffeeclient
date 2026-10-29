package dev.coffeeclient.mixin;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Events;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes raw key presses on the client event bus (for per-module keybinds, which are arbitrary keys
 * rather than registered key mappings). Modules may cancel movement keys before vanilla processes them.
 */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$onKeyPress(long handle, int action, KeyEvent event, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (handle != mc.getWindow().handle() || CoffeeClient.events() == null) return;
		Events.KeyPress keyPress = CoffeeClient.events().post(new Events.KeyPress(event.key(), event.scancode(), action, event.modifiers(), mc.gui.screen() != null));
		if (keyPress.isCancelled()) ci.cancel();
	}
}
