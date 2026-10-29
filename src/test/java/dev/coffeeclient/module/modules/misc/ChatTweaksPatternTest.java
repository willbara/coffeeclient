package dev.coffeeclient.module.modules.misc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTweaksPatternTest {
	@Test
	void senderIsTheLeadingName() {
		var m = ChatTweaks.SENDER.matcher("<Steve> hello");
		assertTrue(m.find());
		assertEquals("Steve", m.group(1));
		m = ChatTweaks.SENDER.matcher("[VIP] Alex: hi");
		assertTrue(m.find());
		assertEquals("Alex", m.group(1));
	}

	@Test
	void joinLeaveLines() {
		assertTrue(ChatTweaks.JOIN_LEAVE.matcher("Steve joined the game").matches());
		assertTrue(ChatTweaks.JOIN_LEAVE.matcher("Steve left the game").matches());
		assertFalse(ChatTweaks.JOIN_LEAVE.matcher("<Steve> I left the game").matches());
	}

	@Test
	void whisperLines() {
		assertTrue(ChatTweaks.WHISPER.matcher("Steve whispers to you: hi").find());
		assertTrue(ChatTweaks.WHISPER.matcher("[Steve -> me] hi").find());
		assertTrue(ChatTweaks.WHISPER.matcher("You whisper to Steve: hi").find());
		assertFalse(ChatTweaks.WHISPER.matcher("<Steve> hi").find());
	}
}
