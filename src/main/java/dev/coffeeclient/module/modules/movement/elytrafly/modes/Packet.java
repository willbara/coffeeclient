package dev.coffeeclient.module.modules.movement.elytrafly.modes;

import dev.coffeeclient.module.modules.movement.ElytraFly;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightMode;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightModes;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Packet mode. Like vanilla, but keeps re-sending START_FALL_FLYING to the server and forces
 * creative-flight abilities on the client.
 */
public class Packet extends ElytraFlightMode {
	private final Vec3 vec3d = new Vec3(0, 0, 0);

	public Packet(ElytraFly elytraFly) {
		super(elytraFly, ElytraFlightModes.Packet);
	}

	@Override
	public void onDeactivate() {
		if (mc.player == null) return;
		mc.player.getAbilities().flying = false;
		mc.player.getAbilities().mayfly = false;
	}

	@Override
	public void onTick() {
		super.onTick();
		if (mc.player == null || mc.getConnection() == null) return;

		if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA || mc.player.fallDistance <= 0.2 || mc.options.keyShift.isDown())
			return;

		if (mc.options.keyUp.isDown()) {
			vec3d.add(0, 0, elytraFly.horizontalSpeed.get());
			vec3d.yRot(-(float) Math.toRadians(mc.player.getYRot()));
		} else if (mc.options.keyDown.isDown()) {
			vec3d.add(0, 0, elytraFly.horizontalSpeed.get());
			vec3d.yRot((float) Math.toRadians(mc.player.getYRot()));
		}

		if (mc.options.keyJump.isDown()) vec3d.add(0, elytraFly.verticalSpeed.get(), 0);
		else vec3d.add(0, -elytraFly.verticalSpeed.get(), 0);

		mc.player.setDeltaMovement(vec3d);
		mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
		mc.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
	}

	@Override
	public void onPacketSend(net.minecraft.network.protocol.Packet<?> packet) {
		if (mc.player == null || mc.getConnection() == null) return;
		if (packet instanceof ServerboundMovePlayerPacket) {
			mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
		}
	}

	@Override
	public void onMove() {
		if (mc.player == null) return;
		mc.player.getAbilities().flying = true;
		mc.player.getAbilities().setFlyingSpeed(elytraFly.horizontalSpeed.get().floatValue() / 20);
	}
}
