package dev.coffeeclient.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfileManagerTest {
	@TempDir
	Path dir;

	@Test
	void notesSurviveASaveAndLoad() throws Exception {
		Path file = dir.resolve("profiles.json");
		Files.writeString(file, "{\"active\":\"Work\",\"profiles\":[{\"name\":\"Work\",\"config\":{},\"notes\":\"line one\\nline two\"}]}");
		ProfileManager first = new ProfileManager(file);
		first.load();
		assertEquals("line one\nline two", first.byName("Work").notes());

		first.setNotes(first.byName("Work"), "changed");
		ProfileManager second = new ProfileManager(file);
		second.load();
		assertEquals("changed", second.byName("Work").notes());
	}
}
