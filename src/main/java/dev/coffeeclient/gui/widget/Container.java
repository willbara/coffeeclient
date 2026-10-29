package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.anim.Motion;

import java.util.ArrayList;
import java.util.List;

/** A widget that owns child widgets and forwards input to them (topmost first). */
public class Container extends Widget {
	protected final List<Widget> children = new ArrayList<>();
	/** Entrance progress for staggered children (1 = settled). */
	private float stagger = 1f;

	public <T extends Widget> T add(T widget) {
		children.add(widget);
		return widget;
	}

	public void remove(Widget widget) {
		children.remove(widget);
	}

	public void clear() {
		children.clear();
	}

	public List<Widget> children() {
		return children;
	}

	@Override
	protected void draw(Ui ui) {
		drawChildren(ui);
	}

	/** Children fade and rise in one after another as {@code progress} runs 0 → 1. */
	public void stagger(float progress) {
		this.stagger = progress;
	}

	protected void drawChildren(Ui ui) {
		if (stagger >= 1f || children.size() < 2) {
			for (Widget child : children) child.render(ui);
			return;
		}
		int n = children.size();
		float step = Math.min(0.07f, 0.45f / n);
		for (int i = 0; i < n; i++) {
			float local = Ease.OUT_CUBIC.apply(Motion.range(stagger, i * step, i * step + 0.5f));
			if (local <= 0.01f) continue;
			Draw.pushAlpha(local);
			ui.g.pose().pushMatrix();
			ui.g.pose().translate(0, (1 - local) * 8);
			try {
				children.get(i).render(ui);
			} finally {
				ui.g.pose().popMatrix();
				Draw.popAlpha();
			}
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		for (int i = children.size() - 1; i >= 0; i--) {
			Widget c = children.get(i);
			if (c.visible && c.contains(mx, my) && c.mouseClicked(mx, my, button)) return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		boolean handled = false;
		for (int i = children.size() - 1; i >= 0; i--) {
			Widget c = children.get(i);
			if (c.visible) handled |= c.mouseReleased(mx, my, button);
		}
		return handled;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		for (int i = children.size() - 1; i >= 0; i--) {
			Widget c = children.get(i);
			if (c.visible && c.mouseDragged(mx, my, button, dx, dy)) return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		for (int i = children.size() - 1; i >= 0; i--) {
			Widget c = children.get(i);
			if (c.visible && c.contains(mx, my) && c.mouseScrolled(mx, my, amount)) return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		for (Widget c : children) {
			if (c.visible && c.keyPressed(key, scancode, modifiers)) return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(String chars) {
		for (Widget c : children) {
			if (c.visible && c.charTyped(chars)) return true;
		}
		return false;
	}
}
