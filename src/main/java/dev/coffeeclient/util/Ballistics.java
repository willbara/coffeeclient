package dev.coffeeclient.util;

/**
 * Aims a shot that falls under gravity and slows with drag, as arrows, tridents and thrown items do:
 * each tick the shot moves by its velocity, the velocity shrinks by the drag factor, then gravity is
 * taken off the vertical part. Works in a vertical plane (distance out, height up), free of
 * Minecraft types so it can be tested.
 */
public final class Ballistics {
	/** Ticks simulated before a shot counts as never arriving. */
	private static final int MAX_TICKS = 300;

	/** Where a shot is when it has gone {@code distance} out: its height and the ticks taken. */
	public record Arrival(double height, double ticks) {
	}

	private Ballistics() {
	}

	/**
	 * The launch elevation in degrees (up is positive, the opposite of Minecraft pitch) that brings a
	 * shot {@code distance} blocks out to {@code height} blocks up, on the low arc; NaN when out of reach.
	 */
	public static double elevation(double distance, double height, double speed, double gravity, double drag) {
		// The low arc ends at the elevation that lands highest at this distance; past it shots lob.
		double lo = -89, hi = Double.NaN, best = Double.NEGATIVE_INFINITY;
		for (int e = 0; e <= 89; e++) {
			Arrival a = arrive(e, distance, speed, gravity, drag);
			if (a == null || a.height <= best) break;
			best = a.height;
			hi = e;
		}
		if (Double.isNaN(hi) || best < height) return Double.NaN;
		// Below the top of the low arc, a steeper launch always lands higher at a given distance.
		for (int i = 0; i < 40; i++) {
			double mid = (lo + hi) / 2;
			Arrival a = arrive(mid, distance, speed, gravity, drag);
			if (a == null || a.height < height) lo = mid;
			else hi = mid;
		}
		// hi always arrives (lo may be a steep shot that never covers the distance).
		return hi;
	}

	/** The shot launched at {@code elevation} degrees once it is {@code distance} out, or null if it never gets there. */
	public static Arrival arrive(double elevation, double distance, double speed, double gravity, double drag) {
		double r = Math.toRadians(elevation);
		double vx = speed * Math.cos(r), vy = speed * Math.sin(r), x = 0, y = 0;
		for (int t = 1; t <= MAX_TICKS && vx > 1.0E-4; t++) {
			double nx = x + vx, ny = y + vy;
			if (nx >= distance) {
				double f = (distance - x) / vx;
				return new Arrival(y + vy * f, t - 1 + f);
			}
			x = nx;
			y = ny;
			vx *= drag;
			vy = vy * drag - gravity;
		}
		return null;
	}
}
