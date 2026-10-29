package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.mixin.ServerboundMovePlayerPacketAccessor;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

import java.util.List;

/**
 * Mines at full speed where vanilla slows you down: with Mining Fatigue, in the air and under water.
 * After NoSlowBreak; {@code PlayerMixin} asks the getters while it computes your break speed.
 * Servers time breaks themselves, so in the air Claim ground also tells the server you are on the
 * ground: one on-ground position before each start and finish of a break, ground kept on your
 * moves for a few ticks, then your real state sent again. Without it the block flickers back.
 */
public final class NoSlowBreak extends Module {
	public final BooleanSetting miningFatigue = setting(new BooleanSetting("Mining fatigue", "Ignore Mining Fatigue", true));
	public final BooleanSetting inAir = setting(new BooleanSetting("In air", "Mine as fast in the air as on the ground (5x)", true));
	public final BooleanSetting claimGround = setting(new BooleanSetting("Claim ground", "In the air, tell the server you're on the ground while a break starts and ends", false));
	public final NumberSetting claimTicks = setting(new NumberSetting("Claim ticks", "Ticks the ground claim stays on", 2, 1, 10, 1));
	public final NumberSetting maxSpeed = setting(new NumberSetting("Max speed", "No claims above this horizontal speed (blocks per tick)", 1, 0, 3, 0.05));
	public final BooleanSetting whileGliding = setting(new BooleanSetting("While gliding", "Also claim while flying an elytra (risky)", false));
	public final BooleanSetting underwater = setting(new BooleanSetting("Underwater", "Mine as fast under water as on land (5x without Aqua Affinity)", false));

	private int claimLeft;
	private boolean claimed;
	/** Set while we send our own claim or restore, so the send hook leaves them alone. */
	private boolean injecting;

	public NoSlowBreak() {
		super("NoSlowBreak", List.of("No Slow Break", "FastMine"), Category.MOVEMENT, "Keeps your break speed up with Mining Fatigue, in the air and under water.");
		claimGround.visibleWhen(inAir::get);
		for (Setting<?> s : List.of(claimTicks, maxSpeed, whileGliding)) s.visibleWhen(() -> inAir.get() && claimGround.get());
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.PacketSend.class, EventBus.HIGH, e -> {
			if (injecting || !inAir.get() || !claimGround.get()) return;
			if (e.packet instanceof ServerboundPlayerActionPacket p) {
				var a = p.getAction();
				if ((a == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK || a == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) && canClaim()) claim();
			} else if (e.packet instanceof ServerboundMovePlayerPacket move && claimLeft > 0 && move.hasPosition()) {
				((ServerboundMovePlayerPacketAccessor) move).coffeeclient$setOnGround(true);
			}
		});
	}

	@Override
	protected void onEnable() {
		claimLeft = 0;
		claimed = false;
	}

	@Override
	protected void onDisable() {
		if (claimed) restore();
	}

	@Override
	public void onTick() {
		if (claimLeft > 0 && --claimLeft == 0 && claimed) restore();
	}

	private boolean canClaim() {
		LocalPlayer p = mc().player;
		if (p == null || p.onGround() || p.isCreative() || p.isSpectator()) return false;
		if (p.isFallFlying() && !whileGliding.get()) return false;
		return p.getDeltaMovement().horizontalDistance() <= maxSpeed.get();
	}

	private void claim() {
		send(true);
		claimLeft = claimTicks.intValue();
		claimed = true;
	}

	private void restore() {
		claimed = false;
		claimLeft = 0;
		LocalPlayer p = mc().player;
		if (p != null) send(p.onGround());
	}

	private void send(boolean onGround) {
		LocalPlayer p = mc().player;
		if (p == null) return;
		injecting = true;
		try {
			p.connection.send(new ServerboundMovePlayerPacket.PosRot(p.position(), p.getYRot(), p.getXRot(), onGround, p.horizontalCollision));
		} finally {
			injecting = false;
		}
	}

	public static boolean miningFatigue() {
		NoSlowBreak m = ModuleManager.ifOn(NoSlowBreak.class);
		return m != null && m.miningFatigue.get();
	}

	public static boolean inAir() {
		NoSlowBreak m = ModuleManager.ifOn(NoSlowBreak.class);
		return m != null && m.inAir.get();
	}

	public static boolean underwater() {
		NoSlowBreak m = ModuleManager.ifOn(NoSlowBreak.class);
		return m != null && m.underwater.get();
	}
}
