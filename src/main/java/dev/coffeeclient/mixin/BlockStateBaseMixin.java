package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.client.StreamerMode;
import dev.coffeeclient.module.modules.movement.Jesus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** StreamerMode: the random offset of flowers and grass, which would also give the position away. Jesus: solid fluids. */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateBaseMixin {
	@Inject(method = "getOffset", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noOffset(BlockPos pos, CallbackInfoReturnable<Vec3> cir) {
		if (StreamerMode.noOffset((BlockState) (Object) this)) cir.setReturnValue(Vec3.ZERO);
	}

	@ModifyVariable(method = "getOffset", at = @At("HEAD"), argsOnly = true)
	private BlockPos coffeeclient$shuffledOffset(BlockPos pos) {
		return StreamerMode.offsetPos((BlockState) (Object) this, pos);
	}

	/**
	 * Jesus: water and lava solid underfoot. Hooked here, not in vanilla's collision loop, because Lithium
	 * replaces that loop but still asks each state for its shape.
	 */
	@ModifyReturnValue(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("RETURN"))
	private VoxelShape coffeeclient$fluidShape(VoxelShape shape, BlockGetter level, BlockPos pos, CollisionContext context) {
		// Block shape caches are built during bootstrap, before the client exists.
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || level == null || level != mc.level) return shape;
		Jesus jesus = ModuleManager.ifOn(Jesus.class);
		return jesus == null ? shape : jesus.collisionShape((BlockState) (Object) this, pos, shape);
	}
}
