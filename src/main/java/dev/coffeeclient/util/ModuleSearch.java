package dev.coffeeclient.util;

import java.util.List;
import java.util.Locale;

/**
 * ClickGUI search scoring, free of Minecraft types so it can be tested. Lower scores are better:
 * the module name (exact, prefix, contains, every word), then an alias, a setting name and the
 * description, each with the reason shown on hover. A near-miss of the name (typo) only counts
 * when nothing else matched.
 */
public final class ModuleSearch {
	public static final int TYPO = 20;

	/** A match: its score and why it matched (null when the name itself matched). */
	public record Hit(int score, String reason) {
	}

	private ModuleSearch() {
	}

	public static Hit score(String query, String name, List<String> aliases, String description, List<String> settings) {
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return new Hit(0, null);
		String n = name.toLowerCase(Locale.ROOT);
		String[] words = q.split("\\s+");
		if (n.equals(q)) return new Hit(0, null);
		if (n.startsWith(q)) return new Hit(1, null);
		if (n.contains(q)) return new Hit(2, null);
		if (containsAll(n, words)) return new Hit(3, null);
		for (String alias : aliases) if (alias.toLowerCase(Locale.ROOT).contains(q)) return new Hit(4, "Alias: " + alias);
		for (String setting : settings) if (containsAll(setting.toLowerCase(Locale.ROOT), words)) return new Hit(5, "Setting: " + setting);
		if (containsAll(description.toLowerCase(Locale.ROOT), words)) return new Hit(6, "Description");
		int tolerance = q.length() >= 6 ? 2 : q.length() >= 4 ? 1 : 0;
		int d = windowDistance(n, q);
		return d <= tolerance ? new Hit(TYPO + d, "Similar to " + name) : null;
	}

	private static boolean containsAll(String text, String[] words) {
		for (String w : words) if (!text.contains(w)) return false;
		return true;
	}

	/** Smallest edit distance between {@code q} and any same-length window of {@code text} (or the whole of a shorter text). */
	static int windowDistance(String text, String q) {
		if (text.length() <= q.length()) return distance(text, q);
		int best = Integer.MAX_VALUE;
		for (int i = 0; i + q.length() <= text.length(); i++) best = Math.min(best, distance(text.substring(i, i + q.length()), q));
		return best;
	}

	static int distance(String a, String b) {
		int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) prev[j] = j;
		for (int i = 1; i <= a.length(); i++) {
			cur[0] = i;
			for (int j = 1; j <= b.length(); j++)
				cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return prev[b.length()];
	}
}
