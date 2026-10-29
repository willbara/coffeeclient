package dev.coffeeclient.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.coffeeclient.config.ConfigManager;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.screen.HudEditorScreen;
import dev.coffeeclient.hud.elements.DirectionElement;
import dev.coffeeclient.hud.elements.KeyboardElement;
import dev.coffeeclient.hud.elements.LagNotifierElement;
import dev.coffeeclient.hud.elements.ModuleListElement;
import dev.coffeeclient.hud.elements.NetherCoordsElement;
import dev.coffeeclient.hud.elements.PositionElement;
import dev.coffeeclient.hud.elements.SpeedElement;
import dev.coffeeclient.hud.elements.TextElement;
import dev.coffeeclient.hud.elements.WatermarkElement;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.render.EffectTimers;
import dev.coffeeclient.util.WorldProjector;
import dev.coffeeclient.module.modules.client.Notifications;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.ErrorGuard;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Owns the HUD elements, their saved layout and toast notifications. */
public final class HudManager {
	private final List<HudElement> elements = new ArrayList<>(List.of(
		new DirectionElement(), new NetherCoordsElement(), new PositionElement(), new SpeedElement(),
		new TextElement(), new KeyboardElement(), new LagNotifierElement(), new ModuleListElement(),
		new WatermarkElement()));
	private final WorldProjector projector = new WorldProjector();

	public HudManager() {
		for (Module m : CoffeeClient.modules().all()) elements.addAll(m.hudElements());
	}
	private final List<Toast> toasts = new ArrayList<>();

	private record Toast(String text, int color, long createdAt) {
	}

	/** Smoothed y of each toast, so the stack glides when one leaves. */
	private final java.util.Map<Toast, Float> toastY = new java.util.IdentityHashMap<>();

	public void init(EventBus bus) {
		HudStats.init(bus);
		bus.subscribe(ModuleManager.ModuleToggled.class, e -> {
			if (e.silent()) return;
			Notifications n = CoffeeClient.modules().get(Notifications.class);
			if (n != null && n.isEnabled() && n.toastNotifications.get() && !(e.module() instanceof Notifications)) {
				toast(e.module().name() + (e.module().isEnabled() ? " enabled" : " disabled"),
					e.module().isEnabled() ? CoffeeClient.theme().accent : CoffeeClient.theme().dimText);
			}
		});
	}

	public List<HudElement> elements() {
		return elements;
	}

	public void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.hud.isHidden()) return;
		Draw.resetAlpha();
		Ui ui = new Ui(g, -1, -1);
		ui.hoverAllowed = false;
		if (projector.begin(g.guiWidth(), g.guiHeight())) {
			boolean profiling = dev.coffeeclient.module.ModuleProfiler.active();
			for (Module m : CoffeeClient.modules().all()) {
				if (!m.isEnabled()) continue;
				long start = profiling ? System.nanoTime() : 0;
				try {
					m.renderOverlay(ui, projector);
				} catch (Throwable t) {
					ErrorGuard.report(m.overlayKey, t);
				}
				if (profiling) dev.coffeeclient.module.ModuleProfiler.record(m, dev.coffeeclient.module.ModuleProfiler.Phase.RENDER, System.nanoTime() - start);
			}
		}
		if (!(mc.gui.screen() instanceof HudEditorScreen)) {
			int w = g.guiWidth(), h = g.guiHeight();
			float scale = CoffeeClient.options().interfaceScale.floatValue();
			layoutAuto(w, h, false);
			for (HudElement e : elements) {
				if (!e.isEnabled() || !e.shouldRender()) continue;
				try {
					e.renderScaled(ui, e.x(w, scale), e.y(w, h, scale), scale, false);
				} catch (Throwable t) {
					ErrorGuard.report(e.hudKey, t);
				}
			}
		}
		ErrorGuard.run("hud:toasts", () -> renderToasts(ui));
	}

	/**
	 * Stack unplaced module panels in a column under the module list (right side), wrapping into further
	 * columns to the left when the screen runs out. Only visible panels take space, so it reflows live.
	 */
	public void layoutAuto(int w, int h, boolean preview) {
		float scale = CoffeeClient.options().interfaceScale.floatValue();
		int margin = Math.round(HudElement.MARGIN * scale);
		int gap = Math.max(1, Math.round(HudElement.GRID * scale));
		int top = margin;
		int listLeft = w - margin;
		for (HudElement e : elements) {
			if (e instanceof ModuleListElement ml && ml.isEnabled() && !ml.isPlaced()) {
				top = ml.y(w, h, scale) + ml.scaledHeight(scale) + gap;
				listLeft = ml.x(w, scale);
				var player = Minecraft.getInstance().player;
				if (player != null && !player.getActiveEffects().isEmpty() && !EffectTimers.hidesVanillaIcons()) {
					top += Math.round(26 * scale);
				}
			}
		}
		// elements with their own spot (placed, or fixed defaults) that the column must flow around
		List<int[]> taken = new ArrayList<>();
		for (HudElement e : elements) {
			if (e.autoStacks() && !e.isPlaced() || !e.isEnabled()) continue;
			if (preview ? !e.showInEditor() : !e.shouldRender()) continue;
			taken.add(new int[] {e.x(w, scale), e.y(w, h, scale), e.scaledWidth(scale), e.scaledHeight(scale)});
		}
		int columnRight = w - margin;
		int cursor = top;
		int columnWidth = 0;
		boolean firstColumn = true;
		for (HudElement e : elements) {
			if (!e.autoStacks() || e.isPlaced() || !e.isEnabled()) continue;
			if (preview ? !e.showInEditor() : !e.shouldRender()) continue;
			int ew = e.scaledWidth(scale), eh = e.scaledHeight(scale);
			// shortcut: gives up after 32 moves and overlaps, fine for a few dozen panels
			for (int tries = 0; tries < 32; tries++) {
				if (cursor + eh > h - margin && (cursor > top || firstColumn)) {
					// next column: left of the previous one (and of the module list), from the top of the screen
					columnRight = Math.min(columnRight - (columnWidth > 0 ? columnWidth : ew), firstColumn ? listLeft : Integer.MAX_VALUE) - gap;
					cursor = margin;
					top = margin;
					columnWidth = 0;
					firstColumn = false;
				}
				int[] hit = overlap(columnRight - ew, cursor, ew, eh, taken);
				if (hit == null) break;
				cursor = hit[1] + hit[3] + gap;
			}
			e.setAuto(columnRight - ew, cursor);
			cursor += eh + gap;
			columnWidth = Math.max(columnWidth, ew);
		}
	}

	private static int[] overlap(int x, int y, int w, int h, List<int[]> rects) {
		for (int[] r : rects) {
			if (x < r[0] + r[2] && r[0] < x + w && y < r[1] + r[3] && r[1] < y + h) return r;
		}
		return null;
	}

	public void toast(String text, int color) {
		toasts.add(new Toast(text, color, System.currentTimeMillis()));
		while (toasts.size() > 5) toastY.remove(toasts.removeFirst());
	}

	private void renderToasts(Ui ui) {
		long now = System.currentTimeMillis();
		long life = Math.round(CoffeeClient.modules().get(Notifications.class).duration.get() * 1000);
		int w = ui.g.guiWidth(), h = ui.g.guiHeight();
		var theme = ui.theme;
		for (Iterator<Toast> it = toasts.iterator(); it.hasNext(); ) {
			Toast t = it.next();
			if (now - t.createdAt() > life + 300) {
				it.remove();
				toastY.remove(t);
			}
		}
		int target = h - 54;
		for (int i = toasts.size() - 1; i >= 0; i--) {
			Toast t = toasts.get(i);
			long age = now - t.createdAt();
			float in = Math.min(1f, age / 260f), out = Math.min(1f, Math.max(0f, (life + 300 - age) / 300f));
			float a = Math.min(Math.min(1f, age / 120f), out);
			float y = toastY.getOrDefault(t, (float) target);
			y += (target - y) * ui.animStep(12f);
			toastY.put(t, y);
			int iy = Math.round(y);
			int tw = Draw.width(t.text()) + (theme.glass() ? 18 : 14);
			// slides in with a slight overshoot, slides out to the right
			float slide = theme.motion() ? Ease.OUT_BACK.apply(in) : in;
			int x = w - tw - 4 + Math.round((1 - slide) * (tw + 8)) + Math.round((1 - out) * 30);
			int accent = Colors.withAlpha(t.color(), 255);
			Draw.pushAlpha(a);
			Draw.shadow(ui.g, theme, x, iy, tw, 15);
			Draw.box(ui.g, x, iy, tw, 15, theme.panel, theme.border);
			float left = 1f - Math.min(1f, age / (float) Math.max(1, life));
			if (theme.glass()) {
				// a glowing status light, and the lifetime as a thin pill draining under the text
				dev.coffeeclient.gui.fx.Shapes.glowDot(ui.g, x + 6.5f, iy + 7.5f, 2, 3, Colors.withAlpha(accent, 0x90));
				dev.coffeeclient.gui.fx.Shapes.disc(ui.g, x + 6.5f, iy + 7.5f, 2, accent);
				dev.coffeeclient.gui.fx.Shapes.roundRect(ui.g, x + 10, iy + 12, Math.round((tw - 18) * left), 1, 0, Colors.withAlpha(accent, 0x80));
			} else {
				Draw.gradientV(ui.g, x + 1, iy + 1, 2, 13, accent, Colors.shade(accent, 0.6f));
				// lifetime bar drains along the bottom
				Draw.rect(ui.g, x + 3, iy + 13, Math.round((tw - 4) * left), 1, Colors.withAlpha(accent, 0xA0));
			}
			Draw.text(ui.g, t.text(), x + (theme.glass() ? 11 : 8), iy + 3, theme.text);
			Draw.popAlpha();
			target -= 17;
		}
	}

	// ---- layout persistence ----

	public JsonObject toJson() {
		JsonObject root = new JsonObject();
		JsonObject positions = new JsonObject();
		for (HudElement e : elements) {
			int[] p = e.savedPosition();
			if (p == null) continue;
			JsonArray arr = new JsonArray();
			for (int v : p) arr.add(v);
			positions.add(e.id(), arr);
		}
		root.add("positions", positions);
		return root;
	}

	public void fromJson(JsonObject json) {
		JsonElement positions = json.get("positions");
		if (positions == null || !positions.isJsonObject()) return;
		for (HudElement e : elements) {
			JsonElement p = positions.getAsJsonObject().get(e.id());
			// The old Coordinates element became Direction; keep its place in an existing layout.
			if (p == null && e.id().equals("direction")) p = positions.getAsJsonObject().get("coordinates");
			try {
				// [anchorX, anchorY, offsetX, offsetY]; older 2-value layouts fall back to defaults
				if (p != null && p.isJsonArray() && p.getAsJsonArray().size() == 4) {
					JsonArray a = p.getAsJsonArray();
					e.restorePosition(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt());
				}
			} catch (RuntimeException ex) {
				e.resetPosition();
			}
		}
	}

	public void resetLayout() {
		elements.forEach(HudElement::resetPosition);
		ConfigManager.markDirty();
	}
}
