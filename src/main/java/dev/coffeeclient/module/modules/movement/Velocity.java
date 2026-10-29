package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.PacketHold;
import dev.coffeeclient.util.Player;
import dev.coffeeclient.util.Setback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scales down the forces that move you around: attack knockback, explosions, flowing liquids and
 * entity pushes, and fishing rods reeling you in. Delay holds a push, and the
 * server's pings after it, for a few ticks before applying them (for testing).
 *
 * <p>Attack knockback has modes: Modify scales it, Cancel drops it, Grim takes it and cuts it with a
 * jump reset plus the vanilla hit-back slowdown, Jump reset jumps the moment it lands, Reduce slows
 * you when you hit back while hurt, and Strafe turns it towards where you walk. Chance, ground and walls gates pick which hits it acts on,
 * and after a setback all knockback is taken in full for a while.
 */
public final class Velocity extends Module {
	public enum Mode implements Labeled {
		MODIFY("Modify"), CANCEL("Cancel"), GRIM("Grim"), JUMP_RESET("Jump reset"), REDUCE("Reduce"), STRAFE("Strafe");

		private final String label;

		Mode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final BooleanSetting knockback = setting(new BooleanSetting("Knockback", "Modifies the knockback you take from attacks", true));
	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Mode",
		"Modify scales it. Cancel drops it (NCP never checks unused knockback). Grim takes it, then jump-resets and slows it on each sprinting hit back, the ways Grim's simulation allows. Jump reset jumps as it lands. Reduce slows you when you hit back while hurt and sprinting. Strafe turns it to where you walk", Mode.MODIFY));
	public final NumberSetting knockbackHorizontal = setting(new NumberSetting("Knockback horizontal", "Fraction of horizontal attack knockback taken", 0, 0, 1, 0.05));
	public final NumberSetting knockbackVertical = setting(new NumberSetting("Knockback vertical", "Fraction of vertical attack knockback taken", 0, 0, 1, 0.05));
	public final NumberSetting reduceFactor = setting(new NumberSetting("Reduce factor", "Share of your speed kept when you hit back while hurt (Grim only accepts 0.6)", 0.6, 0, 1, 0.05));
	public final BooleanSetting hitWhileHurt = setting(new BooleanSetting("Hit while hurt", "KillAura hits on the first knockback ticks even before its cooldown, so each one gets the slowdown", true));
	public final NumberSetting chance = setting(new NumberSetting("Chance", "Percent of knockback hits the mode acts on", 100, 0, 100, 5));
	public final BooleanSetting onlyGround = setting(new BooleanSetting("Only on ground", "Act only on hits taken while on the ground", false));
	public final BooleanSetting onlyWalls = setting(new BooleanSetting("Only in walls", "Act only while you stand inside a block, so a hit can't push you out", false));
	public final NumberSetting setbackPause = setting(new NumberSetting("Setback pause", "Take knockback in full for this many ticks after the server sets you back (0 = off)", 20, 0, 100, 1));
	public final BooleanSetting explosions = setting(new BooleanSetting("Explosions", "Modifies your knockback from explosions", true));
	public final BooleanSetting liquids = setting(new BooleanSetting("Liquids", "Modifies how much flowing liquids push you", true));
	public final NumberSetting liquidsHorizontal = setting(new NumberSetting("Liquids horizontal", "Fraction of horizontal liquid flow taken", 0, 0, 1, 0.05));
	public final NumberSetting liquidsVertical = setting(new NumberSetting("Liquids vertical", "Fraction of vertical liquid flow taken", 0, 0, 1, 0.05));
	public final BooleanSetting entityPush = setting(new BooleanSetting("Entity push", "Modifies how much entities push you", true));
	public final NumberSetting entityPushAmount = setting(new NumberSetting("Entity push amount", "Fraction of entity push taken", 0, 0, 1, 0.05));
	public final BooleanSetting blocks = setting(new BooleanSetting("Blocks", "Prevents you from being pushed out of blocks", true));
	public final BooleanSetting fishingRods = setting(new BooleanSetting("Fishing rods", "Prevents fishing rods from pulling you", false));
	public final BooleanSetting sinking = setting(new BooleanSetting("Sinking", "Prevents you from sinking in liquids", false));
	public final BooleanSetting delay = group(new BooleanSetting("Delay knockback", "Hold attack knockback and the pings after it, then apply them late", false), "Delay");
	public final NumberSetting delayTicks = group(new NumberSetting("Delay ticks", "How long to hold them", 6, 1, 40, 1), "Delay");

	/** Ticks left before held knockback is applied; 0 when nothing is held. */
	private int holding;
	/** The sprint state the server was last sent, and the tick of the last hit-back slowdown. */
	private volatile boolean serverSprinting;
	private int reducedTick = -1;

	public Velocity() {
		super("Velocity", Category.MOVEMENT, "Prevents you from being moved by external forces.");
		delayTicks.visibleWhen(delay::get);
		for (Setting<?> s : List.of(knockbackHorizontal, knockbackVertical)) s.visibleWhen(() -> knockback.get() && mode.get() == Mode.MODIFY);
		reduceFactor.visibleWhen(() -> knockback.get() && mode.get() == Mode.REDUCE);
		hitWhileHurt.visibleWhen(this::reducing);
		for (Setting<?> s : List.of(mode, chance, onlyGround, onlyWalls, setbackPause)) s.visibleWhen(knockback::get);
	}

	@Override
	protected void init(EventBus bus) {
		// Event 31 on a hook is the reel-in pull; vanilla applies it only to the player it hooked.
		listen(Events.PacketApply.Pre.class, e -> {
			if (!fishingRods.get() || !(e.packet instanceof ClientboundEntityEventPacket p) || p.getEventId() != 31) return;
			Minecraft mc = mc();
			if (mc.level != null && p.getEntity(mc.level) instanceof FishingHook hook && hook.getHookedIn() == mc.player) e.cancel();
		});
		listen(Events.PacketApply.Pre.class, e -> {
			if (!delay.get() || mc().player == null) return;
			if (e.packet instanceof ClientboundSetEntityMotionPacket m && m.id() == mc().player.getId()) {
				if (PacketHold.hold(this, e) && holding == 0) holding = delayTicks.intValue();
			} else if (holding > 0 && e.packet instanceof ClientboundPingPacket) {
				PacketHold.hold(this, e);
			}
		});
		listen(Events.PacketSend.class, e -> {
			LocalPlayer player = mc().player;
			if (e.isCancelled() || player == null) return;
			if (e.packet instanceof ServerboundPlayerCommandPacket command) {
				if (command.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) serverSprinting = true;
				else if (command.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING) serverSprinting = false;
				return;
			}
			if (!(e.packet instanceof ServerboundAttackPacket attack) || !reducing() || player.hurtTime <= 0 || !serverSprinting
				|| reducedTick == player.tickCount || !(mc().level.getEntity(attack.entityId()) instanceof net.minecraft.world.entity.player.Player)) return;
			// Grim simulates one 0.6 per tick for a hit made sprinting at a player; vanilla makes it itself on a full-strength hit.
			reducedTick = player.tickCount;
			if (player.isSprinting() && player.getAttackStrengthScale(0.5F) > 0.9F) return;
			double f = mode.get() == Mode.GRIM ? 0.6 : reduceFactor.get();
			player.setDeltaMovement(player.getDeltaMovement().multiply(f, 1, f));
		});
	}

	private boolean reducing() {
		return knockback.get() && (mode.get() == Mode.REDUCE || mode.get() == Mode.GRIM);
	}

	/** KillAura: hit now, cooldown or not, so each of the first knockback ticks gets its hit-back slowdown. */
	public static boolean wantsHit() {
		Velocity velocity = instance();
		LocalPlayer player = Minecraft.getInstance().player;
		return velocity != null && player != null && velocity.reducing() && velocity.hitWhileHurt.get() && velocity.serverSprinting
			&& player.hurtTime >= 7 && velocity.reducedTick != player.tickCount;
	}

	@Override
	protected void onEnable() {
		LocalPlayer player = mc().player;
		serverSprinting = player != null && player.isSprinting();
	}

	@Override
	protected void onDisable() {
		PacketHold.release(this);
		holding = 0;
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public void onTick() {
		if (holding > 0 && (--holding == 0 || !delay.get())) {
			holding = 0;
			PacketHold.release(this);
		}
		LocalPlayer player = mc().player;
		if (!sinking.get()) return;
		if (player == null || mc().options.keyJump.isDown() || mc().options.keyShift.isDown()) return;
		Vec3 velocity = player.getDeltaMovement();
		if ((player.isInWater() || player.isInLava()) && velocity.y < 0) {
			player.setDeltaMovement(velocity.x, 0, velocity.z);
		}
	}

	/** {@code ClientPacketListenerMixin}: the motion a motion packet gives {@code entity}, changed for you by the mode. */
	public static Vec3 knockback(Entity entity, Vec3 motion) {
		Velocity velocity = instance();
		Minecraft mc = Minecraft.getInstance();
		if (velocity == null || !velocity.knockback.get() || mc.player == null || entity != mc.player || !velocity.applies(mc.player)) return motion;
		Vec3 delta = mc.player.getDeltaMovement();
		return switch (velocity.mode.get()) {
			case MODIFY -> {
				double horizontal = velocity.knockbackHorizontal.get();
				double vertical = velocity.knockbackVertical.get();
				yield new Vec3((motion.x - delta.x) * horizontal + delta.x, (motion.y - delta.y) * vertical + delta.y, (motion.z - delta.z) * horizontal + delta.z);
			}
			case CANCEL -> delta;
			case GRIM, JUMP_RESET -> {
				// A real jump on the knockback tick (the sprint-jump push eats into it); Grim and NCP see it in the input packet.
				if (mc.player.onGround()) Player.requestJump();
				yield motion;
			}
			case REDUCE -> motion;
			case STRAFE -> strafe(mc.player, motion);
		};
	}

	/** The knockback's horizontal speed, along the direction your keys walk you (unchanged when standing still). */
	private static Vec3 strafe(LocalPlayer player, Vec3 motion) {
		Vec2 input = player.input.getMoveVector();
		if (input.lengthSquared() < 1.0E-5F) return motion;
		double yaw = Math.toRadians(player.getYRot());
		double x = input.x * Math.cos(yaw) - input.y * Math.sin(yaw);
		double z = input.y * Math.cos(yaw) + input.x * Math.sin(yaw);
		double scale = Math.hypot(motion.x, motion.z) / Math.hypot(x, z);
		return new Vec3(x * scale, motion.y, z * scale);
	}

	/** Chance, the ground and walls gates, and the pause after a setback. */
	private boolean applies(LocalPlayer player) {
		if (setbackPause.intValue() > 0 && Setback.recent(setbackPause.intValue() * 50L)) return false;
		if (onlyGround.get() && !player.onGround()) return false;
		if (onlyWalls.get() && !player.level().getBlockCollisions(player, player.getBoundingBox().deflate(0.01)).iterator().hasNext()) return false;
		return ThreadLocalRandom.current().nextDouble(100) < chance.get();
	}

	/** Called from {@code ClientPacketListenerMixin} on an explosion's push; scaled by the knockback fractions. */
	public static Vec3 modifyExplosion(Vec3 push) {
		Velocity velocity = instance();
		if (velocity == null || !velocity.explosions.get()) return push;
		return switch (velocity.mode.get()) {
			case MODIFY -> {
				double h = velocity.knockbackHorizontal.get();
				yield push.multiply(h, velocity.knockbackVertical.get(), h);
			}
			case CANCEL -> Vec3.ZERO;
			default -> push;
		};
	}

	private static Velocity instance() {
		return ModuleManager.on(Velocity.class) ? CoffeeClient.modules().get(Velocity.class) : null;
	}

	@Override
	public String infoString() {
		if (mode.get() != Mode.MODIFY) return mode.get().label();
		return "H" + Math.round(knockbackHorizontal.get() * 100) + "% V" + Math.round(knockbackVertical.get() * 100) + "%";
	}
}
