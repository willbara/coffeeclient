package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.player.AutoRespawn;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** AutoRespawn: a button on the death screen that turns it on and respawns. */
@Mixin(DeathScreen.class)
abstract class DeathScreenMixin extends Screen {
	private DeathScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void coffeeclient$autoRespawnButton(CallbackInfo ci) {
		if (!AutoRespawn.showButton()) return;
		addRenderableWidget(Button.builder(Component.literal("AutoRespawn: OFF"), b -> AutoRespawn.enableAndRespawn())
			.bounds(width / 2 - 100, height / 4 + 48, 200, 20).build());
	}
}
