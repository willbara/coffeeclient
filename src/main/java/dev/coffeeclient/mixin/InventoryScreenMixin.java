package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.coffeeclient.module.modules.misc.InvLeak;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** InvLeak: your model in the inventory screen is built with no equipment. */
@Mixin(InventoryScreen.class)
abstract class InventoryScreenMixin {
	@WrapOperation(method = "extractEntityInInventoryFollowsMouse", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/inventory/InventoryScreen;extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
	private static EntityRenderState coffeeclient$bareModel(LivingEntity entity, Operation<EntityRenderState> original) {
		if (!InvLeak.hidesModelOf(entity)) return original.call(entity);
		InvLeak.bare(true);
		try {
			return original.call(entity);
		} finally {
			InvLeak.bare(false);
		}
	}
}
