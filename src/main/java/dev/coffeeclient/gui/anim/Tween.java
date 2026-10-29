package dev.coffeeclient.gui.anim;

/** A timed 0→1 run on wall time (open/close transitions, pops). Speed scales the duration. */
public final class Tween {
	private long startNanos = -1;
	private float seconds;

	/** Start (or restart) the run lasting {@code seconds}. */
	public Tween start(float seconds) {
		this.seconds = Math.max(0.001f, seconds);
		this.startNanos = System.nanoTime();
		return this;
	}

	public boolean started() {
		return startNanos >= 0;
	}

	/** Raw progress 0..1 (1 when never started). */
	public float progress() {
		if (startNanos < 0) return 1f;
		return Math.min(1f, (System.nanoTime() - startNanos) / 1e9f / seconds);
	}

	public float eased(Ease ease) {
		return ease.apply(progress());
	}

	public boolean done() {
		return progress() >= 1f;
	}

	/** Seconds since start. */
	public float elapsed() {
		return startNanos < 0 ? Float.MAX_VALUE : (System.nanoTime() - startNanos) / 1e9f;
	}

	public void stop() {
		startNanos = -1;
	}
}
