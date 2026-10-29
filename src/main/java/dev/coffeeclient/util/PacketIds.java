package dev.coffeeclient.util;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Packet type ids as strings, built once per type: they are looked up for every packet on the network thread. */
public final class PacketIds {
	private static final Map<PacketType<?>, String> IDS = new ConcurrentHashMap<>();

	private PacketIds() {
	}

	/** The packet's type id (e.g. {@code minecraft:keep_alive}), or its class name when it has none. */
	public static String of(Packet<?> packet) {
		if (packet == null) return null;
		PacketType<?> type;
		try {
			type = packet.type();
		} catch (RuntimeException e) {
			return packet.getClass().getSimpleName();
		}
		if (type == null || type.id() == null) return packet.getClass().getSimpleName();
		return IDS.computeIfAbsent(type, t -> t.id().toString());
	}
}
