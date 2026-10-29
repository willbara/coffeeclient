package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.coffeeclient.module.modules.exploit.Spoofs;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Spoofs: the item name the anvil fills in from the server, resolved as vanilla would. */
@Mixin(AnvilScreen.class)
abstract class AnvilScreenMixin {
	@WrapOperation(method = "slotChanged", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"))
	private String coffeeclient$vanillaName(Component name, Operation<String> original) {
		return Spoofs.protectsText() ? Spoofs.vanillaText(name, "anvil") : original.call(name);
	}
}
