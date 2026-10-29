package dev.coffeeclient.setting;

import com.google.gson.JsonElement;
import dev.coffeeclient.config.ConfigManager;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A named, persisted value. Every change marks the config dirty (debounced save) and notifies listeners.
 */
public abstract class Setting<T> {
	private final String name;
	private final String description;
	private final T defaultValue;
	protected T value;
	private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
	private BooleanSupplier visibility = () -> true;
	private boolean persistent = true;
	private String group = "";

	protected Setting(String name, String description, T defaultValue) {
		this.name = name;
		this.description = description;
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public String name() {
		return name;
	}

	public String description() {
		return description;
	}

	public T get() {
		return value;
	}

	public T defaultValue() {
		return defaultValue;
	}

	public void set(T newValue) {
		T sanitized = sanitize(newValue);
		if (Objects.equals(value, sanitized)) return;
		value = sanitized;
		for (Consumer<T> listener : listeners) listener.accept(sanitized);
		if (persistent) ConfigManager.markDirty();
	}

	public void reset() {
		set(defaultValue);
	}

	/** Clamp/normalise incoming values. */
	protected T sanitize(T v) {
		return v == null ? defaultValue : v;
	}

	public Setting<T> onChange(Consumer<T> listener) {
		listeners.add(listener);
		return this;
	}

	/** For editor-only values (e.g. a colour channel in an editor) that must not trigger a config save. */
	public Setting<T> transientValue() {
		this.persistent = false;
		return this;
	}

	/** Section heading this setting appears under in a module's config window. */
	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S group(String group) {
		this.group = group;
		return (S) this;
	}

	public String group() {
		return group;
	}

	/**
	 * Show this setting only while {@code condition} holds. Generic in the concrete setting type so
	 * builders can be chained: {@code setting(new NumberSetting(...).visibleWhen(...))}.
	 */
	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S visibleWhen(BooleanSupplier condition) {
		this.visibility = condition;
		return (S) this;
	}

	public boolean isVisible() {
		return visibility.getAsBoolean();
	}

	public abstract JsonElement toJson();

	/** Parse a stored value; implementations must fall back to the default on bad input rather than throw. */
	public abstract void fromJson(JsonElement json);

	/** Short human-readable form of the current value. */
	public String display() {
		return String.valueOf(value);
	}
}
