package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.modules.movement.ElytraFly;
import dev.coffeeclient.module.modules.movement.NoSlow;
import dev.coffeeclient.module.modules.render.NoEffect;
import dev.coffeeclient.util.fakeplayer.FakePlayerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Inject;

/** Stand-on-fluid event (Jesus), NoEffect (block status effects, hide their view effects) and ElytraFly's heading lock. */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	/** Movement correction: the sprint-jump boost goes along the yaw the server is sent. */
	@ModifyExpressionValue(method = "jumpFromGround", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F"))
	private float coffeeclient$jumpYaw(float yaw) {
		if ((Object) this != Minecraft.getInstance().player) return yaw;
		float sent = dev.coffeeclient.util.Rotations.movementYaw();
		return Float.isNaN(sent) ? yaw : sent;
	}

	/** ElytraFly heading lock: glide physics turn by the take-off yaw, not the camera's. */
	@WrapMethod(method = "updateFallFlyingMovement")
	private Vec3 coffeeclient$glideHeading(Vec3 movement, Operation<Vec3> original) {
		LivingEntity self = (LivingEntity) (Object) this;
		Float heading = self == Minecraft.getInstance().player ? ElytraFly.glideHeading() : null;
		if (heading == null) return original.call(movement);
		float yaw = self.getYRot();
		self.setYRot(heading);
		try {
			return original.call(movement);
		} finally {
			self.setYRot(yaw);
		}
	}

	@ModifyReturnValue(method = "canStandOnFluid", at = @At("RETURN"))
	private boolean coffeeclient$canWalkOnFluid(boolean original, FluidState fluid) {
		if ((Object) this != Minecraft.getInstance().player) return original;
		var bus = CoffeeClient.events();
		if (bus == null || !bus.has(Events.CanWalkOnFluid.class)) return original;
		return bus.post(new Events.CanWalkOnFluid(fluid, original)).canWalk;
	}

	/** NoSlow "Ignore vines": climbing clamps sideways speed; keep it unclamped while in a vine. */
	@ModifyReturnValue(method = "handleOnClimbable", at = @At("RETURN"))
	private Vec3 coffeeclient$vineSpeed(Vec3 clamped, @Local(argsOnly = true) Vec3 delta) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self != Minecraft.getInstance().player || !NoSlow.ignoreVines()) return clamped;
		var state = self.getInBlockState();
		if (!state.is(Blocks.VINE) && !state.is(Blocks.CAVE_VINES) && !state.is(Blocks.CAVE_VINES_PLANT)
			&& !state.is(Blocks.TWISTING_VINES) && !state.is(Blocks.TWISTING_VINES_PLANT)
			&& !state.is(Blocks.WEEPING_VINES) && !state.is(Blocks.WEEPING_VINES_PLANT)) return clamped;
		return new Vec3(delta.x, clamped.y, delta.z);
	}

	/** InvLeak: your model in the inventory screen without its gear. */
	@ModifyReturnValue(method = "getItemBySlot", at = @At("RETURN"))
	private net.minecraft.world.item.ItemStack coffeeclient$invLeakBare(net.minecraft.world.item.ItemStack stack) {
		return dev.coffeeclient.module.modules.misc.InvLeak.modelItem((LivingEntity) (Object) this, stack);
	}

	@ModifyReturnValue(method = "hasEffect", at = @At("RETURN"))
	private boolean coffeeclient$hasEffect(boolean original, Holder<MobEffect> effect) {
		if (!original || effect == null || effect.value() == null) return original;
		if (!ModuleManager.on(NoEffect.class)) return original;

		Identifier key = BuiltInRegistries.MOB_EFFECT.getKey(effect.value());
		if (key == null) return original;
		return !CoffeeClient.modules().get(NoEffect.class).blocked(key.toString());
	}

	/** A frozen fake player holds its item-use progress; the partial tick would otherwise sweep the arm every frame. */
	@ModifyReturnValue(method = "getTicksUsingItem(F)F", at = @At("RETURN"))
	private float coffeeclient$frozenUseProgress(float original, float partial) {
		if (!((Object) this instanceof FakePlayerEntity fake) || !fake.isFrozen()) return original;
		return fake.getTicksUsingItem();
	}

	/** Darkness pulsing and the nausea/blindness fade all read this blend factor. */
	@ModifyReturnValue(method = "getEffectBlendFactor", at = @At("RETURN"))
	private float coffeeclient$hideViewEffect(float original, Holder<MobEffect> effect) {
		if (original == 0 || (Object) this != Minecraft.getInstance().player) return original;
		return NoEffect.hidesView(effect) ? 0 : original;
	}

	/** NoEffect: no crumbs from eating and no bits from a breaking item. */
	@Inject(method = "spawnItemParticles", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noItemParticles(net.minecraft.world.item.ItemStack stack, int count, CallbackInfo ci) {
		if (NoEffect.hides(m -> m.itemParticles)) ci.cancel();
	}

	/** HandView's swing modifier: the local player's swings run faster or slower. */
	@ModifyReturnValue(method = "getCurrentSwingDuration", at = @At("RETURN"))
	private int coffeeclient$swingDuration(int original) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self != Minecraft.getInstance().player) return original;
		return dev.coffeeclient.module.modules.render.HandView.swingDuration(original, self.swingingArm == null ? net.minecraft.world.InteractionHand.MAIN_HAND : self.swingingArm);
	}
}
