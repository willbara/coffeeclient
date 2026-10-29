package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.movement.Jesus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Jesus: give water/lava a full block collision shape so the player can walk on it. */
@Mixin(BlockCollisions.class)
abstract class BlockCollisionsMixin {
	@WrapOperation(method = "computeNext", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/CollisionContext;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
	private VoxelShape coffeeclient$fluidShape(CollisionContext context, BlockState state, CollisionGetter level, BlockPos pos, Operation<VoxelShape> original) {
		VoxelShape shape = original.call(context, state, level, pos);
		if (level != Minecraft.getInstance().level) return shape;
		Jesus jesus = ModuleManager.ifOn(Jesus.class);
		return jesus == null ? shape : jesus.collisionShape(state, pos, shape);
	}
}
