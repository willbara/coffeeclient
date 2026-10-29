package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.PacketHold;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * Flight. Creative mode toggles creative-style flying; Velocity mode drives your motion directly from
 * the movement keys. Servers that don't allow flying will kick or correct you (no bypasses), though
 * anti-kick's small regular drop keeps vanilla's "flying is not enabled" kick away. Glide caps your
 * fall speed; Jump air-jumps to hold a height (jump raises it, sneak lowers it). Packet sends its
 * own position each tick and drops vanilla's, with small anti-kick drops; the server's corrections
 * are accepted as vanilla accepts them, so nothing is guessed about teleport ids. Matrix and Vulcan
 * are anticheat-specific tries kept for testing: Matrix flicks an elytra from your inventory on
 * every 21 ticks and rises and dips in a fixed rhythm; Vulcan glides down in alternating steps,
 * Vulcan 2.7.7 the same once falling; Spartan holds height and drifts forward at a fixed speed;
 * Grim 2.3.73 claims an elytra start every tick once in the air, and after the server pushes you,
 * holds its pings until its next position correction.
 * Anti-kick and
 * scroll speed follow Flight.
 */
public final class Flight extends Module {
	public enum Mode { CREATIVE, VELOCITY, GLIDE, JUMP, PACKET, MATRIX, VULCAN, VULCAN_277, SPARTAN, GRIM_2373 }

	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Mode", "How flight works", Mode.CREATIVE));
	public final NumberSetting speed = setting(new NumberSetting("Speed", "Horizontal speed multiplier", 1.0, 0.1, 5, 0.1));
	public final NumberSetting vertical = setting(new NumberSetting("Vertical speed", "Up/down speed (velocity mode)", 0.6, 0.1, 3, 0.1));
	public final NumberSetting sprintBoost = setting(new NumberSetting("Sprint boost", "Speed multiplier while sprinting", 1.5, 1, 3, 0.1));
	public final NumberSetting descendSpeed = setting(new NumberSetting("Sneak descent speed", "Downward speed while sneaking (velocity mode)", 0.6, 0.1, 3, 0.1));
	public final BooleanSetting slowSneak = setting(new BooleanSetting("Slow sneaking", "Fly slower while sneaking, for precise placement", false));
	public final NumberSetting sneakFactor = setting(new NumberSetting("Sneak speed", "Share of your speed kept while sneaking", 0.3, 0.05, 1, 0.05));
	public final BooleanSetting stopOnRelease = setting(new BooleanSetting("Stop when idle", "No drifting when you let go of the keys", true));
	public final BooleanSetting stopOnDisable = setting(new BooleanSetting("Stop on disable", "Kill momentum when turning flight off", true));
	public final BooleanSetting scrollSpeed = setting(new BooleanSetting("Scroll to change speed", "The mouse wheel changes Speed while flying", false));
	public final NumberSetting speedStep = setting(new NumberSetting("Speed step", "Speed change per scroll notch", 0.1, 0.05, 1, 0.05));

	public final NumberSetting glideFall = setting(new NumberSetting("Fall speed", "Fastest you fall while gliding (glide mode)", 0.125, 0.005, 0.5, 0.005));
	public final NumberSetting glideMinHeight = setting(new NumberSetting("Min height", "Stop gliding this close to the ground (glide mode)", 0, 0, 3, 0.1));

	public final NumberSetting packetSpeed = setting(new NumberSetting("Packet speed", "Horizontal speed in blocks per second (packet mode)", 5.2, 0.5, 20, 0.1));
	public final NumberSetting packetVertical = setting(new NumberSetting("Packet vertical", "Up and down speed in blocks per second (packet mode)", 1.2, 0.1, 10, 0.1));
	public final NumberSetting acSpeed = setting(new NumberSetting("Anticheat speed", "Horizontal blocks per tick (Matrix mode)", 0.3, 0.05, 2, 0.05));
	public final BooleanSetting grimInAir = setting(new BooleanSetting("Start in air", "Begin the elytra claims after some ticks in the air, not only after a push (Grim 2.3.73 mode)", true));
	public final NumberSetting grimAirTicks = setting(new NumberSetting("Air ticks", "Ticks in the air before starting (Grim 2.3.73 mode)", 3, 0, 12, 1));
	public final NumberSetting packetDrop = setting(new NumberSetting("Packet drop every", "Ticks between the small anti-kick drops (packet mode)", 4, 1, 30, 1));

	public final BooleanSetting antiKick = group(new BooleanSetting("Anti-kick", "Drop a little and come back up every so often, so vanilla doesn't kick you for flying", false), "Anti-kick");
	public final NumberSetting antiKickInterval = group(new NumberSetting("Interval (ticks)", "Ticks between drops (vanilla kicks after 80)", 70, 5, 80, 1), "Anti-kick");
	public final NumberSetting antiKickDistance = group(new NumberSetting("Distance", "How far each drop goes", 0.035, 0.01, 0.2, 0.005), "Anti-kick");

	private boolean hadMayfly;
	private int antiKickTicks;
	private double holdY;
	/** Packet mode: our own move packets, let through the drop below; ticks since the last anti-kick drop. */
	private final java.util.Set<net.minecraft.network.protocol.Packet<?>> ours = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
	private int packetTicks;
	private int acTicks;
	/** Grim 2.3.73 mode: claiming every tick; a push came in, so pings are held; ticks off the ground. */
	private boolean grimStarted, grimHolding;
	private int airTicks;

	public Flight() {
		super("Flight", Category.MOVEMENT, "Fly in survival (creative-style or velocity).");
		for (Setting<?> s : java.util.List.of(speed, sprintBoost, slowSneak, stopOnRelease, scrollSpeed, antiKick)) s.visibleWhen(this::flying);
		speedStep.visibleWhen(() -> flying() && scrollSpeed.get());
		vertical.visibleWhen(() -> mode.get() == Mode.VELOCITY);
		descendSpeed.visibleWhen(() -> mode.get() == Mode.VELOCITY);
		sneakFactor.visibleWhen(() -> flying() && slowSneak.get());
		for (Setting<?> s : java.util.List.of(antiKickInterval, antiKickDistance)) s.visibleWhen(() -> flying() && antiKick.get());
		for (Setting<?> s : java.util.List.of(glideFall, glideMinHeight)) s.visibleWhen(() -> mode.get() == Mode.GLIDE);
		for (Setting<?> s : java.util.List.of(packetSpeed, packetVertical, packetDrop)) s.visibleWhen(() -> mode.get() == Mode.PACKET);
		acSpeed.visibleWhen(() -> mode.get() == Mode.MATRIX);
		grimInAir.visibleWhen(() -> mode.get() == Mode.GRIM_2373);
		grimAirTicks.visibleWhen(() -> mode.get() == Mode.GRIM_2373 && grimInAir.get());
		listen(Events.SendMovementPackets.Pre.class, e -> {
			if (mode.get() == Mode.PACKET) packetStep();
			if (mode.get() == Mode.GRIM_2373 && !grimStarted && (grimInAir.get() && airTicks >= grimAirTicks.intValue() || grimHolding)) {
				grimStarted = true;
				ElytraFly.sendCommand();
			}
		});
		listen(Events.SendMovementPackets.Post.class, e -> {
			if (mode.get() == Mode.GRIM_2373 && grimStarted) ElytraFly.sendCommand();
		});
		listen(Events.PacketApply.Pre.class, e -> {
			if (mode.get() != Mode.GRIM_2373 || mc().player == null) return;
			if (e.packet instanceof ClientboundSetEntityMotionPacket m && m.id() == mc().player.getId()) grimHolding = true;
			if (!grimHolding) return;
			// The correction lets the held pings go first, then applies as normal.
			if (e.packet instanceof ClientboundPingPacket) PacketHold.hold(this, e);
			else if (e.packet instanceof ClientboundPlayerPositionPacket) PacketHold.release(this);
		});
		listen(Events.PacketSend.class, e -> {
			if (mode.get() == Mode.PACKET && e.packet instanceof ServerboundMovePlayerPacket && !ours.remove(e.packet)) e.cancel();
		});
	}

	@Override
	protected void init(EventBus bus) {
		// Low priority: a held zoom key gets the wheel first.
		listen(Events.MouseScroll.class, EventBus.LOW, e -> {
			if (e.isCancelled() || !scrollSpeed.get() || !flying() || mc().gui.screen() != null) return;
			speed.set(Math.max(speed.min(), Math.min(speed.max(), speed.get() + Math.signum(e.amount) * speedStep.get())));
			e.cancel();
		});
	}

	@Override
	protected void onEnable() {
		LocalPlayer p = mc().player;
		hadMayfly = p != null && p.getAbilities().mayfly;
		antiKickTicks = 0;
		if (p != null) holdY = p.getY();
		acTicks = 0;
		grimReset();
	}

	private void grimReset() {
		PacketHold.release(this);
		grimStarted = grimHolding = false;
		airTicks = 0;
	}

	@Override
	protected void onDisable() {
		grimReset();
		LocalPlayer p = mc().player;
		if (p == null) return;
		var abilities = p.getAbilities();
		if (!p.isCreative() && !p.isSpectator()) {
			abilities.mayfly = hadMayfly;
			abilities.flying = false;
		}
		abilities.setFlyingSpeed(0.05f);
		if (stopOnDisable.get()) p.setDeltaMovement(Vec3.ZERO);
	}

	@Override
	public void onTick() {
		LocalPlayer p = mc().player;
		if (p == null || p.isSpectator()) return;
		Input in = p.input.keyPresses;
		float boost = in.sprint() ? sprintBoost.floatValue() : 1f;
		if (slowSneak.get() && in.shift()) boost *= sneakFactor.floatValue();
		if (mode.get() == Mode.CREATIVE) {
			var abilities = p.getAbilities();
			abilities.mayfly = true;
			abilities.flying = true;
			abilities.setFlyingSpeed(0.05f * speed.floatValue() * boost);
			if (antiKick.get()) antiKick(p);
			return;
		}
		p.getAbilities().flying = false;
		if (mode.get() == Mode.GLIDE) {
			glide(p);
			return;
		}
		if (mode.get() == Mode.JUMP) {
			jump(p, in);
			return;
		}
		if (mode.get() == Mode.PACKET) {
			p.setDeltaMovement(Vec3.ZERO);
			p.resetFallDistance();
			return;
		}
		if (mode.get() == Mode.MATRIX) {
			matrix(p, in);
			return;
		}
		if (mode.get() == Mode.VULCAN) {
			Vec3 v = p.getDeltaMovement();
			if (!p.onGround()) p.setDeltaMovement(v.x, -(acTicks++ % 2 == 0 ? 0.17 : 0.10), v.z);
			else acTicks = 0;
			return;
		}
		if (mode.get() == Mode.VULCAN_277) {
			Vec3 v = p.getDeltaMovement();
			if (p.fallDistance > 0.1) p.setDeltaMovement(v.x, p.tickCount % 2 == 0 ? -0.155 : -0.1, v.z);
			return;
		}
		if (mode.get() != Mode.GRIM_2373 && (grimStarted || grimHolding)) grimReset();
		if (mode.get() == Mode.GRIM_2373) {
			airTicks = p.onGround() ? 0 : airTicks + 1;
			return;
		}
		if (mode.get() == Mode.SPARTAN) {
			double yaw = Math.toRadians(p.getYRot());
			p.setDeltaMovement(-Math.sin(yaw) * 0.28, 0, Math.cos(yaw) * 0.28);
			return;
		}
		double forward = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0);
		double strafe = (in.left() ? 1 : 0) - (in.right() ? 1 : 0);
		double up = (in.jump() ? 1 : 0) - (in.shift() ? 1 : 0);
		double yaw = Math.toRadians(p.getYRot());
		double h = 0.5 * speed.get() * boost;
		double len = Math.hypot(forward, strafe);
		double vx = 0, vz = 0;
		if (len > 0) {
			forward /= len;
			strafe /= len;
			vx = (-Math.sin(yaw) * forward + Math.cos(yaw) * strafe) * h;
			vz = (Math.cos(yaw) * forward + Math.sin(yaw) * strafe) * h;
		}
		Vec3 current = p.getDeltaMovement();
		if (len == 0 && !stopOnRelease.get()) {
			vx = current.x;
			vz = current.z;
		}
		double vy = up > 0 ? vertical.get() * 0.5 : up < 0 ? -descendSpeed.get() * 0.5 : 0;
		p.setDeltaMovement(vx, vy, vz);
		p.resetFallDistance();
		if (antiKick.get()) antiKick(p);
	}

	private void glide(LocalPlayer p) {
		Vec3 v = p.getDeltaMovement();
		if (p.onGround() || p.isInWater() || p.isInLava() || p.onClimbable() || v.y >= 0) return;
		double min = glideMinHeight.get();
		if (min > 0 && !mc().level.noCollision(p, p.getBoundingBox().expandTowards(0, -min, 0))) return;
		p.setDeltaMovement(v.x, Math.max(v.y, -glideFall.get()), v.z);
		p.resetFallDistance();
	}

	/** Air-jumps whenever you drop to the held height. */
	private void jump(LocalPlayer p, Input in) {
		if (in.shift() || p.onGround()) holdY = p.getY();
		else if (in.jump()) holdY = Math.max(holdY, p.getY());
		if (!in.shift() && !p.onGround() && p.getY() <= holdY && p.getDeltaMovement().y <= 0) {
			p.setDeltaMovement(p.getDeltaMovement().x, 0.42, p.getDeltaMovement().z);
			p.resetFallDistance();
		}
	}

	/** Matrix: start-fly with an elytra swapped in for one flush every 21 ticks; 13 ticks up, 3 down. */
	private void matrix(LocalPlayer p, Input in) {
		if (acTicks % 21 == 0 && p.containerMenu == p.inventoryMenu) {
			int elytra = dev.coffeeclient.util.Inv.find(s -> s.is(net.minecraft.world.item.Items.ELYTRA), 0, 35).slot();
			if (elytra >= 0) {
				int from = dev.coffeeclient.util.Inv.inventoryId(elytra), chest = dev.coffeeclient.util.Inv.armorId(net.minecraft.world.entity.EquipmentSlot.CHEST);
				dev.coffeeclient.util.PacketBatch.run(() -> {
					dev.coffeeclient.util.Inv.move(from, chest);
					ElytraFly.sendCommand();
					ElytraFly.sendCommand();
					dev.coffeeclient.util.Inv.move(chest, from);
				});
			}
		}
		int phase = acTicks % 16;
		acTicks++;
		double forward = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0), strafe = (in.left() ? 1 : 0) - (in.right() ? 1 : 0);
		double yaw = Math.toRadians(p.getYRot()), len = Math.hypot(forward, strafe), s = acSpeed.get();
		double vx = len == 0 ? 0 : (-Math.sin(yaw) * forward + Math.cos(yaw) * strafe) / len * s;
		double vz = len == 0 ? 0 : (Math.cos(yaw) * forward + Math.sin(yaw) * strafe) / len * s;
		p.setDeltaMovement(vx, phase < 13 ? 0.100000001490116 : -0.060000001490116, vz);
		p.resetFallDistance();
	}

	/** Packet mode, before vanilla would send its move: one position packet of our own. */
	private void packetStep() {
		LocalPlayer p = mc().player;
		if (p == null || p.connection == null) return;
		Input in = p.input.keyPresses;
		double forward = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0), strafe = (in.left() ? 1 : 0) - (in.right() ? 1 : 0);
		double yaw = Math.toRadians(p.getYRot()), len = Math.hypot(forward, strafe), h = packetSpeed.get() / 20;
		double dx = 0, dz = 0;
		if (len > 0) {
			dx = (-Math.sin(yaw) * forward + Math.cos(yaw) * strafe) / len * h;
			dz = (Math.cos(yaw) * forward + Math.sin(yaw) * strafe) / len * h;
		}
		double dy = in.jump() ? packetVertical.get() / 20 : in.shift() ? -packetVertical.get() / 20 : 0;
		if (++packetTicks >= packetDrop.intValue() && dy >= 0) {
			packetTicks = 0;
			dy = -0.04;
		}
		Vec3 to = p.position().add(dx, dy, dz);
		var packet = new ServerboundMovePlayerPacket.Pos(to, false, p.horizontalCollision);
		ours.add(packet);
		p.connection.send(packet);
		p.setPos(to);
	}

	/** Creative and Velocity actually fly; Glide and Jump only shape a fall. */
	private boolean flying() {
		return mode.get() == Mode.CREATIVE || mode.get() == Mode.VELOCITY;
	}

	/** Tick 0 of each cycle drops by the distance, tick 1 climbs back; skipped when already falling that fast. */
	private void antiKick(LocalPlayer p) {
		if (antiKickTicks > antiKickInterval.intValue() + 1) antiKickTicks = 0;
		Vec3 v = p.getDeltaMovement();
		double distance = antiKickDistance.get();
		switch (antiKickTicks) {
			case 0 -> {
				if (v.y <= -distance) antiKickTicks = 2;
				else p.setDeltaMovement(v.x, -distance, v.z);
			}
			case 1 -> p.setDeltaMovement(v.x, distance, v.z);
			default -> {
			}
		}
		antiKickTicks++;
	}

	@Override
	public String infoString() {
		return mode.display();
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}
}
