package dev.coffeeclient.hud;

import dev.coffeeclient.gui.Ui;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HudElementTest {
	private static final class Box extends HudElement {
		private final int w, h;

		Box(int w, int h) {
			super("box", "Box");
			this.w = w;
			this.h = h;
		}

		@Override public boolean isEnabled() { return true; }
		@Override public void toggle() { }
		@Override public int width() { return w; }
		@Override public int height() { return h; }
		@Override protected int[] defaultPosition(int screenW, int screenH) { return new int[] {0, 0}; }
		@Override public void render(Ui ui, int x, int y, boolean preview) { }
	}

	/** An unsnapped (neighbour-flush) move lands on the exact pixel in every anchor third, and survives a save. */
	@Test
	void flushMoveRoundTripsAtAnyAnchorAndScale() {
		int screenW = 427, screenH = 240;
		for (float scale : new float[] {0.5f, 0.75f, 1f}) {
			for (int x : new int[] {3, 61, 200, 215, 333, 380}) {
				for (int y : new int[] {5, 97, 121, 199}) {
					Box e = new Box(37, 23);
					e.moveToScaled(x, y, screenW, screenH, scale, false, false);
					assertEquals(x, e.x(screenW, scale), "x at scale " + scale);
					assertEquals(y, e.y(screenW, screenH, scale), "y at scale " + scale);
					int[] p = e.savedPosition();
					Box restored = new Box(37, 23);
					restored.restorePosition(p[0], p[1], p[2], p[3]);
					assertEquals(x, restored.x(screenW, scale), "restored x at scale " + scale);
					assertEquals(y, restored.y(screenW, screenH, scale), "restored y at scale " + scale);
				}
			}
		}
	}
}
