package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.exploit.Spoofs;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Spoofs: the sign lines the editor starts from, resolved as vanilla would (they are sent back on close). */
@Mixin(AbstractSignEditScreen.class)
abstract class AbstractSignEditScreenMixin {
	@Shadow
	private SignText text;
	@Shadow
	@Final
	private String[] messages;

	// the lines are read through a method reference, so redo them once the constructor is done
	@Inject(method = "<init>(Lnet/minecraft/world/level/block/entity/SignBlockEntity;ZZLnet/minecraft/network/chat/Component;)V", at = @At("RETURN"))
	private void coffeeclient$vanillaLines(SignBlockEntity sign, boolean isFrontText, boolean shouldFilter, Component title, CallbackInfo ci) {
		if (!Spoofs.protectsText()) return;
		for (int i = 0; i < messages.length; i++) messages[i] = Spoofs.vanillaText(text.getMessage(i, shouldFilter), "sign");
	}
}
