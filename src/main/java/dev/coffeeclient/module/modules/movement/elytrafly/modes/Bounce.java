package dev.coffeeclient.module.modules.movement.elytrafly.modes;

import dev.coffeeclient.mixin.LivingEntityAccessor;
import dev.coffeeclient.module.modules.movement.ElytraFly;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightMode;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightModes;
import dev.coffeeclient.util.KeyNames;
import dev.coffeeclient.util.Latency;
import dev.coffeeclient.util.Player;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bounce mode (elytra recast). Leans on vanilla elytra physics and keeps re-opening the glide as
 * the server drops it on every landing.
 *
 * <p>Pitch spoof glides at a faked pitch within 4 blocks of the ground, in both the physics and
 * the movement packet, while the camera stays where you look; it lets go once you climb past
 * that until you land again. Yaw spoof turns the glide's yaw the same way within a block of the
 * ground. Re-glide starts the glide again in the same tick the server drops it. Cancel Y motion
 * flattens the hop off the ground once you are fast enough.
 *
 * <p>A setback does not stop the glide. It only switches the pitch fake and the ground relaunch
 * off for a short window, and a velocity push from the server freezes sprint and re-glide for a
 * while.
 */
public class Bounce extends ElytraFlightMode {
	/** The pitch fake works this close to the ground, the yaw fake this close. */
	private static final double PITCH_RANGE = 4, YAW_RANGE = 1;

	/** Ticks since the last setback or relaunch; the pitch fake and the relaunch wait for the setback window. */
	private int sinceSetback = 1000;
	/** Ticks left to leave sprint and re-glide alone after the server set our velocity. */
	private int freeze;
	/** Tick counter, and the tick a start-fly was last sent. */
	private int now, lastStart = -100;

	private double prevFov;
	/** Climbed past the pitch fake's range since the last time on the ground. */
	private boolean tooHigh;
	/** Whether the glide was open when last looked at, so a drop shows. */
	private boolean glidingSeen;
	private boolean turned;
	/** Whether Auto walk is holding forward down. */
	private boolean walking;
	/** The glide was dropped on the ground and the hop is made; open the glide once airborne. */
	private boolean glideInAir;
	/** Debug: what happened this tick, for the trace line. */
	private final StringBuilder events = new StringBuilder();
	/** Debug: the pitch the physics and the packet used this tick (null = the camera's), and the last ticks' lines. */
	private Float physicsPitch, sentPitch;
	private final java.util.ArrayDeque<String> trace = new java.util.ArrayDeque<>();
	private int traceTick;
	private float savedYaw, savedPitch;
	/** The locked line, for lane keeping. */
	private Vec3 laneOrigin;
	/** The strafe key lane keeping holds this tick, or null. */
	private KeyMapping laneKey;

	public Bounce(ElytraFly elytraFly) {
		super(elytraFly, ElytraFlightModes.Bounce);
	}

	@Override
	public void onTick() {
		super.onTick();
		LocalPlayer player = mc.player;
		if (player == null || mc.getConnection() == null) return;
		now++;
		// Only the debug trace reads and clears the events, so drop them when it is off.
		if (!elytraFly.bounceDebug.get()) events.setLength(0);
		if (sinceSetback < 1000) sinceSetback++;
		releaseLane();
		checkReglide(player);
		tickBounce(player);
		glidingSeen = player.isFallFlying();
	}

	/**
	 * Keys are held but nothing is torn down on a setback: the glide goes on, jumping is a one-tick
	 * press while gliding, and a relaunch only comes after the setback window.
	 */
	private void tickBounce(LocalPlayer player) {
		if (freeze > 0) freeze--;
		if (player.onGround()) tooHigh = false;

		if (checkConditions(player)) {
			calmFov();
			walk();
			player.setYRot(getYawDirection());
			keepLane(player);
			if (elytraFly.autoJump.get() && player.isFallFlying()) {
				((LivingEntityAccessor) player).coffeeclient$setNoJumpDelay(0);
				Player.requestJump();
			}
		}
		if (freeze > 0) return;

		if (checkConditions(player)) applySprint(player);

		if (player.isFallFlying()) glideInAir = false;
		if (glideInAir && !player.isFallFlying() && !player.onGround()) {
			glideInAir = false;
			if (!startInFlight() && canGlide(player)) {
				pressToGlide();
				events.append(" A");
			}
		}

		if (elytraFly.reglide.get() && !elytraFly.manualTakeoff.get() && !player.isFallFlying() && !glideInAir && canGlide(player)) {
			if (player.onGround() && sinceSetback >= elytraFly.setbackWindow.intValue()) {
				events.append(" J");
				// Hop off the ground; the glide opens once the fall begins.
				sinceSetback = 0;
				((LivingEntityAccessor) player).coffeeclient$setNoJumpDelay(0);
				Player.requestJump();
			} else if (player.getDeltaMovement().y < 0 && !player.onGround() && !startInFlight()) {
				pressToGlide();
			}
		}
	}

	/** The glide was open and is not now: open it again in the same tick, locally and on the server. */
	private void checkReglide(LocalPlayer player) {
		boolean now = player.isFallFlying();
		boolean dropped = glidingSeen && !now;
		glidingSeen = now;
		if (!dropped || !elytraFly.reglide.get() || freeze > 0 || !canGlide(player)) return;
		if (startInFlight()) {
			// Our own start-fly is still on its way: this drop is the old one, and another start would stop the glide again.
			events.append(" x");
			return;
		}
		if (player.onGround() && !elytraFly.reglideOnGround.get()) {
			// The server turns a start-fly from the ground down, so hop as a plain jump and glide from the air.
			glideInAir = true;
			((LivingEntityAccessor) player).coffeeclient$setNoJumpDelay(0);
			Player.requestJump();
			events.append(" H");
			return;
		}
		player.startFallFlying();
		if (!mc.isLocalServer()) sendStart();
		events.append(" R");
	}

	/** The server reads a start-fly while gliding as a stop, so one is not sent again until the first has had its round trip. */
	private boolean startInFlight() {
		return now - lastStart <= Math.max(2, Math.min(8, (int) Math.ceil(Latency.rtt() / 50.0) + 1));
	}

	/**
	 * Opens the glide the way vanilla does: a fresh jump press in the air, so the start-fly goes out on
	 * the same tick as an input packet with jump down, not on its own.
	 */
	private void pressToGlide() {
		lastStart = now;
		Player.requestJump();
		events.append(" j");
	}

	private void sendStart() {
		lastStart = now;
		ElytraFly.sendCommand();
	}

	@Override
	public void onEntityData() {
		if (mc.player != null && mc.getConnection() != null) checkReglide(mc.player);
	}

	@Override
	public void onSelfMotion() {
		freeze = elytraFly.velocityFreeze.intValue();
	}

	/** Not riding, not in a fluid, and wearing something that glides. */
	private static boolean canGlide(LocalPlayer player) {
		return !player.isPassenger() && !player.isInWater() && !player.isInLava()
			&& player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER);
	}

	private void calmFov() {
		if (prevFov != 0 && !elytraFly.sprint.get())
			mc.options.fovEffectScale().set(0.0); // Stops the FOV effect flapping on and off.
	}

	/**
	 * Sprint is only forced while forward is held. Without forward input vanilla clears sprint itself,
	 * and a client that keeps sprint on disagrees with the server and gets set back. Nothing is forced
	 * off in the air: flipping sprint there sends sprint packets that cost the glide.
	 */
	private void applySprint(LocalPlayer player) {
		if (holdingForward(player)) player.setSprinting(true);
	}

	private static boolean holdingForward(LocalPlayer player) {
		return player.input.keyPresses.forward();
	}

	// ---- Faked pitch and yaw ----

	/** How far the closest of the feet's corners and middle is above a block, up to 5 blocks, else 999. */
	private double groundDistance(LocalPlayer player) {
		AABB box = player.getBoundingBox();
		Vec3[] origins = {
			new Vec3(box.minX, box.minY, box.minZ),
			new Vec3(box.maxX, box.minY, box.minZ),
			new Vec3(box.maxX, box.minY, box.maxZ),
			new Vec3(box.minX, box.minY, box.maxZ),
			player.position()
		};
		double nearest = 999;
		for (Vec3 from : origins) {
			BlockHitResult hit = mc.level.clip(new ClipContext(from, from.subtract(0, 5, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			if (hit.getType() == HitResult.Type.BLOCK) nearest = Math.min(nearest, hit.getLocation().distanceTo(from));
		}
		return nearest;
	}

	@Override
	public Float glidePitch() {
		physicsPitch = spoofPitch();
		return physicsPitch;
	}

	private Float spoofPitch() {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || !elytraFly.pitchSpoof.get() || !player.isFallFlying()) return null;
		if (elytraFly.pitchControl.get() && player.getXRot() < 0) return null;
		if (!player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) return null;
		double ground = groundDistance(player);
		if (ground > PITCH_RANGE) tooHigh = true;
		boolean settled = sinceSetback >= elytraFly.setbackWindow.intValue();
		return settled && ground <= PITCH_RANGE && !tooHigh ? elytraFly.pitch.floatValue() : null;
	}

	@Override
	public float glideYawOffset() {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || !elytraFly.yawSpoof.get() || !player.isFallFlying()) return 0;
		if (groundDistance(player) >= YAW_RANGE) return 0;
		float offset = elytraFly.yawOffset.floatValue();
		return player.horizontalCollision ? offset / 2 : offset;
	}

	/** The movement packet is built from the player's look, so the fake goes on for it and comes off after. */
	@Override
	public void onSendMovementPre() {
		LocalPlayer player = mc.player;
		if (player == null) return;
		Float pitch = spoofPitch();
		float yaw = glideYawOffset();
		sentPitch = pitch;
		if (pitch == null && yaw == 0) return;
		turned = true;
		savedYaw = player.getYRot();
		savedPitch = player.getXRot();
		if (pitch != null) player.setXRot(pitch);
		if (yaw != 0) player.setYRot(savedYaw + yaw);
	}

	@Override
	public void onSendMovementPost() {
		if (elytraFly.bounceDebug.get()) record();
		if (!turned) return;
		turned = false;
		if (mc.player == null) return;
		mc.player.setYRot(savedYaw);
		mc.player.setXRot(savedPitch);
	}

	// ---- Debug trace ----

	private static String pitchText(Float pitch) {
		return pitch == null ? "cam" : String.format(java.util.Locale.ROOT, "%.0f", pitch);
	}

	/** One line per tick: where the player is, which pitch the physics and the packet used, and the recovery state. */
	private void record() {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		String line = String.format(java.util.Locale.ROOT, "t%d %s%s y=%.2f vy=%.2f %.0fb/s gnd=%.1f camP=%.0f phys=%s sent=%s win=%d frz=%d%s",
			traceTick++, p.isFallFlying() ? "G" : "-", p.onGround() ? "g" : "-", p.getY(), p.getDeltaMovement().y,
			p.getDeltaMovement().horizontalDistance() * 20, groundDistance(p), turned ? savedPitch : p.getXRot(), pitchText(physicsPitch), pitchText(sentPitch),
			Math.min(sinceSetback, 99), freeze, (tooHigh ? " HIGH" : "") + (events.length() > 0 ? " |" + events : ""));
		events.setLength(0);
		physicsPitch = sentPitch = null;
		trace.addLast(line);
		while (trace.size() > 10) trace.removeFirst();
	}

	private void dumpTrace() {
		elytraFly.debug("setback, last ticks:");
		for (String line : trace) elytraFly.debug(line);
		trace.clear();
	}

	// ---- Lane keeping ----

	/** Sneak-strafes toward the locked line when more than Lane width off it. */
	private void keepLane(LocalPlayer player) {
		if (!elytraFly.laneKeeping.get() || elytraFly.yawLockMode.get() == ElytraFly.YawLock.None) return;
		if (laneOrigin == null) laneOrigin = player.position();
		double rad = Math.toRadians(getYawDirection());
		double dx = -Math.sin(rad), dz = Math.cos(rad);
		double ox = player.getX() - laneOrigin.x, oz = player.getZ() - laneOrigin.z;
		double off = dx * oz - dz * ox;
		if (Math.abs(off) <= elytraFly.laneWidth.get()) return;
		// Positive: you drifted to the right of the line, so strafe left; negative: right.
		laneKey = off > 0 ? mc.options.keyLeft : mc.options.keyRight;
		laneKey.setDown(true);
		mc.options.keyShift.setDown(true);
	}

	private void releaseLane() {
		if (laneKey == null) return;
		laneKey.setDown(KeyNames.isPhysicallyDown(laneKey));
		mc.options.keyShift.setDown(KeyNames.isPhysicallyDown(mc.options.keyShift));
		laneKey = null;
	}

	@Override
	public Vec3 adjustMove(Vec3 vanilla, Vec3 movement) {
		LocalPlayer player = mc.player;
		if (player == null || !elytraFly.yMotion.get() || !player.onGround() || !player.isFallFlying()) return movement;
		double speed = movement.horizontalDistance() * 20;
		if (speed <= elytraFly.yMotionStart.get() || speed >= elytraFly.yMotionLimit.get()) return movement;
		if (elytraFly.yMotionDiagonal.get()) {
			float off = Math.abs(player.getYRot() % 90);
			if (off < 15 || off > 75) return movement;
		}
		return new Vec3(movement.x, 0, movement.z);
	}

	@Override
	public void onPreTick() {
		super.onPreTick();
		if (mc.player != null && checkConditions(mc.player) && elytraFly.sprint.get() && holdingForward(mc.player)) mc.player.setSprinting(true);
	}

	/** Holds forward when Auto walk is on; turned off mid-flight, forward goes back to the key itself. */
	private void walk() {
		if (elytraFly.autoWalk.get()) {
			mc.options.keyUp.setDown(true);
			walking = true;
		} else {
			releaseWalk();
		}
	}

	private void releaseWalk() {
		if (!walking) return;
		walking = false;
		mc.options.keyUp.setDown(KeyNames.isPhysicallyDown(mc.options.keyUp));
	}

	@Override
	public void onPositionCorrection() {
		if (mc.player == null) return;
		if (elytraFly.bounceDebug.get()) {
			record();
			dumpTrace();
		}
		sinceSetback = 0;
	}

	@Override
	public void onPacketSend(net.minecraft.network.protocol.Packet<?> packet) {
		if (mc.player == null) return;
		if (packet instanceof ServerboundPlayerCommandPacket command
			&& command.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
			&& !elytraFly.sprint.get() && holdingForward(mc.player)) {
			mc.player.setSprinting(true);
		}
		if (packet instanceof ServerboundPlayerCommandPacket command && command.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING)
			events.append(" S");
	}

	/** Re-glide takes off, not the jump-hold timer. */
	@Override
	public void autoTakeoff() {
	}

	public static boolean checkConditions(LocalPlayer player) {
		boolean isClimbing = player.onClimbable();
		return !player.getAbilities().flying && !player.isPassenger() && !isClimbing
			&& !player.isInWater() && !player.hasEffect(MobEffects.LEVITATION);
	}

	private float getYawDirection() {
		return switch (elytraFly.yawLockMode.get()) {
			case None -> mc.player.getYRot();
			case Smart -> Math.round((mc.player.getYRot() + 1f) / 45f) * 45f;
			case Simple -> elytraFly.yaw.floatValue();
		};
	}

	@Override
	public void onActivate() {
		prevFov = mc.options.fovEffectScale().get();
		laneOrigin = null;
		freeze = 0;
		sinceSetback = 1000;
		tooHigh = false;
		turned = false;
		glideInAir = false;
		lastStart = -100;
		glidingSeen = mc.player != null && mc.player.isFallFlying();
	}

	@Override
	public void onDeactivate() {
		releaseWalk();
		releaseLane();
		tooHigh = false;
		if (prevFov != 0 && !elytraFly.sprint.get()) mc.options.fovEffectScale().set(prevFov);
	}
}
