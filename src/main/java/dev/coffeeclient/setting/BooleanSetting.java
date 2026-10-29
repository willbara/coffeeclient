package dev.coffeeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class BooleanSetting extends Setting<Boolean> {
	public BooleanSetting(String name, String description, boolean defaultValue) {
		super(name, description, defaultValue);
	}

	public void toggle() {
		set(!value);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement json) {
		set(json != null && json.isJsonPrimitive() ? json.getAsBoolean() : defaultValue());
	}

	@Override
	public String display() {
		return value ? "On" : "Off";
	}
}
