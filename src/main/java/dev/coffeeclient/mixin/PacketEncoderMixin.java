package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Events;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Reports each outgoing play packet with its encoded size (PacketInspector). Only client-to-server
 * packets are reported, so the integrated server's side of a singleplayer connection is not counted twice.
 */
@Mixin(PacketEncoder.class)
abstract class PacketEncoderMixin {
	@WrapMethod(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V")
	private void coffeeclient$wire(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, Operation<Void> original) throws Exception {
		// Nothing listens unless PacketInspector is on: skip the bookkeeping on every packet.
		var bus = CoffeeClient.events();
		if (bus == null || !bus.has(Events.PacketWire.class)) {
			original.call(ctx, packet, out);
			return;
		}
		int start = out.writerIndex();
		original.call(ctx, packet, out);
		if (packet.type().flow() != PacketFlow.SERVERBOUND || Minecraft.getInstance().player == null) return;
		bus.post(new Events.PacketWire(true, packet, out.writerIndex() - start));
	}
}
