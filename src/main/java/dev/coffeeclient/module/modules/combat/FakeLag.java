package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.OutboundHold;
import dev.coffeeclient.util.Targets;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Random;

/**
 * Makes you lag on purpose near an enemy: your packets go out late, so the server (and the enemy)
 * see you where you were. Smooth releases each packet a delay after it was made, keeping the gaps
 * between them; Pulse lets everything out in bursts. The delay can drift around its setting, and a
 * hit or use can send everything first so it lands from where you really are.
 */
public final class FakeLag extends Module {
	public enum Mode implements Labeled {
		SMOOTH("Smooth"), PULSE("Pulse");

		private final String label;

		Mode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Mode", "Smooth: each packet a delay late, gaps kept. Pulse: everything held, then sent at once", Mode.SMOOTH));
	public final NumberSetting delay = setting(new NumberSetting("Delay", "Milliseconds your packets are held", 200, 25, 1000, 25));
	public final NumberSetting fluctuation = setting(new NumberSetting("Fluctuation", "How far (ms) the delay drifts around its setting", 0, 0, 300, 10));
	public final NumberSetting range = setting(new NumberSetting("Range", "Lag only while an enemy is this close (0 = always)", 6, 0, 16, 0.5));
	public final BooleanSetting flushOnAction = setting(new BooleanSetting("Flush on action", "Send everything held before a hit, use or interaction", true));

	private final Random random = new Random();
	private double drift;
	private long pulseAt;

	public FakeLag() {
		super("FakeLag", List.of("Fake Lag", "Lag"), Category.COMBAT, "Delays your packets near an enemy.");
	}

	@Override
	protected void init(EventBus bus) {
		// Low: after the rewriters, so what is held is what would have gone out.
		listen(Events.PacketSend.class, EventBus.LOW, e -> {
			if (e.isCancelled() || mc().player == null) return;
			var packet = e.packet;
			if (packet instanceof ServerboundChatPacket || packet instanceof ServerboundChatCommandPacket || packet instanceof ServerboundChatAckPacket) return;
			boolean action = packet instanceof ServerboundAttackPacket || packet instanceof ServerboundInteractPacket
				|| packet instanceof ServerboundUseItemPacket || packet instanceof ServerboundUseItemOnPacket;
			if (action && flushOnAction.get()) {
				OutboundHold.release(this);
				return;
			}
			if (lagging()) OutboundHold.hold(this, e);
		});
	}

	@Override
	protected void onDisable() {
		OutboundHold.release(this);
	}

	private boolean lagging() {
		if (range.get() <= 0) return true;
		return Targets.get(e -> e instanceof Player p && Targets.isAttackablePlayer(p) && Targets.withinRange(p, range.get()), Targets.SortPriority.LowestDistance) != null;
	}

	@Override
	public void onTick() {
		if (mc().player == null) return;
		if (!lagging()) {
			OutboundHold.release(this);
			return;
		}
		// A mean-reverting walk: wanders inside about the fluctuation, never runs off.
		drift = drift * 0.9 + random.nextGaussian() * fluctuation.get() * 0.3;
		double fluct = fluctuation.get();
		long current = Math.max(0, Math.round(delay.get() + Math.max(-fluct, Math.min(fluct, drift))));
		long now = System.currentTimeMillis();
		if (mode.get() == Mode.SMOOTH) {
			OutboundHold.releaseUntil(this, now - current);
		} else if (now - pulseAt >= current) {
			OutboundHold.release(this);
			pulseAt = now;
		}
	}

	@Override
	public String infoString() {
		int held = OutboundHold.count(this);
		return held == 0 ? mode.get().label() : mode.get().label() + " " + held;
	}
}
