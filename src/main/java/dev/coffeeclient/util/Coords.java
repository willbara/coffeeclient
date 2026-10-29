package dev.coffeeclient.util;

import java.util.Locale;

/** Display formatting for coordinates, with thousands separated: 123456 becomes "123,456". */
public final class Coords {
	private Coords() {
	}

	public static String format(int value) {
		return format((long) value);
	}

	public static String format(long value) {
		String text = Long.toString(value);
		int start = text.startsWith("-") ? 1 : 0;
		String digits = text.substring(start);
		StringBuilder out = new StringBuilder(text.length() + digits.length() / 3);
		if (start == 1) out.append('-');
		int first = digits.length() % 3;
		if (first == 0) first = 3;
		out.append(digits, 0, first);
		for (int i = first; i < digits.length(); i += 3) out.append(',').append(digits, i, i + 3);
		return out.toString();
	}

	public static String format(double value) {
		return format(Math.round(value));
	}

	/** A decimal coordinate, e.g. 123456.7 -> "123,456.7". */
	public static String format(double value, int decimals) {
		String text = String.format(Locale.ROOT, "%." + decimals + "f", value);
		int dot = text.indexOf('.');
		if (dot < 0) return format(Long.parseLong(text));
		return format(Long.parseLong(text.substring(0, dot))) + text.substring(dot);
	}

	/** Three block coordinates separated by ", ". */
	public static String xyz(double x, double y, double z) {
		return format(x) + ", " + format(y) + ", " + format(z);
	}

	/** Two horizontal block coordinates separated by ", ". */
	public static String xz(double x, double z) {
		return format(x) + ", " + format(z);
	}
}
