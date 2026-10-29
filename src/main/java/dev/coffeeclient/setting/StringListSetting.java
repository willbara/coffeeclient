package dev.coffeeclient.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered list of free-text entries (commands to run, chat lines, names, ...), edited in
 * {@code StringListScreen}. Duplicates are dropped and entries are trimmed to {@code maxLength}.
 */
public class StringListSetting extends Setting<List<String>> {
	private final int maxLength;
	private final int maxEntries;

	public StringListSetting(String name, String description, List<String> defaults, int maxLength) {
		this(name, description, defaults, maxLength, 0);
	}

	/** @param maxEntries hard cap on entries, or 0 for no cap */
	public StringListSetting(String name, String description, List<String> defaults, int maxLength, int maxEntries) {
		super(name, description, List.copyOf(defaults));
		this.maxLength = maxLength;
		this.maxEntries = maxEntries;
	}

	public int maxLength() {
		return maxLength;
	}

	public int maxEntries() {
		return maxEntries;
	}

	public void add(String entry) {
		String trimmed = trim(entry);
		if (trimmed.isEmpty() || value.contains(trimmed)) return;
		if (maxEntries > 0 && value.size() >= maxEntries) return;
		List<String> next = new ArrayList<>(value);
		next.add(trimmed);
		set(next);
	}

	public void remove(String entry) {
		if (!value.contains(entry)) return;
		List<String> next = new ArrayList<>(value);
		next.remove(entry);
		set(next);
	}

	/** Rename an entry in place (used by inline editors). */
	public void replace(String from, String to) {
		int index = value.indexOf(from);
		if (index < 0) return;
		String trimmed = trim(to);
		if (trimmed.isEmpty() || trimmed.equals(from)) return;
		List<String> next = new ArrayList<>(value);
		next.set(index, trimmed);
		set(next);
	}

	private String trim(String entry) {
		if (entry == null) return "";
		String trimmed = entry.strip();
		return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
	}

	@Override
	protected List<String> sanitize(List<String> list) {
		if (list == null) return defaultValue();
		List<String> out = new ArrayList<>();
		for (String entry : list) {
			String trimmed = trim(entry);
			if (trimmed.isEmpty() || out.contains(trimmed)) continue;
			out.add(trimmed);
			if (maxEntries > 0 && out.size() >= maxEntries) break;
		}
		return List.copyOf(out);
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
			List<String> list = new ArrayList<>();
			for (JsonElement e : json.getAsJsonArray()) list.add(e.getAsString());
			set(list);
		} catch (RuntimeException e) {
			set(defaultValue());
		}
	}

	@Override
	public String display() {
		if (value.isEmpty()) return "empty";
		return value.size() == 1 ? value.getFirst() : value.size() + " entries";
	}
}
