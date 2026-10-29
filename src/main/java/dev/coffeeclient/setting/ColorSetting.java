package dev.coffeeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** An ARGB colour. */
public class ColorSetting extends Setting<Integer> {
	public ColorSetting(String name, String description, int defaultArgb) {
		super(name, description, defaultArgb);
	}

	public int argb() {
		return value;
	}

	public int red() {
		return (value >> 16) & 0xFF;
	}

	public int green() {
		return (value >> 8) & 0xFF;
	}

	public int blue() {
		return value & 0xFF;
	}

	public int alpha() {
		return (value >>> 24) & 0xFF;
	}

	/** Set one channel: 0 = red, 1 = green, 2 = blue, 3 = alpha. */
	public void setChannel(int channel, int v) {
		int shift = switch (channel) {
			case 0 -> 16;
			case 1 -> 8;
			case 2 -> 0;
			default -> 24;
		};
		int clamped = Math.max(0, Math.min(255, v));
		set((value & ~(0xFF << shift)) | (clamped << shift));
	}

	public int channel(int channel) {
		return switch (channel) {
			case 0 -> red();
			case 1 -> green();
			case 2 -> blue();
			default -> alpha();
		};
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(String.format("#%08X", value));
	}

	@Override
	public void fromJson(JsonElement json) {
		try {
			String s = json.getAsString().replace("#", "");
			set((int) Long.parseLong(s, 16));
		} catch (RuntimeException e) {
			set(defaultValue());
		}
	}

	@Override
	public String display() {
		return String.format("#%06X", value & 0xFFFFFF);
	}
}
