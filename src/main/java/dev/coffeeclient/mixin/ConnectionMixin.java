package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.exploit.AntiPacketKick;
import dev.coffeeclient.module.modules.exploit.PacketRate;
import dev.coffeeclient.util.PacketBatch;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.timeout.TimeoutException;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.SkipPacketEncoderException;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packet events for the client's own connection (the integrated server's connections are ignored).
 * Outgoing packets go through {@link Events.PacketSend}, where modules rewrite (NoFall, Jesus,
 * BuildHeight) or drop (Blink, Freecam, AntiHunger) them in priority order. Remote connections also go
 * through the active proxy (the proxy manager).
 */
@Mixin(Connection.class)
abstract class ConnectionMixin {
	/**
	 * The client's own connection to the server it is joining or playing on, from login onwards (so
	 * Spoofs sees the brand and channel packets of the configuration phase). Server-list pings and the
	 * integrated server's side of the connection have other listeners.
	 */
	private boolean coffeeclient$isClientConnection() {
		PacketListener listener = ((Connection) (Object) this).getPacketListener();
		return listener instanceof ClientCommonPacketListenerImpl || listener instanceof ClientHandshakePacketListenerImpl;
	}

	/** The proxy manager: the active proxy's handler goes first, for connections to remote servers. */
	@Inject(method = "configureSerialization", at = @At("RETURN"))
	private static void coffeeclient$proxy(ChannelPipeline pipeline, PacketFlow inbound, boolean local, BandwidthDebugMonitor monitor, CallbackInfo ci) {
		if (inbound != PacketFlow.CLIENTBOUND || local || CoffeeClient.proxies() == null) return;
		ProxyHandler handler = CoffeeClient.proxies().handler();
		if (handler != null) pipeline.addFirst("coffeeclient_proxy", handler);
	}

	@Inject(method = "channelRead0", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$onRead(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
		if (CoffeeClient.events() == null || !coffeeclient$isClientConnection()) return;
		if (CoffeeClient.events().post(new Events.PacketReceive(packet, (Connection) (Object) this)).isCancelled()) ci.cancel();
	}

	@Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$exceptionCaught(ChannelHandlerContext ctx, Throwable cause, CallbackInfo ci) {
		AntiPacketKick apk = ModuleManager.ifOn(AntiPacketKick.class);
		if (apk == null || !apk.catchExceptions()) return;
		if (cause instanceof TimeoutException || cause instanceof SkipPacketEncoderException) return;
		if (apk.logExceptions.get()) CoffeeClient.LOG.warn("Caught exception: {}", cause);
		ci.cancel();
	}

	/**
	 * Posts {@link Events.PacketSend} and {@link Events.PacketOut}. On the client thread the packet and
	 * anything its handlers send (Criticals' hops before an attack) share one {@link PacketBatch}, so
	 * they leave in a single flush.
	 */
	@WrapMethod(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V")
	private void coffeeclient$onSend(Packet<?> packet, ChannelFutureListener listener, boolean flush, Operation<Void> original) {
		EventBus bus = CoffeeClient.events();
		if (bus == null || !coffeeclient$isClientConnection()) {
			original.call(packet, listener, flush);
			return;
		}
		// A packet PacketRate held back: its rewrites already ran, so it goes out without the send event.
		if (PacketRate.releasing()) {
			boolean batched = flush && PacketBatch.active();
			original.call(packet, listener, flush && !batched);
			if (batched) PacketBatch.deferFlush((Connection) (Object) this);
			bus.post(new Events.PacketOut(packet));
			return;
		}
		PacketBatch.run(() -> {
			Events.PacketSend send = bus.post(new Events.PacketSend(packet, (Connection) (Object) this));
			if (send.isCancelled() || send.packet == null) return;
			bus.post(new Events.PacketOut(send.packet));
			boolean batched = flush && PacketBatch.active();
			original.call(send.packet, listener, flush && !batched);
			if (batched) PacketBatch.deferFlush((Connection) (Object) this);
		});
	}
}
