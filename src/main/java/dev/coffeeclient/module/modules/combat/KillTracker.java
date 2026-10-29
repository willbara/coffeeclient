package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Session tally of kills (targets you hit that died soon after) and deaths, with K/D and streak. */
public final class KillTracker extends Module {
	public final BooleanSetting playersOnly = setting(new BooleanSetting("Players only", "Only count player kills", false));
	public final NumberSetting window = setting(new NumberSetting("Credit window (s)", "A target counts as your kill if it dies this soon after your hit", 5, 1, 20, 1));
	public final BooleanSetting notifyKills = setting(new BooleanSetting("Kill toast", "Toast on each kill", true));
	public final BooleanSetting resetOnLeave = setting(new BooleanSetting("Reset on disconnect", "Start fresh in each world", true));
	public final BooleanSetting showStreak = setting(new BooleanSetting("Show streak", "Current and best kill streak", true));
	public final ActionSetting reset = setting(new ActionSetting("Reset", "Clear kills, deaths and streaks", this::clear));

	private final Map<Integer, Long> hits = new HashMap<>();
	private int kills, deaths, streak, bestStreak;
	private boolean wasDead;
	private boolean inWorld;

	public KillTracker() {
		super("KillTracker", Category.COMBAT, "Keeps a session tally of kills and deaths.");
	}

	private void clear() {
		kills = deaths = streak = bestStreak = 0;
		hits.clear();
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.AttackEntity.class, e -> {
			if (e.target() instanceof LivingEntity && (!playersOnly.get() || e.target() instanceof Player)) {
				hits.put(e.target().getId(), System.currentTimeMillis());
			}
		});
	}

	@Override
	public void onTick() {
		var mc = mc();
		if (mc.level == null || mc.player == null) {
			if (inWorld && resetOnLeave.get()) clear();
			inWorld = false;
			return;
		}
		inWorld = true;
		long now = System.currentTimeMillis();
		hits.entrySet().removeIf(entry -> {
			Entity entity = mc.level.getEntity(entry.getKey());
			boolean expired = now - entry.getValue() > window.get() * 1000;
			if (entity instanceof LivingEntity le && le.isDeadOrDying() && !expired) {
				kills++;
				streak++;
				bestStreak = Math.max(bestStreak, streak);
				if (notifyKills.get()) notify("Killed " + le.getName().getString() + (streak > 1 ? " (" + streak + " streak)" : ""));
				return true;
			}
			return expired || entity == null;
		});
		boolean dead = mc.player.isDeadOrDying();
		if (dead && !wasDead) {
			deaths++;
			streak = 0;
		}
		wasDead = dead;
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new ModuleHudElement(this, "kills", "Kill tracker", () -> {
			List<String[]> lines = new ArrayList<>();
			lines.add(ModuleHudElement.row("Kills", kills));
			lines.add(ModuleHudElement.row("Deaths", deaths));
			lines.add(ModuleHudElement.row("K/D", String.format(Locale.ROOT, "%.2f", deaths == 0 ? kills : kills / (double) deaths)));
			if (showStreak.get()) lines.add(ModuleHudElement.row("Streak", streak + " (best " + bestStreak + ")"));
			return lines;
		}, (w, h) -> new int[] {w - 90, h - 110}));
	}
}
