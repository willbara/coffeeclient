package dev.coffeeclient.util;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.hud.HudStats;

import java.util.EnumMap;
import java.util.Map;

/**
 * Who owns each kind of combat action right now, so two modules don't act on the same thing in one
 * tick. A module {@link #claim}s a kind for a few ticks (renewing it while it keeps acting) and
 * {@link #release}s it when it stops; others check {@link #busy} and wait. A claim lasts through the
 * tick after its last one, so the answer doesn't depend on which module ticks first. Client thread only.
 */
public final class CombatTick {
	public enum Kind { MELEE, CRYSTAL, PLACE, USE, SWAP }

	/** No time update for this long counts as lag (time packets come once a second). */
	private static final long LAG_MS = 1500;

	private record Claim(Object owner, int priority, int until) {
	}

	private static final Map<Kind, Claim> claims = new EnumMap<>(Kind.class);
	private static int tick;

	private CombatTick() {
	}

	public static void init(EventBus bus) {
		bus.subscribe(Events.Tick.class, EventBus.HIGHEST, e -> tick++);
		bus.subscribe(Events.GameLeft.class, e -> claims.clear());
	}

	/**
	 * Takes {@code kind} for {@code ticks} ticks. Fails while another owner holds it with a higher or
	 * equal priority; the same owner can always renew.
	 */
	public static boolean claim(Kind kind, Object owner, int priority, int ticks) {
		Claim held = live(kind);
		if (held != null && held.owner != owner && held.priority >= priority) return false;
		claims.put(kind, new Claim(owner, priority, tick + ticks));
		return true;
	}

	/** Drops every claim {@code owner} holds. */
	public static void release(Object owner) {
		claims.values().removeIf(c -> c.owner == owner);
	}

	/** True while someone other than {@code self} holds {@code kind}; pass null to ask about anyone. */
	public static boolean busy(Kind kind, Object self) {
		Claim held = live(kind);
		return held != null && held.owner != self;
	}

	/** True while a melee or crystal fight is going on. */
	public static boolean inCombat() {
		return busy(Kind.MELEE, null) || busy(Kind.CRYSTAL, null);
	}

	/** True while the server has gone quiet: actions sent now would land late or not at all. */
	public static boolean lagging() {
		long last = HudStats.lastServerUpdate();
		return last > 0 && System.currentTimeMillis() - last >= LAG_MS;
	}

	private static Claim live(Kind kind) {
		Claim held = claims.get(kind);
		return held != null && held.until >= tick ? held : null;
	}
}
