package dev.coffeeclient.module.modules.movement.elytrafly;

import dev.coffeeclient.mixin.ServerboundMovePlayerPacketAccessor;
import dev.coffeeclient.module.modules.movement.ElytraFly;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.Latency;
import dev.coffeeclient.util.PacketBatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * ElytraFly's packet-level options, each off by default and kept for testing:
 * <ul>
 * <li>Durability saver: while gliding, re-send start-fly every few ticks. The server reads that as
 * "stop", its glide tick counter resets, and the elytra only takes wear on the 20th tick of one
 * unbroken glide; the client starts the glide again once the server has stopped it.</li>
 * <li>Chestplate fake-fly: wear a chestplate; each glide start swaps an elytra from the inventory
 * in, sends start-fly and swaps it back in one packet batch.</li>
 * <li>Heading lock: the glide physics and the yaw the server sees stay on the take-off heading
 * while you look around.</li>
 * <li>Packet gap and yaw flip-flop: while gliding with no keys held, send a move packet only every
 * N ticks; nudge the sent yaw by a hair on every other packet.</li>
 * <li>Elytra walk: on the ground, move packets say you are in the air and the glide is lit on the
 * server, so you skate along in the gliding pose.</li>
 * </ul>
 */
public final class GlideTricks {
	private final ElytraFly ef;
	private int sinceRecast, gapTicks, walkWait;
	private boolean restart, flip, wasGliding, serverGliding;
	private float heading;

	public GlideTricks(ElytraFly ef) {
		this.ef = ef;
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	public void reset() {
		sinceRecast = gapTicks = walkWait = 0;
		restart = flip = wasGliding = serverGliding = false;
	}

	public void tick(LocalPlayer p) {
		if (p.isFallFlying() && !wasGliding) heading = p.getYRot();
		wasGliding = p.isFallFlying();
		tickRecast(p);
		tickFakeFly(p);
		tickWalk(p);
	}

	private void tickRecast(LocalPlayer p) {
		if (!ef.recast.get()) {
			restart = false;
			return;
		}
		if (p.isFallFlying()) {
			if (ef.recastStill.get() && moving()) return;
			if (++sinceRecast < ef.recastEvery.intValue()) return;
			sinceRecast = 0;
			ElytraFly.sendCommand();
			restart = true;
		} else if (restart) {
			restart = false;
			if (p.onGround() || p.isInWater() || !hasGlider(p)) return;
			p.startFallFlying();
			ElytraFly.sendCommand();
		}
	}

	private void tickFakeFly(LocalPlayer p) {
		if (!ef.fakeFly.get() || p.isFallFlying() || p.isInWater() || p.isPassenger() || p.getAbilities().flying) return;
		if (p.onGround()) {
			if (mc().options.keyUp.isDown()) p.jumpFromGround();
			return;
		}
		if (p.containerMenu != p.inventoryMenu) return;
		boolean worn = p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
		int slot = worn ? -1 : Inv.find(s -> s.is(Items.ELYTRA), 0, 35).slot();
		if (!worn && slot < 0) return;
		int from = Inv.inventoryId(slot), chest = Inv.armorId(EquipmentSlot.CHEST);
		PacketBatch.run(() -> {
			if (!worn) Inv.move(from, chest);
			ElytraFly.sendCommand();
			if (!worn) Inv.move(chest, from);
		});
		p.startFallFlying();
	}

	/** ElytraFly's steering runs without a worn elytra while fake-fly holds a glide. */
	public boolean fakeGliding(LocalPlayer p) {
		return ef.fakeFly.get() && p.isFallFlying();
	}

	/** LivingEntityMixin: the yaw the glide physics use, or null for the player's own. */
	public Float heading(LocalPlayer p) {
		return ef.headingLock.get() && p.isFallFlying() ? heading : null;
	}

	// ---- Elytra walk ----

	private boolean walking(LocalPlayer p) {
		if (!ef.walk.get() || ef.flightMode.get() == ElytraFlightModes.Bounce || p.isPassenger() || p.isInWater() || p.isInLava() || p.getAbilities().flying) return false;
		ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
		return chest.is(Items.ELYTRA) && (!chest.isDamageableItem() || chest.getMaxDamage() - chest.getDamageValue() > 1);
	}

	private void tickWalk(LocalPlayer p) {
		if (!walking(p) || !p.onGround()) return;
		if (walkWait > 0) walkWait--;
		if (!serverGliding && walkWait == 0) {
			// Light the pose: an in-air position, then start-fly; wait about a round trip for the answer.
			walkWait = Math.min(60, Math.max(4, (int) Math.ceil(Latency.rtt() / 50.0) + 3));
			p.connection.send(new ServerboundMovePlayerPacket.PosRot(p.position(), p.getYRot(), p.getXRot(), false, p.horizontalCollision));
			ElytraFly.sendCommand();
		}
		if (p.isFallFlying()) p.stopFallFlying();
	}

	/** Our own entity data: bit 7 of the shared flags is the server's glide. */
	public void onEntityData(ClientboundSetEntityDataPacket packet) {
		LocalPlayer p = mc().player;
		if (p == null || packet.id() != p.getId()) return;
		for (var value : packet.packedItems()) {
			if (value.id() != 0 || !(value.value() instanceof Byte flags)) continue;
			boolean now = (flags & 0x80) != 0;
			if (now != serverGliding) walkWait = 0;
			serverGliding = now;
		}
	}

	public Vec3 walkMove(MoverType type, Vec3 movement) {
		LocalPlayer p = mc().player;
		if (p == null || type != MoverType.SELF || !p.onGround() || !walking(p) || mc().gui.screen() != null) return movement;
		var o = mc().options;
		double forward = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0), side = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
		double len = Math.hypot(forward, side);
		if (len < 1e-6) return movement;
		double yaw = Math.toRadians(p.getYRot()), s = ef.walkSpeed.get() / len;
		return new Vec3((-Math.sin(yaw) * forward + Math.cos(yaw) * side) * s, movement.y, (Math.cos(yaw) * forward + Math.sin(yaw) * side) * s);
	}

	// ---- Outgoing move packets ----

	/** The move packet to send instead (the same one when nothing changes), or null to drop it. */
	public ServerboundMovePlayerPacket onMovePacket(ServerboundMovePlayerPacket m) {
		LocalPlayer p = mc().player;
		if (p == null) return m;
		if (walking(p) && p.onGround()) {
			((ServerboundMovePlayerPacketAccessor) m).coffeeclient$setOnGround(false);
			return m;
		}
		if (!p.isFallFlying()) {
			gapTicks = 0;
			return m;
		}
		int gap = ef.packetGap.intValue();
		if (gap > 0 && !moving()) {
			if (++gapTicks < gap) return null;
			gapTicks = 0;
		} else {
			gapTicks = 0;
		}
		Float lock = heading(p);
		if (lock == null && !ef.yawFlipFlop.get()) return m;
		float yaw = lock != null ? lock : m.getYRot(p.getYRot());
		if (ef.yawFlipFlop.get()) {
			if (flip) yaw += 0.0001f;
			flip = !flip;
		}
		return new ServerboundMovePlayerPacket.PosRot(m.getX(p.getX()), m.getY(p.getY()), m.getZ(p.getZ()), yaw, m.getXRot(p.getXRot()),
			m.isOnGround(), m.horizontalCollision());
	}

	private static boolean moving() {
		var o = mc().options;
		return o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown() || o.keyJump.isDown() || o.keyShift.isDown();
	}

	private static boolean hasGlider(LocalPlayer p) {
		return p.getItemBySlot(EquipmentSlot.CHEST).has(net.minecraft.core.component.DataComponents.GLIDER);
	}
}
