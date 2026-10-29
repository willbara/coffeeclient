package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.module.modules.render.Ambience;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Ambience: the moon phase, which the sky reads from the level's attribute system rather than a probe. */
@Mixin(EnvironmentAttributeSystem.class)
abstract class EnvironmentAttributeSystemMixin {
	@ModifyReturnValue(method = {
		"getDimensionValue(Lnet/minecraft/world/attribute/EnvironmentAttribute;)Ljava/lang/Object;",
		"getValue(Lnet/minecraft/world/attribute/EnvironmentAttribute;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/attribute/SpatialAttributeInterpolator;)Ljava/lang/Object;"},
		at = @At("RETURN"), require = 2)
	private Object coffeeclient$moonPhase(Object original, EnvironmentAttribute<?> attribute) {
		if (attribute != EnvironmentAttributes.MOON_PHASE) return original;
		var level = Minecraft.getInstance().level;
		if (level == null || (Object) this != level.environmentAttributes()) return original;
		return Ambience.moonPhase((MoonPhase) original);
	}
}
