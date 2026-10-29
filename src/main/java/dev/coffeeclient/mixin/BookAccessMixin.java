package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.module.modules.misc.ChatTweaks;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** ChatTweaks' filter on written book pages. */
@Mixin(BookViewScreen.BookAccess.class)
abstract class BookAccessMixin {
	@ModifyReturnValue(method = "getPage", at = @At("RETURN"))
	private Component coffeeclient$filterPage(Component original) {
		return ChatTweaks.filterText(original, ChatTweaks.Where.BOOK);
	}
}
