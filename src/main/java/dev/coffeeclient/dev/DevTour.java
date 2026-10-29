package dev.coffeeclient.dev;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.screen.ClickGuiScreen;
import dev.coffeeclient.gui.screen.DashboardScreen;
import dev.coffeeclient.gui.screen.HudEditorScreen;
import dev.coffeeclient.gui.screen.SettingsScreen;
import dev.coffeeclient.gui.widget.CategoryColumn;
import dev.coffeeclient.gui.widget.ModuleRow;
import dev.coffeeclient.module.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * DEV ONLY (excluded from the release jar). Run with {@code ./gradlew runClient -PdevTour}: creates or opens
 * a test world, waits for it to settle, opens every screen and saves a screenshot of each to
 * {@code run/screenshots/tour-*.png}, then quits.
 */
public final class DevTour {
	private static final String WORLD = "CoffeeTour";
	private static final int SETTLE_TICKS = 400;
	private static final int SCREEN_TICKS = 30;

	private record Step(String name, Supplier<Screen> screen, Consumer<Minecraft> before) {
	}

	private static final List<Step> STEPS = new ArrayList<>();
	private static int phase;
	private static int timer;
	private static int index;
	private static String pendingTyping;
	/** Modules the tour switched on; switched back off at the end so the dev config stays clean. */
	private static final List<dev.coffeeclient.module.Module> ENABLED = new ArrayList<>();

	private DevTour() {
	}

	public static void install() {
		CoffeeClient.LOG.warn("[DevTour] enabled: will create/open world '{}' and screenshot every screen", WORLD);
		var theme = CoffeeClient.theme();
		CoffeeClient.LOG.info("[DevTour] theme loaded from config: preset={} accent={}", theme.preset.display(), theme.accentColor.display());
		theme.resetColors();
		theme.opacity.reset();
		var options = CoffeeClient.options();
		options.interfaceScale.reset();
		STEPS.add(new Step("hud", () -> null, mc -> {
		}));
		STEPS.add(new Step("dashboard", () -> new DashboardScreen(null), mc -> {
		}));
		STEPS.add(new Step("dashboard-scaled", () -> new DashboardScreen(null), mc -> {
			CoffeeClient.options().interfaceScale.set(0.75);
			options.dashboardLogHeight.set(60.0);
		}));
		STEPS.add(new Step("clickgui-scaled", () -> new ClickGuiScreen(null), mc -> {
		}));
		STEPS.add(new Step("settings", () -> new SettingsScreen(null), mc -> {
			CoffeeClient.options().interfaceScale.reset();
			options.dashboardLogHeight.reset();
		}));
		STEPS.add(new Step("settings-search", () -> {
			SettingsScreen screen = new SettingsScreen(null);
			pendingTyping = "sprint";
			return screen;
		}, mc -> {
		}));
		STEPS.add(new Step("settings-opacity", () -> new SettingsScreen(null), mc -> CoffeeClient.theme().opacity.set(1.0)));
		STEPS.add(new Step("clickgui", () -> new ClickGuiScreen(null), mc -> CoffeeClient.theme().opacity.reset()));
		STEPS.add(new Step("clickgui-search", () -> {
			pendingTyping = "entityesp";
			return new ClickGuiScreen(null);
		}, mc -> {
		}));
		STEPS.add(new Step("hudeditor", () -> new HudEditorScreen(null), mc -> {
		}));
		STEPS.add(new Step("picker", () -> new dev.coffeeclient.gui.screen.IdPickerScreen(new ClickGuiScreen(null),
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.combat.HitSounds.class).sound), mc -> {
		}));
		STEPS.add(new Step("esp-entities", () -> new dev.coffeeclient.gui.screen.ModuleScreen(null,
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.EntityESP.class)), mc -> {
		}));
		STEPS.add(new Step("esp-items", () -> new dev.coffeeclient.gui.screen.ModuleScreen(null,
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.ItemESP.class)), mc -> {
		}));
		STEPS.add(new Step("ambience", () -> new dev.coffeeclient.gui.screen.ModuleScreen(null,
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.Ambience.class)), mc -> {
		}));
		STEPS.add(new Step("hud-modules", () -> null, mc -> {
		}));
		var amb = CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.Ambience.class);
		STEPS.add(new Step("ambience-end-snow", () -> null, mc -> {
			amb.setEnabled(true);
			amb.sky.set(dev.coffeeclient.module.modules.render.Ambience.Sky.END);
			amb.weather.set(dev.coffeeclient.module.modules.render.Ambience.Weather.SNOW);
		}));
		STEPS.add(new Step("ambience-end-sunset", () -> null, mc -> {
			amb.weather.reset();
			amb.fixedTime.set(true);
			amb.time.set(12600.0);
			mc.player.setYRot(90);
			mc.player.setXRot(-8);
		}));
		STEPS.add(new Step("ambience-nether-storm", () -> null, mc -> {
			amb.sky.set(dev.coffeeclient.module.modules.render.Ambience.Sky.NETHER);
			amb.weather.set(dev.coffeeclient.module.modules.render.Ambience.Weather.THUNDERSTORM);
			amb.fixedTime.set(true);
			amb.time.set(18000.0);
		}));
		STEPS.add(new Step("esp-world", () -> null, mc -> {
			amb.sky.reset();
			amb.weather.reset();
			amb.fixedTime.reset();
			amb.setEnabled(false);
			var esp = CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.EntityESP.class);
			esp.tracers.set(true);
			esp.boxes.set(true);
			esp.tracersPlayersOnly.set(false);
			esp.passive.set(true);
			mc.player.connection.sendCommand("summon item ^ ^1 ^4 {Item:{id:\"minecraft:diamond\",count:3}}");
			mc.player.connection.sendCommand("summon item ^1 ^1 ^4 {Item:{id:\"minecraft:diamond\",count:2}}");
			mc.player.connection.sendCommand("summon item ^-2 ^1 ^5 {Item:{id:\"minecraft:netherite_ingot\",count:1}}");
			mc.player.connection.sendCommand("summon pig ^3 ^ ^7");
			mc.player.connection.sendCommand("summon zombie ^-3 ^ ^8 {NoAI:1b}");
			mc.player.setXRot(62);
		}));
		// PlayerProtect: alone on the tab list, the borrowed skin falls back to Steve.
		STEPS.add(new Step("player-protect", () -> null, mc -> {
			var protect = CoffeeClient.modules().get(dev.coffeeclient.module.modules.client.PlayerProtect.class);
			protect.skinSource.set(dev.coffeeclient.module.modules.client.PlayerProtect.SkinSource.Tab_player);
			protect.setEnabled(true);
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
			mc.player.setXRot(10);
		}));
		// World overlays (own pipelines and shaders): outlines and filled faces through terrain.
		STEPS.add(new Step("esp-blocks", () -> null, mc -> {
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.client.PlayerProtect.class).setEnabled(false);
			var storage = CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.StorageESP.class);
			storage.shape.set(dev.coffeeclient.render.WorldOverlays.Shape.Both);
			storage.setEnabled(true);
			var blocks = CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.BlockESP.class);
			blocks.blocks.set(java.util.List.of("minecraft:gold_block"));
			blocks.shape.set(dev.coffeeclient.render.WorldOverlays.Shape.Both);
			blocks.setEnabled(true);
			mc.player.connection.sendCommand("setblock ^2 ^ ^5 minecraft:chest");
			mc.player.connection.sendCommand("setblock ^-2 ^ ^5 minecraft:gold_block");
			mc.player.connection.sendCommand("setblock ^ ^-2 ^6 minecraft:barrel");
			mc.player.setXRot(55);
		}));
		// Phase B: X-Ray over the tour world, then a trajectory for a held snowball.
		STEPS.add(new Step("xray", () -> null, mc -> {
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.StorageESP.class).setEnabled(false);
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.BlockESP.class).setEnabled(false);
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.XRay.class).setEnabled(true);
			mc.player.setXRot(35);
		}));
		STEPS.add(new Step("trajectories", () -> null, mc -> {
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.XRay.class).setEnabled(false);
			mc.player.connection.sendCommand("item replace entity @s weapon.mainhand with minecraft:snowball 16");
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.Trajectories.class).setEnabled(true);
			mc.player.setXRot(-20);
		}));
		STEPS.add(new Step("trajectories-off", () -> null, mc -> {
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.Trajectories.class).setEnabled(false);
			mc.player.connection.sendCommand("item replace entity @s weapon.mainhand with minecraft:air");
		}));
		// visual flair: cosmetics in third person, each screen filter, the title screen and HUD extras
		List<Class<? extends dev.coffeeclient.module.Module>> cosmetics = List.of(
			dev.coffeeclient.module.modules.cosmetics.Cape.class, dev.coffeeclient.module.modules.cosmetics.Halo.class,
			dev.coffeeclient.module.modules.cosmetics.Wings.class, dev.coffeeclient.module.modules.cosmetics.Hats.class,
			dev.coffeeclient.module.modules.cosmetics.OrbitAura.class);
		STEPS.add(new Step("cosmetic-front", () -> null, mc -> {
			for (var c : cosmetics) CoffeeClient.modules().get(c).setEnabled(true);
			if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
			mc.player.setXRot(5);
		}));
		STEPS.add(new Step("cosmetic-back", () -> null, mc -> {
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.cosmetics.Hats.class).setEnabled(false);
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			mc.player.setXRot(15);
		}));
		STEPS.add(new Step("cosmetic-off", () -> null, mc -> {
			for (var c : cosmetics) CoffeeClient.modules().get(c).setEnabled(false);
			if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			mc.player.setXRot(10);
		}));
		// close-ups of the solid cosmetics: every hat style, the scarf, each buddy, and a crouch
		var hats = CoffeeClient.modules().get(dev.coffeeclient.module.modules.cosmetics.Hats.class);
		var scarf = CoffeeClient.modules().get(dev.coffeeclient.module.modules.cosmetics.Scarf.class);
		var buddy = CoffeeClient.modules().get(dev.coffeeclient.module.modules.cosmetics.LilBuddy.class);
		var camera = CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.CameraTweaks.class);
		for (var style : dev.coffeeclient.module.modules.cosmetics.Hats.Style.values()) {
			STEPS.add(new Step("closeup-hat-" + style.name().toLowerCase(Locale.ROOT), () -> null, mc -> {
				camera.cameraDistance.set(1.6);
				camera.setEnabled(true);
				mc.player.connection.sendCommand("time set noon");
				if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
				hats.style.set(style);
				hats.setEnabled(true);
				mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
				mc.player.setXRot(-22);
			}));
		}
		STEPS.add(new Step("closeup-scarf-front", () -> null, mc -> {
			hats.setEnabled(false);
			scarf.setEnabled(true);
			camera.cameraDistance.set(2.0);
			mc.player.setXRot(-8);
		}));
		STEPS.add(new Step("closeup-scarf-back", () -> null, mc -> {
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			mc.player.setXRot(18);
		}));
		for (var kind : dev.coffeeclient.module.modules.cosmetics.LilBuddy.Kind.values()) {
			STEPS.add(new Step("closeup-buddy-" + kind.name().toLowerCase(Locale.ROOT), () -> null, mc -> {
				scarf.setEnabled(false);
				buddy.kind.set(kind);
				buddy.setEnabled(true);
				camera.cameraDistance.set(2.0);
				mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
				mc.player.setXRot(-12);
			}));
			STEPS.add(new Step("closeup-buddy-" + kind.name().toLowerCase(Locale.ROOT) + "-back", () -> null, mc -> {
				mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
				mc.player.setXRot(20);
			}));
		}
		STEPS.add(new Step("closeup-crouch", () -> null, mc -> {
			scarf.setEnabled(true);
			mc.options.keyShift.setDown(true);
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
			mc.player.setXRot(5);
		}));
		var cape = CoffeeClient.modules().get(dev.coffeeclient.module.modules.cosmetics.Cape.class);
		STEPS.add(new Step("closeup-elytra", () -> null, mc -> {
			mc.options.keyShift.setDown(false);
			for (var m : List.of(scarf, buddy)) m.setEnabled(false);
			cape.setEnabled(true);
			mc.player.connection.sendCommand("item replace entity @s armor.chest with minecraft:elytra");
			camera.cameraDistance.set(2.4);
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			mc.player.setXRot(20);
		}));
		STEPS.add(new Step("closeup-off", () -> null, mc -> {
			mc.player.connection.sendCommand("item replace entity @s armor.chest with minecraft:air");
			cape.setEnabled(false);
			mc.options.keyShift.setDown(false);
			hats.style.reset();
			buddy.kind.reset();
			for (var m : List.of(hats, scarf, buddy)) m.setEnabled(false);
			camera.cameraDistance.reset();
			camera.setEnabled(false);
			if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			mc.player.setXRot(10);
		}));
		var filter = CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.ScreenFilter.class);
		for (var mode : dev.coffeeclient.module.modules.render.ScreenFilter.Mode.values()) {
			STEPS.add(new Step("filter-" + mode.name().toLowerCase(Locale.ROOT), () -> null, mc -> {
				mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				filter.mode.set(mode);
				filter.intensity.set(dev.coffeeclient.module.modules.render.ScreenFilter.Intensity.HIGH);
				filter.setEnabled(true);
			}));
		}
		STEPS.add(new Step("filter-off", () -> null, mc -> filter.setEnabled(false)));
		STEPS.add(new Step("title", dev.coffeeclient.gui.screen.CoffeeTitleScreen::new, mc -> {
		}));
		STEPS.add(new Step("hud-flair", () -> new HudEditorScreen(null), mc -> {
			var hud = CoffeeClient.modules().get(dev.coffeeclient.module.modules.client.HudModule.class);
			hud.watermark.set(true);
			hud.listStyle.set(dev.coffeeclient.module.modules.client.HudModule.ListStyle.GLOW);
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.ReactiveFx.class).setEnabled(true);
			CoffeeClient.hud().toast("Brewing complete", CoffeeClient.theme().accent);
		}));
		STEPS.add(new Step("hud-flair-off", () -> null, mc -> {
			var hud = CoffeeClient.modules().get(dev.coffeeclient.module.modules.client.HudModule.class);
			hud.watermark.reset();
			hud.listStyle.reset();
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.ReactiveFx.class).setEnabled(false);
		}));
		// every interface font on the same screen
		for (var font : dev.coffeeclient.gui.theme.Theme.TextFont.values()) {
			STEPS.add(new Step("font-" + font.name().toLowerCase(Locale.ROOT), () -> new ClickGuiScreen(null),
				mc -> CoffeeClient.theme().textFont.set(font)));
		}
		STEPS.add(new Step("font-restore", () -> null, mc -> CoffeeClient.theme().textFont.reset()));
		// every theme preset, alternating screens so each preset is seen on a different layout
		dev.coffeeclient.gui.theme.ThemePreset[] presets = dev.coffeeclient.gui.theme.ThemePreset.values();
		for (int i = 0; i < presets.length; i++) {
			var preset = presets[i];
			int screen = i % 3;
			STEPS.add(new Step("preset-" + preset.name().toLowerCase(Locale.ROOT), () -> switch (screen) {
				case 0 -> new DashboardScreen(null);
				case 1 -> new ClickGuiScreen(null);
				default -> new SettingsScreen(null);
			}, mc -> {
				mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				CoffeeClient.theme().preset.set(preset);
				CoffeeClient.theme().revertTheme();
			}));
		}
		STEPS.add(new Step("preset-restore", () -> null, mc -> CoffeeClient.theme().preset.set(dev.coffeeclient.gui.theme.ThemePreset.ESPRESSO)));
		// the remaining windows, first in the default theme, then every main screen in the Glass theme
		List<Supplier<Screen>> windows = List.of(() -> new dev.coffeeclient.gui.screen.MacrosScreen(null),
			() -> new dev.coffeeclient.gui.screen.FriendsScreen(null), () -> new dev.coffeeclient.gui.screen.ProfilesScreen(null),
			() -> new dev.coffeeclient.gui.screen.KeybindsScreen(null), () -> new dev.coffeeclient.gui.screen.HistoryScreen(null));
		String[] windowNames = {"macros", "friends", "profiles", "keybinds", "history"};
		for (int i = 0; i < windows.size(); i++) STEPS.add(new Step("window-" + windowNames[i], windows.get(i), mc -> {
		}));
		STEPS.add(new Step("glass-dashboard", () -> new DashboardScreen(null), mc -> {
			CoffeeClient.theme().preset.set(dev.coffeeclient.gui.theme.ThemePreset.NITRO);
			CoffeeClient.theme().revertTheme();
		}));
		STEPS.add(new Step("glass-clickgui", () -> new ClickGuiScreen(null), mc -> {
		}));
		STEPS.add(new Step("glass-settings", () -> new SettingsScreen(null), mc -> {
		}));
		STEPS.add(new Step("glass-module", () -> new dev.coffeeclient.gui.screen.ModuleScreen(null,
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.EntityESP.class)), mc -> {
		}));
		STEPS.add(new Step("glass-picker", () -> new dev.coffeeclient.gui.screen.IdPickerScreen(new ClickGuiScreen(null),
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.combat.HitSounds.class).sound), mc -> {
		}));
		STEPS.add(new Step("glass-hudeditor", () -> new HudEditorScreen(null), mc -> {
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.client.HudModule.class).listStyle.set(dev.coffeeclient.module.modules.client.HudModule.ListStyle.PILL);
			CoffeeClient.hud().toast("Brewing complete", CoffeeClient.theme().accent);
		}));
		STEPS.add(new Step("glass-hud", () -> null, mc -> {
		}));
		for (int i = 0; i < windows.size(); i++) STEPS.add(new Step("glass-" + windowNames[i], windows.get(i), mc -> {
		}));
		STEPS.add(new Step("glass-title", dev.coffeeclient.gui.screen.CoffeeTitleScreen::new, mc -> {
		}));
		STEPS.add(new Step("glass-restore", () -> null, mc -> {
			CoffeeClient.modules().get(dev.coffeeclient.module.modules.client.HudModule.class).listStyle.reset();
			CoffeeClient.theme().preset.set(dev.coffeeclient.gui.theme.ThemePreset.ESPRESSO);
		}));
		String only = System.getProperty("coffeeclient.devTourOnly");
		if (only != null && !only.isBlank()) {
			List<String> keys = List.of(only.split(","));
			STEPS.removeIf(step -> keys.stream().noneMatch(k -> step.name().contains(k.trim())));
		}
		ClientTickEvents.END_CLIENT_TICK.register(DevTour::tick);
	}

	private static void tick(Minecraft mc) {
		try {
			step(mc);
		} catch (Exception e) {
			CoffeeClient.LOG.error("[DevTour] failed", e);
			phase = 99;
		}
	}

	private static void step(Minecraft mc) throws Exception {
		switch (phase) {
			case 0 -> {
				if (!(mc.gui.screen() instanceof TitleScreen) && !(mc.gui.screen() instanceof dev.coffeeclient.gui.screen.CoffeeTitleScreen)) return;
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
				phase = 2;
				timer = SETTLE_TICKS;
				setUpWorld(mc);
			}
			case 2 -> {
				if (--timer > 0) return;
				phase = 3;
				openStep(mc);
			}
			case 3 -> {
				if (--timer > 0) return;
				Step step = STEPS.get(index);
				Screenshot.grab(mc.gameDirectory, "tour-" + step.name() + ".png", mc.gameRenderer.mainRenderTarget(), 1,
					msg -> CoffeeClient.LOG.info("[DevTour] {}", msg.getString()));
				index++;
				if (index >= STEPS.size()) {
					phase = 4;
					timer = 20;
					mc.gui.setScreen(null);
				} else {
					openStep(mc);
				}
			}
			case 4 -> {
				if (timer == 20) behaviourChecks(mc);
				if (timer == 14) phaseAResults(mc);
				if (--timer > 0) return;
				ENABLED.forEach(m -> m.setEnabled(false));
				CoffeeClient.LOG.info("[DevTour] done");
				phase = 99;
				mc.stop();
			}
			default -> {
			}
		}
	}

	private static void openStep(Minecraft mc) {
		Step step = STEPS.get(index);
		step.before().accept(mc);
		mc.gui.setScreen(step.screen().get());
		if (mc.gui.screen() instanceof ClickGuiScreen gui) expandSome(gui);
		if (pendingTyping != null && mc.gui.screen() != null) {
			for (int c : pendingTyping.codePoints().toArray()) mc.gui.screen().charTyped(new CharacterEvent(c));
			pendingTyping = null;
		}
		timer = SCREEN_TICKS;
	}

	private static void setUpWorld(Minecraft mc) {
		ENABLED.clear();
		String[] enable = {"HitSounds", "DamageNumbers", "KillTracker", "ChatTimestamps", "BetterTab",
			"Brightness", "SelectOutline", "ChunkBorders", "ItemESP", "EntityESP", "HandView", "ItemPhysics", "LightOverlay", "ParticleLimiter",
			"Ambience", "Breadcrumbs", "Step", "ArmorStatus", "EffectTimers", "Inventory+", "ItemCounter", "PickupNotifier",
			"XpTracker", "AutoTool", "ChunkTracer", "DesyncDetector", "EntityCounter", "ProtocolInfo", "TickMonitor", "SessionStats",
			"PacketInspector"};
		for (String name : enable) CoffeeClient.modules().byName(name).ifPresent(m -> {
			if (!m.isEnabled()) ENABLED.add(m);
			m.setEnabled(true);
		});
		mc.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("Dev tour chat line (timestamp test)"));
		mc.options.pauseOnLostFocus = false;
		mc.player.setYRot(200);
		mc.player.setXRot(10);
	}

	/** Quick functional checks for gameplay modules, logged as PASS/FAIL. */
	private static void behaviourChecks(Minecraft mc) {
		var modules = CoffeeClient.modules();
		var flight = modules.get(dev.coffeeclient.module.modules.movement.Flight.class);
		var step = modules.get(dev.coffeeclient.module.modules.movement.Step.class);
		flight.setEnabled(true);
		flight.onTick();
		boolean flying = mc.player.getAbilities().flying && mc.player.getAbilities().mayfly;
		flight.setEnabled(false);
		CoffeeClient.LOG.info("[DevTour] Flight creative mode: {}", flying ? "PASS" : "FAIL");
		step.setEnabled(true);
		mc.player.setOnGround(true);
		step.onTick();
		double h = mc.player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT).getBaseValue();
		step.setEnabled(false);
		double after = mc.player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT).getBaseValue();
		CoffeeClient.LOG.info("[DevTour] Step height {} -> restored {}: {}", h, after, h > 0.99 && Math.abs(after - 0.6) < 1e-6 ? "PASS" : "FAIL");
		phaseAChecks(mc);
		phaseBChecks();
	}

	/** Panic switches modules off quietly and brings back exactly those. */
	private static void phaseBChecks() {
		var modules = CoffeeClient.modules();
		var panic = modules.get(dev.coffeeclient.module.modules.misc.Panic.class);
		var step = modules.get(dev.coffeeclient.module.modules.movement.Step.class);
		var sprint = modules.get(dev.coffeeclient.module.modules.movement.Sprint.class);
		boolean sprintWas = sprint.isEnabled();
		step.setEnabled(true);
		sprint.setEnabled(false);
		panic.setEnabled(true);
		boolean off = !step.isEnabled();
		panic.setEnabled(false);
		boolean restored = step.isEnabled() && !sprint.isEnabled();
		step.setEnabled(false);
		sprint.setEnabled(sprintWas);
		CoffeeClient.LOG.info("[DevTour] Panic off {} restored {}: {}", off, restored, off && restored ? "PASS" : "FAIL");
	}

	// ---- Phase A infrastructure: started here, results read a few ticks later in phaseAResults ----

	private static boolean rotationCallbackRan;
	private static float pitchBeforeRotation;
	private static dev.coffeeclient.util.BlockSearch.Handle searchProbe;
	private static net.minecraft.core.BlockPos probePos;

	private static void phaseAChecks(Minecraft mc) {
		var bus = CoffeeClient.events();
		var safeWalk = CoffeeClient.modules().get(dev.coffeeclient.module.modules.movement.SafeWalk.class);
		boolean before = bus.has(dev.coffeeclient.core.Events.ClipAtLedge.class);
		safeWalk.setEnabled(true);
		boolean during = bus.has(dev.coffeeclient.core.Events.ClipAtLedge.class);
		safeWalk.setEnabled(false);
		boolean after = bus.has(dev.coffeeclient.core.Events.ClipAtLedge.class);
		CoffeeClient.LOG.info("[DevTour] Listeners only while enabled ({} {} {}): {}", before, during, after, !before && during && !after ? "PASS" : "FAIL");

		// Silent rotation: the server sees straight down, the camera does not move.
		pitchBeforeRotation = mc.player.getXRot();
		rotationCallbackRan = false;
		dev.coffeeclient.util.Rotations.rotate(mc.player.getYRot(), 90, dev.coffeeclient.util.Rotations.HIGH, () -> rotationCallbackRan = mc.player.getXRot() == 90 && dev.coffeeclient.util.Rotations.serverPitch() == 90);

		// Incremental block search: a block placed after the search started must show up via block updates.
		java.util.Set<net.minecraft.world.level.block.Block> key = java.util.Set.of(net.minecraft.world.level.block.Blocks.LAPIS_BLOCK);
		searchProbe = dev.coffeeclient.util.BlockSearch.acquire(key, state -> key.contains(state.getBlock()));
		probePos = mc.player.blockPosition().offset(3, 2, 3);
		mc.player.connection.sendCommand("setblock " + probePos.getX() + " " + probePos.getY() + " " + probePos.getZ() + " minecraft:lapis_block");
	}

	private static void phaseAResults(Minecraft mc) {
		// The callback saw the turned player and the sent pitch; the camera never moved.
		boolean cameraKept = mc.player.getXRot() == pitchBeforeRotation;
		CoffeeClient.LOG.info("[DevTour] Silent rotation (callback saw pitch 90 sent {}, camera kept {}): {}", rotationCallbackRan,
			cameraKept, rotationCallbackRan && cameraKept ? "PASS" : "FAIL");
		boolean found = searchProbe.collect(mc.player.blockPosition(), 16, 100).contains(probePos);
		searchProbe.release();
		CoffeeClient.LOG.info("[DevTour] Block search picks up block updates: {}", found ? "PASS" : "FAIL");
		mc.player.connection.sendCommand("setblock " + probePos.getX() + " " + probePos.getY() + " " + probePos.getZ() + " minecraft:air");
	}

	private static void expandSome(ClickGuiScreen gui) {
		for (var widget : gui.rootChildren()) {
			if (!(widget instanceof CategoryColumn column)) continue;
			for (ModuleRow row : column.rows()) {
				Module m = row.module();
				if (m.name().equals("Flight") || m.name().equals("HitSounds") || m.name().equals("Breadcrumbs") || m.name().equals("EntityESP")) row.setExpanded(true);
			}
		}
	}
}
