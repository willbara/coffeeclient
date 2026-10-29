package dev.coffeeclient.module.modules.world.stashfinder;

/** What a StashFinder detector found. Each kind has its own colour. */
public enum Kind {
	MAN_MADE("Man-made blocks"),
	HOLE("Hole"),
	TUNNEL("Tunnel"),
	STAIR("Staircase"),
	COLUMN("Ladder or water column"),
	POCKET("Bedrock pocket"),
	SKY("Sky build"),
	ROOF("Nether roof build"),
	TRAIL("Mining trail"),
	AGE("Signs of time");

	public final String label;

	Kind(String label) {
		this.label = label;
	}
}
