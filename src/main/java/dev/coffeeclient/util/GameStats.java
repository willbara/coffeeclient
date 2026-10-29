package dev.coffeeclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;

import java.util.Locale;
import java.util.OptionalLong;

/**
 * Live values for the dashboard, read from loaded mods, game registries and the client's world. Values
 * the client can't legitimately know (e.g. the seed on a remote server) are reported as unknown.
 */
public final class GameStats {
	public static final String UNKNOWN = "Unknown";

	private GameStats() {
	}

	/** Seed only when the integrated (singleplayer/LAN host) server is running in this client. */
	public static OptionalLong seed() {
		MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) return OptionalLong.empty();
		try {
			return OptionalLong.of(server.overworld().getSeed());
		} catch (RuntimeException e) {
			return OptionalLong.empty();
		}
	}

	public static String dimensionId(ClientLevel level) {
		return level == null ? UNKNOWN : level.dimension().identifier().toString();
	}

	public static String dimensionShort(ClientLevel level) {
		return level == null ? "-" : level.dimension().identifier().getPath();
	}

	public static String biomeName(ClientLevel level, BlockPos pos) {
		if (level == null) return UNKNOWN;
		return biomeName(level.getBiome(pos));
	}

	public static String biomeName(Holder<Biome> holder) {
		return holder.unwrapKey().map(GameStats::biomeName).orElse(UNKNOWN);
	}

	public static String biomeName(ResourceKey<Biome> key) {
		return biomeName(key.identifier());
	}

	/** Translated biome name, falling back to a title-cased path for modded biomes without a lang entry. */
	public static String biomeName(Identifier id) {
		String langKey = "biome." + id.getNamespace() + "." + id.getPath();
		String translated = Component.translatable(langKey).getString();
		if (!translated.equals(langKey)) return translated;
		String[] words = id.getPath().replace('/', ' ').split("_");
		StringBuilder sb = new StringBuilder();
		for (String w : words) {
			if (w.isEmpty()) continue;
			if (!sb.isEmpty()) sb.append(' ');
			sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
		}
		return sb.toString();
	}

	public static long usedMemoryMb() {
		Runtime rt = Runtime.getRuntime();
		return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
	}

	public static long maxMemoryMb() {
		return Runtime.getRuntime().maxMemory() / (1024 * 1024);
	}

	public static int loadedChunks() {
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? 0 : level.getChunkSource().getLoadedChunksCount();
	}
}
