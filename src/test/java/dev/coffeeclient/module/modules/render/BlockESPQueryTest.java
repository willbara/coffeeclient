package dev.coffeeclient.module.modules.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockESPQueryTest {
	@Test
	void matchesIdPathSpacedPathAndName() {
		assertTrue(BlockESP.matchesTerms("ore", "minecraft:diamond_ore", "Diamond Ore"));
		assertTrue(BlockESP.matchesTerms("diamond ore", "minecraft:deepslate_diamond_ore", "Deepslate Diamond Ore"));
		assertTrue(BlockESP.matchesTerms("diamond", "minecraft:deepslate_diamond_ore", "Deepslate Diamond Ore"));
		assertFalse(BlockESP.matchesTerms("gold", "minecraft:diamond_ore", "Diamond Ore"));
	}

	@Test
	void termsSplitOnCommasAndEmptyTermsMatchNothing() {
		assertTrue(BlockESP.matchesTerms(" gold , diamond", "minecraft:diamond_ore", "Diamond Ore"));
		assertFalse(BlockESP.matchesTerms(" , ", "minecraft:stone", "Stone"));
	}
}
