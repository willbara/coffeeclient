package dev.coffeeclient.gui.screen;

import dev.coffeeclient.config.ConfigManager;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.module.modules.client.HudModule;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.ErrorGuard;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.List;

/** Drag HUD elements around; right-click toggles one, R resets the layout. Positions save automatically. */
public class HudEditorScreen extends CoffeeScreen {
	private record PositionSnap(int x, int y, boolean snappedX, boolean snappedY) {
	}

	private static final int BAR_W = 236, BAR_H = 34;
	/** Bar offset from the screen centre; kept for the session like the other movable windows. */
	private static int barOffX, barOffY;

	private @Nullable HudElement dragging;
	private boolean draggingBar;
	private double grabX, grabY;
	/** Bar top-left in screen pixels; the bar itself is laid out in its own units, drawn at {@link #barScale}. */
	private int barX, barY, barW;
	private float barScale = 1f;

	public HudEditorScreen(@Nullable Screen parent) {
		super("HUD Editor", parent);
	}

	@Override
	protected @Nullable KeyMapping toggleKey() {
		return CoffeeClient.keybinds().hudEditor;
	}

	/** Always 1:1 so elements are edited exactly where the HUD draws them. */
	@Override
	protected float uiScale() {
		return 1f;
	}

	@Override
	protected void build() {
		// the screen stays 1:1 for the elements; only the bar follows the interface scale, like other windows
		barScale = super.uiScale();
		barW = Math.min((int) ((width - 20) / barScale), BAR_W);
		// buttons share the title row, so the help line below gets the full width
		root.add(new Button("Settings", () -> minecraft.gui.setScreen(
			new ModuleScreen(this, CoffeeClient.modules().get(HudModule.class))))).setBounds(barW - 166, 4, 50, 14);
		root.add(new Button("Reset layout", () -> CoffeeClient.hud().resetLayout())).setBounds(barW - 112, 4, 68, 14);
		root.add(new Button("Done", this::onClose)).setBounds(barW - 40, 4, 34, 14);
		placeBar(barOffX, barOffY);
	}

	private int barPixelW() {
		return Math.round(barW * barScale);
	}

	private int barPixelH() {
		return Math.round(BAR_H * barScale);
	}

	/** Position the bar at an offset from the screen centre, kept fully on screen. */
	private void placeBar(int offX, int offY) {
		int w = barPixelW(), h = barPixelH();
		barX = Math.max(0, Math.min(width - w, (width - w) / 2 + offX));
		barY = Math.max(0, Math.min(height - h, (height - h) / 2 + offY));
		barOffX = barX - (width - w) / 2;
		barOffY = barY - (height - h) / 2;
	}

	private boolean overBar(double mx, double my) {
		return mx >= barX && mx < barX + barPixelW() && my >= barY && my < barY + barPixelH();
	}

	private double barLocalX(double mx) {
		return (mx - barX) / barScale;
	}

	private double barLocalY(double my) {
		return (my - barY) / barScale;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (minecraft.level == null) {
			super.extractBackground(g, mouseX, mouseY, a);
			return;
		}
		Theme t = theme();
		Draw.rect(g, 0, 0, width, height, Colors.withAlpha(0, 0x40));
		// Draw every grid step so the denser snap spacing is visible.
		int step = HudElement.GRID;
		int grid = t.accent(0x0C);
		for (int x = HudElement.MARGIN; x < width; x += step) Draw.rect(g, x, 0, 1, height, grid);
		for (int y = HudElement.MARGIN; y < height; y += step) Draw.rect(g, 0, y, width, 1, grid);
	}

	@Override
	protected void renderContent(Ui ui) {
		Theme t = ui.theme;
		float hudScale = CoffeeClient.options().interfaceScale.floatValue();
		if (dragging != null) {
			if (dragging.centredX()) Draw.rect(ui.g, width / 2, 0, 1, height, t.accent(0x90));
			if (dragging.centredY()) Draw.rect(ui.g, 0, height / 2, width, 1, t.accent(0x90));
		}
		CoffeeClient.hud().layoutAuto(width, height, true);
		HudElement hovered = overBar(ui.mouseX, ui.mouseY) ? null : elementAt(ui.mouseX, ui.mouseY);
		for (HudElement e : CoffeeClient.hud().elements()) {
			if (!e.showInEditor()) continue;
			int x = e.x(width, hudScale), y = e.y(width, height, hudScale);
			int w = e.scaledWidth(hudScale), h = e.scaledHeight(hudScale);
			ErrorGuard.run("hud-editor:" + e.id(), () -> e.renderScaled(ui, x, y, hudScale, true));
			if (!e.isEnabled()) {
				Draw.rect(ui.g, x, y, w, h, Colors.withAlpha(0, 0xA0));
				Draw.textCentered(ui.g, "hidden", x + w / 2, y + h / 2 - 4, t.dimText);
			}
			boolean active = e == dragging || e == hovered;
			// drawn inside the element's own bounds, so flush neighbours sit side by side instead of overlapping
			if (active) Draw.rect(ui.g, x, y, w, h, t.accent(0x18));
			Draw.border(ui.g, x, y, w, h, active ? t.accent : t.accent(0x60));
			if (e == dragging) {
				String label = e.displayName() + (e.isEnabled() ? "" : " (hidden)");
				int ly = y > 12 ? y - 11 : y + h + 1;
				Draw.rect(ui.g, x, ly, Draw.width(label) + 6, 10, t.panel);
				Draw.text(ui.g, label, x + 2, ly + 1, t.text);
			}
		}
		ui.g.pose().pushMatrix();
		try {
			ui.g.pose().translate(barX, barY);
			ui.g.pose().scale(barScale, barScale);
			Ui bar = new Ui(ui.g, (int) Math.floor(barLocalX(ui.mouseX)), (int) Math.floor(barLocalY(ui.mouseY)));
			bar.hoverAllowed = ui.hoverAllowed;
			Draw.panel(bar.g, t, 0, 0, barW, BAR_H);
			if (t.glass()) {
				dev.coffeeclient.gui.fx.Shapes.glowDot(bar.g, 9.5f, 11f, 2, 3, t.accent(0x70));
				dev.coffeeclient.gui.fx.Shapes.disc(bar.g, 9.5f, 11f, 2, t.accent);
				Draw.text(bar.g, "HUD Editor", 15, 7, t.strongText());
			} else {
				Draw.accentBar(bar.g, t, 6, 6, 9);
				Draw.text(bar.g, "HUD Editor", 11, 7, t.accent);
			}
			Draw.textCentered(bar.g, Draw.trim("Drag: move · Right-click: show/hide · R: reset", barW - 12), barW / 2, 22, t.dimText);
			super.renderContent(bar);
		} finally {
			ui.g.pose().popMatrix();
		}
		if (hovered != null && dragging == null) ui.tooltip(hovered.displayName(), "Drag to move", "Right-click to " + (hovered.isEnabled() ? "hide" : "show"));
	}

	private @Nullable HudElement elementAt(double mx, double my) {
		float scale = CoffeeClient.options().interfaceScale.floatValue();
		List<HudElement> list = CoffeeClient.hud().elements();
		for (int i = list.size() - 1; i >= 0; i--) {
			HudElement e = list.get(i);
			if (!e.showInEditor()) continue;
			int x = e.x(width, scale), y = e.y(width, height, scale);
			if (mx >= x && mx < x + e.scaledWidth(scale) && my >= y && my < y + e.scaledHeight(scale)) return e;
		}
		return null;
	}

	private PositionSnap snapToNeighbors(HudElement moving, int x, int y, float scale) {
		int movingW = moving.scaledWidth(scale), movingH = moving.scaledHeight(scale);
		int threshold = Math.max(3, Math.round(HudElement.GRID * 2.5f * scale));
		int snappedX = x, snappedY = y, bestXDistance = threshold + 1, bestYDistance = threshold + 1;
		for (HudElement other : CoffeeClient.hud().elements()) {
			if (other == moving || !other.showInEditor()) continue;
			int otherX = other.x(width, scale), otherY = other.y(width, height, scale);
			int otherW = other.scaledWidth(scale), otherH = other.scaledHeight(scale);
			int[] xCandidates = {otherX, otherX + otherW - movingW, otherX - movingW, otherX + otherW, otherX + (otherW - movingW) / 2};
			int[] yCandidates = {otherY, otherY + otherH - movingH, otherY - movingH, otherY + otherH, otherY + (otherH - movingH) / 2};
			for (int candidate : xCandidates) {
				int distance = Math.abs(x - candidate);
				if (candidate >= 0 && candidate <= width - movingW && distance < bestXDistance) {
					bestXDistance = distance;
					snappedX = candidate;
				}
			}
			for (int candidate : yCandidates) {
				int distance = Math.abs(y - candidate);
				if (candidate >= 0 && candidate <= height - movingH && distance < bestYDistance) {
					bestYDistance = distance;
					snappedY = candidate;
				}
			}
		}
		return new PositionSnap(snappedX, snappedY, bestXDistance <= threshold, bestYDistance <= threshold);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (root.mouseClicked(barLocalX(event.x()), barLocalY(event.y()), event.button())) return true;
		if (overBar(event.x(), event.y())) {
			if (event.button() != Widget.LEFT) return true;
			draggingBar = true;
			grabX = event.x() - barX;
			grabY = event.y() - barY;
			return true;
		}
		HudElement e = elementAt(event.x(), event.y());
		if (e == null) return false;
		if (event.button() == Widget.RIGHT) {
			e.toggle();
			return true;
		}
		dragging = e;
		float scale = CoffeeClient.options().interfaceScale.floatValue();
		grabX = event.x() - e.x(width, scale);
		grabY = event.y() - e.y(width, height, scale);
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingBar) {
			placeBar((int) Math.round(event.x() - grabX) - (width - barPixelW()) / 2, (int) Math.round(event.y() - grabY) - (height - barPixelH()) / 2);
			return true;
		}
		if (dragging == null) return super.mouseDragged(event, dx, dy);
		float scale = CoffeeClient.options().interfaceScale.floatValue();
		int x = (int) Math.round(event.x() - grabX), y = (int) Math.round(event.y() - grabY);
		PositionSnap snap = snapToNeighbors(dragging, x, y, scale);
		dragging.moveToScaled(snap.x(), snap.y(), width, height, scale, !snap.snappedX(), !snap.snappedY());
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (draggingBar) {
			draggingBar = false;
			return true;
		}
		if (dragging != null) {
			dragging = null;
			ConfigManager.markDirty();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	protected boolean onKey(int key, int modifiers) {
		if (key == InputConstants.KEY_R) {
			CoffeeClient.hud().resetLayout();
			return true;
		}
		return false;
	}
}
