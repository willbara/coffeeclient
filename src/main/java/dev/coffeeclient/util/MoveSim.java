package dev.coffeeclient.util;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Steps an entity's box forward with gravity, air drag and block collisions until something stops
 * the fall: landing (with the distance fallen), water, lava, a ladder or vine, cobweb, or the void.
 * Answers "where, on what, and after how long would I land". Elytra flight is not modelled.
 */
public final class MoveSim {
	public static final double GRAVITY = 0.08, DRAG = 0.98, AIR_FRICTION = 0.91;

	public enum Stop { NONE, LANDED, WATER, LAVA, CLIMBABLE, WEB, VOID }

	/** Where it ended, after how many ticks, why, and how far it fell on the way. */
	public record Result(AABB box, int ticks, Stop stop, double fallen) {
		public Vec3 feet() {
			return new Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
		}

		public BlockPos below() {
			return BlockPos.containing(feet().x, box.minY - 0.01, feet().z);
		}
	}

	private MoveSim() {
	}

	/** One airborne tick of velocity: gravity then drag vertically; horizontally kept (when {@code keep}) or slowed by air friction. */
	static Vec3 step(Vec3 v, boolean keep) {
		double h = keep ? 1 : AIR_FRICTION;
		return new Vec3(v.x * h, (v.y - GRAVITY) * DRAG, v.z * h);
	}

	/**
	 * Runs up to {@code maxTicks} from {@code box} at {@code velocity}. With {@code keepHorizontal}
	 * the sideways speed stays as given (a module holding a speed), otherwise air friction slows it.
	 */
	public static Result run(Entity entity, AABB box, Vec3 velocity, boolean keepHorizontal, int maxTicks) {
		Level level = entity.level();
		double fallen = 0;
		for (int tick = 1; tick <= maxTicks; tick++) {
			Vec3 moved = Entity.collideBoundingBox(entity, velocity, box, level, List.of());
			box = box.move(moved);
			if (moved.y < 0) fallen -= moved.y;
			Stop stop = stopAt(level, box);
			if (stop != Stop.NONE) return new Result(box, tick, stop, fallen);
			if (velocity.y < 0 && moved.y > velocity.y + 1e-7) return new Result(box, tick, Stop.LANDED, fallen);
			velocity = step(velocity, keepHorizontal);
		}
		return new Result(box, maxTicks, Stop.NONE, fallen);
	}

	/** Something at the box's feet that ends a fall without landing on a floor. */
	private static Stop stopAt(Level level, AABB box) {
		if (box.minY < level.getMinY() - 16) return Stop.VOID;
		BlockPos feet = BlockPos.containing((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
		BlockState state = level.getBlockState(feet);
		if (state.getFluidState().is(FluidTags.LAVA)) return Stop.LAVA;
		if (state.getFluidState().is(FluidTags.WATER)) return Stop.WATER;
		if (state.is(Blocks.COBWEB)) return Stop.WEB;
		if (state.is(BlockTags.CLIMBABLE)) return Stop.CLIMBABLE;
		return Stop.NONE;
	}

	/** Fall damage for landing after {@code fallen} blocks (before armour and effects). */
	public static double fallDamage(double fallen) {
		return Math.max(0, Math.ceil(fallen - 3));
	}
}
