package dev.coffeeclient.module.modules.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamerModeTest {
	private static final String LOGIN = "^/(login|log|l|register|reg|changepassword|changepass)\\s+";

	@Test
	void masksAfterTheLoginCommand() {
		assertEquals(7, StreamerMode.maskFrom("/login hunter2", LOGIN));
		assertEquals(-1, StreamerMode.maskFrom("/locate hunter2", LOGIN));
		assertEquals(-1, StreamerMode.maskFrom("say /login x", LOGIN));
		assertEquals(-1, StreamerMode.maskFrom("/login x", "(unclosed"));
	}
}
