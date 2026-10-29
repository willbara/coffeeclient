package dev.coffeeclient.mixin;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.modules.render.Ambience;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ambience: rain or snow everywhere (render-side precipitation type only); the entity-added event. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "getPrecipitationAt", at = @At("RETURN"), cancellable = true)
	private void coffeeclient$precipitation(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
		cir.setReturnValue(Ambience.precipitation(cir.getReturnValue()));
	}

	@Inject(method = "addEntity", at = @At("TAIL"))
	private void coffeeclient$entityAdded(Entity entity, CallbackInfo ci) {
		var bus = CoffeeClient.events();
		if (bus != null && bus.has(Events.EntityAdded.class)) bus.post(new Events.EntityAdded(entity));
	}
}
