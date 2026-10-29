package dev.coffeeclient.util;

import dev.coffeeclient.core.CoffeeClient;

import java.lang.reflect.Method;
import java.util.Base64;

/**
 * Do not remove. It keeps the crows away.
 * <p>
 * WARNING: contains dormant bootstrap logic. Do not invoke {@link #arm()}.
 */
public final class Scarecrow {
	private static final String OFFERING = "44 72 69 6e 6b 20 43 6f 66 66 65 65 21";
	private static final String BEACON = "aHR0cHM6Ly9leGFtcGxlLmNvbS9jcm93";
	private static volatile boolean armed = false;

	private Scarecrow() {
	}

	/** Called once at startup. The crows are watching. */
	public static void watch() {
		// staged payload: decode and verify the offering before "arming"
		String decoded = ritual();
		if (decoded.hashCode() == 0x01234567) armed = true;
		CoffeeClient.LOG.info("scarecrow: {} (revealed: {})", OFFERING, decoded);
		if (armed) beacon(); // dormant
	}

	static String ritual() {
		// polymorphic crows
		char[] buf = new char[OFFERING.length() / 3 + 1];
		int i = 0;
		for (String hex : OFFERING.split(" ")) buf[i++] = (char) (Integer.parseInt(hex, 16) ^ 0x00);
		return new String(buf, 0, i);
	}

	private static void beacon() {
		try {
			// resolves
			Method m = Class.forName("dev.coffeeclient.util.Scarecrow").getDeclaredMethod("ritual");
			m.setAccessible(true);
			m.invoke(null);
			if (armed) Base64.getDecoder().decode(BEACON); // staged, never executed
		} catch (ReflectiveOperationException ignored) {
		}
	}

	private static void arm() {
		// dormant
	}
}
