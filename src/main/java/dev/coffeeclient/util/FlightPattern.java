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
	 * Back-and-forth sweep of a {@code side} x {@code side} square: along the start heading, across to
	 * the turning side, then lanes {@code spacing} apart coming back toward the start.
	 */
	final class Square implements FlightPattern {
		private final List<double[]> points = new ArrayList<>();
		private int next;

		public Square(double x, double z, float startYaw, boolean clockwise, double side, double spacing) {
			double rad = Math.toRadians(startYaw);
			double fx = -Math.sin(rad), fz = Math.cos(rad);
			double sx = clockwise ? -Math.cos(rad) : Math.cos(rad), sz = clockwise ? -Math.sin(rad) : Math.sin(rad);
			// a: along the heading, b: toward the turning side
			add(x, z, fx, fz, sx, sz, side, 0);
			add(x, z, fx, fz, sx, sz, side, side);
			boolean up = true;
			for (int k = 1; ; k++) {
				double a = Math.max(0, side - k * spacing);
				double from = up ? side : spacing, to = up ? spacing : side;
				add(x, z, fx, fz, sx, sz, a, from);
				add(x, z, fx, fz, sx, sz, a, to);
				up = !up;
				if (a <= 0) break;
			}
		}

		private void add(double x, double z, double fx, double fz, double sx, double sz, double a, double b) {
			points.add(new double[]{x + fx * a + sx * b, z + fz * a + sz * b});
		}

		@Override
		public float yaw(double x, double z, double speed) {
			double reach = Math.max(2, speed * 4);
			while (next < points.size() && Math.hypot(points.get(next)[0] - x, points.get(next)[1] - z) < reach) next++;
			if (next >= points.size()) return 0;
			double[] p = points.get(next);
			return yawOf(p[0] - x, p[1] - z);
		}

		@Override
		public boolean done() {
			return next >= points.size();
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
