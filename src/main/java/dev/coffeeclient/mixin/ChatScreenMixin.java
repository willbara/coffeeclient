package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.coffeeclient.module.modules.client.StreamerMode;
import dev.coffeeclient.module.modules.misc.ChatEffects;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.util.StringUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * ChatEffects' long commands: a bigger chat box, and commands are not cut to 256 characters on send.
 * StreamerMode: what you type after a login command shows as stars.
 */
@Mixin(ChatScreen.class)
abstract class ChatScreenMixin {
	@Shadow
	protected EditBox input;

	@Inject(method = "formatChat", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$maskPassword(String text, int offset, CallbackInfoReturnable<FormattedCharSequence> cir) {
		FormattedCharSequence masked = StreamerMode.maskInput(input.getValue(), text, offset);
		if (masked != null) cir.setReturnValue(masked);
	}

	@ModifyArg(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;setMaxLength(I)V"))
	private int coffeeclient$inputLimit(int original) {
		return ChatEffects.inputLimit(original);
	}

	@WrapOperation(method = "normalizeChatMessage", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/StringUtil;trimChatMessage(Ljava/lang/String;)Ljava/lang/String;"))
	private String coffeeclient$keepCommand(String message, Operation<String> original) {
		return ChatEffects.keepsLongCommand(message) ? message : original.call(message);
	}
}
