package dev.coffeeclient.mixin;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.modules.client.MainMenu;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets modules observe (and cancel) screens being opened — e.g. AutoRespawn. */
@Mixin(Gui.class)
abstract class GuiMixin {
	/** MainMenu: the vanilla title screen is swapped for Coffee's before anything else sees it. */
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen coffeeclient$replaceTitle(Screen screen) {
		return MainMenu.replace(screen);
	}

	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$onSetScreen(Screen screen, CallbackInfo ci) {
		if (CoffeeClient.events() == null) return;
		Events.OpenScreen event = new Events.OpenScreen(screen);
		CoffeeClient.events().post(event);
		if (event.isCancelled()) ci.cancel();
	}
}
