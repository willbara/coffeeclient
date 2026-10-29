package dev.coffeeclient.friend;

import dev.coffeeclient.setting.Labeled;

/**
 * What being a friend means. Each rule has a default for all friends (the Friends module) that a
 * single friend can override (always / never).
 */
public enum FriendTrait implements Labeled {
	PROTECTED("Never targeted", "Combat and targeting modules leave them alone"),
	HIGHLIGHTED("Highlighted", "Friend colour in ESP and the tab list; Nametags' \"ignore friends\" hides theirs"),
	TRUSTED("Trusted", "AutoLog does not leave for them; PlayerSpots does not alert on them"),
	SPARED("Spared by chat tools", "MessageAura and similar skip them"),
	COSMETICS("See cosmetics", "Cosmetics set to \"friends\" show on them");

	private final String label;
	private final String description;

	FriendTrait(String label, String description) {
		this.label = label;
		this.description = description;
	}

	@Override
	public String label() {
		return label;
	}

	public String description() {
		return description;
	}
}
