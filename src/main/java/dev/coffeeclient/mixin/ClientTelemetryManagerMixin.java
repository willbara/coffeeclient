package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.module.modules.misc.NoChatReports;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.client.telemetry.TelemetryEventSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NoChatReports' Block telemetry: events are handed to a sender that checks the setting each time. */
@Mixin(ClientTelemetryManager.class)
abstract class ClientTelemetryManagerMixin {
	@ModifyReturnValue(method = "createEventSender", at = @At("RETURN"))
	private TelemetryEventSender coffeeclient$noTelemetry(TelemetryEventSender original) {
		return (type, build) -> {
			if (!NoChatReports.blocksTelemetry()) original.send(type, build);
		};
	}
}
