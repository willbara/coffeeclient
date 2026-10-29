package dev.coffeeclient.mixin;

import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.render.Freecam;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam, XRay and NoEffect: stop cave culling (while detached / while x-raying). Runs for every opaque block of every rebuilt section, so no allocation here. */
@Mixin(VisGraph.class)
public abstract class VisGraphMixin {
	@Inject(method = "setOpaque", at = @At("HEAD"), cancellable = true)
	private void onMarkClosed(BlockPos pos, CallbackInfo ci) {
		if (ModuleManager.on(Freecam.class) || dev.coffeeclient.module.modules.render.XRay.active()
			|| dev.coffeeclient.module.modules.render.NoEffect.hides(m -> m.caveCulling)) ci.cancel();
	}
}
