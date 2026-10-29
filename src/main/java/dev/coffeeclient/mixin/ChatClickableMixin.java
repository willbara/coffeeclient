package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.misc.ChatTweaks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** ChatTweaks' player heads: clickable chat text moves with the drawn text. */
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$ClickableTextOnlyGraphicsAccess")
abstract class ChatClickableMixin {
	@ModifyArg(method = "handleMessage", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/ActiveTextCollector;accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/util/FormattedCharSequence;)V"), index = 1)
	private int coffeeclient$shift(int x) {
		return x + ChatTweaks.headOffset();
	}
}
