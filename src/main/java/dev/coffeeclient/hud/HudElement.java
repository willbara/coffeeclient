package dev.coffeeclient.hud;

import dev.coffeeclient.gui.Ui;

/**
 * A draggable HUD element.
 *
 * <p>Its position is an <em>anchor</em> (left/centre/right × top/middle/bottom, picked from the third of
 * the screen it sits in) plus an offset from that anchor in whole {@link #GRID} steps. Offsets are in GUI
 * pixels, so an element keeps the same spacing from its edge at every GUI scale and window size, and
 * elements line up with each other on the grid. The final position is always clamped on screen.
 */
public abstract class HudElement {
	/** Snap step in GUI pixels. */
	public static final int GRID = 2;
	/** Gap kept between an element and the screen edge it is anchored to. */
	public static final int MARGIN = 2;

	private final String id;
	final String hudKey;
	private final String displayName;
	private boolean placed;
	/** Position assigned by {@link HudManager}'s auto-stacking while the user hasn't placed the element. */
	private int[] auto;
	/** -1 = left/top, 0 = centre, 1 = right/bottom. */
	private int anchorX, anchorY;
	/** Offset from the anchor, in GUI pixels (multiples of {@link #GRID}). */
	private int offsetX, offsetY;

	protected HudElement(String id, String displayName) {
		this.id = id;
		this.hudKey = "hud:" + id;
		this.displayName = displayName;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public abstract boolean isEnabled();

	/** Whether there is anything to show right now (e.g. only while gliding). Checked after {@link #isEnabled()}. */
	public boolean shouldRender() {
		return true;
	}

	/** Whether the HUD editor lists this element (module elements only appear while their module is on). */
	public boolean showInEditor() {
		return true;
	}

	/** Toggle from the HUD editor (right-click). */
	public abstract void toggle();

	public abstract int width();

	public abstract int height();

	/** Default top-left position in GUI pixels for a fresh layout. */
	protected abstract int[] defaultPosition(int screenW, int screenH);

	/** Draw with the top-left corner at (x, y). {@code preview} is true inside the HUD editor. */
	public abstract void render(Ui ui, int x, int y, boolean preview);

	public int x(int screenW) {
		int w = width();
		int x;
		if (!placed) x = auto != null && autoStacks() ? auto[0] : defaultPosition(screenW, 0)[0];
		else x = switch (anchorX) {
			case -1 -> MARGIN + offsetX;
			case 1 -> screenW - MARGIN - w - offsetX;
			default -> screenW / 2 - w / 2 + offsetX;
		};
		return clamp(x, screenW - w);
	}

	public int scaledWidth(float scale) {
		return Math.max(1, Math.round(width() * scale));
	}

	public int scaledHeight(float scale) {
		return Math.max(1, Math.round(height() * scale));
	}

	public int x(int screenW, float scale) {
		if (!placed && auto != null && autoStacks()) return auto[0];
		int baseWidth = width(), scaledWidth = scaledWidth(scale);
		int baseX = placed ? 0 : defaultPosition(screenW, 0)[0];
		int ax = placed ? anchorX : third(baseX + baseWidth / 2, screenW);
		int offset = placed ? offsetX : switch (ax) {
			case -1 -> baseX - MARGIN;
			case 1 -> screenW - MARGIN - baseWidth - baseX;
			default -> baseX + baseWidth / 2 - screenW / 2;
		};
		int scaledOffset = Math.round(offset * scale);
		int scaledMargin = Math.round(MARGIN * scale);
		int value = switch (ax) {
			case -1 -> scaledMargin + scaledOffset;
			case 1 -> screenW - scaledMargin - scaledWidth - scaledOffset;
			default -> screenW / 2 + scaledOffset - scaledWidth / 2;
		};
		return clamp(value, screenW - scaledWidth);
	}

	public int y(int screenW, int screenH) {
		int h = height();
		int y;
		if (!placed) y = auto != null && autoStacks() ? auto[1] : defaultPosition(screenW, screenH)[1];
		else y = switch (anchorY) {
			case -1 -> MARGIN + offsetY;
			case 1 -> screenH - MARGIN - h - offsetY;
			default -> screenH / 2 - h / 2 + offsetY;
		};
		return clamp(y, screenH - h);
	}

	public int y(int screenW, int screenH, float scale) {
		if (!placed && auto != null && autoStacks()) return auto[1];
		int baseHeight = height(), scaledHeight = scaledHeight(scale);
		int baseY = placed ? 0 : defaultPosition(screenW, screenH)[1];
		int ay = placed ? anchorY : third(baseY + baseHeight / 2, screenH);
		int offset = placed ? offsetY : switch (ay) {
			case -1 -> baseY - MARGIN;
			case 1 -> screenH - MARGIN - baseHeight - baseY;
			default -> baseY + baseHeight / 2 - screenH / 2;
		};
		int scaledOffset = Math.round(offset * scale);
		int scaledMargin = Math.round(MARGIN * scale);
		int value = switch (ay) {
			case -1 -> scaledMargin + scaledOffset;
			case 1 -> screenH - scaledMargin - scaledHeight - scaledOffset;
			default -> screenH / 2 + scaledOffset - scaledHeight / 2;
		};
		return clamp(value, screenH - scaledHeight);
	}

	public void renderScaled(Ui ui, int x, int y, float scale, boolean preview) {
		ui.g.pose().pushMatrix();
		try {
			ui.g.pose().translate(x, y);
			ui.g.pose().scale(scale, scale);
			render(ui, 0, 0, preview);
		} finally {
			ui.g.pose().popMatrix();
		}
	}

	/** Place the element near (x, y): picks the anchor from the screen third and snaps the offset to the grid. */
	public void moveTo(int x, int y, int screenW, int screenH) {
		moveToScaled(x, y, screenW, screenH, 1f);
	}

	public void moveToScaled(int x, int y, int screenW, int screenH, float scale) {
		moveToScaled(x, y, screenW, screenH, scale, true, true);
	}

	public void moveToScaled(int x, int y, int screenW, int screenH, float scale, boolean snapX, boolean snapY) {
		int w = scaledWidth(scale), h = scaledHeight(scale);
		anchorX = third(x + w / 2, screenW);
		anchorY = third(y + h / 2, screenH);
		double safeScale = Math.max(0.01, scale);
		offsetX = snapOffset((int) Math.round(switch (anchorX) {
			case -1 -> x - MARGIN * safeScale;
			case 1 -> screenW - MARGIN * safeScale - w - x;
			default -> x + w / 2.0 - screenW / 2.0;
		} / safeScale), anchorX != 0, snapX);
		offsetY = snapOffset((int) Math.round(switch (anchorY) {
			case -1 -> y - MARGIN * safeScale;
			case 1 -> screenH - MARGIN * safeScale - h - y;
			default -> y + h / 2.0 - screenH / 2.0;
		} / safeScale), anchorY != 0, snapY);
		placed = true;
	}

	private static int third(int center, int size) {
		if (center < size / 3) return -1;
		if (center > size * 2 / 3) return 1;
		return 0;
	}

	private static int snap(int offset, boolean edge) {
		int snapped = Math.round(offset / (float) GRID) * GRID;
		return edge ? Math.max(0, snapped) : snapped;
	}

	private static int snapOffset(int offset, boolean edge, boolean snapToGrid) {
		if (snapToGrid) return snap(offset, edge);
		return edge ? Math.max(0, offset) : offset;
	}

	/** True when the element hugs the right half of the screen (right-aligned content). */
	public boolean onRightSide(int screenW) {
		return x(screenW) + width() / 2 > screenW / 2;
	}

	/** Whether the element is centred on an axis (for editor guide lines). */
	public boolean centredX() {
		return placed && anchorX == 0 && offsetX == 0;
	}

	public boolean centredY() {
		return placed && anchorY == 0 && offsetY == 0;
	}

	/** Module panels without a natural spot flow into a column under the module list until moved. */
	public boolean autoStacks() {
		return false;
	}

	public boolean isPlaced() {
		return placed;
	}

	void setAuto(int x, int y) {
		auto = new int[] {x, y};
	}

	public void resetPosition() {
		placed = false;
	}

	/** {anchorX, anchorY, offsetX, offsetY}, or null if at the default position. */
	public int[] savedPosition() {
		return placed ? new int[] {anchorX, anchorY, offsetX, offsetY} : null;
	}

	public void restorePosition(int ax, int ay, int ox, int oy) {
		anchorX = Math.max(-1, Math.min(1, ax));
		anchorY = Math.max(-1, Math.min(1, ay));
		offsetX = Math.round(ox / (float) GRID) * GRID;
		offsetY = Math.round(oy / (float) GRID) * GRID;
		placed = true;
	}

	private static int clamp(int v, int max) {
		return Math.max(0, Math.min(Math.max(0, max), v));
	}
}
