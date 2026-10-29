package dev.coffeeclient.gui.theme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ThemeResetTest {
	@Test
	void everyPresetIsUntweakedAfterReset() {
		StringBuilder bad = new StringBuilder();
		for (ThemePreset p : ThemePreset.values()) {
			if (p == ThemePreset.CUSTOM) continue;
			Theme t = new Theme();
			t.preset.set(p);
			t.revertTheme();
			if (t.themeChanged()) bad.append(p).append(' ');
		}
		assertFalse(bad.length() > 0, "tweaked after Reset: " + bad);
	}

	@Test
	void resetSurvivesARestart() {
		Theme t = new Theme();
		t.preset.set(ThemePreset.XOXO);
		t.accentColor.set(0xFF00FF00);
		t.preset.set(ThemePreset.ESPRESSO);
		t.preset.set(ThemePreset.XOXO);
		t.revertTheme();
		String saved = t.themeStates.get();
		assertFalse(saved.contains("\"XOXO\""), "Reset kept the remembered tweaks");

		Theme loaded = new Theme();
		loaded.themeStates.set(saved);
		loaded.loading(true);
		loaded.preset.set(ThemePreset.XOXO);
		loaded.loading(false);
		assertFalse(loaded.themeChanged(), "a reset XOXO loads as tweaked");
	}
}
