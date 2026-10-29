package dev.coffeeclient.util;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatTickTest {
	private final EventBus bus = new EventBus();
	private final Object aura = new Object(), surround = new Object();

	@BeforeEach
	void setUp() {
		CombatTick.init(bus);
		CombatTick.release(aura);
		CombatTick.release(surround);
	}

	private void tick() {
		bus.post(new Events.Tick(null));
	}

	@Test
	void higherPriorityTakesOverAndEqualDoesNot() {
		assertTrue(CombatTick.claim(CombatTick.Kind.PLACE, aura, 0, 1));
		assertFalse(CombatTick.claim(CombatTick.Kind.PLACE, surround, 0, 1));
		assertTrue(CombatTick.busy(CombatTick.Kind.PLACE, surround));
		assertFalse(CombatTick.busy(CombatTick.Kind.PLACE, aura));
		assertTrue(CombatTick.claim(CombatTick.Kind.PLACE, surround, 10, 1));
		assertTrue(CombatTick.busy(CombatTick.Kind.PLACE, aura));
	}

	@Test
	void claimLastsThroughTheTickAfterItsLast() {
		CombatTick.claim(CombatTick.Kind.MELEE, aura, 0, 1);
		tick();
		assertTrue(CombatTick.inCombat());
		tick();
		assertFalse(CombatTick.inCombat());
	}

	@Test
	void releaseFreesTheKind() {
		CombatTick.claim(CombatTick.Kind.CRYSTAL, aura, 0, 8);
		CombatTick.release(aura);
		assertFalse(CombatTick.busy(CombatTick.Kind.CRYSTAL, null));
	}
}
