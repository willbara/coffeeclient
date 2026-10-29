package dev.coffeeclient.util;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Where other players really are and where they are heading.
 * Remote players are drawn sliding towards the last position the server sent; {@link #position} is that
 * position. {@link #predict} walks a player's average velocity over the last few ticks forward with
 * gravity and block collisions, so CrystalAura can rate damage where a target will be once its
 * packets arrive.
 */
public final class MotionTracker {
	private static final int SAMPLES = 4;
	private static final double GRAVITY = 0.08;
	private static final double DRAG = 0.98;
	/** A jump between samples longer than this is a teleport, which restarts the history. */
	private static final double TELEPORT_SQ = 4.0 * 4.0;
	private static final double GLIDING_TELEPORT_SQ = 10.0 * 10.0;

	private static final Int2ObjectOpenHashMap<ArrayDeque<Vec3>> history = new Int2ObjectOpenHashMap<>();

	private MotionTracker() {
	}

	public static void init(EventBus bus) {
		bus.subscribe(Events.Tick.class, e -> tick());
		bus.subscribe(Events.GameLeft.class, e -> history.clear());
		bus.subscribe(Events.WorldChange.class, e -> history.clear());
	}

	/** The latest position the server sent for {@code entity} (its own position for you and for riders). */
	public static Vec3 position(Entity entity) {
		if (entity == Minecraft.getInstance().player || entity.isPassenger()) return entity.position();
		InterpolationHandler interpolation = entity.getInterpolation();
		return interpolation == null ? entity.position() : interpolation.position();
	}

	/** {@code entity}'s bounding box at {@link #position}. */
	public static AABB box(Entity entity) {
		return entity.getBoundingBox().move(position(entity).subtract(entity.position()));
	}

	/** Average movement per tick over the last few ticks (zero when unknown). */
	public static Vec3 velocity(Player player) {
		ArrayDeque<Vec3> samples = history.get(player.getId());
		if (samples == null || samples.size() < 2) return Vec3.ZERO;
		return samples.peekLast().subtract(samples.peekFirst()).scale(1.0 / (samples.size() - 1));
	}

	/** {@code entity}'s box {@code ticks} ticks from now, falling and colliding with blocks; players only move. */
	public static AABB predict(Entity entity, int ticks) {
		AABB box = box(entity);
		if (ticks <= 0 || !(entity instanceof Player player)) return box;
		Vec3 velocity = velocity(player);
		if (velocity.lengthSqr() < 1.0E-6) return box;

		for (int i = 0; i < ticks; i++) {
			Vec3 moved = Entity.collideBoundingBox(player, velocity, box, player.level(), List.of());
			box = box.move(moved);
			boolean landed = velocity.y < 0 && moved.y > velocity.y;
			velocity = new Vec3(velocity.x, landed ? 0 : (velocity.y - GRAVITY) * DRAG, velocity.z);
		}
		return box;
	}

	/** Feet position at the bottom centre of {@code box}. */
	public static Vec3 feet(AABB box) {
		return new Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
	}

	private static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		IntOpenHashSet seen = new IntOpenHashSet();
		for (Player player : mc.level.players()) {
			if (player == mc.player) continue;
			seen.add(player.getId());
			ArrayDeque<Vec3> samples = history.computeIfAbsent(player.getId(), id -> new ArrayDeque<>());
			Vec3 position = position(player);
			double limit = player.isFallFlying() ? GLIDING_TELEPORT_SQ : TELEPORT_SQ;
			if (!samples.isEmpty() && samples.peekLast().distanceToSqr(position) > limit) samples.clear();
			samples.addLast(position);
			while (samples.size() > SAMPLES + 1) samples.removeFirst();
		}
		history.keySet().removeIf(id -> !seen.contains(id));
	}
}
