package dev.coffeeclient.module.modules.client;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NotifierRuleTest {
	@Test
	void bareNameWatchesEverything() {
		Notifier.Rule r = Notifier.Rule.parse("Steve");
		assertEquals("Steve", r.name());
		assertEquals(Notifier.Rule.ALL, r.events());
		assertNull(r.sound());
		assertNull(r.command());
	}

	@Test
	void eventsSoundAndCommand() {
		Notifier.Rule r = Notifier.Rule.parse("Alex join death nosound | /msg {name} hi");
		assertEquals(Set.of("joined", "died"), r.events());
		assertEquals(false, r.sound());
		assertEquals("/msg {name} hi", r.command());
	}
}
