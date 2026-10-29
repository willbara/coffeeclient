package dev.coffeeclient.gui.anim;

import dev.coffeeclient.gui.Ui;

/**
 * A value that glides toward a target. The step is {@code 1 - e^(-rate*dt)}, so motion looks the same at
 * any frame rate, and the user's animation speed scales every rate.
 */
public final class Anim {
	private static final float SNAP = 5e-4f;

	private final float rate;
	private float value = Float.NaN;

	public Anim(float rate) {
		this.rate = rate;
	}

	/** Step toward {@code target} and return the new value. The first call jumps straight to it. */
	public float update(Ui ui, float target) {
		if (Float.isNaN(value)) value = target;
		value += (target - value) * ui.animStep(rate);
		if (Math.abs(target - value) < SNAP) value = target;
		return value;
	}

	public float get() {
		return Float.isNaN(value) ? 0f : value;
	}

	public void set(float v) {
		value = v;
	}
}
