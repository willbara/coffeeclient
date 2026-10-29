package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.coffeeclient.module.modules.render.BossStack;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import java.util.Iterator;

/** BossStack: replace the bar iterator, append counters to titles, and adjust spacing. */
@Mixin(BossHealthOverlay.class)
abstract class BossHealthOverlayMixin {
	@ModifyExpressionValue(method = "extractRenderState",
		at = @At(value = "INVOKE", target = "Ljava/util/Collection;iterator()Ljava/util/Iterator;"))
	private Iterator<LerpingBossEvent> coffeeclient$stackBars(Iterator<LerpingBossEvent> original) {
		return BossStack.stackBars(original);
	}

	@ModifyExpressionValue(method = "extractRenderState",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/LerpingBossEvent;getName()Lnet/minecraft/network/chat/Component;"))
	private Component coffeeclient$barName(Component original, @Local LerpingBossEvent event) {
		return BossStack.barName(event, original);
	}

	@ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = 9, ordinal = 1))
	private int coffeeclient$barSpacing(int value) {
		return BossStack.barSpacing(value);
	}
}
