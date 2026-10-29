package dev.coffeeclient.module.modules.movement.elytrafly.modes;

import dev.coffeeclient.module.modules.movement.ElytraFly;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightMode;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightModes;
import dev.coffeeclient.util.KeyNames;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

/**
 * Bounce mode (elytra recast). Leans on vanilla elytra physics and re-sends START_FALL_FLYING to
 * fake out the server, recasting after rubberbands.
 *
 * <p>Smart pitch looks down while rising and almost level while falling. Lane keeping
 * sneak-strafes you back onto the locked line. A setback pauses everything for a few ticks, and
 * Cancel Y motion flattens the hop off the ground once you are fast enough.
 */
public class Bounce extends ElytraFlightMode {
	private boolean rubberbanded = false;
	private int tickDelay;
	private double prevFov;
	private int pause;
	private int sinceStartFly;
	/** The locked line, for lane keeping. */
	private Vec3 laneOrigin;
	/** The strafe key lane keeping holds this tick, or null. */
	private KeyMapping laneKey;

	public Bounce(ElytraFly elytraFly) {
		super(elytraFly, ElytraFlightModes.Bounce);
		this.tickDelay = elytraFly.restartDelay.intValue();
	}

	@Override
	public void onTick() {
		super.onTick();
		if (mc.player == null || mc.getConnection() == null) return;
		sinceStartFly++;
		releaseLane();

		if (pause > 0) {
			pause--;
			unpress();
			return;
		}

		if (mc.options.keyJump.isDown() && !mc.player.isFallFlying() && !elytraFly.manualTakeoff.get() && canStartFly(mc.player)) {
			mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
			sinceStartFly = 0;
		}

		if (checkConditions(mc.player)) {
			if (!rubberbanded) {
				if (prevFov != 0 && !elytraFly.sprint.get())
					mc.options.fovEffectScale().set(0.0); // Stops the FOV effect flapping on and off.
				if (elytraFly.autoJump.get()) mc.options.keyJump.setDown(true);
				mc.options.keyUp.setDown(true);
				mc.player.setYRot(getYawDirection());
				if (elytraFly.lockPitch.get()) mc.player.setXRot(pitch(mc.player));
				keepLane(mc.player);
			}

			if (!elytraFly.sprint.get()) {
				// Sprinting constantly (while not on ground) rubberbands on some anticheats.
				if (mc.player.isFallFlying()) mc.player.setSprinting(mc.player.onGround());
				else mc.player.setSprinting(true);
			}

			if (rubberbanded && elytraFly.restart.get()) {
				if (tickDelay > 0) {
					tickDelay--;
				} else {
					mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
					rubberbanded = false;
					tickDelay = elytraFly.restartDelay.intValue();
				}
			}
		}
	}

	/** With Minimize packets: only in the air (vanilla can't start a glide on the ground), and not twice in a row. */
	private boolean canStartFly(LocalPlayer player) {
		return !elytraFly.minimizePackets.get() || !player.onGround() && sinceStartFly > 1;
	}

	private float pitch(LocalPlayer player) {
		if (!elytraFly.smartPitch.get()) return elytraFly.pitch.get().floatValue();
		return player.getDeltaMovement().y > -0.2 ? 90 : 4;
	}

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
		if (mc.player != null && checkConditions(mc.player) && elytraFly.sprint.get()) mc.player.setSprinting(true);
	}

	private void unpress() {
		mc.options.keyUp.setDown(false);
		if (elytraFly.autoJump.get()) mc.options.keyJump.setDown(false);
	}

	@Override
	public void onPositionCorrection() {
		if (mc.player == null) return;
		rubberbanded = true;
		mc.player.stopFallFlying();
		pause = elytraFly.setbackPause.intValue();
	}

	@Override
	public void onPacketSend(net.minecraft.network.protocol.Packet<?> packet) {
		if (mc.player == null) return;
		if (packet instanceof ServerboundPlayerCommandPacket command
			&& command.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
			&& !elytraFly.sprint.get()) {
			mc.player.setSprinting(true);
		}
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
		pause = 0;
	}

	@Override
	public void onDeactivate() {
		unpress();
		releaseLane();
		rubberbanded = false;
		if (prevFov != 0 && !elytraFly.sprint.get()) mc.options.fovEffectScale().set(prevFov);
	}
}
