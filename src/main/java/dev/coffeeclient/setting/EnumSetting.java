package dev.coffeeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

public class EnumSetting<E extends Enum<E>> extends Setting<E> {
	private final E[] constants;

	public EnumSetting(String name, String description, E defaultValue) {
		super(name, description, defaultValue);
		this.constants = defaultValue.getDeclaringClass().getEnumConstants();
	}

	public void cycle(int direction) {
		int next = Math.floorMod(value.ordinal() + direction, constants.length);
		set(constants[next]);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value.name());
	}

	@Override
	public void fromJson(JsonElement json) {
		try {
			set(Enum.valueOf(defaultValue().getDeclaringClass(), json.getAsString()));
		} catch (RuntimeException e) {
			set(defaultValue());
		}
	}

	@Override
	public String display() {
		if (value instanceof Labeled l) return l.label();
		String n = value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
		return Character.toUpperCase(n.charAt(0)) + n.substring(1);
	}
}
