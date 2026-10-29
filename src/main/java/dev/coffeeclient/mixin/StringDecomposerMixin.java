package dev.coffeeclient.mixin;

import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.client.PlayerProtect;
import net.minecraft.util.StringDecomposer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(StringDecomposer.class)
public abstract class StringDecomposerMixin {
	@ModifyArg(at = @At(value = "INVOKE",
		target = "Lnet/minecraft/util/StringDecomposer;iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z",
		ordinal = 0),
		method = {
			"iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z"},
		index = 0)
	private static String adjustText(String text) {
		if (PlayerProtect.suspended) return text;
		PlayerProtect protect = ModuleManager.ifOn(PlayerProtect.class);
		return protect == null ? text : protect.replaceName(text);
	}
}
