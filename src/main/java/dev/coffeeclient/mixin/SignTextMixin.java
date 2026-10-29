package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.module.modules.misc.ChatTweaks;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** ChatTweaks' filter on sign lines. */
@Mixin(SignText.class)
abstract class SignTextMixin {
	@ModifyReturnValue(method = "getMessage", at = @At("RETURN"))
	private Component coffeeclient$filterLine(Component original) {
		return ChatTweaks.filterText(original, ChatTweaks.Where.SIGN);
	}
}
