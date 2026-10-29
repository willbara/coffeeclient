package dev.coffeeclient.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Registry helpers for block ids. Shared by every block-list setting (StorageESP, block ESP
 * modules) so the option list, display names and lookups stay consistent.
 */
public final class BlockIds {
	private BlockIds() {
	}

	/** Every block id, sorted, without air. */
	public static List<String> all() {
		return BuiltInRegistries.BLOCK.keySet().stream()
			.map(Identifier::toString)
			.filter(id -> !id.equals("minecraft:air"))
			.sorted()
			.toList();
	}

	/** Display name for a block id, falling back to the raw id. */
	public static String name(String id) {
		Block block = of(id);
		return block == null ? id : block.getName().getString();
	}

	public static Block of(String id) {
		Identifier key = Identifier.tryParse(id);
		return key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
	}

	public static String id(Block block) {
		Identifier key = BuiltInRegistries.BLOCK.getKey(block);
		return key == null ? "" : key.toString();
	}
}
