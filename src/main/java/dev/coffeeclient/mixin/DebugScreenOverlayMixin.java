package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.client.StreamerMode;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/** StreamerMode: hides coordinate lines on the vanilla F3 debug screen. */
@Mixin(DebugScreenOverlay.class)
abstract class DebugScreenOverlayMixin {
	@ModifyVariable(method = "extractLines", at = @At("HEAD"), argsOnly = true)
	private static List<String> coffeeclient$hideCoordinates(List<String> lines) {
		if (!StreamerMode.hideCoords() || lines.isEmpty()) return lines;
		List<String> masked = new ArrayList<>(lines.size());
		for (String line : lines) masked.add(StreamerMode.maskCoordinates(line));
		return masked;
	}
}
