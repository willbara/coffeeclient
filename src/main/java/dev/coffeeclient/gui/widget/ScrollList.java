package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.fx.Shapes;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;

/**
 * Vertical list of fixed-height children inside a clipped viewport, with eased wheel scrolling and a
 * slim draggable scrollbar. Children keep their own heights; the list assigns x/y/width.
 */
public class ScrollList extends Container {
	private static final int BAR_W = 3;

	private double scroll;
	private double target;
	private int padding = 2;
	private boolean draggingBar;
	private double dragOffset;

	public ScrollList padding(int padding) {
		this.padding = padding;
		return this;
	}

	/** The padding, plus room to clear a Glass card's rounded corners. */
	private int pad() {
		return padding + (dev.coffeeclient.core.CoffeeClient.theme().glass() ? 2 : 0);
	}

	public int contentHeight() {
		int h = pad() * 2;
		for (Widget c : children) if (c.visible) h += c.height;
		return h;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight() - height);
	}

	public void scrollToTop() {
		scroll = target = 0;
	}

	/** Lay children out at the current scroll offset. */
	public void layout() {
		target = Math.max(0, Math.min(maxScroll(), target));
		scroll = Math.max(0, Math.min(maxScroll(), scroll));
		int pad = pad();
		int cy = y + pad - (int) Math.round(scroll);
		int cw = width - pad * 2 - (maxScroll() > 0 ? BAR_W + 3 : 0);
		for (Widget c : children) {
			if (!c.visible) continue;
			c.x = x + pad;
			c.y = cy;
			c.width = cw;
			if (c instanceof Relayout r) r.relayout();
			cy += c.height;
		}
	}

	/** Children that position their own sub-widgets after being moved. */
	public interface Relayout {
		void relayout();
	}

	private boolean inViewport(double mx, double my) {
		return mx >= x && mx < x + width && my >= y && my < y + height;
	}

	@Override
	protected void draw(Ui ui) {
		scroll += (target - scroll) * ui.animStep(18f);
		if (Math.abs(target - scroll) < 0.3) scroll = target;
		layout();
		boolean prevHover = ui.hoverAllowed;
		ui.hoverAllowed = prevHover && inViewport(ui.mouseX, ui.mouseY);
		ui.g.enableScissor(x, y, x + width, y + height);
		for (Widget c : children) {
			if (!c.visible || c.y + c.height < y || c.y > y + height) continue;
			c.render(ui);
		}
		ui.g.disableScissor();
		ui.hoverAllowed = prevHover;
		drawScrollbar(ui);
	}

	private void drawScrollbar(Ui ui) {
		int max = maxScroll();
		if (max <= 0) return;
		Theme t = ui.theme;
		int bx = x + width - BAR_W - 1;
		int thumbH = Math.max(12, (int) ((long) height * height / contentHeight()));
		int thumbY = y + 1 + (int) ((height - 2 - thumbH) * (scroll / max));
		boolean over = ui.mouseX >= bx - 2 && ui.mouseX <= x + width && ui.mouseY >= thumbY && ui.mouseY <= thumbY + thumbH;
		if (t.glass()) {
			// a slim pill thumb on a barely-there track
			Shapes.roundRect(ui.g, bx, y + 3, BAR_W, height - 6, 1, Colors.withAlpha(0xFFFFFF, 0x0A));
			Shapes.roundRect(ui.g, bx, thumbY + 2, BAR_W, thumbH - 4, 1, draggingBar || over ? t.accent : Colors.withAlpha(0xFFFFFF, 0x50));
			return;
		}
		Draw.rect(ui.g, bx, y + 1, BAR_W, height - 2, Colors.withAlpha(t.control, 0xA0));
		Draw.rect(ui.g, bx, thumbY, BAR_W, thumbH, draggingBar || over ? t.accent : Colors.lerp(t.border, t.accent, 0.5f));
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!inViewport(mx, my)) return false;
		int max = maxScroll();
		if (max > 0 && mx >= x + width - BAR_W - 3) {
			int thumbH = Math.max(12, (int) ((long) height * height / contentHeight()));
			int thumbY = y + 1 + (int) ((height - 2 - thumbH) * (scroll / max));
			draggingBar = true;
			dragOffset = (my >= thumbY && my <= thumbY + thumbH) ? my - thumbY : thumbH / 2.0;
			mouseDragged(mx, my, button, 0, 0);
			return true;
		}
		layout();
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (draggingBar) {
			int max = maxScroll();
			int thumbH = Math.max(12, (int) ((long) height * height / contentHeight()));
			double f = (my - dragOffset - y - 1) / Math.max(1, height - 2 - thumbH);
			target = scroll = Math.max(0, Math.min(max, f * max));
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		draggingBar = false;
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!inViewport(mx, my)) return false;
		// let a hovered child (slider, stepper) consume the wheel first
		if (super.mouseScrolled(mx, my, amount)) return true;
		target = Math.max(0, Math.min(maxScroll(), target - amount * 24));
		return true;
	}
}
