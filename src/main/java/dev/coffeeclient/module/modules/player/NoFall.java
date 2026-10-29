package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.movement.Flight;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Prevents fall damage by reporting "on ground" to the server while falling. Servers with movement
 * checks may still apply damage or correct you. The other modes are anticheat-specific tries kept
 * for testing: No ground never reports ground at all; Matrix claims ground past 2.7 blocks with the
 * game slowed; Vulcan claims it on a half-block Y; Vulcan 2.7.7 claims it past 7 blocks and stops
 * the fall; Grim swaps the landing packet after a 2.5+ block fall for a ground-only one and hops.
 */
public final class NoFall extends Module {
	public enum Mode implements dev.coffeeclient.setting.Labeled {
		PACKET("Packet"), NO_GROUND("No ground"), MATRIX("Matrix"), VULCAN("Vulcan"), VULCAN_277("Vulcan 2.7.7"), GRIM("Grim");

		private final String label;

		Mode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final dev.coffeeclient.setting.EnumSetting<Mode> mode = setting(new dev.coffeeclient.setting.EnumSetting<>("Mode", "Packet: claim ground while falling. The rest are anticheat-specific tries", Mode.PACKET));
	public final NumberSetting minFall = setting(new NumberSetting("Min fall", "Only kick in after falling this many blocks", 2.5, 1, 10, 0.5));
	public final BooleanSetting notGliding = setting(new BooleanSetting("Not while gliding", "Leave elytra flight alone", true));
	public final NumberSetting elytraMinFall = setting(new NumberSetting("Elytra min fall", "Fall distance at which to kick in while gliding", 2.5, 1, 30, 0.5));
	public final BooleanSetting pauseFlight = setting(new BooleanSetting("Pause during Flight", "Do nothing while the Flight module or creative-style flying is on", true));
	public final BooleanSetting survivalOnly = setting(new BooleanSetting("Survival only", "Do nothing in creative/spectator", true));

	private boolean grimLanding;

	public NoFall() {
		super("NoFall", Category.PLAYER, "Prevents fall damage.");
		elytraMinFall.visibleWhen(() -> !notGliding.get());
		// First rewriter in the chain, so Jesus and the droppers see the on-ground packet.
		listen(Events.PacketSend.class, EventBus.HIGHEST, e -> {
			if (!e.isCancelled()) e.packet = transform(e.packet);
		});
	}

	/** Spoofs the on-ground flag of movement packets. */
	@Override
	public boolean affectsMovement() {
		return true;
	}

	@Override
	protected void onDisable() {
		grimLanding = false;
		dev.coffeeclient.module.modules.movement.Timer.release(this);
	}

	private Packet<?> transform(Packet<?> packet) {
		if (!(packet instanceof ServerboundMovePlayerPacket move)) return packet;
		var p = Minecraft.getInstance().player;
		if (p == null) return packet;
		if (mode.get() != Mode.PACKET) {
			if (pauseFlight.get() && (ModuleManager.on(Flight.class) || p.getAbilities().flying)) return packet;
			if (survivalOnly.get() && (p.isCreative() || p.isSpectator())) return packet;
			return anticheat(p, move);
		}
		if (move.isOnGround()) return packet;
		if (p.isFallFlying()) {
			if (notGliding.get() || p.fallDistance < elytraMinFall.get()) return packet;
		} else if (p.fallDistance < minFall.get()) {
			return packet;
		}
		if (pauseFlight.get() && (ModuleManager.on(Flight.class) || p.getAbilities().flying)) return packet;
		if (survivalOnly.get() && (p.isCreative() || p.isSpectator())) return packet;
		return with(move, true, move.getY(0));
	}

	private Packet<?> anticheat(net.minecraft.client.player.LocalPlayer p, ServerboundMovePlayerPacket move) {
		switch (mode.get()) {
			case NO_GROUND -> {
				return move.isOnGround() ? with(move, false, move.getY(0)) : move;
			}
			case MATRIX -> {
				if (p.onGround()) {
					dev.coffeeclient.module.modules.movement.Timer.release(this);
					return move;
				}
				if (p.fallDistance > 3.5) dev.coffeeclient.module.modules.movement.Timer.override(this, 0.3);
				else dev.coffeeclient.module.modules.movement.Timer.release(this);
				if (p.fallDistance <= 2.69) return move;
				p.resetFallDistance();
				return with(move, true, move.getY(0));
			}
			case VULCAN -> {
				if (p.fallDistance <= 3.5625 || !move.hasPosition()) return move;
				p.resetFallDistance();
				double y = Math.round(move.getY(0) * 2) / 2.0;
				p.setPos(p.getX(), y, p.getZ());
				return with(move, true, y);
			}
			case VULCAN_277 -> {
				if (p.fallDistance <= 7) return move;
				p.resetFallDistance();
				p.setDeltaMovement(p.getDeltaMovement().x, 0, p.getDeltaMovement().z);
				return with(move, true, move.getY(0));
			}
			case GRIM -> {
				if (!p.onGround()) {
					if (p.fallDistance >= 2.5) grimLanding = true;
					return move;
				}
				if (!grimLanding) return move;
				grimLanding = false;
				// The landing position is never sent; hopping straight off makes the next one read as a jump.
				p.jumpFromGround();
				return new ServerboundMovePlayerPacket.StatusOnly(true, move.horizontalCollision());
			}
			default -> {
				return move;
			}
		}
	}

	/** The same packet rebuilt with {@code onGround} and (when it carries one) position Y {@code y}. */
	private static Packet<?> with(ServerboundMovePlayerPacket move, boolean onGround, double y) {
		boolean hc = move.horizontalCollision();
		if (move.hasPosition() && move.hasRotation()) {
			return new ServerboundMovePlayerPacket.PosRot(move.getX(0), y, move.getZ(0), move.getYRot(0), move.getXRot(0), onGround, hc);
		}
		if (move.hasPosition()) return new ServerboundMovePlayerPacket.Pos(move.getX(0), y, move.getZ(0), onGround, hc);
		if (move.hasRotation()) return new ServerboundMovePlayerPacket.Rot(move.getYRot(0), move.getXRot(0), onGround, hc);
		return new ServerboundMovePlayerPacket.StatusOnly(onGround, hc);
	}
}
