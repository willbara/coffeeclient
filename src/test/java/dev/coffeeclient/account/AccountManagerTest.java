package dev.coffeeclient.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.coffeeclient.util.JsonFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountManagerTest {
	@TempDir
	Path dir;

	@Test
	void loginTheKeyCannotOpenSurvivesASave() throws Exception {
		Path file = dir.resolve("accounts.json");
		Vault.useKeyFile(dir.resolve("old.key"));
		JsonObject old = new JsonObject();
		old.addProperty("type", "MICROSOFT");
		old.addProperty("name", "Old");
		old.addProperty("uuid", UUID.randomUUID().toString());
		old.addProperty("secret", Vault.seal("old-token"));
		JsonArray array = new JsonArray();
		array.add(old);
		JsonObject root = new JsonObject();
		root.add("accounts", array);
		JsonFiles.writeAtomic(file, JsonFiles.GSON.toJson(root));

		// Another machine: a different key, and one new account saved over the file.
		Vault.useKeyFile(dir.resolve("new.key"));
		AccountManager other = new AccountManager(file);
		other.load();
		assertTrue(other.all().isEmpty());
		other.addOffline("Steve");

		// The old key opens the old login again.
		Vault.useKeyFile(dir.resolve("old.key"));
		AccountManager back = new AccountManager(file);
		back.load();
		assertEquals(2, back.all().size());
		Account restored = back.all().stream().filter(a -> a.name().equals("Old")).findFirst().orElseThrow();
		assertEquals("old-token", restored.secret);
	}
}
