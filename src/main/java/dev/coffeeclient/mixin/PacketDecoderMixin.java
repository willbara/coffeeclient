package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Events;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * Reports each incoming play packet with its decoded size (PacketInspector). Only server-to-client
 * packets are reported, the mirror of {@link PacketEncoderMixin}.
 */
@Mixin(PacketDecoder.class)
abstract class PacketDecoderMixin {
	@WrapMethod(method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V")
	private void coffeeclient$wire(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, Operation<Void> original) throws Exception {
		// Nothing listens unless PacketInspector is on: skip the bookkeeping on every packet.
		var bus = CoffeeClient.events();
		if (bus == null || !bus.has(Events.PacketWire.class)) {
			original.call(ctx, in, out);
			return;
		}
		int before = out.size();
		int start = in.readerIndex();
		original.call(ctx, in, out);
		if (out.size() == before || Minecraft.getInstance().player == null) return;
		int bytes = in.readerIndex() - start;
		for (int i = before; i < out.size(); i++) {
			if (out.get(i) instanceof Packet<?> packet && packet.type().flow() == PacketFlow.CLIENTBOUND) {
				bus.post(new Events.PacketWire(false, packet, bytes));
			}
		}
	}
}
