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
	 * Square spiral: straight lanes {@code spacing} apart, turning 90 degrees toward the turning side at
	 * each corner. Inward, the start is the outer corner of a {@code side} x {@code side} square and the
	 * laps shrink to {@code innerSide}; outward, the start is the inner end and the laps grow until the
	 * square is closed. Like {@link Spiral}, the yaw aims at a point a little ahead along the path, so
	 * corners are rounded off and drift is pulled back onto the lane.
	 */
	final class Square implements FlightPattern {
		private final List<double[]> points = new ArrayList<>();
		private int seg;
		private boolean done;

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
			points.add(new double[]{x, z});
			for (double len : lengths) {
				x += dx * len;
				z += dz * len;
				points.add(new double[]{x, z});
				// right of (dx, dz) is (-dz, dx), left is (dz, -dx)
				double t = dx;
				dx = clockwise ? -dz : dz;
				dz = clockwise ? t : -t;
			}
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
			int last = points.size() - 2;
			while (seg < last && distance(seg + 1, x, z) < distance(seg, x, z)) seg++;
			double t = along(seg, x, z);
			if (seg == last && length(seg) - t < Math.max(2, speed * 4)) done = true;

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

		/** Back onto the lane nearest where the player is now. */
		@Override
		public void resync(double x, double z) {
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
	 * Follows a trail of freshly generated chunks: heads for the recency-weighted middle of the
	 * detections ahead, ignores ones more than {@code maxDeviation} degrees off the trail, circles
	 * when none have come for {@code lostTicks}, and gives up after {@code giveUpTicks}.
	 */
	final class Trail implements FlightPattern {
		private final double maxDeviation, circleRate;
		private final int lostTicks, giveUpTicks;
		private List<double[]> points = List.of();
		private float heading;
		private int sinceSeen;

		public Trail(float startYaw, double maxDeviation, int lostTicks, int giveUpTicks, double circleRate) {
			this.heading = startYaw;
			this.maxDeviation = maxDeviation;
			this.lostTicks = lostTicks;
			this.giveUpTicks = giveUpTicks;
			this.circleRate = circleRate;
		}

		/** This tick's recent detections (block x, z), oldest first. */
		public void points(List<double[]> points) {
			this.points = points;
		}

		@Override
		public float yaw(double x, double z, double speed) {
			double hx = -Math.sin(Math.toRadians(heading)), hz = Math.cos(Math.toRadians(heading));
			double sx = 0, sz = 0, weight = 0;
			for (int i = 0; i < points.size(); i++) {
				double dx = points.get(i)[0] - x, dz = points.get(i)[1] - z;
				if (dx * hx + dz * hz <= 0) continue;
				if (Math.abs(wrap(yawOf(dx, dz) - heading)) > maxDeviation) continue;
				sx += dx * (i + 1);
				sz += dz * (i + 1);
				weight += i + 1;
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
			return sinceSeen > giveUpTicks;
		}

		private static float wrap(float degrees) {
			float d = degrees % 360;
			if (d >= 180) d -= 360;
			if (d < -180) d += 360;
			return d;
		}
	}
}
