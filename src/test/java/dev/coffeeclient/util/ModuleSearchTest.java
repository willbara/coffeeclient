package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModuleSearchTest {
	private static ModuleSearch.Hit hit(String query) {
		return ModuleSearch.score(query, "ElytraFly", List.of("ElytraPlus"), "Fly with an elytra", List.of("Auto take-off", "Use fireworks"));
	}

	@Test
	void nameBeatsEverythingElse() {
		assertEquals(0, hit("elytrafly").score());
		assertEquals(1, hit("elytra").score());
		assertEquals(2, hit("fly").score());
	}

	@Test
	void settingsAndAliasesGiveAReason() {
		assertEquals("Alias: ElytraPlus", hit("plus").reason());
		assertEquals("Setting: Use fireworks", hit("fireworks").reason());
		assertEquals("Setting: Auto take-off", hit("take off").reason());
	}

	@Test
	void typosOnlyWithinTolerance() {
		assertEquals(ModuleSearch.TYPO + 1, hit("elytrs").score());
		assertNull(hit("zzzz"));
		assertNull(hit("ab"));
	}

	@Test
	void editDistance() {
		assertEquals(0, ModuleSearch.distance("abc", "abc"));
		assertEquals(1, ModuleSearch.distance("abc", "abd"));
		assertEquals(3, ModuleSearch.distance("", "abc"));
	}
}
