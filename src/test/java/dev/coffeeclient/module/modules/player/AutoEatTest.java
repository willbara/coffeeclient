package dev.coffeeclient.module.modules.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.coffeeclient.module.modules.player.AutoEat.Pick;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class AutoEatTest {
	private static final FoodProperties BREAD = new FoodProperties(5, 6f, false);
	private static final FoodProperties STEAK = new FoodProperties(8, 12.8f, false);
	private static final FoodProperties APPLE = new FoodProperties(4, 2.4f, false);

	@Test
	void chorusEscapeWantsOnlyChorus() {
		bootstrap();
		assertTrue(AutoEat.wanted(Items.CHORUS_FRUIT, true, true, true));
		assertFalse(AutoEat.wanted(Items.GOLDEN_APPLE, true, true, false));
	}

	@Test
	void emergencyWantsOnlyGoldenApples() {
		bootstrap();
		assertTrue(AutoEat.wanted(Items.GOLDEN_APPLE, false, true, true));
		assertFalse(AutoEat.wanted(Items.BREAD, false, true, false));
	}

	@Test
	void hungerSkipsAvoidedFood() {
		bootstrap();
		assertTrue(AutoEat.wanted(Items.BREAD, false, false, false));
		assertFalse(AutoEat.wanted(Items.ROTTEN_FLESH, false, false, true));
	}

	private static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void bestTakesTheMostSaturation() {
		assertTrue(AutoEat.better(Pick.Best, STEAK, BREAD, 6));
		assertFalse(AutoEat.better(Pick.Best, BREAD, STEAK, 6));
	}

	@Test
	void efficientTakesTheSmallestFoodThatFillsTheGap() {
		// Gap of 5: bread fills it exactly, steak wastes 3.
		assertTrue(AutoEat.better(Pick.Efficient, BREAD, STEAK, 5));
		assertFalse(AutoEat.better(Pick.Efficient, STEAK, BREAD, 5));
		// Gap of 7: only steak fills it.
		assertTrue(AutoEat.better(Pick.Efficient, STEAK, BREAD, 7));
		// Gap of 20: nothing fills it, take the biggest.
		assertTrue(AutoEat.better(Pick.Efficient, STEAK, APPLE, 20));
	}
}
