package dev.coffeeclient.macro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTriggerTest {
	@Test
	void matchModes() {
		assertTrue(MacroManager.chatMatches("Hello there", "hello", "Contains"));
		assertFalse(MacroManager.chatMatches("Hello there", "hello", "Exact"));
		assertTrue(MacroManager.chatMatches("  hello  ", "Hello", "Exact"));
		assertTrue(MacroManager.chatMatches("price: 42 diamonds", "\\d+ diamonds", "Regex"));
		assertFalse(MacroManager.chatMatches("anything", "(", "Regex"));
		assertFalse(MacroManager.chatMatches("anything", " ", "Contains"));
	}

	@Test
	void findsTheSender() {
		assertEquals("Steve", MacroManager.sender("<Steve> hi"));
		assertEquals("Alex_2", MacroManager.sender("Alex_2 whispers to you: yo"));
		assertEquals("Notch", MacroManager.sender("[Notch -> me] hey"));
		assertNull(MacroManager.sender("The server restarts soon"));
	}
}
