package dev.coffeeclient.util;

import dev.coffeeclient.config.ClientOptions;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;
import java.util.function.Predicate;

/**
 * Server-side ("silent") rotations: a module asks to look somewhere, and for one movement packet the
 * server sees that look while the camera stays where the player put it.
 *
 * <p>Structure from {@code Rotations} (a request per tick with a priority, a callback that
 * runs right after the rotated packet is built, a hold for a few ticks, tracked server look).
 * Only the
 * highest-priority request of a tick is used and no extra look packets are sent: a module that lost
 * simply asks again next tick. Extra packets are what anti-cheats flag.
 *
 * <p>Timing: a request made during a client tick (usually {@code onTick}) is applied when the player
 * sends its next movement packet, early in the following tick. The callback then runs with the player
 * still turned, so anything it sends ({@code useItem}, {@code useItemOn}) carries the same look.
 * Nothing happens while riding or while the camera is detached from the player (Freecam): the request
 * is dropped and its callback never runs. Client thread only.
 */
public final class Rotations {
	/** A look direction in degrees (vanilla convention: yaw 0 = south, pitch 90 = straight down). */
	public record Rotation(float yaw, float pitch) {
		public static Rotation wrapped(float yaw, float pitch) {
			return new Rotation(Mth.wrapDegrees(yaw), Mth.wrapDegrees(pitch));
		}

		/** Angle in degrees between this look and {@code other}. */
		public double angleTo(Rotation other) {
			float dy = Mth.wrapDegrees(yaw - other.yaw);
			float dp = Mth.wrapDegrees(pitch - other.pitch);
			return Math.sqrt(dy * dy + dp * dp);
		}

		public Vec3 lookVec() {
			return Vec3.directionFromRotation(pitch, yaw);
		}
	}

	private record Request(Rotation rotation, int priority, Runnable callback, int tick) {
	}

	/** How {@link #smoothTowards} sizes each tick's turn. */
	public enum Smoother {
		Linear, Sigmoid, Interpolation, Acceleration;

		/** Degrees to turn this tick with {@code angle} degrees left, {@code speed} the top speed and {@code lastStep} the last tick's turn. */
		public float step(float angle, float speed, float lastStep) {
			float step = switch (this) {
				case Linear -> speed;
				// Half speed at speed degrees away, slowing to a crawl near the target.
				case Sigmoid -> speed / (1 + (float) Math.exp(-(angle - speed) / Math.max(speed / 4, 0.01f)));
				case Interpolation -> angle * Math.min(1, speed / 180);
				case Acceleration -> Math.min(speed, lastStep + speed / 4);
			};
			// A floor so the slow-near-target modes still arrive.
			return Math.min(angle, Math.max(step, 1));
		}
	}

	/** Where on a target's hitbox to aim. */
	public enum AimPoint {
		Eyes, Torso, Feet, Auto, LeastRotation
	}

	/** A smoothed look this close to the player's own counts as back, and the server stops being sent a separate one. */
	private static final float RESET_ANGLE = 2;
	private static final Random RANDOM = new Random();

	/** Priorities for {@link #rotate}; higher wins when two modules want the same tick. */
	public static final int LOW = -100, NORMAL = 0, HIGH = 100;

	private static Request pending;
	/** Counts client ticks, bumped first thing at the end of each tick (see {@link #fresh}). */
	private static int tick;

	private static Rotation held;
	private static int heldTicks;

	/** Set by {@link #smoothTowards}: how the look eases back to the player's own once modules stop asking. */
	private static Smoother resetSmoother;
	private static float resetSpeed;
	/** Degrees the last sent look turned. */
	private static volatile float lastStep;

	private static boolean applied;
	private static float savedYaw, savedPitch;
	private static Runnable callback;

	/** The look the server last received (from any outgoing movement packet). */
	private static volatile float serverYaw, serverPitch;

	private Rotations() {
	}

	public static void init(EventBus bus) {
		bus.subscribe(Events.Tick.class, EventBus.HIGHEST, e -> tick++);
		// Last at Pre so modules that ask from a Pre handler still make this packet; first at Post so
		// the callback runs (and the look is restored) before anything else looks at the player.
		bus.subscribe(Events.SendMovementPackets.Pre.class, EventBus.LOWEST, e -> pre());
		bus.subscribe(Events.SendMovementPackets.Post.class, EventBus.HIGHEST, e -> post());
		bus.subscribe(Events.PacketOut.class, EventBus.LOWEST, e -> {
			if (e.packet() instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
				Rotation last = new Rotation(serverYaw, serverPitch);
				serverYaw = move.getYRot(serverYaw);
				serverPitch = move.getXRot(serverPitch);
				lastStep = (float) last.angleTo(new Rotation(serverYaw, serverPitch));
			}
		});
		bus.subscribe(Events.GameLeft.class, e -> reset());
	}

	/**
	 * Asks for the server to see {@code yaw}/{@code pitch} in the next movement packet.
	 *
	 * @param afterSend run right after that packet is built, still turned; null for none. Never runs if
	 *                  a higher-priority request wins the tick.
	 * @return false when a request with higher or equal priority already holds this tick
	 */
	public static boolean rotate(float yaw, float pitch, int priority, Runnable afterSend) {
		if (fresh(pending) && pending.priority >= priority) return false;
		pending = new Request(new Rotation(yaw, Mth.clamp(pitch, -90, 90)), priority, afterSend, tick);
		return true;
	}

	public static boolean rotate(Rotation rotation, int priority, Runnable afterSend) {
		return rotate(rotation.yaw(), rotation.pitch(), priority, afterSend);
	}

	/** Looks at {@code target} from the player's eyes. */
	public static boolean rotate(Vec3 target, int priority, Runnable afterSend) {
		return rotate(needed(target), priority, afterSend);
	}

	/**
	 * A request lives until the next movement packet: it was made this tick, or in the previous one
	 * by a handler that ran before the counter moved (other end-of-tick listeners). One made earlier
	 * was never sent (riding, Freecam) and is dropped.
	 */
	private static boolean fresh(Request request) {
		return request != null && request.tick >= tick - 1;
	}

	private static void pre() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Request request = fresh(pending) ? pending : null;
		pending = null;
		if (player == null || mc.getCameraEntity() != player) {
			held = null;
			resetSmoother = null;
			return;
		}

		Rotation target;
		if (request != null) {
			target = request.rotation;
			held = target;
			heldTicks = 0;
			callback = request.callback;
		} else if (held != null && heldTicks < CoffeeClient.options().rotationHold.intValue()) {
			target = held;
			heldTicks++;
		} else {
			held = null;
			target = resetStep(player);
			if (target == null) return;
		}

		ClientOptions options = CoffeeClient.options();
		double jitter = options.rotationJitter.get();
		if (jitter > 0) {
			target = new Rotation(target.yaw() + (float) (RANDOM.nextGaussian() * jitter),
				Mth.clamp(target.pitch() + (float) (RANDOM.nextGaussian() * jitter), -90, 90));
		}

		savedYaw = player.getYRot();
		savedPitch = player.getXRot();
		if (options.rotationGcd.get()) {
			// Already continuous with the last sent yaw, so the turn stays a whole number of mouse steps.
			target = snap(target, serverYaw, serverPitch, mc.options.sensitivity().get());
			player.setYRot(target.yaw());
		} else {
			// Turn the short way round from the current yaw so the sent yaw does not jump by 360.
			player.setYRot(limitAngleChange(savedYaw, target.yaw()));
		}
		player.setXRot(target.pitch());
		applied = true;
	}

	private static void post() {
		if (!applied) return;
		applied = false;
		LocalPlayer player = Minecraft.getInstance().player;
		Runnable run = callback;
		callback = null;
		try {
			if (run != null) ErrorGuard.run("rotation callback", run);
		} finally {
			if (player != null) {
				player.setYRot(savedYaw);
				player.setXRot(savedPitch);
			}
		}
	}

	/** The next step back towards the player's own look after a smoothed rotation, or null once there. */
	private static Rotation resetStep(LocalPlayer player) {
		if (resetSmoother == null) return null;
		Rotation own = new Rotation(player.getYRot(), player.getXRot());
		float angle = (float) new Rotation(serverYaw, serverPitch).angleTo(own);
		if (angle <= RESET_ANGLE) {
			resetSmoother = null;
			return null;
		}
		return slowlyTurnTowards(own, resetSmoother.step(angle, resetSpeed, lastStep));
	}

	private static void reset() {
		pending = null;
		held = null;
		resetSmoother = null;
		callback = null;
		applied = false;
	}

	/** True while a module's rotation is being sent, held or eased back. */
	public static boolean rotating() {
		return held != null || resetSmoother != null;
	}

	/** Movement correction: the yaw the next movement packet carries for a module's turn, else NaN (move as usual). */
	public static float movementYaw() {
		if (!CoffeeClient.options().movementFix.get()) return Float.NaN;
		// shortcut: ignores the jitter, mouse-step rounding and ease-back pre() adds, a degree or so; exact once pre() runs before movement.
		if (fresh(pending)) return pending.rotation.yaw();
		return held != null && heldTicks < CoffeeClient.options().rotationHold.intValue() ? held.yaw() : Float.NaN;
	}

	/**
	 * The key direction (a unit move vector, x left and y forward) that moves closest to where
	 * {@code input} goes at {@code clientYaw} once movement runs at {@code sentYaw}.
	 */
	public static Vec2 correctInput(Vec2 input, float clientYaw, float sentYaw) {
		if (input.lengthSquared() < 1.0E-5F) return input;
		// Entity#getInputVector turns (left, forward) by the yaw; turning by the difference keeps the world direction.
		double a = Math.toRadians(clientYaw - sentYaw);
		double x = input.x * Math.cos(a) - input.y * Math.sin(a);
		double y = input.y * Math.cos(a) + input.x * Math.sin(a);
		double snapped = Math.round(Math.atan2(y, x) / (Math.PI / 4)) * (Math.PI / 4);
		return new Vec2((float) Math.round(Math.cos(snapped)), (float) Math.round(Math.sin(snapped))).normalized();
	}

	public static float serverYaw() {
		return serverYaw;
	}

	public static float serverPitch() {
		return serverPitch;
	}

	// ---- math ----

	public static Vec3 eyes() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player.getEyePosition();
	}

	/** The look from the player's eyes to {@code target}. */
	public static Rotation needed(Vec3 target) {
		return needed(eyes(), target);
	}

	public static Rotation needed(Vec3 from, Vec3 target) {
		double dx = target.x - from.x, dy = target.y - from.y, dz = target.z - from.z;
		double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90;
		double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		return Rotation.wrapped((float) yaw, (float) pitch);
	}

	/** Looks at the middle of {@code entity}'s hitbox. */
	public static Rotation needed(Entity entity) {
		return needed(entity.getBoundingBox().getCenter());
	}

	/**
	 * The point on {@code entity}'s hitbox to aim at: in reach of the eyes and in sight, or through
	 * blocks within {@code wallsRange}. Auto takes the first usable of eyes, torso and feet; least
	 * rotation the usable point nearest the look last sent. Null when no point is usable.
	 */
	public static Vec3 aimPoint(Entity entity, AimPoint mode, double range, double wallsRange) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 eyes = eyes();
		// The pick box: Hitbox grows it.
		AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
		Predicate<Vec3> usable = point -> {
			double distSq = eyes.distanceToSqr(point);
			if (distSq > range * range) return false;
			if (distSq <= wallsRange * wallsRange) return true;
			return mc.level.clip(new ClipContext(eyes, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
		};
		Vec3 centre = box.getCenter();
		Vec3 eyePoint = new Vec3(centre.x, Math.min(entity.getEyeY(), box.maxY - 0.05), centre.z);
		Vec3 feet = new Vec3(centre.x, box.minY + 0.1, centre.z);
		return switch (mode) {
			case Eyes -> usable.test(eyePoint) ? eyePoint : null;
			case Torso -> usable.test(centre) ? centre : null;
			case Feet -> usable.test(feet) ? feet : null;
			case Auto -> usable.test(eyePoint) ? eyePoint : usable.test(centre) ? centre : usable.test(feet) ? feet : null;
			case LeastRotation -> leastRotationPoint(box, eyes, new Rotation(serverYaw, serverPitch), usable);
		};
	}

	/** Where the {@code look} ray from {@code eyes} enters {@code box} if usable, else the usable sample of the box nearest it. */
	static Vec3 leastRotationPoint(AABB box, Vec3 eyes, Rotation look, Predicate<Vec3> usable) {
		Vec3 hit = box.clip(eyes, eyes.add(look.lookVec().scale(64))).orElse(null);
		if (hit != null && usable.test(hit)) return hit;
		AABB inset = box.deflate(box.getXsize() * 0.1, box.getYsize() * 0.1, box.getZsize() * 0.1);
		Vec3 best = null;
		double bestAngle = Double.MAX_VALUE;
		for (int i = 0; i <= 2; i++) for (int j = 0; j <= 2; j++) for (int k = 0; k <= 2; k++) {
			Vec3 point = new Vec3(Mth.lerp(i / 2.0, inset.minX, inset.maxX), Mth.lerp(j / 2.0, inset.minY, inset.maxY), Mth.lerp(k / 2.0, inset.minZ, inset.maxZ));
			double angle = look.angleTo(needed(eyes, point));
			if (angle < bestAngle && usable.test(point)) {
				best = point;
				bestAngle = angle;
			}
		}
		return best;
	}

	/**
	 * The look that lands a shot ({@code speed} blocks per tick, {@code gravity}, {@code drag}) on the
	 * middle of {@code target}, leading its movement over the flight; null when out of reach.
	 */
	public static Rotation ballistic(Entity target, double speed, double gravity, double drag) {
		Vec3 eyes = eyes();
		Vec3 aim = target.getBoundingBox().getCenter();
		double elevation = Double.NaN;
		// Each pass aims where the target will be after the last pass's flight time.
		for (int i = 0; i < 3; i++) {
			double distance = Math.hypot(aim.x - eyes.x, aim.z - eyes.z);
			elevation = Ballistics.elevation(distance, aim.y - eyes.y, speed, gravity, drag);
			if (Double.isNaN(elevation)) return null;
			Ballistics.Arrival arrival = Ballistics.arrive(elevation, distance, speed, gravity, drag);
			if (arrival == null) return null;
			aim = MotionTracker.predict(target, (int) Math.ceil(arrival.ticks())).getCenter();
		}
		return new Rotation(needed(aim).yaw(), (float) -elevation);
	}

	/** Angle in degrees between the look the server last received and the one needed for {@code target}. */
	public static double angleToServerLook(Vec3 target) {
		return new Rotation(serverYaw, serverPitch).angleTo(needed(target));
	}

	/** True when the look the server last received, cast {@code range} blocks from the eyes, hits {@code box}. */
	public static boolean isFacingBox(AABB box, double range) {
		Vec3 start = eyes();
		Vec3 end = start.add(new Rotation(serverYaw, serverPitch).lookVec().scale(range));
		return box.contains(start) || box.clip(start, end).isPresent();
	}

	/**
	 * {@code current} moved towards {@code intended} by at most {@code maxChange} degrees, the short way
	 * round. Do not wrap {@code current} first: the result stays continuous with it.
	 */
	public static float limitAngleChange(float current, float intended, float maxChange) {
		float change = Mth.wrapDegrees(intended - current);
		return current + Mth.clamp(change, -maxChange, maxChange);
	}

	/** {@code intended} expressed as the nearest angle to {@code current} (no 358-degree turns). */
	public static float limitAngleChange(float current, float intended) {
		return current + Mth.wrapDegrees(intended - current);
	}

	/**
	 * The next step from the last sent look towards {@code end}, sized by {@code smoother} with top
	 * speed {@code speed} degrees per tick. Once modules stop asking, the look eases back the same way.
	 */
	public static Rotation smoothTowards(Rotation end, Smoother smoother, float speed) {
		resetSmoother = smoother;
		resetSpeed = speed;
		float angle = (float) new Rotation(serverYaw, serverPitch).angleTo(end);
		return slowlyTurnTowards(end, smoother.step(angle, speed, lastStep));
	}

	/**
	 * {@code look} rounded onto the grid a real mouse turn from ({@code fromYaw}, {@code fromPitch})
	 * can reach at this sensitivity (vanilla: (s * 0.6 + 0.2)^3 * 8 * 0.15 degrees per mouse step).
	 */
	static Rotation snap(Rotation look, float fromYaw, float fromPitch, double sensitivity) {
		double f = sensitivity * 0.6 + 0.2;
		float step = (float) (f * f * f * 1.2);
		float yaw = fromYaw + Math.round(Mth.wrapDegrees(look.yaw() - fromYaw) / step) * step;
		float pitch = fromPitch + Math.round((look.pitch() - fromPitch) / step) * step;
		return new Rotation(yaw, Mth.clamp(pitch, -90, 90));
	}

	/**
	 * The next step from the last sent look towards {@code end}, turning about {@code maxChange} degrees
	 * per tick with yaw and pitch arriving together.
	 */
	public static Rotation slowlyTurnTowards(Rotation end, float maxChange) {
		float startYaw = serverYaw, startPitch = serverPitch;
		float yawChange = Math.abs(Mth.wrapDegrees(end.yaw() - startYaw));
		float pitchChange = Math.abs(Mth.wrapDegrees(end.pitch() - startPitch));
		float maxYaw = pitchChange == 0 ? maxChange : Math.min(maxChange, maxChange * yawChange / pitchChange);
		float maxPitch = yawChange == 0 ? maxChange : Math.min(maxChange, maxChange * pitchChange / yawChange);
		return new Rotation(limitAngleChange(startYaw, end.yaw(), maxYaw), limitAngleChange(startPitch, end.pitch(), maxPitch));
	}
}
