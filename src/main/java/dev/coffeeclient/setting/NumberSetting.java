package dev.coffeeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;
import java.util.function.DoubleFunction;

/** A bounded number with a step. Rendered as a slider, or as a {@code ◀ value ▶} stepper when given a formatter. */
public class NumberSetting extends Setting<Double> {
	private final double min;
	private final double max;
	private final double step;
	private DoubleFunction<String> formatter;

	public NumberSetting(String name, String description, double defaultValue, double min, double max, double step) {
		super(name, description, defaultValue);
		this.min = min;
		this.max = max;
		this.step = step;
		this.formatter = v -> step >= 1 ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
	}

	public NumberSetting format(DoubleFunction<String> formatter) {
		this.formatter = formatter;
		return this;
	}

	public double min() {
		return min;
	}

	public double max() {
		return max;
	}

	public double step() {
		return step;
	}

	public int intValue() {
		return (int) Math.round(value);
	}

	public float floatValue() {
		return value.floatValue();
	}

	/** Position of the value in [0, 1]. */
	public double fraction() {
		return max == min ? 0 : (value - min) / (max - min);
	}

	public void setFraction(double f) {
		set(min + Math.max(0, Math.min(1, f)) * (max - min));
	}

	public void stepBy(int steps) {
		double increment = step > 0 ? step : (max - min) / 100.0;
		set(value + steps * increment);
	}

	@Override
	protected Double sanitize(Double v) {
		if (v == null || v.isNaN()) return defaultValue();
		double clamped = Math.max(min, Math.min(max, v));
		if (step > 0) clamped = min + Math.round((clamped - min) / step) * step;
		return Math.max(min, Math.min(max, clamped));
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement json) {
		try {
			set(json.getAsDouble());
		} catch (RuntimeException e) {
			set(defaultValue());
		}
	}

	@Override
	public String display() {
		return formatter.apply(value);
	}
}
