package dev.coffeeclient.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Paths for AutoFly: given where the player is, each pattern says which way to face next. Closed loop
 * (it looks at the real position every tick), so speed changes and drift just bend the path instead of
 * throwing it off. Yaw follows Minecraft: 0 = south (+Z), 90 = west (-X), turning right adds degrees.
 */
public interface FlightPattern {
	/** The yaw to face from {@code (x, z)}; {@code speed} is blocks per tick, for cutting corners. */
	float yaw(double x, double z, double speed);

	boolean done();

	/** The player is somewhere new (landed and came back): pick the path up from there instead of from where it was left. */
	default void resync(double x, double z) {
	}

	/** Blocks left to fly from {@code (x, z)}, or NaN when the pattern has no end. */
	default double remaining(double x, double z) {
		return Double.NaN;
	}

	static float yawOf(double dx, double dz) {
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	/**
	 * Archimedean spiral. Inward, the centre sits {@code radius} blocks to the side of the start (the
	 * way the player turns), so the start is the outer end, and each lap ends {@code spacing} blocks
	 * closer. Outward, the start is the inner end ({@code endRadius} from the centre) and each lap
	 * ends {@code spacing} blocks farther out, until {@code radius}. The yaw aims at a point a little ahead on that ideal spiral (pure pursuit), which pulls a
	 * flyer back onto it even though its movement lags behind the way it faces.
	 */
	final class Spiral implements FlightPattern {
		private final double cx, cz, radius, spacing, endRadius, startAngle;
		private final boolean outward;
		private final int sense;
		private double progress, lastAngle;
		private boolean done;

		public Spiral(double x, double z, float startYaw, boolean clockwise, boolean outward, double radius, double spacing, double endRadius) {
			double rad = Math.toRadians(startYaw);
			// right of the heading is (-cos, -sin), left is (cos, sin)
			double side = clockwise ? -1 : 1;
			double startRadius = outward ? endRadius : radius;
			this.cx = x + side * Math.cos(rad) * startRadius;
			this.cz = z + side * Math.sin(rad) * startRadius;
			this.outward = outward;
			this.radius = radius;
			this.spacing = spacing;
			this.endRadius = endRadius;
			// the angle around the centre grows the way a clockwise (right-turning) flyer goes
			this.sense = clockwise ? 1 : -1;
			this.startAngle = Math.atan2(z - cz, x - cx);
			this.lastAngle = startAngle;
		}

		private double idealRadius(double progress) {
			double lap = spacing * progress / (2 * Math.PI);
			return outward ? endRadius + lap : radius - lap;
		}

		@Override
		public float yaw(double x, double z, double speed) {
			double ux = x - cx, uz = z - cz;
			double rho = Math.hypot(ux, uz);
			double angle = Math.atan2(uz, ux);
			double step = angle - lastAngle;
			step -= 2 * Math.PI * Math.round(step / (2 * Math.PI));
			progress += sense * step;
			lastAngle = angle;

			double ahead = Math.max(10, speed * 10);
			double targetProgress = progress + ahead / Math.max(rho, endRadius);
			double targetRho = idealRadius(targetProgress);
			if (outward ? rho >= radius || targetRho >= radius : rho <= endRadius || targetRho <= endRadius) done = true;
			double targetAngle = startAngle + sense * targetProgress;
			double tx = cx + targetRho * Math.cos(targetAngle), tz = cz + targetRho * Math.sin(targetAngle);
			return yawOf(tx - x, tz - z);
		}

		@Override
		public boolean done() {
			return done;
		}

		/** Arc length left: the angle still to turn times the mean of the current and final radius. */
		@Override
		public double remaining(double x, double z) {
			double endProgress = Math.abs(radius - endRadius) * 2 * Math.PI / spacing;
			double angleLeft = Math.max(0, endProgress - progress);
			double rho = Math.hypot(x - cx, z - cz);
			return angleLeft * (rho + (outward ? radius : endRadius)) / 2;
		}

		/** Back onto the spiral at the lap nearest the current radius, keeping the angle around the centre. */
		@Override
		public void resync(double x, double z) {
			double ux = x - cx, uz = z - cz;
			double rho = Math.hypot(ux, uz);
			double angle = Math.atan2(uz, ux);
			double base = sense * (angle - startAngle);
			base -= 2 * Math.PI * Math.floor(base / (2 * Math.PI));
			double best = progress, bestError = Double.MAX_VALUE;
			double laps = Math.abs(radius - endRadius) / spacing + 1;
			for (int k = 0; k <= laps; k++) {
				double candidate = base + 2 * Math.PI * k;
				double error = Math.abs(idealRadius(candidate) - rho);
				if (error < bestError) {
					bestError = error;
					best = candidate;
				}
			}
			progress = best;
			lastAngle = angle;
		}
	}

	/**
	 * A path of straight segments flown by pure pursuit: the yaw aims at a point a little ahead along
	 * the path, so corners are rounded off and drift is pulled back onto the line.
	 */
	abstract class Polyline implements FlightPattern {
		protected final List<double[]> points = new ArrayList<>();
		private int seg;
		private boolean done;

		/** Adds the next corner; a point on top of the last one is skipped. */
		protected void to(double x, double z) {
			if (!points.isEmpty()) {
				double[] last = points.getLast();
				if (Math.hypot(x - last[0], z - last[1]) < 1e-6) return;
			}
			points.add(new double[]{x, z});
		}

		/** How close to the last point counts as arrived. */
		protected double finishWithin(double speed) {
			return Math.max(2, speed * 4);
		}

		/** How far along segment {@code i} the closest point to {@code (x, z)} is, from 0 to its length. */
		private double along(int i, double x, double z) {
			double[] a = points.get(i), b = points.get(i + 1);
			double len = length(i);
			return Math.clamp(((x - a[0]) * (b[0] - a[0]) + (z - a[1]) * (b[1] - a[1])) / len, 0, len);
		}

		private double length(int i) {
			double[] a = points.get(i), b = points.get(i + 1);
			return Math.hypot(b[0] - a[0], b[1] - a[1]);
		}

		private double[] pointAt(int i, double t) {
			double[] a = points.get(i), b = points.get(i + 1);
			double f = t / length(i);
			return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f};
		}

		private double distance(int i, double x, double z) {
			double[] p = pointAt(i, along(i, x, z));
			return Math.hypot(p[0] - x, p[1] - z);
		}

		@Override
		public float yaw(double x, double z, double speed) {
			if (points.size() < 2) {
				done = true;
				return 0;
			}
			int last = points.size() - 2;
			while (seg < last && distance(seg + 1, x, z) < distance(seg, x, z)) seg++;
			double t = along(seg, x, z);
			if (seg == last && length(seg) - t < finishWithin(speed)) done = true;

			int i = seg;
			double left = t + Math.max(10, speed * 10);
			while (i < last && left > length(i)) left -= length(i++);
			double[] target = pointAt(i, Math.min(left, length(i)));
			return yawOf(target[0] - x, target[1] - z);
		}

		@Override
		public boolean done() {
			return done;
		}

		@Override
		public double remaining(double x, double z) {
			if (points.size() < 2) return 0;
			double left = length(seg) - along(seg, x, z);
			for (int i = seg + 1; i < points.size() - 1; i++) left += length(i);
			return left;
		}

		/** Back onto the segment nearest where the player is now. */
		@Override
		public void resync(double x, double z) {
			if (points.size() < 2) return;
			double best = Double.MAX_VALUE;
			for (int i = 0; i < points.size() - 1; i++) {
				double d = distance(i, x, z);
				if (d < best) {
					best = d;
					seg = i;
				}
			}
			done = false;
		}
	}

	/**
	 * Square spiral: straight lanes {@code spacing} apart, turning 90 degrees toward the turning side at
	 * each corner. Inward, the start is the outer corner of a {@code side} x {@code side} square and the
	 * laps shrink to {@code innerSide}; outward, the start is the inner end and the laps grow until the
	 * square is closed.
	 */
	final class Square extends Polyline {
		public Square(double x, double z, float startYaw, boolean clockwise, boolean outward, double side, double spacing, double innerSide) {
			// edge lengths from the middle out: two of each, growing by spacing, then one more to close the outer edge
			List<Double> lengths = new ArrayList<>();
			for (double len = innerSide; len <= side; len += spacing) {
				lengths.add(len);
				lengths.add(len);
			}
			if (lengths.isEmpty()) {
				lengths.add(side);
				lengths.add(side);
			}
			lengths.add(lengths.getLast());
			if (!outward) java.util.Collections.reverse(lengths);

			double rad = Math.toRadians(startYaw);
			double dx = -Math.sin(rad), dz = Math.cos(rad);
			to(x, z);
			for (double len : lengths) {
				x += dx * len;
				z += dz * len;
				to(x, z);
				// right of (dx, dz) is (-dz, dx), left is (dz, -dx)
				double t = dx;
				dx = clockwise ? -dz : dz;
				dz = clockwise ? t : -t;
			}
		}
	}

	/**
	 * Back-and-forth lanes covering the rectangle from {@code (x, z)} to the far corner. Lanes run
	 * along X (or Z) and are spread evenly across the other axis, never more than {@code spacing} apart.
	 */
	final class Lawnmower extends Polyline {
		public Lawnmower(double x, double z, double farX, double farZ, double spacing, boolean alongX) {
			// Work in (u = along the lanes, v = across them), then map back.
			double u0 = alongX ? x : z, v0 = alongX ? z : x;
			double u1 = alongX ? farX : farZ, v1 = alongX ? farZ : farX;
			double across = v1 - v0;
			int gaps = Math.max(1, (int) Math.ceil(Math.abs(across) / Math.max(1, spacing)));
			if (Math.abs(across) < 1) gaps = 0;
			double step = gaps == 0 ? 0 : across / gaps;
			boolean forward = true;
			for (int lane = 0; lane <= gaps; lane++) {
				double v = v0 + step * lane;
				double from = forward ? u0 : u1, until = forward ? u1 : u0;
				add(alongX, from, v);
				add(alongX, until, v);
				forward = !forward;
			}
		}

		private void add(boolean alongX, double u, double v) {
			if (alongX) to(u, v);
			else to(v, u);
		}
	}

	/** A straight line to {@code (x, z)}, finished within 5 blocks of it. */
	final class Goto extends Polyline {
		public Goto(double fromX, double fromZ, double x, double z) {
			to(fromX, fromZ);
			to(x, z);
		}

		@Override
		protected double finishWithin(double speed) {
			return Math.max(5, speed * 4);
		}
	}

	/** An endless circle of {@code radius} around a centre that far to the turning side of the start, flown by pure pursuit. */
	final class Orbit implements FlightPattern {
		private final double cx, cz, radius;
		private final int sense;

		public Orbit(double x, double z, float startYaw, boolean clockwise, double radius) {
			double rad = Math.toRadians(startYaw);
			double side = clockwise ? -1 : 1;
			this.cx = x + side * Math.cos(rad) * radius;
			this.cz = z + side * Math.sin(rad) * radius;
			this.radius = radius;
			this.sense = clockwise ? 1 : -1;
		}

		@Override
		public float yaw(double x, double z, double speed) {
			double angle = Math.atan2(z - cz, x - cx) + sense * Math.max(10, speed * 10) / radius;
			return yawOf(cx + radius * Math.cos(angle) - x, cz + radius * Math.sin(angle) - z);
		}

		@Override
		public boolean done() {
			return false;
		}
	}

	/**
	 * Follows a trail of detected chunks (a line of chunks someone loaded before you): heads for the
	 * recency-weighted middle of the detections ahead, ignores ones more than {@code maxDeviation}
	 * degrees off the trail, circles when none have come for {@code lostTicks}, and gives up after
	 * {@code giveUpTicks}. Until {@code lockOn} detections have come in it just holds the start heading.
	 */
	final class Trail implements FlightPattern {
		/** Which side to favour where the trail splits. */
		public enum Split { Straight, Left, Right }

		private final double maxDeviation, circleRate;
		private final int lostTicks, giveUpTicks, lockOn;
		private final Split split;
		private List<double[]> points = List.of();
		/** When each chunk centre was first detected: a later detection of the same chunk (a reload) is ignored. */
		private final java.util.Map<Long, Double> firstSeen = new java.util.HashMap<>();
		private float heading;
		private int sinceSeen;
		private boolean locked;

		public Trail(float startYaw, double maxDeviation, int lostTicks, int giveUpTicks, double circleRate, int lockOn, Split split) {
			this.heading = startYaw;
			this.maxDeviation = maxDeviation;
			this.lostTicks = lostTicks;
			this.giveUpTicks = giveUpTicks;
			this.circleRate = circleRate;
			this.lockOn = Math.max(1, lockOn);
			this.split = split;
			// One detection to lock on: steer (and circle when lost) from the start.
			this.locked = this.lockOn <= 1;
		}

		/** This tick's recent detections (block x, z, and the detection time), oldest first. */
		public void points(List<double[]> detections) {
			List<double[]> kept = new ArrayList<>(detections.size());
			for (double[] p : detections) {
				long key = ((long) Math.floor(p[0]) << 32) ^ ((long) Math.floor(p[1]) & 0xFFFFFFFFL);
				double time = p.length > 2 ? p[2] : 0;
				Double first = firstSeen.putIfAbsent(key, time);
				if (first == null || first == time) kept.add(p);
			}
			if (firstSeen.size() > 4096) firstSeen.clear();
			this.points = kept;
		}

		public boolean locked() {
			return locked;
		}

		@Override
		public float yaw(double x, double z, double speed) {
			if (!locked) {
				if (points.size() < lockOn) return heading;
				locked = true;
			}
			double hx = -Math.sin(Math.toRadians(heading)), hz = Math.cos(Math.toRadians(heading));
			double sx = 0, sz = 0, weight = 0;
			for (int i = 0; i < points.size(); i++) {
				double dx = points.get(i)[0] - x, dz = points.get(i)[1] - z;
				if (dx * hx + dz * hz <= 0) continue;
				float off = wrap(yawOf(dx, dz) - heading);
				if (Math.abs(off) > maxDeviation) continue;
				double w = i + 1;
				// Turning right adds degrees, so the right side is a positive offset.
				if (split == Split.Right && off > 0 || split == Split.Left && off < 0) w *= 2;
				sx += dx * w;
				sz += dz * w;
				weight += w;
			}
			if (weight > 0) {
				heading = yawOf(sx / weight, sz / weight);
				sinceSeen = 0;
			} else if (++sinceSeen > lostTicks) {
				heading += (float) circleRate;
			}
			return heading;
		}

		@Override
		public boolean done() {
			return locked && sinceSeen > giveUpTicks;
		}

		private static float wrap(float degrees) {
			float d = degrees % 360;
			if (d >= 180) d -= 360;
			if (d < -180) d += 360;
			return d;
		}
	}
}
