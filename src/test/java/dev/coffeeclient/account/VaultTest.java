package dev.coffeeclient.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VaultTest {
	@TempDir
	Path dir;

	@Test
	void sealedSecretOpensAgain() {
		Vault.useKeyFile(dir.resolve("vault.key"));
		assertEquals("secret", Vault.open(Vault.seal("secret")));
	}

	@Test
	void strayCharacterInKeyFileStillOpensSecrets() throws Exception {
		Path key = dir.resolve("vault.key");
		Vault.useKeyFile(key);
		String sealed = Vault.seal("secret");
		String text = Files.readString(key);
		Files.writeString(key, text.substring(0, 5) + "!" + text.substring(5));

		Vault.useKeyFile(key);
		assertEquals("secret", Vault.open(sealed));
	}

	@Test
	void unreadableKeyFileMakesANewKeyInsteadOfThrowing() throws Exception {
		Path key = dir.resolve("vault.key");
		Files.writeString(key, "a");
		Vault.useKeyFile(key);
		assertEquals("secret", Vault.open(Vault.seal("secret")));
	}
}
