package dev.coffeeclient.util;

/**
 * A token bucket: tokens refill at {@code rate} per second up to {@code burst}, and each send takes one.
 * Starts full. Time is passed in, so the bucket can be tested without a clock.
 */
public final class TokenBucket {
	private double rate;
	private double burst;
	private double tokens;
	private long last;
	private boolean started;

	public TokenBucket(double rate, double burst) {
		configure(rate, burst);
	}

	/** Changes the limits; tokens above the new burst are dropped. */
	public void configure(double rate, double burst) {
		this.rate = Math.max(0, rate);
		this.burst = Math.max(1, burst);
		tokens = Math.min(tokens, this.burst);
	}

	public void reset() {
		started = false;
		tokens = 0;
	}

	/** Adds the tokens earned since the last call. */
	public void refill(long nowMs) {
		if (!started) {
			started = true;
			tokens = burst;
		} else {
			tokens = Math.min(burst, tokens + (nowMs - last) / 1000.0 * rate);
		}
		last = nowMs;
	}

	/** Takes one token if there is one. */
	public boolean tryTake(long nowMs) {
		refill(nowMs);
		if (tokens < 1) return false;
		tokens -= 1;
		return true;
	}

	/** Tokens available right now (after refilling to {@code nowMs}). */
	public double available(long nowMs) {
		refill(nowMs);
		return tokens;
	}
}
