package dev.coffeeclient.dev;

import dev.coffeeclient.core.CoffeeClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev-only profiling run ({@code ./gradlew runClient -Pbench}): opens the tour world with the current
 * config, lets chunks settle, holds still for the measured window while JFR records, then quits.
 */
public final class DevBench {
	private static final String WORLD = "CoffeeTour";
	private static final int SETTLE_TICKS = 600;
	private static final int MEASURE_TICKS = 1200;

	/** With -Pbench=fly the player flies forward at roughly elytra speed so new chunks keep streaming in. */
	private static final boolean FLY = "fly".equals(System.getProperty("coffeeclient.benchMode"));
	private static final double FLY_SPEED = 1.5;
	private static final int FLY_SETTLE_TICKS = 200;

	private static int phase;
	private static int timer;

	private DevBench() {
	}

	public static void install() {
		CoffeeClient.LOG.warn("[DevBench] enabled: settle {} ticks, measure {} ticks", SETTLE_TICKS, MEASURE_TICKS);
		ClientTickEvents.END_CLIENT_TICK.register(DevBench::tick);
	}

	private static void tick(Minecraft mc) {
		switch (phase) {
			case 0 -> {
				if (!(mc.gui.screen() instanceof TitleScreen)) return;
				if (mc.getLevelSource().levelExists(WORLD)) {
					mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.gui.setScreen(new TitleScreen()));
				} else {
					LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
					mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(424242L, true, false), WorldPresets::createNormalWorldDimensions, mc.gui.screen());
				}
				phase = 1;
			}
			case 1 -> {
				if (mc.level == null || mc.player == null || mc.gui.screen() != null) return;
				mc.options.pauseOnLostFocus = false;
				timer = FLY ? FLY_SETTLE_TICKS : SETTLE_TICKS;
				if (FLY) {
					mc.player.getAbilities().flying = true;
					mc.player.setPos(mc.player.getX(), 200, mc.player.getZ());
				}
				phase = 2;
			}
			case 2 -> {
				if (--timer > 0) return;
				CoffeeClient.LOG.warn("[DevBench] measure start t={}", System.currentTimeMillis());
				timer = MEASURE_TICKS;
				phase = 3;
			}
			case 3 -> {
				if (FLY) {
					mc.player.setDeltaMovement(0, 0, 0);
					mc.player.setPos(mc.player.getX() + FLY_SPEED, 200, mc.player.getZ());
				}
				if (--timer > 0) return;
				CoffeeClient.LOG.warn("[DevBench] measure end t={} fps={}", System.currentTimeMillis(), mc.getFps());
				phase = 4;
				mc.stop();
			}
			default -> {
			}
		}
	}
}
