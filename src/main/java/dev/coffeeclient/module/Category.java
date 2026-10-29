package dev.coffeeclient.module;

/** ClickGUI columns, in display order. */
public enum Category {
	COMBAT("Combat"),
	MISC("Misc"),
	RENDER("Render"),
	WORLD("World"),
	MOVEMENT("Movement"),
	PLAYER("Player"),
	EXPLOIT("Exploit"),
	COSMETICS("Cosmetics"),
	CLIENT("Client");

	private final String displayName;

	Category(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
