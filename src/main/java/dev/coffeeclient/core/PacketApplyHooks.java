package dev.coffeeclient.core;

import net.minecraft.network.protocol.Packet;

/**
 * Shared body of the packet-apply mixins: Pre (may skip the packet), vanilla's handling, Post. Lives
 * outside the mixin package because mixin code may not load classes from there.
 */
public final class PacketApplyHooks {
	private PacketApplyHooks() {
	}

	public static void apply(Packet<?> packet, Runnable handle) {
		EventBus bus = CoffeeClient.events();
		if (bus == null) {
			handle.run();
			return;
		}
		if (bus.has(Events.PacketApply.Pre.class) && bus.post(new Events.PacketApply.Pre(packet)).isCancelled()) return;
		handle.run();
		if (bus.has(Events.PacketApply.Post.class)) bus.post(new Events.PacketApply.Post(packet));
	}
}
