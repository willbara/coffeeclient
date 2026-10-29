package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.movement.Timer;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.MoveSim;
import dev.coffeeclient.util.Player;
import dev.coffeeclient.util.Setback;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Moves you faster on the ground. Four modes: Vanilla (a fixed speed) and Strafe (an NCP bunny hop)
 * from Speed; Smooth (steers toward a target speed, with drift control) and Potion (holds
 * vanilla walk, sprint, Speed I or Speed II speed against slowdowns) from SpeedHack; and Accelerate
 * (builds up to a top speed while you keep moving). Safe hops simulate each hop first and skip it
 * when it would land you in lava, the void or a lethal fall; hops that would clip a block corner
 * wait a tick. A hit can give a short speed boost.
 */
public final class Speed extends Module {
	public enum Mode implements Labeled {
		VANILLA("Vanilla"), STRAFE("Strafe"), SMOOTH("Smooth"), POTION("Potion"), ACCELERATE("Accelerate");

		private final String label;

		Mode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum Hop implements Labeled {
		OFF("Off"), JUMP("Jump"), LOW_HOP("Low hop");

		private final String label;

		Hop(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	/** Potion mode's target: vanilla speeds in blocks per second (walk, sprint), from . */
	public enum Level implements Labeled {
		NORMAL("Normal", 4.317, 5.6112, 2.0), SPEED_I("Speed I", 5.180, 6.734, 4.0), SPEED_II("Speed II", 6.044, 7.857, 4.0);

		private final String label;
		final double walk, sprint, maxBoost;

		Level(String label, double walk, double sprint, double maxBoost) {
			this.label = label;
			this.walk = walk;
			this.sprint = sprint;
			this.maxBoost = maxBoost;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final EnumSetting<Mode> mode = group(new EnumSetting<>("Mode", "Vanilla: fixed speed. Strafe: bunny hop for NCP. Smooth: speed with drift control. Potion: vanilla-like speeds", Mode.VANILLA), "Mode");
	public final NumberSetting vanillaSpeed = group(new NumberSetting("Speed", "Ground speed in blocks per second", 5.6, 0, 20, 0.1), "Mode");
	public final NumberSetting strafeSpeed = group(new NumberSetting("Strafe speed", "Boost applied on each hop", 1.6, 0, 3, 0.01), "Mode");
	public final BooleanSetting speedLimit = group(new BooleanSetting("Speed limit", "Cap the speed for very strict anticheats", false), "Mode");
	public final NumberSetting maxSpeed = group(new NumberSetting("Max speed", "Target speed in blocks per second", 13, 2, 40, 0.5), "Mode");
	public final NumberSetting groundAccel = group(new NumberSetting("Ground accel", "How fast you reach the target on the ground", 0.35, 0.01, 1, 0.01), "Mode");
	public final NumberSetting airAccel = group(new NumberSetting("Air accel", "How fast you reach the target in the air", 0.02, 0, 1, 0.01), "Mode");
	public final NumberSetting sidewaysDamping = group(new NumberSetting("Sideways drift", "Sideways momentum kept when turning (0 = none, 1 = ice)", 0.15, 0, 1, 0.01), "Mode");
	public final NumberSetting accelTop = group(new NumberSetting("Top speed", "Speed reached in blocks per second", 9, 4, 30, 0.5), "Mode");
	public final NumberSetting accelTicks = group(new NumberSetting("Build-up ticks", "Ticks of moving to reach the top speed", 40, 5, 200, 5), "Mode");
	public final EnumSetting<Level> level = group(new EnumSetting<>("Level", "Speed to hold, as if you had this effect", Level.NORMAL), "Mode");

	/** Optional anticheat-specific hops, tried in place of the mode above. Version-pinned and brittle, kept for testing. */
	public enum AcMode implements Labeled {
		OFF("Off"), NCP_HOP("NCP hop"), NCP_STRAFE("Old NCP strafe"), AAC_HOP("AAC hop"), MATRIX("Matrix"), MATRIX_670("Matrix 6.7"), MATRIX_7("Matrix 7"),
		VULCAN("Vulcan 2.8"), VULCAN_288("Vulcan 2.8.8"), VULCAN_GROUND("Vulcan ground"), SPARTAN("Spartan"), GRIM_COLLIDE("Grim collide");

		private final String label;

		AcMode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final EnumSetting<AcMode> acMode = group(new EnumSetting<>("Anticheat mode", "Try a hop tuned for one anticheat instead of the mode above (for testing; may flag)", AcMode.OFF), "Anticheat");
	public final NumberSetting acSpeed = group(new NumberSetting("Anticheat speed", "Scales the anticheat mode's horizontal speed (1 = as tuned; above 1 is likelier to flag)", 1, 0.5, 2, 0.05), "Anticheat");
	public final BooleanSetting boatBoost = group(new BooleanSetting("Boat boost", "Grim collide: a much bigger push while a boat, shulker or happy ghast is within a block", false), "Anticheat");

	public final EnumSetting<Hop> hop = group(new EnumSetting<>("Hop", "Jump automatically while moving on the ground", Hop.OFF), "General");
	public final NumberSetting lowHopHeight = group(new NumberSetting("Low hop height", "Upward speed of a low hop", 0.25, 0.05, 0.42, 0.01), "General");
	public final BooleanSetting autoSprint = group(new BooleanSetting("Auto sprint", "Sprint while moving forwards", true), "General");
	public final NumberSetting timer = group(new NumberSetting("Timer", "Game speed while moving (1 = off)", 1, 0.1, 10, 0.05), "General");
	public final BooleanSetting onlyOnGround = group(new BooleanSetting("Only on ground", "Leave air movement alone", false), "General");
	public final BooleanSetting inLiquids = group(new BooleanSetting("In liquids", "Also in water and lava", false), "General");
	public final BooleanSetting whenSneaking = group(new BooleanSetting("When sneaking", "Also while sneaking", false), "General");
	public final BooleanSetting safeHops = group(new BooleanSetting("Safe hops", "Skip a hop that would land you in lava, the void or a fall that kills", true), "General");
	public final BooleanSetting cornerBump = group(new BooleanSetting("Anti corner bump", "Wait a tick instead of hopping into a block corner", true), "General");
	public final BooleanSetting pauseScaffold = group(new BooleanSetting("Pause with Scaffold", "Leave movement alone while Scaffold is on", true), "General");
	public final NumberSetting damageBoost = group(new NumberSetting("Damage boost", "Speed multiplier for half a second after you are hit (1 = off)", 1, 1, 3, 0.05), "General");
	public final NumberSetting setbackPause = group(new NumberSetting("Pause after setback", "Wait this long after the server sets you back (0 = off)", 1000, 0, 5000, 100)
		.format(v -> v == 0 ? "Off" : (int) v + " ms"), "General");

	// Strafe state (SpeedMode).
	private int stage;
	private double distance;
	private double strafe;
	private long limitTimer;
	// Potion state (auto normalise).
	private double boost = 1;
	// Accelerate and damage boost state.
	private int movingTicks, boostTicks, lastHurt;
	/** Vulcan: ticks since our hop (-1 = not hopping) and the Speed effect level at the hop. */
	private int acTicks = -1, acLevel;
	/** Grim collide: pushers seen on each of the last 3 moves (ring), and moves since a boat, shulker or happy ghast was within a block. */
	private final int[] grimPushers = new int[3];
	private int grimMove, hardMoves = 99;

	public Speed() {
		super("Speed", List.of("BunnyHop", "BHop", "SpeedHack", "Strafe"), Category.MOVEMENT, "Moves you faster on the ground.");
		vanillaSpeed.visibleWhen(() -> mode.get() == Mode.VANILLA);
		for (Setting<?> s : List.of(strafeSpeed, speedLimit)) s.visibleWhen(() -> mode.get() == Mode.STRAFE);
		for (Setting<?> s : List.of(maxSpeed, groundAccel, airAccel, sidewaysDamping)) s.visibleWhen(() -> mode.get() == Mode.SMOOTH);
		level.visibleWhen(() -> mode.get() == Mode.POTION);
		acSpeed.visibleWhen(() -> acMode.get() != AcMode.OFF);
		boatBoost.visibleWhen(() -> acMode.get() == AcMode.GRIM_COLLIDE);
		acMode.onChange(v -> {
			acTicks = -1;
			reset();
			Timer.release(this);
		});
		for (Setting<?> s : List.of(accelTop, accelTicks)) s.visibleWhen(() -> mode.get() == Mode.ACCELERATE);
		hop.visibleWhen(() -> mode.get() != Mode.STRAFE);
		lowHopHeight.visibleWhen(() -> mode.get() != Mode.STRAFE && hop.get() == Hop.LOW_HOP);
		onlyOnGround.visibleWhen(() -> mode.get() != Mode.STRAFE);
		mode.onChange(m -> reset());

		listen(Events.PlayerMove.class, e -> {
			if (e.type == MoverType.SELF) e.movement = move(e.movement);
		});
		listen(Events.ServerTeleport.class, e -> reset());
		// Two Vulcan modes also touch the move packets: ground claims while falling, and a tiny lift on the ground.
		listen(Events.PacketSend.class, dev.coffeeclient.core.EventBus.HIGH, e -> {
			if (!(e.packet instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket move) || mc().player == null) return;
			var acc = (dev.coffeeclient.mixin.ServerboundMovePlayerPacketAccessor) move;
			if (acMode.get() == AcMode.VULCAN_288 && mc().player.getDeltaMovement().y < 0) acc.coffeeclient$setOnGround(true);
			if (acMode.get() == AcMode.VULCAN_GROUND && move.hasPosition() && groundBelow(mc().player) && !mc().options.keyJump.isDown())
				acc.coffeeclient$setY(move.getY(0) + 0.005);
		});
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onEnable() {
		reset();
	}

	@Override
	protected void onDisable() {
		Timer.release(this);
	}

	@Override
	public void onTick() {
		distance = Player.lastTickDistance();
		LocalPlayer p = mc().player;
		if (p == null) return;
		movingTicks = Player.isMoving() ? movingTicks + 1 : 0;
		if (p.hurtTime > lastHurt && damageBoost.get() > 1) boostTicks = 10;
		lastHurt = p.hurtTime;
		if (boostTicks > 0) boostTicks--;
	}

	private void reset() {
		stage = 0;
		distance = 0;
		strafe = Player.BASE_SPEED;
		boost = 1;
		movingTicks = 0;
		Arrays.fill(grimPushers, 0);
		hardMoves = 99;
	}

	/** Gliding, climbing, riding, flying, or another module that owns movement: leave it alone. */
	private boolean paused(LocalPlayer p) {
		if (p.isFallFlying() || p.onClimbable() || p.isPassenger() || p.getAbilities().flying) return true;
		if (!whenSneaking.get() && p.isShiftKeyDown()) return true;
		if (!inLiquids.get() && (p.isInWater() || p.isInLava())) return true;
		if (pauseScaffold.get() && ModuleManager.on(Scaffold.class)) return true;
		return ModuleManager.on(Flight.class) || ModuleManager.on(ElytraFly.class);
	}

	private Vec3 move(Vec3 movement) {
		LocalPlayer p = mc().player;
		if (p == null) return movement;
		// Grim remembers the last 3 moves, so keep counting through pauses.
		if (acMode.get() == AcMode.GRIM_COLLIDE) trackGrim(p);
		if (paused(p) || setbackPause.get() > 0 && Setback.recent(setbackPause.intValue())) {
			if (stage != 0) reset();
			Timer.release(this);
			return movement;
		}

		boolean moving = Player.isMoving();
		if (acMode.get() != AcMode.OFF) {
			Vec3 ac = anticheat(p, movement, moving);
			if (ac != movement) p.setDeltaMovement(ac);
			// Scaled for this move only: fed back into the velocity it would compound every tick in the air.
			double k = acSpeed.get();
			return k == 1 ? ac : new Vec3(ac.x * k, ac.y, ac.z * k);
		}
		if (timer.get() != 1) {
			if (moving) Timer.override(this, timer.get());
			else Timer.release(this);
		}
		if (autoSprint.get() && !ModuleManager.on(Sprint.class) && p.zza > 0 && !p.horizontalCollision) p.setSprinting(true);

		Vec3 out = switch (mode.get()) {
			case VANILLA -> vanilla(p, movement);
			case STRAFE -> strafe(p, movement);
			case SMOOTH -> steer(p, movement, maxSpeed.get() / 20, groundAccel.get(), airAccel.get(), sidewaysDamping.get());
			case POTION -> potion(p, movement);
			case ACCELERATE -> {
				double top = accelTop.get() / 20, base = Player.baseSpeed();
				yield steer(p, movement, base + (top - base) * Math.min(1, movingTicks / accelTicks.get()), 0.35, 0.02, 0.15);
			}
		};
		if (boostTicks > 0) out = new Vec3(out.x * damageBoost.get(), out.y, out.z * damageBoost.get());

		if (mode.get() != Mode.STRAFE && hop.get() != Hop.OFF && moving && p.onGround() && !mc().options.keyJump.isDown()) {
			double y = hop.get() == Hop.JUMP ? Player.hopHeight(0.42) : lowHopHeight.get();
			Vec3 hopped = new Vec3(out.x, y, out.z);
			// A full hop is a real jump next tick, which the server sees pressed; a low hop has no vanilla equivalent.
			if (hopSafe(p, hopped)) {
				if (hop.get() == Hop.JUMP) Player.requestJump();
				else out = hopped;
			}
		}
		// edits the move vector in place, which is also the player's velocity: keep them in step.
		if (out != movement) p.setDeltaMovement(out);
		return out;
	}

	/** The anticheat-specific hops; each mirrors one public bypass, timer tricks included. */
	private Vec3 anticheat(LocalPlayer p, Vec3 m, boolean moving) {
		boolean ground = p.onGround();
		return switch (acMode.get()) {
			case OFF -> m;
			case NCP_HOP -> {
				// A vanilla sprint-jump each landing (NCP replays it exactly), just more ticks a second: 22 move packets a second is NCP's limit.
				if (moving && ground) {
					Timer.override(this, 1.0865);
					if (!mc().options.keyJump.isDown()) Player.requestJump();
				}
				if (!moving) Timer.release(this);
				yield m;
			}
			case AAC_HOP -> {
				if (!moving) {
					Timer.release(this);
					yield m;
				}
				if (ground) yield new Vec3(m.x, Player.hopHeight(0.42), m.z);
				if (p.fallDistance <= 0.1) Timer.override(this, 1.5);
				else if (p.fallDistance < 1.3) Timer.override(this, 0.7);
				else Timer.release(this);
				yield m;
			}
			case MATRIX -> {
				if (!moving) {
					Timer.release(this);
					yield m;
				}
				if (!ground) yield m;
				Timer.override(this, 1.055);
				yield new Vec3(m.x, Player.hopHeight(0.42), m.z);
			}
			case MATRIX_670 -> {
				if (!moving) yield new Vec3(0, m.y, 0);
				double y = m.y;
				Vec3 out = m;
				if (!ground && p.hurtTime == 0) {
					if (y > 0) y -= 0.0005;
					y -= 0.0094001145;
					double speed = m.horizontalDistance();
					out = speed < 0.2177 ? strafeTo(0.2177, y) : new Vec3(m.x, y, m.z);
				}
				yield ground ? strafeTo(m.horizontalDistance(), 0.41050001145) : out;
			}
			case NCP_STRAFE -> {
				if (!moving) yield m;
				MobEffectInstance sp = p.getEffect(MobEffects.SPEED);
				int level = sp == null ? 0 : sp.getAmplifier();
				double speed = m.horizontalDistance();
				if (ground) {
					acTicks = 0;
					yield strafeTo(Math.max(speed, 0.281 + 0.199999999 * level) * 1.00718, Player.hopHeight(0.42));
				}
				double y = ++acTicks == 5 ? m.y - 0.1523351824467155 : m.y;
				yield strafeTo(Math.max(speed, 0.2 + 0.199999999 * level) * 1.00718, y);
			}
			case MATRIX_7 -> {
				if (!moving) yield m;
				if (ground) yield strafeTo(m.horizontalDistance(), 0.419652);
				yield m.x * m.x + m.z * m.z < 0.04 ? strafeTo(m.horizontalDistance(), m.y) : m;
			}
			case VULCAN_288 -> {
				MobEffectInstance sp = p.getEffect(MobEffects.SPEED);
				boolean fast = sp != null && sp.getAmplifier() != 0;
				if (moving && ground && acTicks < 0) {
					acTicks = 0;
					yield strafeTo(fast ? 0.771 : 0.5, Player.hopHeight(0.42));
				}
				if (acTicks < 0) yield m;
				acTicks++;
				Vec3 out = switch (acTicks) {
					case 1 -> strafeTo(fast ? 0.605 : 0.31, m.y);
					case 2 -> strafeTo(fast ? 0.57 : 0.29, fast ? -0.5 : -0.37);
					case 3 -> strafeTo(fast ? 0.595 : 0.27, m.y);
					case 4 -> strafeTo(fast ? 0.595 : 0.28, m.y);
					default -> m;
				};
				if (acTicks >= 4) acTicks = -1;
				if (!ground && fast && p.fallDistance > 0) out = new Vec3(out.x * 1.055, out.y, out.z * 1.055);
				yield out;
			}
			case VULCAN_GROUND -> {
				if (!moving || !groundBelow(p) || mc().options.keyJump.isDown()) yield m;
				MobEffectInstance sp = p.getEffect(MobEffects.SPEED);
				double speed = sp != null && sp.getAmplifier() > 0 ? 0.59 : p.input.getMoveVector().x != 0 ? 0.41 : 0.42;
				yield strafeTo(speed, 0.005);
			}
			case SPARTAN -> {
				if (!p.input.keyPresses.forward() || !ground) yield m;
				double boost = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).is(net.minecraft.world.item.Items.LEATHER_BOOTS) ? 1.8 : 1.3;
				yield new Vec3(m.x * boost, Player.hopHeight(0.42), m.z * boost);
			}
			case GRIM_COLLIDE -> {
				Vec3 dir = Player.moveDir();
				int pushers = Math.max(grimPushers[0], Math.max(grimPushers[1], grimPushers[2]));
				boolean boat = boatBoost.get() && hardMoves < 2;
				if (!moving || dir == Vec3.ZERO || pushers == 0 && !boat) yield m;
				// The push lenience is a box (0.08 per pusher on each axis), so diagonals take more; the boat lenience covers the whole offset.
				double k = 0.08 * pushers / Math.max(Math.abs(dir.x), Math.abs(dir.z)) + (boat ? 1.0 : 0);
				yield new Vec3(m.x + dir.x * k, m.y, m.z + dir.z * k);
			}
			case VULCAN -> {
				if (moving && ground && acTicks < 0) {
					acTicks = 0;
					MobEffectInstance speed = p.getEffect(MobEffects.SPEED);
					acLevel = speed == null ? 0 : speed.getAmplifier();
					yield new Vec3(m.x, Player.hopHeight(0.42), m.z);
				}
				if (acTicks < 0) yield m;
				acTicks++;
				if (acTicks == 1) yield strafeTo(0.3355 * (1 + acLevel * 0.3819), m.y);
				if (acTicks == 2 && p.isSprinting()) yield strafeTo(0.3284 * (1 + acLevel * 0.355), m.y);
				if (acTicks == 4) yield new Vec3(m.x, m.y - 0.376, m.z);
				if (acTicks >= 6) {
					acTicks = -1;
					if (m.horizontalDistance() > 0.298) yield strafeTo(0.298, m.y);
				}
				yield m;
			}
		};
	}

	/** Grim collide: what the server can see pushing you on this move, by Grim's own counting. */
	private void trackGrim(LocalPlayer p) {
		AABB box = p.getBoundingBox();
		grimPushers[grimMove] = mc().level.getEntities(p, box.inflate(0.2), e -> grimPushes(p, e)).size();
		grimMove = (grimMove + 1) % 3;
		boolean hard = !mc().level.getEntities(p, box.inflate(1), e -> e instanceof AbstractBoat || e instanceof Shulker || e instanceof HappyGhast).isEmpty();
		hardMoves = hard ? 0 : Math.min(hardMoves + 1, 99);
	}

	/** Grim's push rule: living things except armor stands, bats and parrots, plus boats and minecarts, under team collision rules. */
	private static boolean grimPushes(LocalPlayer p, Entity e) {
		if (e.isSpectator() || e instanceof ArmorStand || e instanceof Bat || e instanceof Parrot) return false;
		if (!(e instanceof LivingEntity || e instanceof AbstractBoat || e instanceof AbstractMinecart)) return false;
		Team theirs = e.getTeam(), own = p.getTeam();
		Team.CollisionRule theirRule = theirs == null ? Team.CollisionRule.ALWAYS : theirs.getCollisionRule();
		Team.CollisionRule ownRule = own == null ? Team.CollisionRule.ALWAYS : own.getCollisionRule();
		if (theirRule == Team.CollisionRule.NEVER || ownRule == Team.CollisionRule.NEVER) return false;
		boolean same = theirs != null && theirs.equals(own);
		return (!same || theirRule != Team.CollisionRule.PUSH_OWN_TEAM && ownRule != Team.CollisionRule.PUSH_OWN_TEAM)
			&& (theirRule != Team.CollisionRule.PUSH_OTHER_TEAMS && ownRule != Team.CollisionRule.PUSH_OTHER_TEAMS || same);
	}

	/** Standing on something within 0.005 below the feet. */
	private static boolean groundBelow(LocalPlayer p) {
		return p.level().getBlockCollisions(p, p.getBoundingBox().move(0, -0.005, 0)).iterator().hasNext();
	}

	/** Horizontal speed {@code speed} along the input direction, keeping vertical {@code y}. */
	private static Vec3 strafeTo(double speed, double y) {
		Vec3 dir = Player.moveDir();
		return new Vec3(dir.x * speed, y, dir.z * speed);
	}

	/** The hop lands somewhere survivable and clears the blocks around it. */
	private boolean hopSafe(LocalPlayer p, Vec3 hop) {
		if (cornerBump.get()) {
			Vec3 ahead = new Vec3(hop.x * 2, hop.y, hop.z * 2);
			Vec3 moved = Entity.collideBoundingBox(p, ahead, p.getBoundingBox(), p.level(), List.of());
			if (Math.abs(moved.x - ahead.x) > 1e-4 || Math.abs(moved.z - ahead.z) > 1e-4) return false;
		}
		if (!safeHops.get()) return true;
		MoveSim.Result r = MoveSim.run(p, p.getBoundingBox(), hop, true, 60);
		if (r.stop() == MoveSim.Stop.LAVA || r.stop() == MoveSim.Stop.VOID || r.stop() == MoveSim.Stop.NONE) return false;
		return r.stop() != MoveSim.Stop.LANDED || MoveSim.fallDamage(r.fallen()) < p.getHealth() + p.getAbsorptionAmount();
	}

	private Vec3 vanilla(LocalPlayer p, Vec3 movement) {
		if (onlyOnGround.get() && !p.onGround()) return movement;
		Vec3 dir = Player.moveDir();
		double speed = vanillaSpeed.get() / 20;
		MobEffectInstance effect = p.getEffect(MobEffects.SPEED);
		if (effect != null) speed *= 1 + (effect.getAmplifier() + 1) * 0.205;
		return new Vec3(dir.x * speed, movement.y, dir.z * speed);
	}

	/** Strafe: a four-stage hop (reset, jump, slow down, decay). */
	private Vec3 strafe(LocalPlayer p, Vec3 movement) {
		boolean moving = Player.isMoving();
		double y = movement.y;
		switch (stage) {
			case 0 -> {
				if (moving) {
					stage = 1;
					strafe = 1.18 * Player.baseSpeed() - 0.01;
				}
			}
			case 1 -> {
				if (moving && p.onGround()) {
					y = Player.hopHeight(0.40123128);
					strafe *= strafeSpeed.get();
					stage = 2;
				}
			}
			case 2 -> {
				strafe = distance - 0.76 * (distance - Player.baseSpeed());
				stage = 3;
			}
			default -> {
				if (!mc().level.noCollision(p.getBoundingBox().move(0, p.getDeltaMovement().y, 0)) || p.verticalCollision) stage = 0;
				strafe = distance - distance / 159.0;
			}
		}
		strafe = Math.max(strafe, Player.baseSpeed());
		if (speedLimit.get()) {
			long now = System.currentTimeMillis();
			if (now - limitTimer > 2500) limitTimer = now;
			strafe = Math.min(strafe, now - limitTimer > 1250 ? 0.44 : 0.43);
		}
		Vec3 dir = Player.moveDir();
		return new Vec3(dir.x * strafe, y, dir.z * strafe);
	}

	/**
	 * SpeedHack: move the horizontal velocity toward {@code target} (blocks per tick) along the
	 * input direction, damping the sideways part so turns stay responsive.
	 */
	private Vec3 steer(LocalPlayer p, Vec3 movement, double target, double ground, double air, double damping) {
		if (onlyOnGround.get() && !p.onGround()) return movement;
		Vec3 dir = Player.moveDir();
		if (dir == Vec3.ZERO) return movement;
		Vec3 velocity = new Vec3(movement.x, 0, movement.z);
		Vec3 along = dir.scale(velocity.dot(dir));
		velocity = along.add(velocity.subtract(along).scale(damping));
		velocity = velocity.add(dir.scale(target).subtract(velocity).scale(p.onGround() ? ground : air));
		double speed = velocity.length();
		if (speed > target) velocity = velocity.scale(target / speed);
		return new Vec3(velocity.x, movement.y, velocity.z);
	}

	/** auto normalise and potion presets: steer to a vanilla speed, boosting while something slows you. */
	private Vec3 potion(LocalPlayer p, Vec3 movement) {
		Level l = level.get();
		boolean sprinting = p.zza > 0 && (mc().options.keySprint.isDown() || p.isSprinting());
		double target = (sprinting ? l.sprint : l.walk) / 20;
		double current = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
		boolean steady = p.onGround() && !p.horizontalCollision && p.zza > 0 && !p.isShiftKeyDown() && !p.isInWater() && !p.isInLava();
		if (steady && current > 0.08 && current < target * 0.8) boost = Math.min(l.maxBoost, boost + 0.05);
		else if (boost > 1 && (!steady || current >= target * 0.95)) boost = Math.max(1, boost - 0.02);
		return steer(p, movement, target * boost, 0.35, 0.02, 0.15);
	}

	@Override
	public String infoString() {
		return String.format(Locale.ROOT, "%s %.1f b/s", mode.display(), distance * 20);
	}
}
