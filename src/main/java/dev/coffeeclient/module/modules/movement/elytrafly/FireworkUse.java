package dev.coffeeclient.module.modules.movement.elytrafly;

import dev.coffeeclient.module.modules.movement.ElytraFly;
import dev.coffeeclient.util.Inv;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Fires firework rockets for ElytraFly and scales the push a rocket gives (see the "Fireworks" and "Rocket boost" settings). */
public final class FireworkUse {
	/** What makes a rocket due. */
	public enum Trigger {
		Timer, Speed, Either
	}

	/** Normal swaps the slot in view and swings; Silent does it in one packet flush without a swing. */
	public enum UseMode {
		Normal, Silent
	}

	private final ElytraFly elytraFly;
	private int sinceUse;

	public FireworkUse(ElytraFly elytraFly) {
		this.elytraFly = elytraFly;
	}

	public void reset() {
		sinceUse = Integer.MAX_VALUE / 2;
	}

	public void tick() {
		sinceUse++;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.gameMode == null || !elytraFly.useFireworks.get() || !p.isFallFlying()) return;
		if (sinceUse < elytraFly.fireworkMinGap.intValue()) return;
		if (elytraFly.fireworkPauseUsing.get() && p.isUsingItem()) return;

		double speed = Math.sqrt(square(p.getX() - p.xo) + square(p.getY() - p.yo) + square(p.getZ() - p.zo)) * 20;
		boolean low = elytraFly.fireBelow.get() && p.getY() < elytraFly.fireBelowY.get();
		if (!low && !due(elytraFly.keepsGlidePhysics() ? elytraFly.fireworkTrigger.get() : Trigger.Timer, sinceUse, elytraFly.autoPilotFireworkDelay.intValue() * 20, speed, elytraFly.fireworkMinSpeed.get())) return;

		if (elytraFly.fireworkWaitForRocket.get() && !mc.level.getEntities(EntityTypes.FIREWORK_ROCKET, p.getBoundingBox().inflate(4), r -> r.getOwner() == p || r.getOwner() == null && r.distanceToSqr(p) < 4).isEmpty()) return;

		if (use(p, mc)) sinceUse = 0;
	}

	/** Fires one rocket now (the firework key), whatever the trigger says. */
	public void fireNow() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.gameMode != null && use(mc.player, mc)) sinceUse = 0;
	}

	/** Whether the trigger asks for a rocket now ({@code timerTicks} since the last one, {@code speed} in blocks per second). */
	public static boolean due(Trigger trigger, int sinceUse, int timerTicks, double speed, double minSpeed) {
		boolean timer = sinceUse >= timerTicks;
		boolean slow = speed < minSpeed;
		return switch (trigger) {
			case Timer -> timer;
			case Speed -> slow;
			case Either -> timer || slow;
		};
	}

	/**
	 * The velocity a rocket adds this tick after the Rocket boost multipliers. Vanilla adds
	 * {@code 0.85 * look - 0.5 * velocity}, which settles at 1.7 blocks per tick along the look vector, so the
	 * multipliers scale that target ({@code 0.85 * look}) and top speed with it. A positive {@code capPerTick}
	 * scales the push down so the result never passes that speed.
	 */
	public static Vec3 boost(Vec3 velocity, Vec3 push, double horizontal, double vertical, double capPerTick) {
		Vec3 scaled = new Vec3(
			(push.x + 0.5 * velocity.x) * horizontal - 0.5 * velocity.x,
			(push.y + 0.5 * velocity.y) * vertical - 0.5 * velocity.y,
			(push.z + 0.5 * velocity.z) * horizontal - 0.5 * velocity.z);
		if (capPerTick <= 0) return scaled;
		Vec3 next = velocity.add(scaled);
		double length = next.length();
		return length > capPerTick ? next.scale(capPerTick / length).subtract(velocity) : scaled;
	}

	/** The push to apply for the local player's rocket; unchanged unless Custom boost is on. */
	public Vec3 boostPush(Vec3 velocity, Vec3 push) {
		if (!elytraFly.rocketBoost.get() || !elytraFly.keepsGlidePhysics()) return push;
		return boost(velocity, push, elytraFly.boostHorizontal.get(), elytraFly.boostVertical.get(), elytraFly.boostMaxSpeed.get() / 20);
	}

	private boolean use(LocalPlayer p, Minecraft mc) {
		Inv.FindItemResult rocket = Inv.findInHotbar(stack -> stack.is(Items.FIREWORK_ROCKET));
		if (!rocket.found() && elytraFly.fireworkSearchInventory.get()) rocket = Inv.find(stack -> stack.is(Items.FIREWORK_ROCKET), 9, 35);
		if (!rocket.found()) return false;

		boolean silent = elytraFly.fireworkUseMode.get() == UseMode.Silent;
		if (rocket.isOffhand()) {
			mc.gameMode.useItem(p, InteractionHand.OFF_HAND);
			if (!silent) p.swing(InteractionHand.OFF_HAND, false);
			return true;
		}
		Runnable fire = () -> {
			mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
			if (!silent) p.swing(InteractionHand.MAIN_HAND, false);
		};
		if (!silent && rocket.isHotbar()) {
			Inv.swap(rocket.slot(), true);
			fire.run();
			Inv.swapBack();
			return true;
		}
		return Inv.withItem(rocket.slot(), silent, fire);
	}

	private static double square(double v) {
		return v * v;
	}
}
