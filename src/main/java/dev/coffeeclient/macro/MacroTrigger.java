package dev.coffeeclient.macro;

import dev.coffeeclient.setting.Labeled;

import java.util.List;

/** What starts a macro. Key triggers react to input; the others run on their own while Macros is on. */
public enum MacroTrigger implements Labeled {
	KEY_PRESS("Key press", "Runs when the key is pressed (in game)", Param.of("key", "Key", Param.Kind.KEY, "-1")),
	KEY_HOLD("While key held", "Runs while the key is held and stops when it is released", Param.of("key", "Key", Param.Kind.KEY, "-1")),
	INTERVAL("Every few seconds", "Runs on a timer while you are in a world", Param.number("seconds", "Seconds", "60")),
	JOIN("On join", "Runs after you join a world or server", Param.number("delay", "Delay (ticks)", "40")),
	CHAT("On chat message", "Runs when a chat or server message matches; can also /msg the sender a reply",
		Param.text("text", "Text", ""), Param.choice("match", "Match", "Contains", "Exact", "Regex"),
		Param.number("cooldown", "Cooldown (s)", "0"), Param.number("delay", "Delay (ticks)", "0"),
		Param.number("jitter", "Random extra (ticks)", "0"), Param.text("reply", "Reply by /msg", "")),
	LOW_HEALTH("On low health", "Runs when your health drops to the value or below (once per drop)", Param.number("health", "Health", "6")),
	DEATH("On death", "Runs when you die"),
	RESPAWN("On respawn", "Runs when you respawn after dying"),
	DIMENSION_CHANGE("On dimension change", "Runs when you move to another dimension",
		Param.choice("to", "Into", "Any", "Overworld", "Nether", "End")),
	SCREEN_OPEN("On screen opened", "Runs when a screen opens in a world",
		Param.choice("screen", "Screen", "Container", "Inventory", "Chat", "Any")),
	MODULE_TOGGLED("On module toggled", "Runs when a module is turned on or off",
		Param.of("module", "Module", Param.Kind.MODULE, ""), Param.choice("when", "When", "Turned on", "Turned off", "Either")),
	MANUAL("Manual only", "Runs only from the Macros window or a Run macro step");

	private final String label;
	private final String description;
	private final List<Param> params;

	MacroTrigger(String label, String description, Param... params) {
		this.label = label;
		this.description = description;
		this.params = List.of(params);
	}

	@Override
	public String label() {
		return label;
	}

	public String description() {
		return description;
	}

	public List<Param> params() {
		return params;
	}

	public boolean isKey() {
		return this == KEY_PRESS || this == KEY_HOLD;
	}
}
