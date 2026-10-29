package dev.coffeeclient.macro;

import com.google.gson.JsonObject;
import dev.coffeeclient.setting.Labeled;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One step of a macro: a {@link Type} and its parameter values (by {@link Param#key()}). */
public final class MacroStep {
	/** Shared condition inputs: "Not" flips the condition, {@code arg} is its value. */
	private static final Param NOT = Param.choice("not", "Is / not", "Is", "Not");
	private static final Param CONDITION = Param.of("condition", "Condition", Param.Kind.CONDITION, "ON_GROUND");
	private static final Param ARG = Param.text("arg", "Value", "");

	public enum Type implements Labeled {
		CHAT("Say / command", "Send a chat message, or a command when it starts with /. Starscript works.",
			Param.text("text", "Message", "")),
		LOCAL("Local message", "Print a line in your own chat only; nothing is sent. Starscript works.",
			Param.text("text", "Message", "")),
		NOTIFY("Notify", "Show a toast (Starscript works).",
			Param.text("text", "Text", "")),
		MODULE("Module", "Turn a module on, off, or toggle it.",
			Param.of("module", "Module", Param.Kind.MODULE, ""), Param.choice("action", "Action", "Toggle", "Enable", "Disable")),
		KEY("Press key", "Use a game control: tap it once, hold it for a while, or press/release it until a later step.",
			Param.of("key", "Control", Param.Kind.KEY_MAPPING, "key.use"), Param.choice("mode", "How", "Tap", "Hold", "Press", "Release"),
			Param.number("ticks", "Ticks", "10")),
		SLOT("Hotbar slot", "Select a hotbar slot (1-9).",
			Param.number("slot", "Slot", "1")),
		SELECT_ITEM("Select item", "Hold an item by id (or part of one, like pickaxe): from the hotbar, or pulled from the inventory.",
			Param.text("item", "Item id (part)", ""), Param.choice("from", "Search", "Hotbar", "Inventory too")),
		LOOK("Look", "Turn the camera: set an exact direction, or turn by an amount.",
			Param.choice("mode", "How", "Turn by", "Set"), Param.number("yaw", "Yaw", "0"), Param.number("pitch", "Pitch", "0")),
		WAIT("Wait", "Pause for a number of ticks (20 per second).",
			Param.number("ticks", "Ticks", "20")),
		WAIT_UNTIL("Wait until", "Pause until a condition holds (or the timeout passes; 0 = no timeout).",
			NOT, CONDITION, ARG, Param.number("timeout", "Timeout", "200")),
		IF("If", "Run the steps up to the matching Else if, Else or End only when a condition holds.",
			NOT, CONDITION, ARG),
		ELSE_IF("Else if", "After an If: run these steps when the earlier checks failed and this condition holds.",
			NOT, CONDITION, ARG),
		ELSE("Else", "After an If: run these steps when every earlier check failed."),
		END("End", "Closes the If or Repeat above it."),
		REPEAT("Repeat", "Run the steps up to the matching End several times, or while a condition holds.",
			Param.choice("mode", "How", "Times", "While"), Param.number("count", "Times", "3"), NOT, CONDITION, ARG),
		STOP_IF("Stop if", "End the macro here when a condition holds.",
			NOT, Param.of("condition", "Condition", Param.Kind.CONDITION, "SCREEN_OPEN"), ARG),
		STOP_MACRO("Stop macro", "End this macro, another running macro, or every macro.",
			Param.choice("which", "Which", "This macro", "Other macro", "All macros"), Param.of("macro", "Macro", Param.Kind.MACRO, "")),
		RUN_MACRO("Run macro", "Start another macro (it runs alongside this one).",
			Param.of("macro", "Macro", Param.Kind.MACRO, ""));

		private final String label;
		private final String description;
		private final List<Param> params;

		Type(String label, String description, Param... params) {
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
	}

	private Type type;
	private final Map<String, String> values = new LinkedHashMap<>();

	public MacroStep(Type type) {
		setType(type);
	}

	public Type type() {
		return type;
	}

	/** Changes the type, keeping values whose param keys the new type shares. */
	public void setType(Type type) {
		this.type = type;
		for (Param p : type.params()) values.putIfAbsent(p.key(), p.defaultValue());
	}

	public String get(String key) {
		String v = values.get(key);
		if (v != null) return v;
		for (Param p : type.params()) if (p.key().equals(key)) return p.defaultValue();
		return "";
	}

	public int getInt(String key) {
		return (int) Math.round(MacroCondition.number(get(key)));
	}

	public void set(String key, String value) {
		values.put(key, value == null ? "" : value);
	}

	/** One line describing the step, for lists and tooltips. */
	public String summary() {
		return switch (type) {
			case CHAT, LOCAL, NOTIFY -> type.label() + ": " + get("text");
			case MODULE -> get("action") + " " + get("module");
			case KEY -> get("mode") + " " + get("key") + ("Hold".equals(get("mode")) ? " for " + get("ticks") + " ticks" : "");
			case WAIT -> "Wait " + get("ticks") + " ticks";
			case WAIT_UNTIL, STOP_IF, IF, ELSE_IF -> type.label() + " " + conditionText();
			case ELSE, END -> type.label();
			case REPEAT -> "Times".equals(get("mode")) ? "Repeat " + get("count") + " times" : "Repeat while " + conditionText();
			case SLOT -> "Slot " + get("slot");
			case SELECT_ITEM -> "Select " + get("item");
			case LOOK -> get("mode") + " " + get("yaw") + " / " + get("pitch");
			case STOP_MACRO -> "Other macro".equals(get("which")) ? "Stop " + get("macro") : "Stop " + get("which").toLowerCase(Locale.ROOT);
			case RUN_MACRO -> "Run " + get("macro");
		};
	}

	/** "not on ground", "health below 6". */
	private String conditionText() {
		MacroCondition c = MacroCondition.parse(get("condition"));
		String text = ("Not".equals(get("not")) ? "not " : "") + c.label().toLowerCase(Locale.ROOT);
		return c.argLabel() == null ? text : text + " " + get("arg");
	}

	public MacroStep copy() {
		MacroStep c = new MacroStep(type);
		c.values.putAll(values);
		return c;
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("type", type.name());
		for (Param p : type.params()) o.addProperty(p.key(), get(p.key()));
		return o;
	}

	static MacroStep fromJson(JsonObject o) {
		Type type;
		try {
			type = Type.valueOf(o.get("type").getAsString());
		} catch (RuntimeException e) {
			type = Type.CHAT;
		}
		MacroStep step = new MacroStep(type);
		for (Param p : type.params()) if (o.has(p.key())) step.set(p.key(), o.get(p.key()).getAsString());
		return step;
	}
}
