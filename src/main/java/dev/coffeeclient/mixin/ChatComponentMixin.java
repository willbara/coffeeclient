package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.coffeeclient.module.modules.misc.ChatTweaks;
import dev.coffeeclient.module.modules.misc.ChatTimestamps;
import dev.coffeeclient.module.modules.misc.NoChatReports;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.MessageSignature;

import java.util.List;

/** ChatTweaks (hide/censor, AntiSpam stacking, mentions), ChatTimestamps (prefix every line) and NoChatReports (tags, refusal text). */
@Mixin(ChatComponent.class)
abstract class ChatComponentMixin {
	@Shadow
	@Final
	private List<GuiMessage> allMessages;

	@Shadow
	protected abstract void refreshTrimmedMessages();

	/** AntiSpam key and count of the message being added, for {@link #coffeeclient$recordStack}. */
	@Unique
	private String coffeeclient$stackKey;
	@Unique
	private int coffeeclient$stackCount;

	@ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Component coffeeclient$timestamp(Component message, @Local(argsOnly = true) GuiMessageSource source) {
		boolean clientSide = source == GuiMessageSource.SYSTEM_CLIENT;
		coffeeclient$stackKey = ChatTweaks.shouldHide(message, clientSide) ? null : ChatTweaks.stackKey(message);
		coffeeclient$stackCount = ChatTweaks.takeRepeat(allMessages, coffeeclient$stackKey);
		if (coffeeclient$stackCount > 1) refreshTrimmedMessages();
		message = NoChatReports.explain(message);
		message = ChatTweaks.withCount(ChatTweaks.mention(ChatTweaks.censor(message, clientSide), clientSide), coffeeclient$stackCount);
		return ChatTimestamps.decorate(message, source != GuiMessageSource.PLAYER);
	}

	@Inject(method = "addMessageToQueue", at = @At("TAIL"))
	private void coffeeclient$recordStack(GuiMessage message, CallbackInfo ci) {
		ChatTweaks.recordStack(allMessages, message, coffeeclient$stackKey, coffeeclient$stackCount);
		coffeeclient$stackKey = null;
	}

	/** ChatTweaks' longer history. */
	@org.spongepowered.asm.mixin.injection.ModifyConstant(method = {"addMessageToQueue", "addMessageToDisplayQueue"}, constant = @org.spongepowered.asm.mixin.injection.Constant(intValue = 100))
	private int coffeeclient$historyLength(int vanilla) {
		return ChatTweaks.historyLength(vanilla);
	}

	@ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true)
	private GuiMessageTag coffeeclient$reportableTag(GuiMessageTag tag, @Local(argsOnly = true) MessageSignature signature) {
		return NoChatReports.tag(signature, tag);
	}

	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
		at = @At("HEAD"), cancellable = true)
	private void coffeeclient$filter(Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
		if (ChatTweaks.shouldHide(message, source == GuiMessageSource.SYSTEM_CLIENT)) ci.cancel();
	}
}
