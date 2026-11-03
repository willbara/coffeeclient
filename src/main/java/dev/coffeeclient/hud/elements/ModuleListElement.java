package dev.coffeeclient.hud.elements;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.client.HudModule;
import dev.coffeeclient.module.modules.render.EffectTimers;
import dev.coffeeclient.util.Colors;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Enabled modules, longest first, with an accent edge bar and a slide-in animation. */
public final class ModuleListElement extends HudElement {
	private static final int LINE_H = 10;
	private static final int INFO_GAP = 4;
	/** width(), height() and render() all run in one frame; reuse one list across them. */
	private static final long CACHE_NS = 2_000_000L;

	private record Row(Module module, String name, String info, int nameWidth, int width) {
	}

	private final Map<Module, Float> slide = new HashMap<>();
	private List<Row> rows;
	private long rowsAt;
	/** Lines that fit on screen, measured last frame; the rest fold into a "+N more" line. */
	private int maxLines = Integer.MAX_VALUE;

	public ModuleListElement() {
		super("module_list", "Module list");
	}

	private HudModule hud() {
		return CoffeeClient.modules().get(HudModule.class);
	}

	@Override
	public boolean isEnabled() {
		return hud().isEnabled() && hud().moduleList.get();
	}

	@Override
	public void toggle() {
		hud().moduleList.toggle();
	}

	private List<Row> rows() {
		long now = System.nanoTime();
		if (rows != null && now - rowsAt < CACHE_NS) return rows;
		rowsAt = now;
		HudModule hud = hud();
		boolean showInfo = hud.moduleInfo.get();
		List<Row> out = new ArrayList<>();
		for (Module m : CoffeeClient.modules().all()) {
			if (!m.listable() || !m.shownInList().get() || !hud.listsCategory(m.category())) continue;
			if (!m.isEnabled() && slide.getOrDefault(m, 0f) <= 0.02f) continue;
			String info = showInfo ? m.safeInfoString() : null;
			if (info != null && hud.infoBrackets.get()) info = "[" + info + "]";
			String name = hud.listLowercase.get() ? m.name().toLowerCase(java.util.Locale.ROOT) : m.name();
			int nameWidth = Draw.width(name);
			int width = info == null ? nameWidth : nameWidth + INFO_GAP + Draw.width(info);
			out.add(new Row(m, name, info, nameWidth, width));
		}
		out.sort(hud.listSort.get() == HudModule.ListSort.ALPHABETICAL
			? Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER)
			: Comparator.comparingInt(Row::width).reversed());
		rows = out;
		return out;
	}

	@Override
	public int width() {
		int w = 60;
		for (Row r : rows()) w = Math.max(w, r.width + 8);
		return w;
	}

	@Override
	public int height() {
		return Math.max(LINE_H, Math.min(rows().size(), maxLines) * LINE_H);
	}

	@Override
	protected int[] defaultPosition(int screenW, int screenH) {
		return new int[] {screenW - width() - MARGIN, MARGIN};
	}

	@Override
	public void render(Ui ui, int x, int y, boolean preview) {
		Theme t = ui.theme;
		boolean right = onRightSide(ui.g.guiWidth());
		boolean bg = hud().backgrounds.get();
		// keep clear of vanilla status-effect icons when docked at the top-right
		Minecraft mc = Minecraft.getInstance();
		boolean vanillaEffects = mc.player != null && !mc.player.getActiveEffects().isEmpty() && !EffectTimers.hidesVanillaIcons();
		float scale = CoffeeClient.options().interfaceScale.floatValue();
		int screenH = ui.g.guiHeight();
		// drawn at a local origin, so ask where the element really sits
		boolean shift = !preview && right && vanillaEffects && y(ui.g.guiWidth(), screenH, scale) < 4;
		if (shift) y += 26;
		maxLines = Math.max(1, (int) ((screenH / Math.max(0.01f, scale) - MARGIN * 2 - (shift ? 26 : 0)) / LINE_H));
		List<Row> list = rows();
		int shown = list.size() > maxLines ? maxLines - 1 : list.size();
		if (list.isEmpty() && preview) {
			Draw.text(ui.g, "(no modules on)", x + 2, y + 1, t.dimText);
			return;
		}
		int w = width();
		int ly = y;
		HudModule.ListStyle style = hud().listStyle.get();
		HudModule.ListColors colors = hud().listColors.get();
		HudModule.ListAnimation anim = hud().listAnimation.get();
		boolean rainbow = hud().listRainbow.get();
		double hue = System.nanoTime() / 1e9 * hud().rainbowSpeed.get();
		for (int i = 0; i < list.size(); i++) {
			Row row = list.get(i);
			float target = row.module.isEnabled() ? 1f : 0f;
			float s = slide.getOrDefault(row.module, 0f);
			s += (target - s) * ui.animStep(10f);
			if (anim == HudModule.ListAnimation.NONE || Math.abs(target - s) < 0.002f) s = target;
			slide.put(row.module, s);
			if (i >= shown) continue;
			// rows slide in with a slight overshoot and glide up as others leave
			float e = t.motion() ? Ease.OUT_BACK.apply(s) : s;
			int tw = row.width + 6;
			int offset = anim == HudModule.ListAnimation.SLIDE ? Math.round((1 - e) * (tw + 4)) : 0;
			int lx = right ? x + w - tw + offset : x - offset;
			float alpha = Math.max(0.05f, s);
			float pos = i * 0.08f;
			double h = hue + i * hud().rainbowSpread.get();
			int accent = rainbow
				? Colors.fromHsv((float) (h - Math.floor(h)) * 360, hud().rainbowSaturation.floatValue(), hud().rainbowBrightness.floatValue(), 0xFF)
				: t.accentAt(pos);
			int nameColor = switch (colors) {
				case CASCADE -> accent;
				case FADE -> Colors.lerp(t.text, rainbow ? accent : t.accent, i / (float) Math.max(1, list.size()) * 0.6f);
				case STATIC -> hud().listColor.argb();
			};
			int textColor = Colors.fade(nameColor, alpha);
			int bgColor = Colors.fade(hud().backgroundColor.argb(), s * 0.85f);
			switch (style) {
				case LINE -> {
					if (bg) Draw.rect(ui.g, lx, ly, tw, LINE_H, bgColor);
					Draw.rect(ui.g, right ? lx + tw : lx - 1, ly, 1, LINE_H, Colors.fade(accent, alpha));
				}
				case BOX -> {
					if (bg) Draw.rect(ui.g, lx, ly, tw, LINE_H, bgColor);
					Draw.rect(ui.g, lx, ly, 1, LINE_H, Colors.fade(accent, alpha));
					Draw.rect(ui.g, lx + tw - 1, ly, 1, LINE_H, Colors.fade(accent, alpha));
					// close off the box where this row is wider than its neighbours (any sort order)
					int next = i + 1 < shown ? list.get(i + 1).width + 6 : 0;
					int prev = i > 0 ? list.get(i - 1).width + 6 : 0;
					if (tw > next) Draw.rect(ui.g, right ? lx : lx + next, ly + LINE_H - 1, tw - next, 1, Colors.fade(accent, alpha));
					if (tw > prev) Draw.rect(ui.g, right ? lx : lx + prev, ly, tw - prev, 1, Colors.fade(accent, alpha));
				}
				case MINIMAL -> {
				}
				case PILL -> {
					// each module a rounded tag, with a soft accent rim fading down it
					int r = (LINE_H - 1) / 2;
					if (bg) dev.coffeeclient.gui.fx.Shapes.roundRect(ui.g, lx, ly, tw, LINE_H - 1, r, bgColor);
					dev.coffeeclient.gui.fx.Shapes.roundOutline(ui.g, lx, ly, tw, LINE_H - 1, r, Colors.fade(accent, alpha * 0.7f), Colors.fade(accent, alpha * 0.15f));
				}
				case GLOW -> {
					if (bg) Draw.rect(ui.g, lx, ly, tw, LINE_H, bgColor);
					Draw.glow(ui.g, lx, ly, tw, LINE_H, accent, alpha, 3);
					Draw.rect(ui.g, right ? lx + tw - 1 : lx, ly, 1, LINE_H, Colors.fade(accent, alpha));
				}
			}
			int tx = lx + (style == HudModule.ListStyle.PILL ? 3 : right ? 2 : 4);
			Draw.text(ui.g, row.name, tx, ly + 1, textColor);
			if (row.info != null) Draw.text(ui.g, row.info, tx + row.nameWidth + INFO_GAP, ly + 1, Colors.fade(t.dimText, alpha));
			ly += Math.round(LINE_H * Ease.OUT_CUBIC.apply(s));
		}
		if (shown < list.size()) {
			String more = "+" + (list.size() - shown) + " more";
			int tw = Draw.width(more) + 6;
			int lx = right ? x + w - tw : x;
			if (bg) Draw.rect(ui.g, lx, ly, tw, LINE_H, Colors.fade(hud().backgroundColor.argb(), 0.85f));
			Draw.text(ui.g, more, lx + (right ? 2 : 4), ly + 1, t.dimText);
		}
	}
}
