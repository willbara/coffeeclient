package dev.coffeeclient.gui;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/** Per-frame drawing context handed to every widget. */
public final class Ui {
	private static long lastFrameNanos = System.nanoTime();
	private static float lastDt = 0.016f;
	private static boolean newFrame = true;

	public final GuiGraphicsExtractor g;
	public final Font font;
	public final Theme theme;
	public final int mouseX;
	public final int mouseY;
	/** Seconds since the previous frame, clamped. */
	public final float dt;
	public final long timeMs;
	/** Seconds since the client started, for looping effects. */
	public final float time;
	/** Drawable area in this context's coordinates (smaller than the GUI size on scaled screens). */
	public int screenWidth;
	public int screenHeight;
	/** False while an overlay (dropdown/context menu) owns the mouse. */
	public boolean hoverAllowed = true;
	private List<String> tooltip;

	public Ui(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		this.g = g;
		this.font = Minecraft.getInstance().font;
		this.theme = CoffeeClient.theme();
		this.mouseX = mouseX;
		this.mouseY = mouseY;
		this.dt = frameDelta();
		this.timeMs = System.currentTimeMillis();
		this.time = dev.coffeeclient.gui.anim.Motion.time();
		if (newFrame) {
			newFrame = false;
			theme.frame(dt);
		}
		this.screenWidth = g.guiWidth();
		this.screenHeight = g.guiHeight();
	}

	/**
	 * Seconds since the last frame. Several Ui instances can be created in one frame (HUD + screen); calls
	 * less than 2 ms apart are treated as the same frame and share the delta.
	 */
	private static float frameDelta() {
		long now = System.nanoTime();
		if (now - lastFrameNanos > 2_000_000L) {
			lastDt = Math.min(0.1f, (now - lastFrameNanos) / 1_000_000_000f);
			lastFrameNanos = now;
			newFrame = true;
		}
		return lastDt;
	}

	/**
	 * Animation step factor for a given base rate, scaled by the user's animation speed. Exponential
	 * ({@code 1 - e^(-rate*dt)}), so an eased value moves the same way at any frame rate.
	 */
	public float animStep(float rate) {
		return 1f - (float) Math.exp(-dt * rate * theme.animationSpeed.floatValue());
	}

	public void tooltip(List<String> lines) {
		if (lines != null && !lines.isEmpty()) this.tooltip = lines;
	}

	public void tooltip(String... lines) {
		tooltip(List.of(lines));
	}

	public List<String> pendingTooltip() {
		return tooltip;
	}

	public void clearTooltip() {
		tooltip = null;
	}
}
