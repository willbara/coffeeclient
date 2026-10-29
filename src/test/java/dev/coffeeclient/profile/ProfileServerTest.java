package dev.coffeeclient.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfileServerTest {
	@Test
	void rootDomainDropsSubdomainsAndPort() {
		assertEquals("example.com", ProfileManager.rootDomain("Play.Example.com:25565"));
		assertEquals("example.com", ProfileManager.rootDomain("example.com"));
		assertEquals("localhost", ProfileManager.rootDomain("localhost"));
		assertEquals("10.0.0.5", ProfileManager.rootDomain("10.0.0.5:25565"));
	}
}
