package dev.coffeeclient.module.modules.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModuleBlocklistTest {
	@Test
	void anAddressMatchesWhenItContainsAnEntryIgnoringCase() {
		assertTrue(ModuleBlocklist.matches("mc.Hypixel.net:25565", List.of("hypixel")));
		assertFalse(ModuleBlocklist.matches("play.example.org", List.of("hypixel")));
	}

	@Test
	void blankEntriesNeverMatch() {
		assertFalse(ModuleBlocklist.matches("anything.net", List.of("", "  ")));
		assertFalse(ModuleBlocklist.matches("anything.net", List.of()));
	}
}
