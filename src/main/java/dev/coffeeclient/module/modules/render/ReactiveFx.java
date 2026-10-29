package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.anim.Motion;
import dev.coffeeclient.gui.fx.FxTextures;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.elements.TargetHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Screen effects that answer what happens to you: a red edge flash when hurt, a heartbeat at low health,
 * a hit marker, kill call-outs, and bursts for totem pops and level-ups. Also feeds the theme's Reactive
 * accent mode. Purely visual; nothing is sent to the server.
 */
public final class ReactiveFx extends Module {
	public final BooleanSetting damageFlash = setting(new BooleanSetting("Damage flash", "Red edges when you take damage, stronger for bigger hits", true));
	public final ColorSetting damageColor = setting(new ColorSetting("Damage color", "Colour of the damage flash", 0xFFE0342C));
	public final BooleanSetting lowHealth = setting(new BooleanSetting("Low health pulse", "Heartbeat vignette while health is low", true));
	public final NumberSetting lowHealthAt = setting(new NumberSetting("Low health at", "Health (half-hearts) where the heartbeat starts", 8, 2, 16, 1));
	public final BooleanSetting hitMarker = setting(new BooleanSetting("Hit marker", "Crosshair tick when you land a hit", true));
	public final BooleanSetting killPopup = setting(new BooleanSetting("Kill popups", "Call-outs for kills and streaks", true));
	public final BooleanSetting totemBurst = setting(new BooleanSetting("Totem burst", "Golden burst when your totem pops", true));
	public final BooleanSetting levelUp = setting(new BooleanSetting("Level-up burst", "Accent burst on reaching a new XP level", true));
	public final BooleanSetting reactiveAccent = setting(new BooleanSetting("Drive accent", "Push the theme's Reactive accent toward red when hurt", true));
	public final BooleanSetting targetHud = setting(new BooleanSetting("Target HUD", "Health panel for what you are fighting", true));

	private static final float FLASH_S = 0.7f;
	private static final float MARKER_S = 0.28f;
	private static final float BURST_S = 0.9f;
	private static final float POPUP_S = 1.6f;
	private static final long KILL_WINDOW_MS = 5000;
	private static final long STREAK_MS = 4500;
	private static final String[] STREAK_NAMES = {"KILL", "DOUBLE KILL", "TRIPLE KILL", "MULTI KILL", "RAMPAGE", "UNSTOPPABLE"};

	private float lastHealth = -1;
	private int lastLevel = -1;
	private long flashAt, markerAt, burstAt;
	private float flashStrength;
	private int burstColor;
	private final Map<Integer, Long> hits = new HashMap<>();
	private long lastKillAt;
	private int streak;
	private final TargetHudElement targetElement = new TargetHudElement(this);

	private record Popup(String text, int color, long at) {
	}

	private final List<Popup> popups = new ArrayList<>();

	public ReactiveFx() {
		super("ReactiveFx", Category.RENDER, "Screen effects that react to damage, low health, hits, kills, totem pops and level-ups.");
		damageColor.visibleWhen(damageFlash::get);
		lowHealthAt.visibleWhen(lowHealth::get);
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(targetElement);
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.AttackEntity.class, e -> {
			if (!(e.target() instanceof LivingEntity living)) return;
			hits.put(living.getId(), System.currentTimeMillis());
			targetElement.target(living);
			if (hitMarker.get()) markerAt = System.nanoTime();
		});
		bus.subscribe(Events.TotemPop.class, e -> {
			LocalPlayer p = mc().player;
			if (isEnabled() && totemBurst.get() && p != null && e.entity() == p) burst(0xFFF2D24B);
		});
	}

	@Override
	protected void onEnable() {
		lastHealth = -1;
		lastLevel = -1;
	}

	@Override
	public void onTick() {
		LocalPlayer p = mc().player;
		if (p == null || mc().level == null) {
			lastHealth = -1;
			lastLevel = -1;
			hits.clear();
			return;
		}
		float health = p.getHealth() + p.getAbsorptionAmount();
		if (lastHealth >= 0 && health < lastHealth - 0.01f && !p.isDeadOrDying()) {
			float dmg = lastHealth - health;
			if (damageFlash.get()) {
				flashStrength = Math.min(1f, 0.35f + dmg / 8f);
				flashAt = System.nanoTime();
			}
			if (reactiveAccent.get()) CoffeeClient.theme().react(Math.min(1f, 0.4f + dmg / 6f));
		}
		lastHealth = health;
		if (reactiveAccent.get() && p.getHealth() <= lowHealthAt.floatValue()) CoffeeClient.theme().react(0.35f);

		int level = p.experienceLevel;
		if (lastLevel >= 0 && level > lastLevel && levelUp.get()) {
			burst(CoffeeClient.theme().accent);
			popup("LEVEL " + level, CoffeeClient.theme().accent);
		}
		lastLevel = level;

		// a target we hit recently that died counts as our kill
		long now = System.currentTimeMillis();
		for (Iterator<Map.Entry<Integer, Long>> it = hits.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, Long> hit = it.next();
			if (now - hit.getValue() > KILL_WINDOW_MS) {
				it.remove();
				continue;
			}
			Entity e = mc().level.getEntity(hit.getKey());
			if (e instanceof LivingEntity living && living.isDeadOrDying()) {
				it.remove();
				onKill(now);
			}
		}
	}

	private void onKill(long now) {
		streak = now - lastKillAt < STREAK_MS ? streak + 1 : 1;
		lastKillAt = now;
		if (killPopup.get()) {
			String name = STREAK_NAMES[Math.min(streak, STREAK_NAMES.length) - 1];
			popup(name, streak > 1 ? 0xFFFF5A3C : CoffeeClient.theme().accent);
		}
	}

	private void burst(int color) {
		burstColor = color;
		burstAt = System.nanoTime();
	}

	private void popup(String text, int color) {
		popups.add(new Popup(text, color, System.nanoTime()));
		while (popups.size() > 3) popups.removeFirst();
	}

	private static float since(long nanos) {
		return nanos == 0 ? Float.MAX_VALUE : (System.nanoTime() - nanos) / 1e9f;
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		var g = ui.g;
		int w = g.guiWidth(), h = g.guiHeight();
		LocalPlayer p = mc().player;
		if (p == null) return;

		float flash = since(flashAt) / FLASH_S;
		if (damageFlash.get() && flash < 1) {
			float a = flashStrength * (1 - Ease.OUT_CUBIC.apply(flash));
			FxTextures.vignette(g, 0, 0, w, h, a, damageColor.argb());
		}
		if (lowHealth.get() && !p.isDeadOrDying() && p.getHealth() <= lowHealthAt.floatValue()) {
			// lower health beats faster and harder
			float danger = 1 - p.getHealth() / Math.max(1f, lowHealthAt.floatValue());
			float beat = Motion.pulse(ui.time, 1.1f - danger * 0.5f);
			FxTextures.vignette(g, 0, 0, w, h, (0.15f + danger * 0.25f) * (0.4f + 0.6f * beat), damageColor.argb());
		}

		float burst = since(burstAt) / BURST_S;
		if (burst < 1) {
			float e = Ease.OUT_CUBIC.apply(burst);
			FxTextures.vignette(g, 0, 0, w, h, 0.6f * (1 - e), burstColor);
			float r = 10 + e * Math.max(w, h) * 0.45f;
			Draw.ring(g, w / 2f, h / 2f, r, 2.5f, Colors.withAlpha(burstColor, Math.round(0xC0 * (1 - e))));
			Draw.ring(g, w / 2f, h / 2f, r * 0.7f, 1.5f, Colors.withAlpha(burstColor, Math.round(0x70 * (1 - e))));
		}

		float marker = since(markerAt) / MARKER_S;
		if (hitMarker.get() && marker < 1) {
			float e = Ease.OUT_CUBIC.apply(marker);
			float cx = w / 2f, cy = h / 2f;
			float in = 3 + e * 3, out = 7 + e * 4;
			int c = Colors.withAlpha(0xFFFFFF, Math.round(0xF0 * (1 - marker)));
			for (int i = 0; i < 4; i++) {
				float dx = (i % 2 == 0 ? 1 : -1), dy = (i < 2 ? 1 : -1);
				Draw.stroke(g, cx + dx * in, cy + dy * in, cx + dx * out, cy + dy * out, 1.3f, c);
			}
		}

		long now = System.nanoTime();
		popups.removeIf(pp -> (now - pp.at) / 1e9f > POPUP_S);
		int py = Math.round(h * 0.3f);
		for (int i = popups.size() - 1; i >= 0; i--) {
			Popup pp = popups.get(i);
			float t = (now - pp.at) / 1e9f;
			float pop = ui.theme.motion() ? Ease.OUT_BACK.apply(Math.min(1, t / 0.35f)) : 1f;
			float fade = 1 - Motion.range(t, POPUP_S - 0.4f, POPUP_S);
			float scale = 1.2f + pop * 0.6f;
			int tw = Draw.width(pp.text);
			Draw.pushAlpha(fade * Math.min(1, t / 0.1f));
			Draw.textScaled(g, pp.text, w / 2f - tw * scale / 2, py - 4 * scale, Colors.withAlpha(pp.color, 255), scale);
			int lw = Math.round(tw * scale * Ease.OUT_CUBIC.apply(Math.min(1, t / 0.5f)));
			Draw.rect(g, w / 2 - lw / 2, Math.round(py + 6 * scale), lw, 1, Colors.withAlpha(pp.color, 0xC0));
			Draw.popAlpha();
			py += Math.round(14 * scale);
		}
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}
}
