package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.Coords;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Floating numbers above entities when their health changes (from synced health, so it works for any
 * damage), and optionally a chat line naming what hurt you and where it was.
 */
public final class DamageNumbers extends Module {
	public final NumberSetting duration = setting(new NumberSetting("Duration (s)", "How long a number floats", 1.2, 0.4, 4, 0.1));
	public final NumberSetting range = setting(new NumberSetting("Range", "Track entities within this many blocks", 24, 4, 64, 4));
	public final BooleanSetting onlyAttacked = setting(new BooleanSetting("Only my targets", "Only entities you hit recently", false));
	public final BooleanSetting showHeals = setting(new BooleanSetting("Show heals", "Green numbers when health goes up", true));
	public final BooleanSetting includeSelf = setting(new BooleanSetting("Include self", "Numbers above yourself (third person)", false));
	public final BooleanSetting decimals = setting(new BooleanSetting("Decimals", "Show half-hearts as .5", true));
	public final NumberSetting scale = setting(new NumberSetting("Scale", "Text size", 1.0, 0.5, 2.0, 0.25));
	public final ColorSetting color = setting(new ColorSetting("Damage color", "Colour of damage numbers", 0xFFFFD27A));
	public final ColorSetting healColor = setting(new ColorSetting("Heal color", "Colour of heal numbers", 0xFF7AE07A));
	public final BooleanSetting damageAlerts = setting(new BooleanSetting("What hit me", "Chat line with the cause, attacker and their position each time you're hurt", false));

	private float lastHealth = -1;
	private int lastHurtTime;
	private record Popup(double x, double y, double z, String text, int color, long born) {
	}

	private final Map<Integer, Float> health = new HashMap<>();
	private final Map<Integer, Long> attacked = new HashMap<>();
	private final List<Popup> popups = new ArrayList<>();

	public DamageNumbers() {
		super("DamageNumbers", Category.COMBAT, "Floats the damage you deal above entities.");
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.AttackEntity.class, e -> {
			attacked.put(e.target().getId(), System.currentTimeMillis());
		});
	}

	@Override
	protected void onDisable() {
		health.clear();
		popups.clear();
		lastHealth = -1;
		lastHurtTime = 0;
	}

	@Override
	public void onTick() {
		var mc = mc();
		if (mc.level == null || mc.player == null) {
			health.clear();
			return;
		}
		long now = System.currentTimeMillis();
		Map<Integer, Float> seen = new HashMap<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity le)) continue;
			if (le == mc.player && !includeSelf.get()) continue;
			if (le.distanceTo(mc.player) > range.get()) continue;
			float hp = le.getHealth();
			seen.put(le.getId(), hp);
			Float before = health.get(le.getId());
			if (before == null || Math.abs(before - hp) < 0.01f) continue;
			if (onlyAttacked.get() && now - attacked.getOrDefault(le.getId(), 0L) > 5000) continue;
			float delta = hp - before;
			if (delta > 0 && !showHeals.get()) continue;
			String amount = decimals.get() ? String.format(Locale.ROOT, "%.1f", Math.abs(delta)) : Integer.toString(Math.round(Math.abs(delta)));
			if (amount.endsWith(".0")) amount = amount.substring(0, amount.length() - 2);
			var r = ThreadLocalRandom.current();
			popups.add(new Popup(le.getX() + (r.nextDouble() - 0.5) * 0.6, le.getY() + le.getBbHeight() + 0.2, le.getZ() + (r.nextDouble() - 0.5) * 0.6,
				(delta < 0 ? "-" : "+") + amount, delta < 0 ? color.argb() : healColor.argb(), now));
		}
		health.clear();
		health.putAll(seen);
		if (damageAlerts.get()) checkHurt(mc.player);
		attacked.values().removeIf(t -> now - t > 10_000);
		while (popups.size() > 64) popups.removeFirst();
	}

	/** A rising hurt timer means a new hit; the health drop since last tick is its size. */
	private void checkHurt(LivingEntity player) {
		float hp = player.getHealth();
		if (player.hurtTime > lastHurtTime) {
			DamageSource source = player.getLastDamageSource();
			Entity actor = source == null ? null : source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
			String cause = "Hurt by " + (source == null ? "something" : source.getMsgId().replace('_', ' '));
			if (actor != null) cause = "Hurt by " + actor.getName().getString() + " (" + source.getMsgId().replace('_', ' ') + ") at "
				+ Coords.format((int) Math.floor(actor.getX())) + ", " + Coords.format((int) Math.floor(actor.getY())) + ", " + Coords.format((int) Math.floor(actor.getZ()));
			float taken = lastHealth < 0 ? 0 : Math.max(0, lastHealth - hp);
			String amount = String.format(Locale.ROOT, "%.1f", taken);
			if (amount.endsWith(".0")) amount = amount.substring(0, amount.length() - 2);
			info(cause + " (-" + amount + " HP)");
		}
		lastHealth = hp;
		lastHurtTime = player.hurtTime;
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		long now = System.currentTimeMillis();
		long life = Math.round(duration.get() * 1000);
		float s = scale.floatValue();
		for (Iterator<Popup> it = popups.iterator(); it.hasNext(); ) {
			Popup p = it.next();
			float age = (now - p.born()) / (float) life;
			if (age >= 1) {
				it.remove();
				continue;
			}
			float[] pos = projector.project(p.x(), p.y() + age * 0.8, p.z());
			if (pos == null) continue;
			float alpha = age < 0.7f ? 1f : 1f - (age - 0.7f) / 0.3f;
			int c = Colors.fade(Colors.withAlpha(p.color(), 255), alpha);
			float w = Draw.width(p.text()) * s;
			Draw.textScaled(ui.g, p.text(), pos[0] - w / 2, pos[1], c, s);
		}
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}
}
