package dev.coffeeclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The camera shake vanilla applies to the level projection (so HUD-projected labels can match it), and post effects. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Invoker("bobHurt")
	void coffeeclient$bobHurt(CameraRenderState state, PoseStack pose);

	@Invoker("bobView")
	void coffeeclient$bobView(CameraRenderState state, PoseStack pose);

	/** ScreenFilter: run one of our post effects after the level. */
	@Invoker("setPostEffect")
	void coffeeclient$setPostEffect(Identifier id);
}
