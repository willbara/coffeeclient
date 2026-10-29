package dev.coffeeclient.render;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.util.WorldProjector;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * The two tracer looks. {@link Style#Classic} is Coffee's original pixel look: a line on the HUD from
 * the screen centre to the target's projected position, at GUI-pixel resolution; a target behind you
 * gets a line to the screen edge on its side. {@link Style#Smooth} draws thin lines in the world
 * ({@link EspRender#tracer}) that start under the crosshair.
 */
public final class Tracers {
	public enum Style { Smooth, Classic }

	private Tracers() {
	}

	/** Pixel-style tracer: a run of GUI-pixel squares from the screen centre (Coffee's original look). */
	public static void pixel(Ui ui, WorldProjector projector, double x, double y, double z, int argb, int width) {
		float[] point = projector.projectTracer(x, y, z);
		if (point != null) Draw.line(ui.g, projector.width() / 2f, projector.height() / 2f, point[0], point[1], width, argb);
	}

	/** Tracer alpha for "Tracer flash": a smooth pulse between a quarter and the full alpha. */
	public static int flash(int alpha) {
		return flash(alpha, System.currentTimeMillis());
	}

	static int flash(int alpha, long millis) {
		double pulse = 0.5 + 0.5 * Math.cos((millis % 1500) / 1500.0 * Math.PI * 2);
		return (int) Math.round(alpha * (0.25 + 0.75 * pulse));
	}

	/** Only the item nearest by {@code distSq} when {@code nearestOnly} is on; otherwise every item. */
	public static <T> List<T> nearestOnly(List<T> items, boolean nearestOnly, ToDoubleFunction<T> distSq) {
		if (!nearestOnly || items.size() < 2) return items;
		T best = items.get(0);
		double bestDist = distSq.applyAsDouble(best);
		for (T item : items) {
			double d = distSq.applyAsDouble(item);
			if (d < bestDist) {
				best = item;
				bestDist = d;
			}
		}
		return List.of(best);
	}
}
