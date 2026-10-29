package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Makes every hit a sprint hit, for the extra knockback: Packet tells the server you stopped and
 * started sprinting just before the hit, Sprint tap starts sprinting again the tick after a hit, and
 * W-tap lets go of forward for a moment after a hit so the sprint restarts.
 */
public final class SuperKnockback extends Module {
	public enum Mode implements Labeled {
		PACKET("Packet"), SPRINT_TAP("Sprint tap"), W_TAP("W-tap");

		private final String label;

		Mode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Mode", "Packet: a sprint restart sent with the hit. Sprint tap: sprint again the tick after. W-tap: let go of forward for a moment", Mode.PACKET));
	public final NumberSetting chance = setting(new NumberSetting("Chance", "Percent of hits to act on", 100, 0, 100, 5));
	public final NumberSetting maxHurtTime = setting(new NumberSetting("Max hurt time", "Only when the target's hurt time is at most this (10 = always)", 10, 0, 10, 1));
	public final NumberSetting tapTicks = setting(new NumberSetting("Tap ticks", "W-tap: ticks forward stays released", 2, 1, 5, 1));

	private boolean resending;
	private int resprint, release;

	public SuperKnockback() {
		super("SuperKnockback", List.of("Super Knockback", "WTap", "W-Tap"), Category.COMBAT, "Every hit lands as a sprint hit, for more knockback.");
		tapTicks.visibleWhen(() -> mode.get() == Mode.W_TAP);
		listen(Events.PacketSend.class, e -> {
			if (!e.isCancelled() && !resending && e.packet instanceof ServerboundAttackPacket attack) onAttack(attack);
		});
	}

	@Override
	protected void onDisable() {
		resprint = release = 0;
	}

	/** LocalPlayerMixin: W-tap holds forward released. */
	public static boolean releaseForward() {
		SuperKnockback m = ModuleManager.ifOn(SuperKnockback.class);
		return m != null && m.release > 0;
	}

	private void onAttack(ServerboundAttackPacket attack) {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || !player.isSprinting()) return;
		if (!(mc().level.getEntity(attack.entityId()) instanceof LivingEntity target) || target.hurtTime > maxHurtTime.intValue()) return;
		if (ThreadLocalRandom.current().nextDouble(100) >= chance.get()) return;
		switch (mode.get()) {
			case PACKET -> {
				resending = true;
				try {
					player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
					player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
				} finally {
					resending = false;
				}
			}
			case SPRINT_TAP -> resprint = 1;
			case W_TAP -> release = tapTicks.intValue();
		}
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (release > 0) release--;
		if (resprint > 0 && --resprint == 0 && player != null && mc().options.keyUp.isDown()) player.setSprinting(true);
	}

	@Override
	public String infoString() {
		return mode.get().label();
	}
}
