package dev.coffeeclient.module.modules.world.stashfinder;

import java.util.ArrayList;
import java.util.List;

/** Block lists for the man-made detector, taken from BaseFinder (vanilla ids, namespace added). */
public final class NaturalBlocks {
	private NaturalBlocks() {
	}

	/** Blocks that world generation places, so they never count as man-made (editable in the module). */
	public static final List<String> NATURAL = ids(
		"air", "cave_air", "bubble_column", "void_air", "water", "lava", "stone", "dirt", "coarse_dirt", "rooted_dirt",
		"podzol", "mycelium", "grass_block", "gravel", "clay", "sand", "red_sand", "sandstone", "red_sandstone", "granite",
		"diorite", "andesite", "tuff", "calcite", "smooth_basalt", "bedrock", "infested_stone", "mossy_cobblestone",
		"cobblestone", "infested_chiseled_stone_bricks", "infested_cobblestone", "infested_cracked_stone_bricks",
		"infested_deepslate", "infested_mossy_stone_bricks", "infested_stone_bricks", "snow", "snow_block", "powder_snow",
		"ice", "packed_ice", "blue_ice", "amethyst_block", "budding_amethyst", "amethyst_cluster", "large_amethyst_bud",
		"medium_amethyst_bud", "small_amethyst_bud", "dripstone_block", "pointed_dripstone", "glow_lichen", "spore_blossom",
		"small_dripleaf", "big_dripleaf", "cave_vines", "cave_vines_plant", "moss_block", "moss_carpet", "azalea",
		"flowering_azalea", "azalea_leaves", "flowering_azalea_leaves", "hanging_roots", "grass", "tall_grass", "fern",
		"large_fern", "dead_bush", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
		"white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "sunflower", "lilac", "rose_bush",
		"peony", "lily_pad", "sweet_berry_bush", "leaf_litter", "pink_petals", "short_dry_grass", "tall_dry_grass",
		"wildflowers", "bush", "cactus_flower", "firefly_bush", "red_mushroom", "brown_mushroom", "red_mushroom_block",
		"brown_mushroom_block", "mushroom_stem", "oak_log", "oak_leaves", "spruce_log", "spruce_leaves", "birch_log",
		"birch_leaves", "jungle_log", "jungle_leaves", "acacia_log", "acacia_leaves", "dark_oak_log", "dark_oak_leaves",
		"mangrove_log", "mangrove_leaves", "mangrove_roots", "muddy_mangrove_roots", "mangrove_propagule", "cherry_log",
		"cherry_leaves", "bamboo", "seagrass", "tall_seagrass", "kelp", "kelp_plant", "sea_pickle", "sugar_cane",
		"tube_coral_block", "brain_coral_block", "bubble_coral_block", "fire_coral_block", "horn_coral_block", "tube_coral",
		"brain_coral", "bubble_coral", "fire_coral", "horn_coral", "tube_coral_fan", "brain_coral_fan", "bubble_coral_fan",
		"fire_coral_fan", "horn_coral_fan", "dead_tube_coral_block", "dead_brain_coral_block", "dead_bubble_coral_block",
		"dead_fire_coral_block", "dead_horn_coral_block", "dead_tube_coral", "dead_brain_coral", "dead_bubble_coral",
		"dead_fire_coral", "dead_horn_coral", "dead_tube_coral_fan", "dead_brain_coral_fan", "dead_bubble_coral_fan",
		"dead_fire_coral_fan", "dead_horn_coral_fan", "wet_sponge", "coal_ore", "iron_ore", "copper_ore", "gold_ore",
		"redstone_ore", "emerald_ore", "diamond_ore", "lapis_ore", "deepslate", "deepslate_coal_ore", "deepslate_iron_ore",
		"deepslate_copper_ore", "deepslate_gold_ore", "deepslate_redstone_ore", "deepslate_emerald_ore",
		"deepslate_diamond_ore", "deepslate_lapis_ore", "raw_copper_block", "suspicious_sand", "suspicious_gravel", "sculk",
		"sculk_vein", "sculk_sensor", "sculk_catalyst", "sculk_shrieker", "netherrack", "basalt", "blackstone", "soul_sand",
		"soul_soil", "magma_block", "nether_quartz_ore", "nether_gold_ore", "ancient_debris", "glowstone", "crimson_nylium",
		"warped_nylium", "crimson_stem", "warped_stem", "crimson_roots", "warped_roots", "nether_sprouts", "shroomlight",
		"weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant", "nether_wart_block",
		"warped_wart_block", "crimson_fungus", "warped_fungus", "nether_wart", "end_stone", "chorus_plant", "chorus_flower",
		"pale_oak_log", "pale_oak_leaves", "pale_moss_block", "pale_moss_carpet", "pale_hanging_moss", "open_eyeblossom",
		"closed_eyeblossom", "resin_clump", "pumpkin", "melon", "cocoa", "trial_spawner", "vault", "candle", "cobweb",
		"chiseled_tuff", "chiseled_tuff_bricks", "polished_tuff", "polished_tuff_slab", "tuff_bricks", "copper_block",
		"cut_copper", "cut_copper_slab", "cut_copper_stairs", "chiseled_copper", "exposed_copper", "exposed_cut_copper",
		"exposed_cut_copper_slab", "exposed_cut_copper_stairs", "exposed_chiseled_copper", "weathered_copper",
		"weathered_cut_copper", "weathered_cut_copper_slab", "weathered_cut_copper_stairs", "weathered_chiseled_copper",
		"oxidized_copper", "oxidized_cut_copper", "oxidized_cut_copper_slab", "oxidized_cut_copper_stairs",
		"oxidized_chiseled_copper", "waxed_copper_block", "waxed_cut_copper", "waxed_cut_copper_slab",
		"waxed_cut_copper_stairs", "waxed_chiseled_copper", "waxed_exposed_copper", "waxed_exposed_cut_copper",
		"waxed_exposed_cut_copper_slab", "waxed_exposed_cut_copper_stairs", "waxed_exposed_chiseled_copper",
		"waxed_weathered_copper", "waxed_weathered_cut_copper", "waxed_weathered_cut_copper_slab",
		"waxed_weathered_cut_copper_stairs", "waxed_weathered_chiseled_copper", "waxed_oxidized_copper",
		"waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper_slab", "waxed_oxidized_cut_copper_stairs",
		"waxed_oxidized_chiseled_copper", "copper_bars", "copper_grate", "copper_chain", "copper_lantern", "copper_bulb",
		"copper_door", "copper_trapdoor", "copper_torch", "copper_wall_torch", "copper_chest", "copper_golem_statue",
		"exposed_copper_bars", "exposed_copper_grate", "exposed_copper_chain", "exposed_copper_lantern",
		"exposed_copper_bulb", "exposed_copper_door", "exposed_copper_trapdoor", "exposed_copper_chest",
		"exposed_copper_golem_statue", "weathered_copper_bars", "weathered_copper_grate", "weathered_copper_chain",
		"weathered_copper_lantern", "weathered_copper_bulb", "weathered_copper_door", "weathered_copper_trapdoor",
		"weathered_copper_chest", "weathered_copper_golem_statue", "oxidized_copper_bars", "oxidized_copper_grate",
		"oxidized_copper_chain", "oxidized_copper_lantern", "oxidized_copper_bulb", "oxidized_copper_door",
		"oxidized_copper_trapdoor", "oxidized_copper_chest", "oxidized_copper_golem_statue", "waxed_copper_bars",
		"waxed_copper_grate", "waxed_copper_chain", "waxed_copper_lantern", "waxed_copper_bulb", "waxed_copper_door",
		"waxed_copper_trapdoor", "waxed_copper_chest", "waxed_copper_golem_statue", "waxed_exposed_copper_bars",
		"waxed_exposed_copper_grate", "waxed_exposed_copper_chain", "waxed_exposed_copper_lantern",
		"waxed_exposed_copper_bulb", "waxed_exposed_copper_door", "waxed_exposed_copper_trapdoor",
		"waxed_exposed_copper_chest", "waxed_exposed_copper_golem_statue", "waxed_weathered_copper_bars",
		"waxed_weathered_copper_grate", "waxed_weathered_copper_chain", "waxed_weathered_copper_lantern",
		"waxed_weathered_copper_bulb", "waxed_weathered_copper_door", "waxed_weathered_copper_trapdoor",
		"waxed_weathered_copper_chest", "waxed_weathered_copper_golem_statue", "waxed_oxidized_copper_bars",
		"waxed_oxidized_copper_grate", "waxed_oxidized_copper_chain", "waxed_oxidized_copper_lantern",
		"waxed_oxidized_copper_bulb", "waxed_oxidized_copper_door", "waxed_oxidized_copper_trapdoor",
		"waxed_oxidized_copper_chest", "waxed_oxidized_copper_golem_statue", "obsidian", "crying_obsidian", "spawner",
		"oak_planks", "oak_fence", "rail", "torch", "wall_torch", "packed_mud", "vine");

	/** Always man-made, even when also natural-looking (beds, terracotta, coral, stone bricks, ...). */
	static final List<String> MAN_MADE = ids(
		"raw_iron_block", "bee_nest", "iron_chain", "potted_dead_bush", "cobbled_deepslate", "red_candle",
		"red_glazed_terracotta", "chiseled_sandstone", "dispenser", "ladder", "cactus", "mossy_stone_bricks",
		"cracked_stone_bricks", "chiseled_stone_bricks", "stone_brick_stairs", "stone_bricks", "iron_bars",
		"stone_brick_slab", "mossy_stone_brick_stairs", "red_concrete", "white_stained_glass", "black_stained_glass", "mud",
		"stone_button", "mangrove_wood", "cut_sandstone", "polished_granite", "light_blue_terracotta", "sandstone_stairs",
		"stone_slab", "tripwire", "tripwire_hook", "creaking_heart", "dirt_path", "white_bed", "orange_bed", "magenta_bed",
		"light_blue_bed", "yellow_bed", "lime_bed", "pink_bed", "gray_bed", "light_gray_bed", "cyan_bed", "purple_bed",
		"blue_bed", "brown_bed", "green_bed", "red_bed", "black_bed", "terracotta", "white_terracotta", "orange_terracotta",
		"magenta_terracotta", "yellow_terracotta", "lime_terracotta", "pink_terracotta", "gray_terracotta",
		"light_gray_terracotta", "cyan_terracotta", "purple_terracotta", "blue_terracotta", "brown_terracotta",
		"green_terracotta", "red_terracotta", "black_terracotta", "tube_coral_block", "brain_coral_block",
		"bubble_coral_block", "fire_coral_block", "horn_coral_block", "tube_coral", "brain_coral", "bubble_coral",
		"fire_coral", "horn_coral", "tube_coral_fan", "brain_coral_fan", "bubble_coral_fan", "fire_coral_fan",
		"horn_coral_fan", "tube_coral_wall_fan", "brain_coral_wall_fan", "bubble_coral_wall_fan", "fire_coral_wall_fan",
		"horn_coral_wall_fan", "dead_tube_coral_block", "dead_brain_coral_block", "dead_bubble_coral_block",
		"dead_fire_coral_block", "dead_horn_coral_block", "dead_tube_coral", "dead_brain_coral", "dead_bubble_coral",
		"dead_fire_coral", "dead_horn_coral", "dead_tube_coral_fan", "dead_brain_coral_fan", "dead_bubble_coral_fan",
		"dead_fire_coral_fan", "dead_horn_coral_fan", "dead_tube_coral_wall_fan", "dead_brain_coral_wall_fan",
		"dead_bubble_coral_wall_fan", "dead_fire_coral_wall_fan", "dead_horn_coral_wall_fan");

	/** Man-made unless waterlogged or underwater (prismarine and ocean-ruin wood sets). */
	static final List<String> MAN_MADE_WHEN_DRY = ids(
		"sea_lantern", "prismarine", "prismarine_slab", "prismarine_stairs", "prismarine_wall", "prismarine_bricks",
		"prismarine_brick_slab", "prismarine_brick_stairs", "dark_prismarine", "dark_prismarine_slab",
		"dark_prismarine_stairs", "spruce_planks", "spruce_stairs", "spruce_slab", "spruce_fence", "spruce_fence_gate",
		"spruce_door", "spruce_trapdoor", "spruce_button", "spruce_pressure_plate", "spruce_sign", "spruce_wall_sign",
		"spruce_hanging_sign", "spruce_wall_hanging_sign", "jungle_planks", "jungle_stairs", "jungle_slab", "jungle_fence",
		"jungle_fence_gate", "jungle_door", "jungle_trapdoor", "jungle_button", "jungle_pressure_plate", "jungle_sign",
		"jungle_wall_sign", "jungle_hanging_sign", "jungle_wall_hanging_sign");

	private static List<String> ids(String... paths) {
		List<String> out = new ArrayList<>(paths.length);
		for (String path : paths) out.add("minecraft:" + path);
		return List.copyOf(out);
	}
}
