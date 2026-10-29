package dev.coffeeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/** A button in a module's settings (e.g. "Reset counters", "Open folder"). Not persisted. */
public class ActionSetting extends Setting<Boolean> {
	private final Runnable action;

	public ActionSetting(String name, String description, Runnable action) {
		super(name, description, false);
		this.action = action;
		transientValue();
	}

	public void run() {
		action.run();
	}

	@Override
	public JsonElement toJson() {
		return JsonNull.INSTANCE;
	}

	@Override
	public void fromJson(JsonElement json) {
	}
}
