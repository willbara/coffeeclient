package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Player;
import net.minecraft.client.player.LocalPlayer;

/**
 * Banks ticks while you stand still and spends them as a short timer burst when you move off,
 * through {@link Timer#override}. The smoothness curve eases the burst down as the bank empties.
 */
public final class TickShift extends Module {
	public enum Smooth implements Labeled {
		OFF("Off"), LINEAR("Linear"), EXPONENT("Exponent");

		private final String label;

		Smooth(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final NumberSetting ticks = setting(new NumberSetting("Ticks", "Most ticks to bank while standing still", 50, 1, 100, 1));
	public final NumberSetting timer = setting(new NumberSetting("Timer", "Game speed while spending banked ticks", 2, 1, 10, 0.1));
	public final EnumSetting<Smooth> smooth = setting(new EnumSetting<>("Smoothness", "Off: full speed until empty. Linear and Exponent ease down as the bank drains", Smooth.EXPONENT));

	private int bank;

	public TickShift() {
		super("TickShift", Category.MOVEMENT, "Banks ticks while still and spends them as a speed burst.");
	}

	@Override
	public boolean affectsMovement() {
		return true;
	}

	@Override
	protected void onEnable() {
		bank = 0;
	}

	@Override
	protected void onDisable() {
		Timer.release(this);
	}

	@Override
	public void onTick() {
		LocalPlayer p = mc().player;
		if (p == null) return;
		if (!Player.isMoving() && p.onGround() && p.getDeltaMovement().horizontalDistanceSqr() < 1e-4) {
			bank = Math.min(ticks.intValue(), bank + 1);
			Timer.release(this);
		} else if (bank > 0) {
			bank--;
			Timer.override(this, speed());
		} else {
			Timer.release(this);
		}
	}

	private double speed() {
		double left = bank / ticks.get();
		double ease = switch (smooth.get()) {
			case OFF -> 1;
			case LINEAR -> left;
			case EXPONENT -> 1 - Math.pow(1 - left, 5);
		};
		return 1 + (timer.get() - 1) * ease;
	}

	@Override
	public String infoString() {
		return String.valueOf(bank);
	}
}
