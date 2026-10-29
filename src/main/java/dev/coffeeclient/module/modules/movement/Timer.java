package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Player;

import java.util.List;
import java.util.Locale;

/**
 * Scales the client-side game speed. NoSlow, Spider and Speed set a temporary override
 * while they need a burst of speed; each override is tagged with its owner, so one module releasing
 * its own never cancels another's. Modes: Constant, Pulse (bursts on a cycle), Ramp (eases up to
 * the multiplier) and Charge (runs for a while, then recharges at a slower speed).
 */
public final class Timer extends Module {
	/** Sentinel meaning "no override". */
	public static final double OFF = -1;

	public enum Mode implements Labeled {
		CONSTANT("Constant"), PULSE("Pulse"), RAMP("Ramp"), CHARGE("Charge");

		private final String label;

		Mode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Mode", "Constant: steady speed. Pulse: bursts on a cycle. Ramp: eases up to the multiplier. Charge: runs, then recharges", Mode.CONSTANT));
	public final NumberSetting multiplier = setting(new NumberSetting("Multiplier", "Game speed multiplier", 1, 0.1, 20, 0.1));
	public final NumberSetting airMultiplier = setting(new NumberSetting("Air multiplier", "Game speed while in the air", 1, 0.1, 20, 0.1));
	public final BooleanSetting onlyMoving = setting(new BooleanSetting("Only moving", "Normal speed while standing still", false));
	public final NumberSetting pulseOn = setting(new NumberSetting("Pulse on", "Ticks at the multiplier each cycle", 10, 1, 40, 1));
	public final NumberSetting pulseOff = setting(new NumberSetting("Pulse off", "Ticks at normal speed each cycle", 10, 1, 40, 1));
	public final NumberSetting rampTicks = setting(new NumberSetting("Ramp ticks", "Ticks to ease from normal up to the multiplier", 20, 1, 100, 1));
	public final NumberSetting workTicks = setting(new NumberSetting("Work ticks", "Ticks at the multiplier before recharging", 20, 1, 200, 1));
	public final NumberSetting rechargeTicks = setting(new NumberSetting("Recharge ticks", "Ticks spent recharging", 40, 1, 200, 1));
	public final NumberSetting rechargeMultiplier = setting(new NumberSetting("Recharge speed", "Game speed while recharging", 0.8, 0.1, 1, 0.05));

	private double override = OFF;
	private Object overrideOwner;
	/** Ticks since enabling (Pulse, Ramp) or into the current Charge phase. */
	private int age;
	private boolean recharging;

	public Timer() {
		super("Timer", Category.MOVEMENT, "Changes the client-side game speed.");
		airMultiplier.visibleWhen(() -> mode.get() == Mode.CHARGE);
		for (Setting<?> s : List.of(pulseOn, pulseOff)) s.visibleWhen(() -> mode.get() == Mode.PULSE);
		rampTicks.visibleWhen(() -> mode.get() == Mode.RAMP);
		for (Setting<?> s : List.of(workTicks, rechargeTicks, rechargeMultiplier)) s.visibleWhen(() -> mode.get() == Mode.CHARGE);
		mode.onChange(m -> reset());
	}

	/** Speeds up ticks, so the server sees too many movement packets. */
	@Override
	public boolean affectsMovement() {
		return true;
	}

	@Override
	protected void onEnable() {
		reset();
	}

	private void reset() {
		age = 0;
		recharging = false;
	}

	@Override
	public void onTick() {
		if (mode.get() != Mode.CHARGE) {
			age++;
			return;
		}
		// Charge only spends work ticks while it is actually speeding you up.
		if (recharging || !onlyMoving.get() || Player.isMoving()) age++;
		if (age > (recharging ? rechargeTicks.intValue() : workTicks.intValue())) {
			recharging = !recharging;
			age = 0;
		}
	}

	/** Override the multiplier on behalf of {@code owner} (the latest caller wins). */
	public static void override(Object owner, double value) {
		Timer timer = instance();
		if (timer == null) return;
		timer.override = value;
		timer.overrideOwner = owner;
	}

	/** Drops {@code owner}'s override; one set since by another module stays. */
	public static void release(Object owner) {
		Timer timer = instance();
		if (timer == null || timer.overrideOwner != owner) return;
		timer.override = OFF;
		timer.overrideOwner = null;
	}

	/** The multiplier the render/tick loop should use right now. */
	public static double multiplier() {
		Timer timer = instance();
		return timer == null ? 1 : timer.current();
	}

	private double current() {
		if (override != OFF) return override;
		if (!isEnabled()) return 1;
		if (recharging) return rechargeMultiplier.get();
		if (onlyMoving.get() && !Player.isMoving()) return 1;
		double base = multiplier.get();
		return switch (mode.get()) {
			case CONSTANT -> base;
			case PULSE -> age % (pulseOn.intValue() + pulseOff.intValue()) < pulseOn.intValue() ? base : 1;
			case RAMP -> 1 + (base - 1) * Math.min(1, age / rampTicks.get());
			case CHARGE -> mc().player != null && !mc().player.onGround() ? airMultiplier.get() : base;
		};
	}

	private static Timer instance() {
		ModuleManager modules = CoffeeClient.modules();
		return modules == null ? null : modules.get(Timer.class);
	}

	@Override
	public String infoString() {
		if (recharging) return "Recharging";
		return String.format(Locale.ROOT, "%.1fx", current());
	}
}
