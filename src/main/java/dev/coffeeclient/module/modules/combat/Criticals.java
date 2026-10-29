package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.core.EventBus;
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
import dev.coffeeclient.util.OutboundHold;
import dev.coffeeclient.util.PacketBatch;
import dev.coffeeclient.util.Targets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Turns your hits into critical hits by telling the server you were falling:
 * packet modes send small fake hops before the attack, Mini jump hops and holds the attack until
 * you fall, Jump has KillAura hop for real (a pressed jump the server sees) and hit on the way down,
 * and maces get a fake drop for a smash attack. Timer slows the game
 * while you fall near an enemy, so more hits land as crits; Blink holds your movement while you
 * rise near one, so the server sees the fall arrive with the hit. KillAura asks {@link #willCrit}
 * before it waits for a critical itself.
 */
public final class Criticals extends Module {
	public enum Mode implements Labeled {
		PACKET("Packet"), STRICT("Strict packet"), UPDATED_NCP("Updated NCP"), OLD_NCP("Old NCP"), GRIM("Grim"), GRIM_V3("Grim V3"), BLOCKSMC("BlocksMC"),
		JUMP("Jump"), MINI_JUMP("Mini jump"), TIMER("Timer"), BLINK("Blink"), NONE("None");

		private final String label;

		Mode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final EnumSetting<Mode> mode = group(new EnumSetting<>("Mode",
		"How the fall is made. Jump: KillAura really hops and hits on the way down (the one that passes Grim and NCP). The packet modes and Mini jump are for older anticheats", Mode.PACKET), "General");
	public final BooleanSetting onlyKillAura = group(new BooleanSetting("Only KillAura", "Only for KillAura's target", false), "General");
	public final BooleanSetting stopSprint = group(new BooleanSetting("Stop sprint", "Tell the server you stopped sprinting for the hit (sprinting hits never crit)", false), "General");
	public final BooleanSetting onlyPhased = group(new BooleanSetting("Only phased", "Packet modes: only while you stand inside a block", false), "General");
	public final BooleanSetting onlyWalls = group(new BooleanSetting("Only walls", "Only phased: need blocks at both feet and head height", false), "General");
	public final NumberSetting enemyRange = group(new NumberSetting("Enemy range", "Timer and Blink: act while an enemy is this close", 4, 1, 8, 0.5), "General");
	public final NumberSetting timerSpeed = group(new NumberSetting("Timer speed", "Game speed while falling near an enemy", 0.8, 0.1, 1, 0.05), "General");
	public final NumberSetting blinkTime = group(new NumberSetting("Blink time", "Most milliseconds of movement held while rising", 450, 50, 1000, 50), "General");
	public final BooleanSetting mace = group(new BooleanSetting("Smash attack", "Always smash-attack with a mace", true), "Mace");
	public final NumberSetting extraHeight = group(new NumberSetting("Extra height", "Extra fake fall height (more height, more damage)", 0, 0, 100, 0.5), "Mace");

	private ServerboundAttackPacket heldAttack;
	private ServerboundSwingPacket heldSwing;
	private boolean holding, resending, resprint;
	/** BlocksMC: its fake hop only works every fourth tick. */
	private static final int BLOCKSMC_EVERY = 4;
	/** Grim: one ground hop per this many milliseconds. */
	private static final long GRIM_GAP = 250;
	private long lastGrim;
	private int sendTimer;

	public Criticals() {
		super("Criticals", List.of("Crits"), Category.COMBAT, "Makes your hits critical hits.");
		onlyKillAura.visibleWhen(() -> mode.get() != Mode.NONE);
		extraHeight.visibleWhen(mace::get);
		for (Setting<?> s : List.of(onlyPhased, stopSprint)) s.visibleWhen(() -> packetMode(mode.get()));
		onlyWalls.visibleWhen(() -> packetMode(mode.get()) && onlyPhased.get());
		enemyRange.visibleWhen(() -> mode.get() == Mode.TIMER || mode.get() == Mode.BLINK);
		timerSpeed.visibleWhen(() -> mode.get() == Mode.TIMER);
		blinkTime.visibleWhen(() -> mode.get() == Mode.BLINK);
		// Before the droppers (Blink), so a held attack really is held.
		listen(Events.PacketSend.class, EventBus.HIGH, e -> {
			if (!e.isCancelled() && !resending && mc().player != null && mc().level != null) onSend(e);
		});
	}

	@Override
	protected void onDisable() {
		Timer.release(this);
		OutboundHold.release(this);
	}

	private static boolean packetMode(Mode mode) {
		return switch (mode) {
			case PACKET, STRICT, UPDATED_NCP, OLD_NCP, GRIM, GRIM_V3, BLOCKSMC -> true;
			default -> false;
		};
	}

	/** The vanilla conditions for a critical hit, apart from the attack cooldown. */
	public static boolean canCrit(LocalPlayer player) {
		return player.fallDistance > 0 && !player.onGround() && !player.onClimbable() && !player.isInWater()
			&& !player.isMobilityRestricted() && !player.isPassenger() && !player.isSprinting();
	}

	/** True when a hit sent now will crit, faked or real. */
	public boolean willCrit() {
		LocalPlayer player = mc().player;
		if (player == null) return false;
		Mode m = mode.get();
		if (packetMode(m)) return !skip(player) && gated(player) && (m != Mode.BLOCKSMC || player.tickCount % BLOCKSMC_EVERY == 0)
			&& (m != Mode.GRIM || System.currentTimeMillis() - lastGrim >= GRIM_GAP);
		if (m == Mode.MINI_JUMP) return !skip(player);
		return canCrit(player);
	}

	/** Jump mode: KillAura hops for real and waits for the fall, so the crit is plain vanilla. */
	public boolean jumpsForCrits() {
		return mode.get() == Mode.JUMP;
	}

	/** A hop can start now: on the ground and out of water, cobwebs and ladders. */
	public boolean canJump(LocalPlayer player) {
		return !skip(player);
	}

	/** Only phased / Only walls. */
	private boolean gated(LocalPlayer player) {
		if (!onlyPhased.get()) return true;
		var box = player.getBoundingBox().deflate(0.01);
		if (!onlyWalls.get()) return mc().level.getBlockCollisions(player, box).iterator().hasNext();
		var feet = box.setMaxY(box.minY + 0.5);
		var head = box.setMinY(box.maxY - 0.5);
		return mc().level.getBlockCollisions(player, feet).iterator().hasNext() && mc().level.getBlockCollisions(player, head).iterator().hasNext();
	}

	private boolean enemyNear() {
		return Targets.get(e -> e instanceof Player p && Targets.isAttackablePlayer(p) && Targets.withinRange(p, enemyRange.get()), Targets.SortPriority.LowestDistance) != null;
	}

	@Override
	protected void onEnable() {
		heldAttack = null;
		heldSwing = null;
		holding = false;
		sendTimer = 0;
	}

	private void onSend(Events.PacketSend e) {
		LocalPlayer player = mc().player;
		if (mode.get() == Mode.BLINK && e.packet instanceof ServerboundMovePlayerPacket && !player.onGround() && !canCrit(player) && enemyNear()) {
			long oldest = OutboundHold.oldest(this);
			if (oldest == 0 || System.currentTimeMillis() - oldest < blinkTime.get()) {
				OutboundHold.hold(this, e);
				return;
			}
		}
		if (e.packet instanceof ServerboundAttackPacket attack) {
			if (mace.get() && player.getMainHandItem().getItem() instanceof MaceItem) {
				if (player.isFallFlying()) return;
				fakeHeight(0);
				fakeHeight(1.501 + extraHeight.get());
				fakeHeight(0);
				return;
			}
			if (!packetMode(mode.get()) && mode.get() != Mode.MINI_JUMP || skip(player)) return;
			if (packetMode(mode.get()) && !willCrit()) return;
			Entity target = mc().level.getEntity(attack.entityId());
			if (!(target instanceof LivingEntity)) return;
			if (onlyKillAura.get()) {
				KillAura aura = ModuleManager.ifOn(KillAura.class);
				if (aura == null || aura.target() != target) return;
			}
			if (stopSprint.get() && packetMode(mode.get()) && player.isSprinting()) {
				send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
				resprint = true;
			}
			switch (mode.get()) {
				case PACKET -> {
					fakeHeight(0.0625);
					fakeHeight(0);
				}
				case STRICT -> {
					fakeHeight(0.0625);
					fakeHeight(0.0625013579);
					fakeHeight(0.0000013579);
				}
				case GRIM -> {
					fullHeight(0.0625, false);
					fullHeight(0.0625013579, false);
					fullHeight(0.0000013579, false);
					lastGrim = System.currentTimeMillis();
				}
				case GRIM_V3 -> {
					fullHeight(0, true);
					fullHeight(0.0625, false);
					fullHeight(0.04535, false);
				}
				case BLOCKSMC -> {
					fullHeight(0.0011, true);
					fullHeight(0, false);
				}
				case UPDATED_NCP -> {
					fakeHeight(0.0000008);
					fakeHeight(0);
				}
				case OLD_NCP -> {
					fakeHeight(0.11);
					fakeHeight(0.1100013579);
					fakeHeight(0.0000013579);
				}
				case MINI_JUMP -> {
					if (holding) return;
					holding = true;
					heldAttack = attack;
					Vec3 v = player.getDeltaMovement();
					player.setDeltaMovement(v.x, 0.25, v.z);
					sendTimer = 4;
					e.cancel();
				}
				default -> {
				}
			}
		} else if (e.packet instanceof ServerboundSwingPacket swing && holding && heldSwing == null && mode.get() == Mode.MINI_JUMP) {
			heldSwing = swing;
			e.cancel();
		}
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null) return;
		if (resprint) {
			resprint = false;
			if (player.isSprinting()) send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
		}
		if (mode.get() == Mode.TIMER && canCrit(player) && enemyNear()) Timer.override(this, timerSpeed.get());
		else Timer.release(this);
		// Blink: once you can crit (or land, or the enemy leaves) the held movement goes out.
		if (OutboundHold.count(this) > 0 && (mode.get() != Mode.BLINK || player.onGround() || canCrit(player) || !enemyNear())) OutboundHold.release(this);
		if (!holding) return;
		if (sendTimer-- > 0) return;
		resending = true;
		try {
			PacketBatch.run(() -> {
				if (heldAttack != null) player.connection.send(heldAttack);
				if (heldSwing != null) player.connection.send(heldSwing);
			});
		} finally {
			resending = false;
		}
		heldAttack = null;
		heldSwing = null;
		holding = false;
	}

	private void fakeHeight(double height) {
		LocalPlayer player = mc().player;
		send(new ServerboundMovePlayerPacket.Pos(player.getX(), player.getY() + height, player.getZ(), false, false));
	}

	/** A fake hop with position and look (anti-cheats that expect full packets). */
	private void fullHeight(double height, boolean onGround) {
		LocalPlayer player = mc().player;
		send(new ServerboundMovePlayerPacket.PosRot(player.getX(), player.getY() + height, player.getZ(), player.getYRot(), player.getXRot(), onGround, false));
	}

	private void send(net.minecraft.network.protocol.Packet<?> packet) {
		resending = true;
		try {
			mc().player.connection.send(packet);
		} finally {
			resending = false;
		}
	}

	private boolean skip(LocalPlayer player) {
		boolean jumpMode = mode.get() == Mode.JUMP || mode.get() == Mode.MINI_JUMP;
		if (jumpMode && mc().level.getBlockState(player.blockPosition()).is(Blocks.COBWEB)) return true;
		return !player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable();
	}

	@Override
	public String infoString() {
		return mode.display();
	}
}
