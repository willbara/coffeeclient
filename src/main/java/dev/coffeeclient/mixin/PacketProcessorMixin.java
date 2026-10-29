package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.coffeeclient.core.PacketApplyHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Posts {@code Events.PacketApply} around each game packet the client thread handles. Bundles are left
 * out here: {@code ClientPacketListenerMixin} posts their packets one by one.
 */
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
abstract class PacketProcessorMixin {
	@Shadow
	@Final
	private PacketListener listener;

	@Shadow
	@Final
	private Packet<?> packet;

	@WrapMethod(method = "handle")
	private void coffeeclient$apply(Operation<Void> original) {
		if (!(listener instanceof ClientPacketListener) || packet instanceof ClientboundBundlePacket) {
			original.call();
			return;
		}
		PacketApplyHooks.apply(packet, original::call);
	}
}
