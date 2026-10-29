package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Rotations;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Makes your head hard to read for others: the look the server (and so every other player) sees
 * spins, jitters or faces backwards, while your camera stays yours. It asks at the lowest priority,
 * so any module that needs to aim wins that tick.
 */
public final class AntiAim extends Module {
	public enum Yaw implements Labeled {
		SPIN("Spin"), JITTER("Jitter"), BACKWARDS("Backwards");

		private final String label;

		Yaw(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum Pitch implements Labeled {
		NONE("Yours"), DOWN("Down"), UP("Up"), ZERO("Level");

		private final String label;

		Pitch(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final EnumSetting<Yaw> yaw = setting(new EnumSetting<>("Yaw", "Spin round, jitter either side of backwards, or face backwards", Yaw.SPIN));
	public final EnumSetting<Pitch> pitch = setting(new EnumSetting<>("Pitch", "Head pitch others see", Pitch.DOWN));
	public final NumberSetting speed = setting(new NumberSetting("Speed", "Spin: degrees per tick. Jitter: degrees either side", 30, 1, 180, 1));

	private float spin;
	private boolean flip;

	public AntiAim() {
		super("AntiAim", List.of("Anti Aim", "Derp"), Category.COMBAT, "Spins or jitters the look other players see.");
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || player.isUsingItem() || mc().options.keyAttack.isDown() || mc().options.keyUse.isDown()) return;
		float own = player.getYRot();
		float y = switch (yaw.get()) {
			case SPIN -> spin = (spin + speed.floatValue()) % 360;
			case JITTER -> own + 180 + ((flip = !flip) ? speed.floatValue() : -speed.floatValue());
			case BACKWARDS -> own + 180;
		};
		float p = switch (pitch.get()) {
			case NONE -> player.getXRot();
			case DOWN -> 90;
			case UP -> -90;
			case ZERO -> 0;
		};
		Rotations.rotate(y, p, Rotations.LOW - 1, null);
	}

	@Override
	public String infoString() {
		return yaw.get().label();
	}
}
