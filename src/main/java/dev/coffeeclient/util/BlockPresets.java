package dev.coffeeclient.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** One-click block groups for the block pickers (BlockESP). */
public final class BlockPresets {
	private BlockPresets() {
	}

	public static Map<String, Supplier<List<String>>> all() {
		Map<String, Supplier<List<String>>> presets = new LinkedHashMap<>();
		presets.put("Beds", () -> tagged(BlockTags.BEDS));
		presets.put("Signs", () -> tagged(BlockTags.ALL_SIGNS));
		presets.put("Redstone", () -> ids("redstone_wire", "repeater", "comparator", "observer", "piston", "sticky_piston", "dispenser",
			"dropper", "hopper", "redstone_lamp", "redstone_torch", "redstone_wall_torch", "redstone_block", "lever", "target", "daylight_detector", "tnt"));
		presets.put("Portals", () -> ids("nether_portal", "end_portal", "end_portal_frame", "end_gateway"));
		presets.put("Barriers", () -> ids("barrier", "light", "structure_void", "structure_block", "jigsaw", "command_block",
			"chain_command_block", "repeating_command_block"));
		presets.put("Workstations", () -> ids("crafting_table", "furnace", "blast_furnace", "smoker", "smithing_table", "fletching_table",
			"cartography_table", "loom", "grindstone", "stonecutter", "anvil", "brewing_stand", "enchanting_table", "lectern", "composter", "barrel"));
		presets.put("Lava and water", () -> ids("lava", "water"));
		presets.put("Spawners", () -> ids("spawner", "trial_spawner", "vault"));
		return presets;
	}

	private static List<String> tagged(TagKey<Block> tag) {
		return BuiltInRegistries.BLOCK.stream().filter(b -> b.defaultBlockState().is(tag)).map(BlockIds::id).sorted().toList();
	}

	/** The given vanilla block names that exist in this version. */
	private static List<String> ids(String... names) {
		return java.util.Arrays.stream(names).map(n -> Identifier.withDefaultNamespace(n).toString()).filter(id -> BlockIds.of(id) != null).toList();
	}
}
