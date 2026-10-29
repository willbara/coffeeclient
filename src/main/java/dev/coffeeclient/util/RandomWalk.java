package dev.coffeeclient.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * A wander around a home point: pick a spot within a radius, walk there, wait a while, repeat. Used by
 * AntiAFK (walking you) and FakePlayer (walking bots). No pathfinding: a walk that stops getting closer
 * is given up, and callers can refuse steps toward a drop with {@link #ledgeAhead}.
 */
public final class RandomWalk {
	/** Ticks without getting closer before a target is given up. */
	private static final int STUCK_TICKS = 40;

	private final Random random = new Random();
	private Vec3 home, target;
	private int waitTicks, stuckTicks;
	private double lastDistance;

	/** Start over around {@code home}. */
	public void reset(Vec3 home) {
		this.home = home;
		target = null;
		waitTicks = 0;
	}

	public Vec3 home() {
		return home;
	}

	/** The point to walk toward this tick, or null while waiting between walks. */
	public Vec3 target(Vec3 pos, double radius, int minWait, int maxWait) {
		if (home == null) reset(pos);
		if (waitTicks > 0) {
			waitTicks--;
			return null;
		}
		if (target == null) {
			double angle = random.nextDouble() * Math.PI * 2, distance = Math.sqrt(random.nextDouble()) * radius;
			target = new Vec3(home.x + Math.cos(angle) * distance, home.y, home.z + Math.sin(angle) * distance);
			stuckTicks = 0;
			lastDistance = Double.MAX_VALUE;
		}
		double d = Math.hypot(target.x - pos.x, target.z - pos.z);
		if (d < 0.6) {
			arrive(minWait, maxWait);
			return null;
		}
		if (d > lastDistance - 0.01) {
			if (++stuckTicks > STUCK_TICKS) {
				arrive(minWait, maxWait);
				return null;
			}
		} else {
			stuckTicks = 0;
		}
		lastDistance = d;
		return target;
	}

	private void arrive(int minWait, int maxWait) {
		target = null;
		waitTicks = minWait + random.nextInt(Math.max(1, maxWait - minWait + 1));
	}

	/** The way ahead is unsafe: drop this target and pick another after a short pause. */
	public void blocked() {
		target = null;
		waitTicks = 10;
	}

	/** True when a step from {@code feet} along {@code dir} would fall more than {@code maxDrop} blocks or into a liquid. */
	public static boolean ledgeAhead(Level level, Vec3 feet, Vec3 dir, int maxDrop) {
		Vec3 ahead = feet.add(dir.normalize().scale(0.8));
		BlockPos column = BlockPos.containing(ahead.x, feet.y + 0.01, ahead.z);
		// Walking into a wall isn't a ledge; the stuck check handles it.
		if (!level.getBlockState(column).getCollisionShape(level, column).isEmpty()) return false;
		for (int i = 1; i <= maxDrop + 1; i++) {
			BlockPos below = column.below(i);
			BlockState state = level.getBlockState(below);
			if (!state.getFluidState().isEmpty()) return true;
			if (!state.getCollisionShape(level, below).isEmpty()) return false;
		}
		return true;
	}
}
