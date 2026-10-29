package dev.coffeeclient.hud.elements;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Anim;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.anim.Motion;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.module.modules.render.ReactiveFx;
import dev.coffeeclient.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Health panel for the entity you are fighting: face, name, an eased health bar with a delayed "ghost"
 * bar that shows the chunk just lost, and a shake and flash on each hit. The target is the last thing you
 * hit (for a few seconds), else whatever living thing is under the crosshair.
 */
public final class TargetHudElement extends HudElement {
	private static final int W = 124;
	private static final int H = 36;
	private static final long KEEP_MS = 6000;
	private static final float GHOST_HOLD_S = 0.45f;

	private final ReactiveFx module;
	private @Nullable LivingEntity attacked;
	private long attackedAt;
	private @Nullable LivingEntity shown;
	private final Anim appear = new Anim(12f);
	private final Anim health = new Anim(10f);
	private float ghost = -1;
	private long ghostHeldAt;
	private int lastHurtTime;
	private long hitAt;

	public TargetHudElement(ReactiveFx module) {
		super("target_hud", "Target HUD");
		this.module = module;
	}

	/** Called when you attack something. */
	public void target(LivingEntity entity) {
		attacked = entity;
		attackedAt = System.currentTimeMillis();
	}

	@Override
	public boolean isEnabled() {
		return module.isEnabled() && module.targetHud.get();
	}

	@Override
	public boolean showInEditor() {
		return isEnabled();
	}

	@Override
	public void toggle() {
		module.targetHud.toggle();
	}

	@Override
	public boolean shouldRender() {
		return current() != null || appear.get() > 0.02f;
	}

	@Override
	public int width() {
		return W;
	}

	@Override
	public int height() {
		return H;
	}

	@Override
	protected int[] defaultPosition(int screenW, int screenH) {
		return new int[] {screenW / 2 + 14, screenH / 2 + 14};
	}

	private @Nullable LivingEntity current() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		if (attacked != null && (attacked.isRemoved() || System.currentTimeMillis() - attackedAt > KEEP_MS
			|| attacked.distanceToSqr(mc.player) > 48 * 48)) attacked = null;
		if (attacked != null) return attacked;
		Entity aim = mc.crosshairPickEntity;
		return aim instanceof LivingEntity living && !living.isInvisible() ? living : null;
	}

	@Override
	public void render(Ui ui, int x, int y, boolean preview) {
		LivingEntity target = current();
		if (target == null && preview) target = Minecraft.getInstance().player;
		float a = appear.update(ui, target != null ? 1f : 0f);
		if (target != null && target != shown) {
			shown = target;
			health.set(Float.NaN);
			ghost = -1;
			lastHurtTime = 0;
		}
		if (shown == null || a <= 0.02f) return;
		LivingEntity e = shown;
		Theme t = ui.theme;

		float max = Math.max(1f, e.getMaxHealth());
		float hp = Math.max(0f, e.getHealth()) / max;
		float smooth = health.update(ui, hp);
		// the ghost bar waits a moment, then drains down to the real health
		if (ghost < 0 || hp > ghost) ghost = hp;
		if (e.hurtTime > lastHurtTime) {
			hitAt = System.nanoTime();
			ghostHeldAt = hitAt;
		}
		lastHurtTime = e.hurtTime;
		float sinceHit = (System.nanoTime() - hitAt) / 1e9f;
		if ((System.nanoTime() - ghostHeldAt) / 1e9f > GHOST_HOLD_S) ghost += (hp - ghost) * ui.animStep(4f);

		float pop = t.motion() ? Ease.OUT_BACK.apply(a) : a;
		float shake = t.motion() && sinceHit < 0.3f ? (float) Math.sin(sinceHit * 90) * 3 * (1 - sinceHit / 0.3f) : 0f;
		var g = ui.g;
		g.pose().pushMatrix();
		g.pose().translate(x + W / 2f + shake, y + H / 2f);
		g.pose().scale(0.85f + 0.15f * pop, 0.85f + 0.15f * pop);
		g.pose().translate(-W / 2f, -H / 2f);
		Draw.pushAlpha(Math.min(1f, a * 1.4f));
		try {
			Draw.shadow(g, t, 0, 0, W, H);
			Draw.glow(g, 0, 0, W, H, t.accent, Math.max(0.3f, t.glow.floatValue()), 4);
			Draw.shapeFill(g, 0, 0, W, H, t.corners.get().insets, t.panelTop, t.panelBottom);
			Draw.shapeOutline(g, 0, 0, W, H, t.corners.get().insets, t.border);

			// face (players) or a lettered tile
			if (e instanceof AbstractClientPlayer player) {
				if (Draw.alpha() > 0.6f) PlayerFaceExtractor.extractRenderState(g, player.getSkin(), 5, 5, 24);
			} else {
				Draw.box(g, 5, 5, 24, 24, t.control, t.border);
				String type = e.getType().getDescription().getString();
				String letter = type.isEmpty() ? "?" : type.substring(0, 1).toUpperCase(Locale.ROOT);
				Draw.textScaled(g, letter, 12, 10, t.accent, 1.5f);
			}
			float flash = sinceHit < 0.25f ? 1 - sinceHit / 0.25f : 0f;
			if (flash > 0) Draw.rect(g, 5, 5, 24, 24, Colors.withAlpha(0xFF3030, Math.round(0x90 * flash)));

			String name = Draw.trim(e.getName().getString(), W - 40);
			Draw.text(g, name, 34, 5, t.strongText());
			String hpText = String.format(Locale.ROOT, "%.1f", e.getHealth()) + " / " + Math.round(max);
			Draw.text(g, hpText, 34, 15, t.dimText);
			if (e.getAbsorptionAmount() > 0) {
				Draw.textRight(g, "+" + Math.round(e.getAbsorptionAmount()), W - 5, 15, 0xFFF2D24B);
			}

			int bx = 34, by = 26, bw = W - 39, bh = 5;
			Draw.rect(g, bx, by, bw, bh, Colors.withAlpha(0, 0x80));
			Draw.rect(g, bx, by, Math.round(bw * Motion.clamp01(ghost)), bh, Colors.withAlpha(0xFFFFFF, 0x70));
			int fill = Math.round(bw * Motion.clamp01(smooth));
			int low = Colors.lerp(0xFFE0342C, t.accent, Motion.clamp01(hp * 2));
			Draw.gradientH(g, bx, by, fill, bh, 0, smooth * 0.4f, pos -> Colors.lerp(low, t.accentAt(pos), Motion.clamp01(hp * 1.5f)));
			Draw.rect(g, bx, by, fill, 1, Colors.withAlpha(0xFFFFFF, 0x50));
			for (int i = 1; i < 10; i++) Draw.rect(g, bx + bw * i / 10, by + 1, 1, bh - 1, Colors.withAlpha(0, 0x50));
			if (flash > 0) Draw.rect(g, bx, by, fill, bh, Colors.withAlpha(0xFFFFFF, Math.round(0x80 * flash)));
		} finally {
			Draw.popAlpha();
			g.pose().popMatrix();
		}
	}
}
