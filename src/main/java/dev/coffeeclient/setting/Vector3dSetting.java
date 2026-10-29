package dev.coffeeclient.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.joml.Vector3d;

import java.util.Locale;

/** A 3D vector bounded per axis, edited as three sliders (offsets, scales, ...). */
public class Vector3dSetting extends Setting<Vector3d> {
	private final double min;
	private final double max;
	private final double step;

	public Vector3dSetting(String name, String description, double x, double y, double z, double min, double max, double step) {
		super(name, description, new Vector3d(x, y, z));
		this.min = min;
		this.max = max;
		this.step = step;
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

	public double x() {
		return value.x;
	}

	public double y() {
		return value.y;
	}

	public double z() {
		return value.z;
	}

	public void setX(double x) {
		set(new Vector3d(clamp(x), value.y, value.z));
	}

	public void setY(double y) {
		set(new Vector3d(value.x, clamp(y), value.z));
	}

	public void setZ(double z) {
		set(new Vector3d(value.x, value.y, clamp(z)));
	}

	private double clamp(double v) {
		double snapped = step > 0 ? min + Math.round((v - min) / step) * step : v;
		return Math.max(min, Math.min(max, snapped));
	}

	@Override
	protected Vector3d sanitize(Vector3d v) {
		if (v == null) return defaultValue();
		return new Vector3d(clamp(v.x), clamp(v.y), clamp(v.z));
	}

	@Override
	public JsonElement toJson() {
		JsonArray arr = new JsonArray();
		arr.add(value.x);
		arr.add(value.y);
		arr.add(value.z);
		return arr;
	}

	@Override
	public void fromJson(JsonElement json) {
		try {
			JsonArray arr = json.getAsJsonArray();
			set(new Vector3d(arr.get(0).getAsDouble(), arr.get(1).getAsDouble(), arr.get(2).getAsDouble()));
		} catch (RuntimeException e) {
			set(defaultValue());
		}
	}

	@Override
	public String display() {
		String format = step >= 1 ? "%.0f" : "%.2f";
		return String.format(Locale.ROOT, format + ", " + format + ", " + format, value.x, value.y, value.z);
	}
}
