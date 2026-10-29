package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.util.Player;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;

/**
 * Sprints for you, so you never have to hold (or double-tap) the sprint key.
 *
 * <p>Legit holds the sprint key exactly like vanilla, so hunger, collisions and water behave
 * normally; Omni forces sprinting whichever way you move.
 */
public final class Sprint extends Module {
	public enum Mode {
		/** Like holding vanilla's sprint key: only while you are pushing forwards. */
		Legit,
		/** Sprint whichever way you move, including backwards and strafing. */
		Omni
	}

	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Mode", "Legit sprints while you move forwards, Omni whichever way you move", Mode.Legit));
	public final BooleanSetting keepSprint = setting(new BooleanSetting("Keep sprint", "Start sprinting again right after you attack (vanilla stops it)", true));
	public final BooleanSetting sprintInWater = setting(new BooleanSetting("Sprint in water", "Keep sprinting while you are in water", false));
	public final BooleanSetting hungrySprint = setting(new BooleanSetting("Hungry sprint", "Sprint even with 3 food or less (from AutoSprint)", false));
	public final BooleanSetting ignoreBlindness = setting(new BooleanSetting("Ignore blindness", "Blindness no longer stops you sprinting", false));
	public final BooleanSetting ignoreCollision = setting(new BooleanSetting("Ignore collision", "Walking into a wall no longer stops your sprint", false));

	/** Set by the attack event, consumed on the next tick. */
	private boolean attacked;

	public Sprint() {
		super("Sprint", Category.MOVEMENT, "Sprints automatically.");
		sprintInWater.visibleWhen(() -> mode.get() == Mode.Omni);
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.AttackEntity.class, e -> {
			attacked = true;
		});
	}

	@Override
	protected void onDisable() {
		release();
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		if (!Player.canUpdate() || player == null) {
			release();
			attacked = false;
			return;
		}

		boolean critHold = dev.coffeeclient.module.modules.combat.KillAura.critSprintHold();
		// Vanilla drops your sprint on the hit, so re-assert it on the first tick after an attack.
		if (attacked) {
			if (keepSprint.get() && !critHold) player.setSprinting(true);
			attacked = false;
		}

		boolean sprint = shouldSprint(player) && !critHold;
		mc.options.keySprint.setDown(sprint);
		// Omni needs the direct call: the movement itself would otherwise clear the flag again.
		if (mode.get() == Mode.Omni) player.setSprinting(sprint);
	}

	/** Whether the player is moving in a direction this mode wants to sprint in. */
	private boolean shouldSprint(LocalPlayer player) {
		Input keys = player.input.keyPresses;
		boolean moving = mode.get() == Mode.Omni
			? keys.forward() || keys.backward() || keys.left() || keys.right()
			: keys.forward();
		if (!moving) return false;
		return mode.get() != Mode.Omni || sprintInWater.get() || !player.isInWater();
	}

	/** LocalPlayerMixin: whether low food may not stop sprinting. */
	public static boolean hungry() {
		Sprint m = ModuleManager.ifOn(Sprint.class);
		return m != null && m.hungrySprint.get();
	}

	/** LocalPlayerMixin: whether blindness may not stop sprinting. */
	public static boolean blind() {
		Sprint m = ModuleManager.ifOn(Sprint.class);
		return m != null && m.ignoreBlindness.get();
	}

	/** LocalPlayerMixin: whether bumping a wall may not stop sprinting. */
	public static boolean bump() {
		Sprint m = ModuleManager.ifOn(Sprint.class);
		return m != null && m.ignoreCollision.get();
	}

	private void release() {
		Minecraft mc = mc();
		if (mc.options != null) mc.options.keySprint.setDown(false);
	}

	@Override
	public String infoString() {
		return mode.display();
	}
}
