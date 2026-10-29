package dev.coffeeclient.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A selection of string ids (entity types, particle types, sounds, packet names, ...) chosen from a
 * dynamic option list. Edited in its own full-screen picker ({@code IdPickerScreen}). In single mode
 * exactly one id is selected.
 */
public class IdListSetting extends Setting<List<String>> {
	private final Supplier<List<String>> options;
	private final boolean single;
	private Function<String, String> labeler = id -> id;
	private Function<String, String> previewer;
	private Function<String, net.minecraft.world.item.ItemStack> icons;
	private java.util.Map<String, Supplier<List<String>>> presets = java.util.Map.of();

	public IdListSetting(String name, String description, List<String> defaults, Supplier<List<String>> options, boolean single) {
		super(name, description, List.copyOf(defaults));
		this.options = options;
		this.single = single;
	}

	public IdListSetting labels(Function<String, String> labeler) {
		this.labeler = labeler;
		return this;
	}

	/** Optional "preview" action per option (e.g. play a sound); the function returns nothing useful. */
	public IdListSetting preview(Function<String, String> previewer) {
		this.previewer = previewer;
		return this;
	}

	/** Optional item icon per option, shown in the picker. */
	public IdListSetting icons(Function<String, net.minecraft.world.item.ItemStack> icons) {
		this.icons = icons;
		return this;
	}

	/** Named id groups the picker offers as one-click additions to the selection. */
	public IdListSetting presets(java.util.Map<String, Supplier<List<String>>> presets) {
		this.presets = presets;
		return this;
	}

	public java.util.Map<String, Supplier<List<String>>> presets() {
		return presets;
	}

	/** Adds the ids of preset {@code name} to the selection. */
	public void addPreset(String name) {
		Supplier<List<String>> preset = presets.get(name);
		if (preset == null) return;
		Set<String> next = new LinkedHashSet<>(value);
		next.addAll(preset.get());
		set(List.copyOf(next));
	}

	public net.minecraft.world.item.ItemStack icon(String id) {
		return icons == null ? null : icons.apply(id);
	}

	public boolean hasPreview() {
		return previewer != null;
	}

	public void runPreview(String id) {
		if (previewer != null) previewer.apply(id);
	}

	public boolean isSingle() {
		return single;
	}

	public List<String> options() {
		return options.get();
	}

	public String label(String id) {
		return labeler.apply(id);
	}

	public boolean contains(String id) {
		return lookup().ids.contains(id);
	}

	/** Hash lookup by registry key, so hot paths need not build an id string. */
	public boolean contains(Identifier key) {
		return key != null && lookup().keys.contains(key);
	}

	private record Lookup(List<String> source, Set<String> ids, Set<Identifier> keys) {
	}

	private record Resolved<T>(List<String> source, Set<T> values) {
	}

	private volatile Lookup lookup;
	private volatile Resolved<?> resolved;

	/** Hash sets of the current selection, rebuilt whenever the (immutable) value list is replaced. */
	private Lookup lookup() {
		List<String> v = value;
		Lookup l = lookup;
		if (l == null || l.source != v) {
			Set<Identifier> keys = new HashSet<>();
			for (String id : v) {
				Identifier key = Identifier.tryParse(id);
				if (key != null) keys.add(key);
			}
			lookup = l = new Lookup(v, Set.copyOf(v), Set.copyOf(keys));
		}
		return l;
	}

	/** The selection mapped through {@code resolver} (unknown ids dropped), cached until the value changes. */
	@SuppressWarnings("unchecked")
	protected <T> Set<T> resolve(Function<String, T> resolver) {
		List<String> v = value;
		Resolved<?> r = resolved;
		if (r == null || r.source != v) {
			Set<T> out = new HashSet<>();
			for (String id : v) {
				T t = resolver.apply(id);
				if (t != null) out.add(t);
			}
			resolved = r = new Resolved<>(v, Collections.unmodifiableSet(out));
		}
		return (Set<T>) r.values;
	}

	public String first() {
		return value.isEmpty() ? null : value.getFirst();
	}

	public void toggle(String id) {
		if (single) {
			set(List.of(id));
			return;
		}
		Set<String> next = new LinkedHashSet<>(value);
		if (!next.remove(id)) next.add(id);
		set(List.copyOf(next));
	}

	public void setAll(List<String> ids) {
		set(List.copyOf(new LinkedHashSet<>(ids)));
	}

	@Override
	protected List<String> sanitize(List<String> v) {
		if (v == null) return defaultValue();
		if (single && v.isEmpty()) return defaultValue();
		return single && v.size() > 1 ? List.of(v.getFirst()) : List.copyOf(v);
	}

	@Override
	public JsonElement toJson() {
		JsonArray arr = new JsonArray();
		value.forEach(arr::add);
		return arr;
	}

	@Override
	public void fromJson(JsonElement json) {
		try {
			List<String> ids = new ArrayList<>();
			for (JsonElement e : json.getAsJsonArray()) ids.add(e.getAsString());
			set(ids);
		} catch (RuntimeException e) {
			set(defaultValue());
		}
	}

	@Override
	public String display() {
		if (single) return value.isEmpty() ? "-" : label(value.getFirst());
		return value.size() + " selected";
	}
}
