package dev.coffeeclient.module.modules.movement.elytrafly;

import dev.coffeeclient.module.modules.movement.ElytraFly;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.Player;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Shared behaviour for the ElytraFly flight modes. */
public class ElytraFlightMode {
	protected final Minecraft mc = Minecraft.getInstance();
	protected final ElytraFly elytraFly;
	private final ElytraFlightModes type;

	protected boolean lastJumpPressed;
	protected boolean incrementJumpTimer;
	public boolean lastForwardPressed;
	protected int jumpTimer;
	public double velX, velY, velZ;
	public Vec3 forward = Vec3.ZERO;
	public Vec3 right = Vec3.ZERO;
	protected double acceleration;

	public ElytraFlightMode(ElytraFly elytraFly, ElytraFlightModes type) {
		this.elytraFly = elytraFly;
		this.type = type;
	}

	public void onTick() {
		if (mc.player == null) return;

		if (elytraFly.autoReplenish.get()) {
			int target = elytraFly.replenishSlot.intValue() - 1;
			if (target >= 0 && target <= 8 && !mc.player.getInventory().getItem(target).is(Items.FIREWORK_ROCKET)) {
				for (int i = 0; i < 36; i++) {
					if (i == target) continue;
					if (mc.player.getInventory().getItem(i).is(Items.FIREWORK_ROCKET)) {
						Inv.move(Inv.inventoryId(i), Inv.inventoryId(target));
						break;
					}
				}
			}
		}

		if (elytraFly.replace.get()) {
			ItemStack chestStack = mc.player.getItemBySlot(EquipmentSlot.CHEST);
			if (chestStack.is(Items.ELYTRA)
				&& chestStack.getMaxDamage() - chestStack.getDamageValue() <= elytraFly.replaceDurability.intValue()) {
				for (int i = 0; i < 36; i++) {
					ItemStack stack = mc.player.getInventory().getItem(i);
					if (stack.is(Items.ELYTRA)
						&& stack.getMaxDamage() - stack.getDamageValue() > elytraFly.replaceDurability.intValue()) {
						Inv.move(Inv.inventoryId(i), Inv.armorId(EquipmentSlot.CHEST));
						break;
					}
				}
			}
		}
	}

	public void onPreTick() {
	}

	public void onPacketSend(Packet<?> packet) {
	}

	/** The server sent a position correction (rubberband). */
	public void onPositionCorrection() {
	}

	/** Called after the movement vector for this tick has been decided. */
	public void onMove() {
	}

	/** The last say on this tick's move: {@code vanilla} is the untouched glide move, {@code movement} what ElytraFly made of it. */
	public Vec3 adjustMove(Vec3 vanilla, Vec3 movement) {
		return movement;
	}

	public void onActivate() {
		lastJumpPressed = false;
		jumpTimer = 0;
		acceleration = 0;
	}

	public void onDeactivate() {
	}

	public void autoTakeoff() {
		if (mc.player == null) return;
		if (incrementJumpTimer) jumpTimer++;

		boolean jumpPressed = mc.options.keyJump.isDown();

		if ((elytraFly.autoTakeOff.get() && elytraFly.flightMode.get() != ElytraFlightModes.Pitch40 && !elytraFly.glidePhysicsMode()) ||
			(!elytraFly.manualTakeoff.get() && elytraFly.flightMode.get() == ElytraFlightModes.Bounce) && jumpPressed) {
			if (!lastJumpPressed && !mc.player.isFallFlying()) {
				jumpTimer = 0;
				incrementJumpTimer = true;
			}

			if (jumpTimer >= 8) {
				jumpTimer = 0;
				incrementJumpTimer = false;
				mc.player.setJumping(false);
				mc.player.setSprinting(true);
				mc.player.jumpFromGround();
				ElytraFly.sendCommand();
			}
		}

		lastJumpPressed = jumpPressed;
	}

	public void handleAutopilot() {
		if (mc.player == null || !mc.player.isFallFlying()) return;

		if (elytraFly.autoPilot.get() && mc.player.getY() > elytraFly.autoPilotMinimumHeight.get() && !elytraFly.glidePhysicsMode()) {
			mc.options.keyUp.setDown(true);
			lastForwardPressed = true;
		}
	}

	public void handleHorizontalSpeed(Vec3 movement) {
		boolean a = false;
		boolean b = false;

		if (mc.options.keyUp.isDown()) {
			velX += forward.x * getSpeed() * 10;
			velZ += forward.z * getSpeed() * 10;
			a = true;
		} else if (mc.options.keyDown.isDown()) {
			velX -= forward.x * getSpeed() * 10;
			velZ -= forward.z * getSpeed() * 10;
			a = true;
		}

		if (mc.options.keyRight.isDown()) {
			velX += right.x * getSpeed() * 10;
			velZ += right.z * getSpeed() * 10;
			b = true;
		} else if (mc.options.keyLeft.isDown()) {
			velX -= right.x * getSpeed() * 10;
			velZ -= right.z * getSpeed() * 10;
			b = true;
		}

		if (a && b) {
			double diagonal = 1 / Math.sqrt(2);
			velX *= diagonal;
			velZ *= diagonal;
		}
	}

	public void handleVerticalSpeed(Vec3 movement) {
		if (mc.options.keyJump.isDown()) velY += 0.5 * elytraFly.verticalSpeed.get();
		else if (mc.options.keyShift.isDown()) velY -= 0.5 * elytraFly.verticalSpeed.get();
	}

	public void handleFallMultiplier() {
		if (velY < 0) velY *= elytraFly.fallMultiplier.get();
		else if (velY > 0) velY = 0;
	}

	public void handleAcceleration() {
		if (elytraFly.acceleration.get()) {
			if (!Player.isMoving()) acceleration = 0;
			acceleration = Math.min(
				acceleration + elytraFly.accelerationMin.get() + elytraFly.accelerationStep.get() * .1,
				elytraFly.horizontalSpeed.get()
			);
		} else {
			acceleration = 0;
		}
	}

	public void zeroAcceleration() {
		acceleration = 0;
	}

	protected double getSpeed() {
		return elytraFly.acceleration.get() ? acceleration : elytraFly.horizontalSpeed.get();
	}

	public String getHudString() {
		return type.name();
	}
}
