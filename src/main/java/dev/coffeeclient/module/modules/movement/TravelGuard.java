package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.combat.AutoLog;
import dev.coffeeclient.setting.BlockPosSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Alarm;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.Jingle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Watches an unattended trip (AutoFly, AutoWalk, ElytraFly) once a second: shows the distance and
 * ETA to a target, and alerts or leaves when the elytra, rockets or food run low, you drop far below
 * where you started, you stop moving, you stop getting closer, or you arrive.
 */
public final class TravelGuard extends Module {
	public enum Action {
		Alert, Disconnect
	}

	public final BooleanSetting useTarget = group(new BooleanSetting("Target", "Track distance, ETA, progress and arrival to an X/Z", false), "Trip");
	public final BlockPosSetting target = group(new BlockPosSetting("Destination", "Where the trip ends (Y is ignored)", 0, 0, 0, -30_000_000, 30_000_000), "Trip");
	public final NumberSetting arrivalRadius = group(new NumberSetting("Arrival radius", "Blocks from the destination that count as arrived", 50, 5, 1000, 5), "Trip");

	public final NumberSetting minElytra = group(new NumberSetting("Elytra below", "Act when the worn elytra has this many uses left (0 = off)", 20, 0, 432, 1), "Checks");
	public final NumberSetting minRockets = group(new NumberSetting("Rockets below", "Act when you carry fewer rockets (0 = off)", 8, 0, 256, 1), "Checks");
	public final NumberSetting minFood = group(new NumberSetting("Food below", "Act at or below this food level (0 = off)", 6, 0, 20, 1), "Checks");
	public final NumberSetting maxDrop = group(new NumberSetting("Drop below start", "Act this many blocks under where the trip began (0 = off)", 50, 0, 400, 5), "Checks");
	public final NumberSetting stuckSeconds = group(new NumberSetting("Stuck for", "Act after moving under 5 blocks for this many seconds (0 = off)", 30, 0, 600, 5), "Checks");
	public final NumberSetting noProgressSeconds = group(new NumberSetting("No progress for", "Act after getting no closer to the destination for this many seconds (0 = off)", 120, 0, 1800, 10), "Checks");

	public final EnumSetting<Action> action = group(new EnumSetting<>("Action", "Alert: toast, chat and sound. Disconnect: leave the server", Action.Alert), "Alert");
	public final NumberSetting repeat = group(new NumberSetting("Repeat every (s)", "Alert again after this long while the problem lasts", 30, 5, 300, 5), "Alert");
	public final BooleanSetting sound = group(new BooleanSetting("Sound", "Play a sound with each alert", true), "Alert");
	public final IdListSetting alarmSound = group(Alarm.soundSetting(), "Alert");
	public final BooleanSetting useJingle = group(Alarm.useJingleSetting(), "Alert");
	public final EnumSetting<Jingle> jingle = group(Alarm.jingleSetting(), "Alert");
	public final NumberSetting soundCount = group(new NumberSetting("Sound count", "How many times the sound plays", 3, 1, 10, 1), "Alert");
	public final NumberSetting volume = group(new NumberSetting("Volume", "How loud each copy is", 1, 0.05, 2, 0.05).format(v -> Math.round(v * 100) + "%"), "Alert");

	private final Alarm alarm = new Alarm(alarmSound, useJingle, jingle, soundCount, volume);
	private int ticks;
	private double startY;
	private Vec3 stuckFrom;
	private int stuckFor, noProgressFor;
	private double bestDistance;
	private double speed;
	private Vec3 lastPos;
	private long lastAlert;

	public TravelGuard() {
		super("TravelGuard", Category.MOVEMENT, "Watches an unattended trip and alerts or leaves when something goes wrong.");
		for (Setting<?> s : List.of(target, arrivalRadius, noProgressSeconds)) s.visibleWhen(useTarget::get);
		repeat.visibleWhen(() -> action.get() == Action.Alert);
		for (Setting<?> s : List.of(useJingle, soundCount, volume)) s.visibleWhen(sound::get);
		jingle.visibleWhen(() -> sound.get() && useJingle.get());
		alarmSound.visibleWhen(() -> sound.get() && !useJingle.get());
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onEnable() {
		LocalPlayer p = mc().player;
		ticks = stuckFor = noProgressFor = 0;
		lastAlert = 0;
		speed = 0;
		startY = p == null ? 0 : p.getY();
		stuckFrom = lastPos = p == null ? null : p.position();
		bestDistance = Double.MAX_VALUE;
	}

	@Override
	protected void onDisable() {
		alarm.reset();
	}

	@Override
	public void onTick() {
		alarm.tick();
		LocalPlayer p = mc().player;
		if (p == null || ++ticks % 20 != 0) return;
		if (lastPos != null) speed = speed * 0.8 + p.position().distanceTo(lastPos) * 0.2;
		lastPos = p.position();
		String problem = check(p);
		if (problem != null) act(problem);
	}

	/** What is wrong this second, or null. */
	private String check(LocalPlayer p) {
		if (useTarget.get() && distance(p) <= arrivalRadius.get()) return "arrived at " + target.x() + ", " + target.z();
		ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
		if (minElytra.get() > 0 && chest.has(DataComponents.GLIDER) && chest.getMaxDamage() - chest.getDamageValue() <= minElytra.intValue())
			return "elytra has " + (chest.getMaxDamage() - chest.getDamageValue()) + " uses left";
		int rockets = Inv.find(s -> s.is(Items.FIREWORK_ROCKET)).count();
		if (minRockets.get() > 0 && rockets < minRockets.intValue()) return rockets + " rockets left";
		if (minFood.get() > 0 && p.getFoodData().getFoodLevel() <= minFood.intValue()) return "food is low";
		if (maxDrop.get() > 0 && p.getY() < startY - maxDrop.get()) return "dropped " + (int) (startY - p.getY()) + " blocks";
		if (stuckSeconds.get() > 0) {
			if (p.position().distanceTo(stuckFrom) >= 5) {
				stuckFrom = p.position();
				stuckFor = 0;
			} else if (++stuckFor >= stuckSeconds.intValue()) return "stuck for " + stuckFor + " s";
		}
		if (useTarget.get() && noProgressSeconds.get() > 0) {
			double d = distance(p);
			if (d < bestDistance - 5) {
				bestDistance = d;
				noProgressFor = 0;
			} else if (++noProgressFor >= noProgressSeconds.intValue()) return "no progress for " + noProgressFor + " s";
		}
		return null;
	}

	private void act(String problem) {
		if (action.get() == Action.Disconnect) {
			AutoLog.disconnect(Component.literal("[TravelGuard] " + problem));
			setEnabled(false);
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastAlert < repeat.get() * 1000) return;
		lastAlert = now;
		info(problem);
		notify(problem);
		if (sound.get()) alarm.trigger();
	}

	private double distance(LocalPlayer p) {
		return Math.hypot(target.x() + 0.5 - p.getX(), target.z() + 0.5 - p.getZ());
	}

	@Override
	public String infoString() {
		LocalPlayer p = mc().player;
		if (p == null || !useTarget.get()) return null;
		double d = distance(p);
		String dist = d >= 1000 ? String.format(Locale.ROOT, "%.1fk", d / 1000) : String.valueOf((int) d);
		if (speed < 0.05) return dist + "m";
		long s = (long) (d / speed);
		return String.format(Locale.ROOT, "%sm %d:%02d:%02d", dist, s / 3600, s / 60 % 60, s % 60);
	}
}
