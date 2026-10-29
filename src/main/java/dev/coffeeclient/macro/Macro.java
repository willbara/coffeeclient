package dev.coffeeclient.macro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A named macro: a {@link MacroTrigger} with its values, a list of {@link MacroStep}s, and how it
 * repeats. Edit through {@link MacroManager} (or call {@link MacroManager#changed()}) so it is saved.
 */
public final class Macro {
	public String name;
	public boolean enabled = true;
	public MacroTrigger trigger = MacroTrigger.KEY_PRESS;
	private final Map<String, String> triggerValues = new HashMap<>();
	public final List<MacroStep> steps = new ArrayList<>();
	/** Times to run per start; 0 = until stopped. */
	public int repeat = 1;
	/** Starting it again while it runs stops it instead. */
	public boolean retriggerStops = true;
	/** Key triggers also fire while a screen is open. */
	public boolean inScreens;

	public Macro(String name) {
		this.name = name;
	}

	public String triggerValue(String key) {
		String v = triggerValues.get(key);
		if (v != null) return v;
		for (Param p : trigger.params()) if (p.key().equals(key)) return p.defaultValue();
		return "";
	}

	public void setTriggerValue(String key, String value) {
		triggerValues.put(key, value);
	}

	/** The bound key for key triggers, or -1. */
	public int key() {
		return (int) MacroCondition.number(triggerValue("key"));
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("enabled", enabled);
		o.addProperty("trigger", trigger.name());
		JsonObject values = new JsonObject();
		for (Param p : trigger.params()) values.addProperty(p.key(), triggerValue(p.key()));
		o.add("triggerValues", values);
		o.addProperty("repeat", repeat);
		o.addProperty("retriggerStops", retriggerStops);
		o.addProperty("inScreens", inScreens);
		JsonArray arr = new JsonArray();
		for (MacroStep step : steps) arr.add(step.toJson());
		o.add("steps", arr);
		return o;
	}

	static Macro fromJson(JsonObject o) {
		Macro m = new Macro(o.has("name") ? o.get("name").getAsString() : "Macro");
		if (o.has("enabled")) m.enabled = o.get("enabled").getAsBoolean();
		try {
			if (o.has("trigger")) m.trigger = MacroTrigger.valueOf(o.get("trigger").getAsString());
		} catch (IllegalArgumentException ignored) {
			// a trigger from a newer version
		}
		if (o.has("triggerValues")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("triggerValues").entrySet()) m.triggerValues.put(e.getKey(), e.getValue().getAsString());
		}
		if (o.has("repeat")) m.repeat = o.get("repeat").getAsInt();
		if (o.has("retriggerStops")) m.retriggerStops = o.get("retriggerStops").getAsBoolean();
		if (o.has("inScreens")) m.inScreens = o.get("inScreens").getAsBoolean();
		if (o.has("steps")) for (JsonElement e : o.getAsJsonArray("steps")) m.steps.add(MacroStep.fromJson(e.getAsJsonObject()));
		return m;
	}
}
