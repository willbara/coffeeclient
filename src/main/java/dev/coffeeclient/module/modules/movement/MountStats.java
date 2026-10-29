package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Max speed, jump height and health of the horse (or other jumping mount) you ride or look at. */
public final class MountStats extends Module {
	public final BooleanSetting whenLooking = setting(new BooleanSetting("When looking at", "Also show for mounts under the crosshair", true));
	public final BooleanSetting showSpeed = setting(new BooleanSetting("Speed", "Max speed in blocks/s", true));
	public final BooleanSetting showJump = setting(new BooleanSetting("Jump", "Max jump height in blocks", true));
	public final BooleanSetting showHealth = setting(new BooleanSetting("Health", "Current / max health", true));

	public MountStats() {
		super("MountStats", Category.MOVEMENT, "Shows speed and jump strength of your mount.");
	}

	/** Only looks, shows or presses keys like the player would: never the cause of a setback. */
	@Override
	public boolean affectsMovement() {
		return false;
	}

	private LivingEntity mount() {
		var mc = mc();
		if (mc.player == null) return null;
		Entity riding = mc.player.getVehicle();
		if (riding instanceof LivingEntity le && hasJump(le)) return le;
		if (whenLooking.get() && mc.crosshairPickEntity instanceof LivingEntity le && hasJump(le)) return le;
		return null;
	}

	private static boolean hasJump(LivingEntity e) {
		return e.getAttributes().hasAttribute(Attributes.JUMP_STRENGTH);
	}

	/** Jump strength to block height (standard fit used by horse calculators). */
	private static double jumpHeight(double s) {
		return -0.1817584952 * s * s * s + 3.689713992 * s * s + 2.128599134 * s - 0.343930367;
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new ModuleHudElement(this, "mount", "Mount stats", () -> {
			List<String[]> rows = new ArrayList<>();
			LivingEntity m = mount();
			if (m == null) return rows;
			rows.add(ModuleHudElement.row("Mount", m.getDisplayName().getString()));
			AttributeInstance speed = m.getAttribute(Attributes.MOVEMENT_SPEED);
			AttributeInstance jump = m.getAttribute(Attributes.JUMP_STRENGTH);
			if (showSpeed.get() && speed != null) rows.add(ModuleHudElement.row("Speed", String.format(Locale.ROOT, "%.2f b/s", speed.getValue() * 42.16)));
			if (showJump.get() && jump != null) rows.add(ModuleHudElement.row("Jump", String.format(Locale.ROOT, "%.2f blocks", jumpHeight(jump.getValue()))));
			if (showHealth.get()) rows.add(ModuleHudElement.row("Health", String.format(Locale.ROOT, "%.0f / %.0f", m.getHealth(), m.getMaxHealth())));
			return rows;
		}, (w, h) -> new int[] {w / 2 + 96, h - 60}).when(() -> mount() != null).fixed());
	}
}
