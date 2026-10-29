package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Action: snaps your view to the nearest angle step (e.g. a cardinal direction). With Lock on, a
 * press holds that view every tick until the next press, with optional jitter.
 */
public final class SnapLook extends Module {
	public final NumberSetting step = setting(new NumberSetting("Step (deg)", "Yaw is rounded to multiples of this", 45, 15, 90, 15));
	public final BooleanSetting levelPitch = setting(new BooleanSetting("Level pitch", "Also look straight ahead", false));
	public final NumberSetting pitchStep = setting(new NumberSetting("Pitch step", "Round pitch to this instead (0 = leave pitch)", 0, 0, 45, 15));
	public final BooleanSetting lock = setting(new BooleanSetting("Lock", "Hold the snapped view until you press again", false));
	public final NumberSetting jitter = setting(new NumberSetting("Jitter", "Random wobble in degrees while locked", 0, 0, 5, 0.1));

	/** The held view while locked, else null. */
	private float[] locked;

	public SnapLook() {
		super("SnapLook", Category.MOVEMENT, "Snaps your view to the nearest direction (runs once per press).");
		jitter.visibleWhen(lock::get);
		lock.onChange(v -> locked = null);
	}

	@Override
	protected void init(EventBus bus) {
		bus.subscribe(Events.Tick.class, e -> {
			LocalPlayer p = mc().player;
			if (locked == null) return;
			if (p == null) {
				locked = null;
				return;
			}
			float j = jitter.floatValue();
			ThreadLocalRandom r = ThreadLocalRandom.current();
			float yaw = locked[0] + (j > 0 ? (float) r.nextDouble(-j, j) : 0);
			p.setYRot(yaw);
			p.setYHeadRot(yaw);
			p.setXRot(Mth.clamp(locked[1] + (j > 0 ? (float) r.nextDouble(-j, j) : 0), -90, 90));
		});
	}

	/** Only looks, shows or presses keys like the player would: never the cause of a setback. */
	@Override
	public boolean affectsMovement() {
		return false;
	}

	@Override
	public boolean isAction() {
		return true;
	}

	@Override
	protected void onAction() {
		var p = mc().player;
		if (p == null) return;
		if (locked != null) {
			locked = null;
			info("unlocked");
			return;
		}
		float s = step.floatValue();
		float yaw = Math.round(Mth.wrapDegrees(p.getYRot()) / s) * s;
		p.setYRot(yaw);
		p.setYHeadRot(yaw);
		if (levelPitch.get()) p.setXRot(0);
		else if (pitchStep.get() > 0) p.setXRot(Math.round(p.getXRot() / pitchStep.floatValue()) * pitchStep.floatValue());
		if (lock.get()) {
			locked = new float[]{yaw, p.getXRot()};
			info("locked");
		}
	}
}
