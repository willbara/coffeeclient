package dev.coffeeclient.util;

/** A per-tick PID controller with a clamped integral so a long hold can't wind it up. */
public final class Pid {
	private final double p, i, d;
	private double integral, last;
	private boolean started;

	public Pid(double p, double i, double d) {
		this.p = p;
		this.i = i;
		this.d = d;
	}

	public void reset() {
		integral = 0;
		started = false;
	}

	/** One tick's output for reaching {@code target} from {@code current}. */
	public double step(double target, double current) {
		double error = target - current;
		integral = Math.max(-100, Math.min(100, integral + error));
		double derivative = started ? error - last : 0;
		last = error;
		started = true;
		return p * error + i * integral + d * derivative;
	}
}
