package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Anim;
import dev.coffeeclient.gui.fx.FxTextures;
import dev.coffeeclient.gui.fx.UiSounds;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.util.Colors;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A ClickGUI column: gleaming header (drag to move, right-click to collapse) over a list of
 * {@link ModuleRow}s that scrolls if it runs past the bottom of the screen.
 */
public class CategoryColumn extends Widget {
	public static final int HEADER_H = 16;

	private final Category category;
	private final List<ModuleRow> rows = new ArrayList<>();
	private final int index;
	private Predicate<Module> filter = m -> true;
	private int maxBottom = Integer.MAX_VALUE;
	private boolean collapsed;
	private float open = 1;
	private double scroll;
	private int contentHeight;
	private boolean dragging;
	private final Anim lift = new Anim(14f);
	private double grabX, grabY;
	private Runnable onMoved = () -> {
	};

	public CategoryColumn(Category category, List<Module> modules, int index) {
		this.category = category;
		this.index = index;
		for (int i = 0; i < modules.size(); i++) rows.add(new ModuleRow(modules.get(i), i + index * 3));
	}

	public Category category() {
		return category;
	}

	public List<ModuleRow> rows() {
		return rows;
	}

	public CategoryColumn filter(Predicate<Module> filter) {
		this.filter = filter;
		return this;
	}

	public CategoryColumn maxBottom(int maxBottom) {
		this.maxBottom = maxBottom;
		return this;
	}

	public CategoryColumn onMoved(Runnable onMoved) {
		this.onMoved = onMoved;
		return this;
	}

	public boolean collapsed() {
		return collapsed;
	}

	public void setCollapsed(boolean collapsed) {
		this.collapsed = collapsed;
		this.open = collapsed ? 0 : 1;
	}

	public boolean isDragging() {
		return dragging;
	}

	private int bodyTop() {
		return y + HEADER_H;
	}

	private int bodyHeight() {
		int available = Math.max(ModuleRow.ROW_H, maxBottom - bodyTop() - bottomPad());
		return Math.round(Math.min(contentHeight, available) * open);
	}

	/** Space under the rows: the frame line, or room above a Glass card's rounded bottom. */
	private static int bottomPad() {
		return dev.coffeeclient.core.CoffeeClient.theme().glass() ? 4 : 1;
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		open += ((collapsed ? 0f : 1f) - open) * ui.animStep(14f);
		if (Math.abs((collapsed ? 0f : 1f) - open) < 0.01f) open = collapsed ? 0 : 1;

		// measure
		int total = 0;
		List<ModuleRow> visible = new ArrayList<>();
		for (ModuleRow row : rows) {
			int h = row.animate(ui);
			if (!filter.test(row.module())) continue;
			row.height = h;
			total += h;
			visible.add(row);
		}
		contentHeight = total;
		int bodyH = bodyHeight();
		scroll = Math.max(0, Math.min(Math.max(0, contentHeight - bodyH), scroll));
		height = HEADER_H + bodyH + (bodyH > 0 ? bottomPad() : 0);

		float l = lift.update(ui, dragging ? 1f : 0f);
		if (t.glass()) {
			drawGlass(ui, visible, bodyH, l);
			return;
		}
		Draw.shadow(ui.g, t, x, y, width, height);
		Draw.glow(ui.g, x, y, width, height, t.accentAt(index * 0.15f), t.glow.floatValue() * (0.5f + l), 4 + Math.round(l * 3));
		if (l > 0.01f) Draw.rect(ui.g, x + 3, y + height, width - 2, Math.round(l * 3), Colors.withAlpha(0, Math.round(0x40 * l)));

		// body
		if (bodyH > 0) {
			Draw.rect(ui.g, x, bodyTop(), width, bodyH + 1, t.panel);
			FxTextures.tile(ui.g, t.texture.get(), x + 1, bodyTop(), width - 2, bodyH, t.textureStrength.floatValue() * 0.5f);
			ui.g.enableScissor(x, bodyTop(), x + width, bodyTop() + bodyH);
			boolean prevHover = ui.hoverAllowed;
			ui.hoverAllowed = prevHover && ui.mouseY >= bodyTop() && ui.mouseY < bodyTop() + bodyH;
			int cy = bodyTop() - (int) scroll;
			for (ModuleRow row : visible) {
				row.x = x + 1;
				row.y = cy;
				row.width = width - 2;
				if (cy + row.height >= bodyTop() && cy <= bodyTop() + bodyH) row.render(ui);
				cy += row.height;
			}
			if (visible.isEmpty()) Draw.textCentered(ui.g, "No matches", x + width / 2, bodyTop() + 3, t.dimText);
			ui.hoverAllowed = prevHover;
			ui.g.disableScissor();
			drawScrollFades(ui, x + 1, width - 2, bodyH, t.panel);
			if (contentHeight > bodyH && open >= 1) drawScrollbar(ui, bodyH);
		}

		drawHeader(ui);
		Draw.border(ui.g, x, y, width, height, Colors.lerp(t.border, t.accent, dragging ? 1f : 0.35f));
	}

	/** Glass card: one frosted surface for header and rows, a plain title and a rim that lights up while held. */
	private void drawGlass(Ui ui, List<ModuleRow> visible, int bodyH, float lifted) {
		Theme t = ui.theme;
		int r = Draw.GLASS_RADIUS;
		Draw.glassBody(ui.g, t, x, y - Math.round(lifted * 2), width, height, r);
		ui.g.pose().pushMatrix();
		ui.g.pose().translate(0, -Math.round(lifted * 2));
		try {
			if (bodyH > 0) {
				ui.g.enableScissor(x, bodyTop(), x + width, bodyTop() + bodyH);
				boolean prevHover = ui.hoverAllowed;
				ui.hoverAllowed = prevHover && ui.mouseY >= bodyTop() && ui.mouseY < bodyTop() + bodyH;
				int cy = bodyTop() - (int) scroll;
				// rows make way for the scrollbar instead of running under it
				int rowW = width - 2 - (contentHeight > bodyH ? 3 : 0);
				for (ModuleRow row : visible) {
					row.x = x + 1;
					row.y = cy;
					row.width = rowW;
					if (cy + row.height >= bodyTop() && cy <= bodyTop() + bodyH) row.render(ui);
					cy += row.height;
				}
				if (visible.isEmpty()) Draw.textCentered(ui.g, "No matches", x + width / 2, bodyTop() + 3, t.dimText);
				ui.hoverAllowed = prevHover;
				ui.g.disableScissor();
				drawScrollFades(ui, x + 3, rowW - 4, bodyH, Colors.lerp(t.panelTop, t.panelBottom, 0.7f));
				if (contentHeight > bodyH && open >= 1) drawScrollbar(ui, bodyH);
				Panel.hairline(ui, x + 6, bodyTop() - 1, width - 12);
			}
			// title on the left; the enabled count lights up in the accent
			Draw.text(ui.g, category.displayName(), x + 7, y + 4, t.strongText());
			long on = rows.stream().filter(row -> row.module().isEnabled()).count();
			String total = "/" + rows.size();
			int right = x + width - 7;
			Draw.textRight(ui.g, total, right, y + 4, t.dimText);
			Draw.textRight(ui.g, Long.toString(on), right - Draw.width(total), y + 4, on > 0 ? t.accent : t.dimText);
			if (ui.hoverAllowed && onHeader(ui.mouseX, ui.mouseY) && !dragging) {
				ui.tooltip(category.displayName(), "Drag to move · Right-click to " + (collapsed ? "expand" : "collapse"));
			}
		} finally {
			ui.g.pose().popMatrix();
		}
		boolean overCard = ui.hoverAllowed && ui.mouseX >= x && ui.mouseX < x + width && ui.mouseY >= y && ui.mouseY < y + height;
		float lit = glassLit.update(ui, dragging ? 1f : overCard ? 0.35f : 0f);
		Draw.glassRim(ui.g, t, x, y - Math.round(lifted * 2), width, height, r, lit);
	}

	private final Anim glassLit = new Anim(12f);

	/**
	 * Soft fades at the top and bottom of the rows while more of the list is scrolled out of view, so a
	 * clipped column reads as scrollable.
	 */
	private void drawScrollFades(Ui ui, int fx, int fw, int bodyH, int color) {
		int max = contentHeight - bodyH;
		if (max <= 0 || bodyH < 24) return;
		int fade = Math.min(10, bodyH / 4);
		int clear = Colors.withAlpha(color, 0);
		if (scroll > 0.5) Draw.gradientV(ui.g, fx, bodyTop(), fw, fade, color, clear);
		if (scroll < max - 0.5) Draw.gradientV(ui.g, fx, bodyTop() + bodyH - fade, fw, fade, clear, color);
	}

	private void drawHeader(Ui ui) {
		Theme t = ui.theme;
		int left = Colors.withAlpha(Colors.shade(t.accent, 0.60f), 0xF2);
		int right = Colors.withAlpha(Colors.shade(t.accent, 0.30f), 0xF2);
		if (t.gleam.get()) {
			// neighbouring columns continue one flow of the accent
			float from = index * 0.15f;
			Draw.gradientH(ui.g, x, y, width, HEADER_H, from, 0.15f,
				pos -> Colors.withAlpha(Colors.shade(t.accentAt(pos), 0.62f - 0.3f * (pos - from) / 0.15f), 0xF2));
			Draw.rect(ui.g, x, y, width, 1, Colors.withAlpha(0xFFFFFF, 0x30));
			if (t.gloss.get()) Draw.gloss(ui.g, x, y, width, HEADER_H);
			if (t.sheen.get()) Draw.sheen(ui.g, x, y, width, HEADER_H, Draw.sheenPhase(ui.timeMs, 5f, index / 7f), Colors.withAlpha(0xFFFFFF, 0x2C));
		} else {
			Draw.rect(ui.g, x, y, width, HEADER_H, Colors.lerp(left, right, 0.5f));
		}
		Draw.text(ui.g, category.displayName(), x + 4, y + 4, 0xFFFFFFFF);
		long on = rows.stream().filter(r -> r.module().isEnabled()).count();
		String count = on + "/" + rows.size();
		Draw.textRight(ui.g, count, x + width - 4, y + 4, Colors.withAlpha(0xFFFFFF, 0xA0));
		Draw.rect(ui.g, x, y + HEADER_H - 1, width, 1, Colors.withAlpha(0, 0x50));
		if (ui.hoverAllowed && ui.mouseX >= x && ui.mouseX < x + width && ui.mouseY >= y && ui.mouseY < y + HEADER_H && !dragging) {
			ui.tooltip(category.displayName(), "Drag to move · Right-click to " + (collapsed ? "expand" : "collapse"));
		}
	}

	private void drawScrollbar(Ui ui, int bodyH) {
		int max = contentHeight - bodyH;
		int thumbH = Math.max(8, bodyH * bodyH / contentHeight);
		int thumbY = bodyTop() + (int) ((bodyH - thumbH) * (scroll / max));
		if (ui.theme.glass()) dev.coffeeclient.gui.fx.Shapes.roundRect(ui.g, x + width - 4, thumbY + 1, 2, thumbH - 2, 1, ui.theme.accent(0x90));
		else Draw.rect(ui.g, x + width - 2, thumbY, 1, thumbH, ui.theme.accent(0xC0));
	}

	private boolean onHeader(double mx, double my) {
		return mx >= x && mx < x + width && my >= y && my < y + HEADER_H;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (onHeader(mx, my)) {
			if (button == RIGHT) {
				collapsed = !collapsed;
				UiSounds.click(collapsed ? 0.9f : 1.1f);
				onMoved.run();
			} else if (button == LEFT) {
				dragging = true;
				grabX = mx - x;
				grabY = my - y;
			}
			return true;
		}
		if (my >= bodyTop() + bodyHeight()) return false;
		for (ModuleRow row : rows) {
			if (filter.test(row.module()) && row.contains(mx, my)) return row.mouseClicked(mx, my, button);
		}
		return true;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (dragging) {
			x = (int) Math.round(mx - grabX);
			y = (int) Math.round(my - grabY);
			return true;
		}
		for (ModuleRow row : rows) if (row.mouseDragged(mx, my, button, dx, dy)) return true;
		return false;
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		boolean any = false;
		if (dragging) {
			dragging = false;
			onMoved.run();
			any = true;
		}
		for (ModuleRow row : rows) any |= row.mouseReleased(mx, my, button);
		return any;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		for (ModuleRow row : rows) {
			if (filter.test(row.module()) && row.contains(mx, my) && row.mouseScrolled(mx, my, amount)) return true;
		}
		scroll -= amount * ModuleRow.ROW_H * 2;
		return true;
	}

	/** Clamp inside the screen after a drag or resize. */
	public void clampTo(int screenW, int screenH) {
		x = Math.max(0, Math.min(screenW - width, x));
		y = Math.max(0, Math.min(screenH - HEADER_H, y));
	}
}
