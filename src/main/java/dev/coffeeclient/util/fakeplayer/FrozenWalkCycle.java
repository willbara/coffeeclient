package dev.coffeeclient.util.fakeplayer;

/** Holds a walk cycle on one frame, so limbs keep the same swing while their entity doesn't tick. */
public interface FrozenWalkCycle {
	void freeze(float speed, float position);

	void thaw();
}
