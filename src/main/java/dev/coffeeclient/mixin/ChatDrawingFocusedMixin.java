package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.misc.ChatTweaks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ChatTweaks' player heads: the face before the line, and the text moved over to make room. */
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$DrawingFocusedGraphicsAccess")
abstract class ChatDrawingFocusedMixin {
	@Shadow
	@Final
	private GuiGraphicsExtractor graphics;

	@Inject(method = "handleMessage", at = @At("HEAD"))
	private void coffeeclient$head(int textTop, float opacity, FormattedCharSequence message, CallbackInfoReturnable<Boolean> cir) {
		ChatTweaks.drawHead(graphics, textTop, opacity, message);
	}

	@ModifyArg(method = "handleMessage", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/ActiveTextCollector;accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/client/gui/ActiveTextCollector$Parameters;Lnet/minecraft/util/FormattedCharSequence;)V"), index = 1)
	private int coffeeclient$shift(int x) {
		return x + ChatTweaks.headOffset();
	}
}
