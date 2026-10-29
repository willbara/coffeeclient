package dev.coffeeclient.mixin;

import dev.coffeeclient.render.CoffeeRenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
	/** apply() clears the device's pipeline cache; recompile ours from the freshly loaded sources. */
	@Inject(method = "apply", at = @At("RETURN"))
	private void coffeeclient$reloadPipelines(CallbackInfo ci) {
		ShaderManager self = (ShaderManager) (Object) this;
		CoffeeRenderPipelines.precompile(self::getShader);
	}
}
