package dev.coffeeclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

/** Small client-side player queries shared by the ported modules. */
public final class Player {
	private Player() {
	}

	/** True when a world and local player exist (i.e. we are in-game). */
	public static boolean canUpdate() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.level != null;
	}

	public static LocalPlayer get() {
		return Minecraft.getInstance().player;
	}

	public static boolean isMoving() {
		LocalPlayer p = get();
		return p != null && (p.zza != 0 || p.xxa != 0);
	}

	private static boolean jumpRequested;

	/**
	 * Presses jump for the next movement tick, so vanilla makes the jump and the input packet lists it.
	 * Grim and NCP only allow a 1.21.2+ client to jump on a tick whose input packet has jump down.
	 */
	public static void requestJump() {
		jumpRequested = true;
	}

	/** {@code LocalPlayerMixin}: whether jump is pressed this tick (clears the request). */
	public static boolean takeJump() {
		boolean jump = jumpRequested;
		jumpRequested = false;
		return jump;
	}

	/** Whether the jump or sneak key is held. */
	public static boolean holdingJumpOrSneak() {
		Minecraft mc = Minecraft.getInstance();
		return mc.options.keyJump.isDown() || mc.options.keyShift.isDown();
	}

	/** Vanilla sprinting ground speed per tick, before effects. */
	public static final double BASE_SPEED = 0.2873;

	/**
	 * Unit horizontal direction the movement keys point, relative to where the player looks, or
	 * {@link Vec3#ZERO} when no key is held. Diagonals are normalised.
	 */
	public static Vec3 moveDir() {
		LocalPlayer p = get();
		if (p == null) return Vec3.ZERO;
		double forward = p.zza, strafe = p.xxa;
		double yaw = Math.toRadians(p.getYRot());
		double sin = Math.sin(yaw), cos = Math.cos(yaw);
		double x = -sin * forward + cos * strafe;
		double z = cos * forward + sin * strafe;
		double length = Math.sqrt(x * x + z * z);
		return length < 1.0E-6 ? Vec3.ZERO : new Vec3(x / length, 0, z / length);
	}

	/** {@link #BASE_SPEED} scaled by Speed and Slowness, as speed modes use it. */
	public static double baseSpeed() {
		LocalPlayer p = get();
		double speed = BASE_SPEED;
		if (p == null) return speed;
		MobEffectInstance fast = p.getEffect(MobEffects.SPEED);
		if (fast != null) speed *= 1.0 + 0.2 * (fast.getAmplifier() + 1);
		MobEffectInstance slow = p.getEffect(MobEffects.SLOWNESS);
		if (slow != null) speed /= 1.0 + 0.2 * (slow.getAmplifier() + 1);
		return speed;
	}

	/** A jump's starting vertical speed {@code height}, plus Jump Boost. */
	public static double hopHeight(double height) {
		LocalPlayer p = get();
		MobEffectInstance boost = p == null ? null : p.getEffect(MobEffects.JUMP_BOOST);
		return boost == null ? height : height + (boost.getAmplifier() + 1) * 0.1;
	}

	/** Horizontal distance moved last tick. */
	public static double lastTickDistance() {
		LocalPlayer p = get();
		if (p == null) return 0;
		double dx = p.getX() - p.xo, dz = p.getZ() - p.zo;
		return Math.sqrt(dx * dx + dz * dz);
	}
}
