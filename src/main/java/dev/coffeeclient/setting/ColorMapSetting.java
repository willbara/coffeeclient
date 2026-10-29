package dev.coffeeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A colour per entry (block or item id), edited in its own window. Entries without one use the
 * fallback, so a list can grow without every new entry needing a colour.
 */
public class ColorMapSetting extends Setting<Map<String, Integer>> {
	private final Supplier<List<String>> ids;
	private final Function<String, String> labels;
	private final IntSupplier fallback;

	public ColorMapSetting(String name, String description, Supplier<List<String>> ids, Function<String, String> labels, IntSupplier fallback) {
		super(name, description, Map.of());
		this.ids = ids;
		this.labels = labels;
		this.fallback = fallback;
	}

	/** The entries that can get a colour (for example a block list's current contents). */
	public List<String> ids() {
		return ids.get();
	}

	public String label(String id) {
		return labels.apply(id);
	}

	/** ARGB for {@code id}: its own colour, else the fallback. */
	public int color(String id) {
		Integer c = value.get(id);
		return c == null ? fallback.getAsInt() : c;
	}

	public void put(String id, int argb) {
		Map<String, Integer> copy = new LinkedHashMap<>(value);
		copy.put(id, argb);
		set(Map.copyOf(copy));
	}

	@Override
	public JsonElement toJson() {
		JsonObject o = new JsonObject();
		value.forEach((id, c) -> o.addProperty(id, String.format("#%08X", c)));
		return o;
	}

	@Override
	public void fromJson(JsonElement json) {
		Map<String, Integer> out = new LinkedHashMap<>();
		try {
			for (var e : json.getAsJsonObject().entrySet()) out.put(e.getKey(), (int) Long.parseLong(e.getValue().getAsString().substring(1), 16));
		} catch (RuntimeException e) {
			out.clear();
		}
		set(Map.copyOf(out));
	}

	@Override
	public String display() {
		return value.isEmpty() ? "Default" : value.size() + " set";
	}
}
